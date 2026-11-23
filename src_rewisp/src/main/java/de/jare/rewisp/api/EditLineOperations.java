/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.api.RewispDriver.EditLineRequest;
import de.jare.rewisp.api.RewispDriver.EditLineResult;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.edit.LineEndings;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.FileSnapshot;
import de.jare.rewisp.snapshot.ProjectState;
import de.jare.rewisp.util.PathUtils;

import static de.jare.rewisp.api.EditSupport.ctx;

/**
 * {@code edit_line}: replaces a single line of a source file. There is no recipe; the file is written directly
 * via the {@link EditManager} (line ending is preserved, fingerprint is checked).
 *
 * @author Janusch Rentenatus
 */
final class EditLineOperations {

    private EditLineOperations() {
    }

    static EditLineResult edit(ProjectManager manager, EditManager editManager, ProjectEntry entry,
            EditLineRequest request) throws IOException {
        String filePath = request.file();
        int lineNumber = request.lineNumber();
        String newContent = request.newContent() == null ? "" : request.newContent();

        EditPipeline.requireEditable(entry);

        Path file = resolveFile(manager, entry, filePath);
        if (file != null && !isInsideProject(entry, file)) {
            throw new RewispException(ErrorType.SECURITY_ERROR,
                    "File is outside the project and cannot be edited: " + filePath,
                    ctx("file", filePath));
        }

        // Three-layer architecture: check file existence via snapshot (REWRITE layer)
        ProjectState state = manager.getProjectState(entry.name());
        // In einer Transaktion hat der gepufferte Inhalt Vorrang vor Snapshot und Platte
        String pendingContent = file != null ? editManager.getPendingChanges().get(PathUtils.canonical(file)) : null;
        if (pendingContent == null
                && (state == null || state.snapshot() == null
                    || !EditManager.fileExistsInSnapshot(state.snapshot().files(), file))) {
            // Fallback to Files.exists for absolute paths outside project
            if (file != null && !Files.exists(file)) {
                throw new RewispException(ErrorType.DOMAIN_ERROR, "File not found: " + file);
            }
        }

        // Read, normalize CR, split into lines
        // For files within the project, read from snapshot; otherwise from filesystem
        String fileContent;
        if (pendingContent != null) {
            fileContent = LineEndings.normalizeForMatch(pendingContent);
        } else if (file != null && state != null && state.snapshot() != null) {
            fileContent = EditManager.getFileContentFromSnapshot(state.snapshot().files(), file);
            if (fileContent != null) {
                fileContent = LineEndings.normalizeForMatch(fileContent);
            } else {
                fileContent = LineEndings.readNormalized(file);
            }
        } else {
            fileContent = LineEndings.readNormalized(file);
        }
        String normalized = fileContent;
        String[] lines = normalized.split("\n", -1);

        // A file ending with a trailing newline produces a phantom empty element
        // after the last content line (e.g. "a\nb\n" -> ["a","b",""]). That phantom
        // is not a real line: "replacing" it silently drops the file's trailing
        // newline and appends content to a non-existent line. edit_line replaces
        // existing lines only, so the phantom trailing element is not addressable.
        boolean trailingNewline = normalized.endsWith("\n");
        int lineCount = trailingNewline ? lines.length - 1 : lines.length;
        if (lineCount < 1) lineCount = lines.length; // empty/single-newline file

        if (lineNumber > lineCount) {
            throw new RewispException(ErrorType.DOMAIN_ERROR,
                    "File has " + lineCount + " line(s); cannot replace line " + lineNumber);
        }

        // Normalize CR from newContent, then strip trailing newlines. The
        // contract is "New content for the line (without line ending)" — any
        // trailing \n or \r\n the caller passes would otherwise produce a
        // spurious blank line after String.join("\n", lines).
        String normalizedNewContent = LineEndings.normalizeForMatch(newContent);
        while (normalizedNewContent.endsWith("\n")) {
            normalizedNewContent = normalizedNewContent.substring(0, normalizedNewContent.length() - 1);
        }
        String oldLine = lines[lineNumber - 1];
        lines[lineNumber - 1] = normalizedNewContent;

        // DRY RUN: nichts schreiben, nur Vorschau der Zeilenänderung
        if (request.dryRun()) {
            return new EditLineResult(entry.name(), true, file, lineNumber, oldLine, normalizedNewContent,
                    entry.modulesDetected(), entry.modulesLoaded());
        }

        String newFileContent = String.join("\n", lines);

        // Write via EditManager (preserves line ending, validates fingerprint)
        ProjectEntry updated = editManager.writeFile(entry, file, newFileContent, request.expectedFingerprint());
        manager.updateEntry(updated);
        editManager.logEdit("edit_line: line " + lineNumber + " in " + file.getFileName());

        return new EditLineResult(updated.name(), false, file, lineNumber, oldLine, normalizedNewContent,
                updated.modulesDetected(), updated.modulesLoaded());
    }

    /** true when the file (after resolving .. and symlinks) lies under the project directory. */
    private static boolean isInsideProject(ProjectEntry entry, Path file) {
        Path real = PathUtils.canonical(file);
        for (Path root : new Path[] {entry.originalProjectDir(), entry.projectDir()}) {
            if (root != null && real.startsWith(PathUtils.canonical(root))) return true;
        }
        return false;
    }

    /**
     * Resolves a file path relative to the project source directory, or uses absolute path.
     * Three-layer architecture: uses snapshot for file existence checks when available.
     */
    private static Path resolveFile(ProjectManager manager, ProjectEntry entry, String filePath) {
        Path p = Path.of(filePath);
        if (p.isAbsolute()) return p;

        ProjectState state = manager.getProjectState(entry.name());
        Map<Path, FileSnapshot> snapshotFiles = (state != null && state.snapshot() != null)
                ? state.snapshot().files() : null;

        // Try project source dir first
        Path srcDir = entry.projectDir();
        if (srcDir != null) {
            Path resolved = srcDir.resolve(filePath);
            if (snapshotFiles != null && EditManager.fileExistsInSnapshot(snapshotFiles, resolved)) {
                return resolved;
            }
            // Fallback to Files.exists for files not in snapshot (e.g., generated files)
            if (Files.exists(resolved)) return resolved;
        }
        // Try original project dir
        if (entry.originalProjectDir() != null) {
            Path resolved = entry.originalProjectDir().resolve(filePath);
            if (snapshotFiles != null && EditManager.fileExistsInSnapshot(snapshotFiles, resolved)) {
                return resolved;
            }
            if (Files.exists(resolved)) return resolved;
        }
        // Try source root (e.g. src/main/java for Maven projects) — a relative
        // path like "com/example/Foo.java" only exists under the source root,
        // not the project root.
        Path sourceRoot = ProjectManager.resolveSourceRoot(entry);
        if (sourceRoot != null && !sourceRoot.equals(srcDir)) {
            Path resolved = sourceRoot.resolve(filePath);
            if (snapshotFiles != null && EditManager.fileExistsInSnapshot(snapshotFiles, resolved)) {
                return resolved;
            }
            if (Files.exists(resolved)) return resolved;
        }
        return p;
    }
}

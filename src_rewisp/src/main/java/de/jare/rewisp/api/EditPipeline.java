/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.build.BuildResolutionException;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.edit.FingerprintValidator;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.observability.Phases;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.RewriteException;
import de.jare.rewisp.rewrite.RewriteRequest;
import de.jare.rewisp.rewrite.RewriteResult;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.util.PathUtils;

/**
 * The common template of all rewrite edits: refresh the snapshot, run the recipe as a preview, check the result
 * for compilability (D2), then write via the {@link EditManager}. Domain errors are reported as
 * {@link RewispException}; there is no MCP dependency.
 *
 * @author Janusch Rentenatus
 */
public final class EditPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(EditPipeline.class);

    private final ProjectManager manager;

    public EditPipeline(ProjectManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    /**
     * Result of the preview. With {@code dryRun}, {@code dryRunSnapshot} is available for rendering the diff
     * and nothing is written.
     */
    public record Outcome(ProjectEntry entry, RewriteResult preview, boolean dryRun, ProjectSnapshot dryRunSnapshot) {
    }

    /** Checks that the project is editable. */
    public static ProjectEntry requireEditable(ProjectEntry entry) {
        if (!entry.editable()) {
            throw new IllegalArgumentException(
                    "Project '" + entry.name() + "' is read-only (loaded from JAR or with editable=false). "
                    + "Edit tools are not available.");
        }
        return entry;
    }

    /**
     * Refresh the snapshot, run the recipe as a preview, check compilability, report the preview for
     * {@code dryRun} and reject an empty result. Nothing is written.
     *
     * @param compileRejectionHint hint appended to the rejection by the compile check
     * @throws RewispException on domain errors (no snapshot, preview failed, edit breaks
     *                         compilation, no change)
     */
    public Outcome preview(ProjectEntry entry, String projectName, RewriteEngine engine, RewriteEdit edit,
            boolean dryRun, String compileRejectionHint) {
        var state = manager.getProjectState(projectName);
        if (state == null || state.snapshot() == null) {
            throw new RewispException(ErrorType.NO_SNAPSHOT, "No snapshot available for project: " + projectName,
                    edit.errorContext());
        }
        // Bei unsynchronisiertem Projekt (vorherige Edits, offene Transaktion): Snapshot aktualisieren, damit
        // OpenRewrite die aktuellen Dateiinhalte sieht
        String refreshError = refreshSnapshotForEdit(entry, edit.broadScope());
        if (refreshError != null) {
            throw new RewispException(ErrorType.SNAPSHOT_REFRESH_FAILED,
                    "Could not refresh project snapshot before edit: " + refreshError, edit.errorContext());
        }

        var request = new RewriteRequest(projectName, edit.recipeId(), edit.params(), edit.targetFiles(),
                state.snapshot().id());
        // BuildContext laden (null-Fallback: RAW-Projekte ohne Build laufen weiter)
        BuildContext buildContext = buildContextOrNull(projectName);

        RewriteResult preview;
        try {
            preview = engine.preview(state.snapshot(), buildContext, request);
        } catch (RewriteException e) {
            Map<String, Object> context = new LinkedHashMap<>(edit.errorContext());
            context.put("errorCode", e.code());
            throw new RewispException(ErrorType.REWRITE_PREVIEW_FAILED,
                    "OpenRewrite preview failed: " + e.getMessage(), context);
        }

        // D2: Ergebnis muss kompilieren, sonst wird nichts geschrieben
        if (edit.checkCompiles()) {
            rejectIfNotCompiling(entry, preview, edit.fileMoves(), compileRejectionHint);
        }

        if (dryRun) {
            return new Outcome(entry, preview, true, state.snapshot());
        }

        if (preview.changedFiles().isEmpty()) {
            Map<String, Object> context = new LinkedHashMap<>(edit.errorContext());
            if (edit.noChangesHint() != null) context.put("suggestion", edit.noChangesHint());
            throw new RewispException(ErrorType.NO_CHANGES, edit.noChangesMessage(), context);
        }
        return new Outcome(entry, preview, false, null);
    }

    /**
     * Pre-write check (D2): if the rewrite result would make the project uncompilable, the edit
     * is rejected and nothing is written. Does nothing when the project did not compile before (repairs
     * must remain possible) or the check is disabled. Files the edit subsequently moves
     * ({@code fileMoves}, old -> new path) are checked at the new path.
     */
    public void rejectIfNotCompiling(ProjectEntry entry, RewriteResult preview, Map<Path, Path> fileMoves,
            String hint) {
        Map<Path, String> changed = new LinkedHashMap<>();
        Set<Path> deleted = new HashSet<>(preview.deletedFiles());
        preview.changedFiles().forEach((p, f) -> {
            Path moved = fileMoves.get(p);
            if (moved != null) deleted.add(p);
            changed.put(moved != null ? moved : p, f.content());
        });
        String problem = manager.validateEditCompiles(entry, changed, deleted);
        if (problem == null) return;
        throw new RewispException(ErrorType.EDIT_BREAKS_COMPILATION,
                "The edit would make the project stop compiling: " + problem + ". Nothing was written. " + hint);
    }

    /**
     * Writes the changed files of the result via the EditManager (canonical paths, so it recognizes the file
     * as "edited in this session"), takes over the entry and logs the edit.
     *
     * @return the updated entry
     */
    public ProjectEntry commit(ProjectEntry entry, EditManager editManager, RewriteResult preview, RewriteEdit edit)
            throws IOException {
        Map<Path, String> writes = new LinkedHashMap<>();
        for (var changed : preview.changedFiles().entrySet()) {
            writes.put(PathUtils.resolveReal(entry.originalProjectDir(), changed.getKey()),
                    changed.getValue().content());
        }
        if (edit.precheckFingerprints()) {
            entry = writeAll(entry, editManager, writes);
        } else {
            for (var w : writes.entrySet()) {
                entry = editManager.writeFile(entry, w.getKey(), w.getValue(), null);
            }
            manager.updateEntry(entry);
        }
        editManager.logEdit(edit.logEntry());
        return entry;
    }

    /**
     * Build context of the project or {@code null} when it cannot be resolved (e.g. RAW project without
     * a build system). The reason is logged instead of swallowed.
     */
    public BuildContext buildContextOrNull(String projectName) {
        long start = Phases.start();
        try {
            return manager.ensureBuildContext(projectName);
        } catch (BuildResolutionException e) {
            LOG.info("No build context for '{}' ({}); continuing without it", projectName, e.getMessage());
            return null;
        } finally {
            Phases.end("build-context", start); // Maven/Gradle-Lauf: im Log sichtbar statt versteckt
        }
    }

    /**
     * Updates the project snapshot before an edit when the project has dirty files (previous edits of this
     * session) or a transaction is open. Narrow scope: only the dirty files are re-read; broad
     * scopes (caller updates, moves) need a full rebuild.
     *
     * @return {@code null} on success, otherwise the error message
     */
    public String refreshSnapshotForEdit(ProjectEntry entry, boolean broadScope) {
        // dirtyFiles und der Overlay einer aktiven Transaktion (gepufferte Inhalte, vorgemerkte
        // Loeschungen) gehoeren zum Rewrite-Zustand; sonst wuerde jeder folgende Edit seine
        // Vorgaenger mit dem Plattenzustand ueberschreiben (letzter Schreiber gewinnt).
        if (!manager.hasUnsyncedChanges(entry)) return null;
        long start = Phases.start();
        try {
            var state = manager.getProjectState(entry.name());
            if (state == null || state.snapshot() == null) {
                return "no snapshot available for project " + entry.name();
            }
            var fresh = manager.effectiveSnapshot(entry, broadScope);
            state.setSnapshot(fresh);
            state.setSnapshotId(fresh.id());
            return null;
        } catch (IOException e) {
            return e.getMessage();
        } finally {
            Phases.end("snapshot-refresh", start);
        }
    }

    /**
     * Writes several files of an edit: validates the fingerprints of ALL target files UP FRONT (an externally
     * changed file aborts the edit before anything was written) and takes over the entry even on
     * an error in the middle of writing, so the dirty tracking stays correct.
     *
     * @param writes absolute target file -> new content (order is preserved)
     * @return the updated entry
     */
    public ProjectEntry writeAll(ProjectEntry entry, EditManager editMgr, Map<Path, String> writes)
            throws IOException {
        for (Path file : writes.keySet()) {
            if (!editMgr.isRemovedByEdit(file)) {
                FingerprintValidator.validateAgainstDisk(entry, file, null);
            }
        }
        ProjectEntry current = entry;
        try {
            for (Map.Entry<Path, String> w : writes.entrySet()) {
                current = editMgr.writeFile(current, w.getKey(), w.getValue(), null);
            }
        } finally {
            manager.updateEntry(current);
        }
        return current;
    }

    /**
     * Re-reads the given (absolute) files fresh from disk into the snapshots of the affected projects.
     * Needed after a rollback: the snapshot then still contains the overlay of the discarded
     * transaction contents. Files outside a project are ignored; files that do not exist on disk
     * (only buffered) disappear from the snapshot.
     */
    public void restoreSnapshotFromDisk(Set<Path> absoluteFiles) throws IOException {
        if (absoluteFiles == null || absoluteFiles.isEmpty()) return;
        var snapshotService = manager.getSnapshotService();
        for (ProjectEntry entry : manager.list()) {
            if (entry.originalProjectDir() == null) continue;
            Set<Path> relative = new HashSet<>();
            for (Path file : absoluteFiles) {
                Path rel = PathUtils.relativizeFromRoot(entry.originalProjectDir(), file);
                if (rel != null && !rel.isAbsolute() && !rel.startsWith("..")) {
                    relative.add(rel);
                }
            }
            if (relative.isEmpty()) continue;
            var state = manager.getProjectState(entry.name());
            if (state == null || state.snapshot() == null) continue;
            var fresh = snapshotService.refreshFiles(entry, state.snapshot(), relative,
                    manager.getSnapshotSequence().incrementAndGet());
            state.setSnapshot(fresh);
            state.setSnapshotId(fresh.id());
        }
    }
}

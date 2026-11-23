package de.jare.rewisp.snapshot;

import java.nio.charset.StandardCharsets;

import java.util.Arrays;

import de.jare.rewisp.util.ProjectLayout;

import de.jare.rewisp.model.ProjectEntry;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

public class SnapshotService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SnapshotService.class);

    private final ContentHasher hasher = new ContentHasher();
    private static final int MAX_RETRIES = 3;

    /**
     * Captures a consistent project snapshot.
     * Detects race conditions (file change during capture)
     * and repeats up to MAX_RETRIES.
     *
     * <p>Published events:
     * <ul>
     *   <li>SNAPSHOT_RACE_DETECTED - when a race condition is detected</li>
     *   <li>SNAPSHOT_COMPLETED - when the snapshot was created successfully</li>
     * </ul>
     */
    private volatile SnapshotListener listener = SnapshotListener.NONE;

    public ProjectSnapshot captureStable(ProjectEntry entry, long sequence) throws IOException {
        Path sourceRoot = entry.originalProjectDir();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            Map<Path, FileSnapshot> firstRead = readAllFiles(sourceRoot);
            Map<Path, String> firstHashes = extractHashes(firstRead);

            // Race-Erkennung ohne zweite Volllesung: Aenderungszeit und Groesse aller gelesenen Dateien
            // muessen noch stimmen (die Attribute stehen schon im FileSnapshot)
            if (unchangedSinceRead(sourceRoot, firstRead)) {
                String rootHash = hasher.rootHash(firstHashes);
                SnapshotId id = new SnapshotId(entry.name(), sequence, rootHash);
                ProjectSnapshot snapshot = new ProjectSnapshot(id, entry.name(), firstRead, Instant.now());
                
                // Event für erfolgreichen Snapshot veröffentlichen
                listener.onSnapshotAttempt(entry.name(), id, snapshot, attempt + 1, false);
                
                return snapshot;
            }

            // Race erkannt — Event veröffentlichen und wiederholen
            listener.onSnapshotAttempt(entry.name(), null, null, attempt + 1, true);
        }

        throw new IOException("SNAPSHOT_RACE: could not capture stable snapshot after "
                + MAX_RETRIES + " attempts for project " + entry.name());
    }

    /**
     * true when every read file still exists and modification time and size are unchanged.
     * Newly added files do not count as a race (they appear in the next snapshot).
     */
    private boolean unchangedSinceRead(Path root, Map<Path, FileSnapshot> read) {
        for (FileSnapshot fs : read.values()) {
            try {
                Path abs = root.resolve(fs.relativePath());
                if (Files.getLastModifiedTime(abs).toMillis() != fs.lastModifiedMillis()
                        || Files.size(abs) != fs.size()) {
                    return false;
                }
            } catch (IOException e) {
                return false; // geloescht oder nicht lesbar: Race
            }
        }
        return true;
    }

    /** Files changed more recently than this window are never skipped in {@link #refreshFiles}. */
    private static final long RACY_WINDOW_MILLIS = 2_000L;

    /**
     * Incremental snapshot refresh: replaces only the given files
     * (paths relative to the project root) in the base snapshot with fresh
     * disk contents. Files that no longer exist are removed from the
     * snapshot.
     *
     * <p>Significantly faster than {@link #captureStable}, because only the
     * dirty files are read — sufficient for edits with a scope
     * on one target file. Broad scopes (project-wide rewrite scope,
     * e.g. rename with caller updates or move_class) still need
     * captureStable.</p>
     *
     * @param entry the project
     * @param base the existing snapshot as base
     * @param relativeFiles the files to update (relative)
     * @param sequence the snapshot sequence number
     * @return the updated snapshot with a new SnapshotId
     * @throws IOException when a file cannot be read
     */
    public ProjectSnapshot refreshFiles(ProjectEntry entry, ProjectSnapshot base,
                                        Set<Path> relativeFiles, long sequence) throws IOException {
        Path sourceRoot = entry.originalProjectDir();
        Map<Path, FileSnapshot> files = new LinkedHashMap<>(base.files());
        for (Path rel : relativeFiles) {
            Path abs = sourceRoot.resolve(rel);
            if (Files.isRegularFile(abs) && abs.toString().endsWith(ProjectLayout.JAVA_EXTENSION)
                    && isSourceFile(abs, sourceRoot)) {
                FileSnapshot known = base.files().get(rel);
                long mtime = Files.getLastModifiedTime(abs).toMillis();
                // "Racy" wie bei Git: eine Datei, die erst vor kurzem geaendert wurde, wird immer gelesen. Bei grober
                // mtime-Aufloesung koennten zwei Aenderungen mit gleicher Groesse sonst identisch aussehen.
                boolean recentlyModified = System.currentTimeMillis() - mtime < RACY_WINDOW_MILLIS;
                if (known != null && !recentlyModified && known.size() == Files.size(abs)
                        && known.lastModifiedMillis() == mtime) {
                    continue; // unveraendert seit dem Basis-Snapshot: nicht erneut lesen und hashen
                }
                String content = de.jare.rewisp.util.SourceFiles.readText(abs);
                String hash = hasher.sha256(content);
                files.put(rel, new FileSnapshot(rel, content, hash,
                        Files.size(abs), Files.getLastModifiedTime(abs).toMillis(),
                        false, false));
            } else {
                files.remove(rel);
            }
        }
        String rootHash = hasher.rootHash(extractHashes(files));
        SnapshotId id = new SnapshotId(entry.name(), sequence, rootHash);
        return new ProjectSnapshot(id, entry.name(), files, Instant.now());
    }

    /**
     * Overlays given contents (paths relative to the project root) directly
     * onto a base snapshot — without disk access. Used for active
     * transactions: there buffered changes exist only in
     * memory; without an overlay every following edit of a transaction
     * would be blind to its predecessors and would overwrite them with the
     * disk state.
     */
    public ProjectSnapshot applyContents(ProjectEntry entry, ProjectSnapshot base,
                                         Map<Path, String> contents, long sequence) {
        Map<Path, FileSnapshot> files = new LinkedHashMap<>(base.files());
        for (Map.Entry<Path, String> e : contents.entrySet()) {
            Path rel = e.getKey();
            String content = e.getValue();
            files.put(rel, new FileSnapshot(rel, content, hasher.sha256(content),
                    byteSize(content), System.currentTimeMillis(), false, false));
        }
        String rootHash = hasher.rootHash(extractHashes(files));
        SnapshotId id = new SnapshotId(entry.name(), sequence, rootHash);
        return new ProjectSnapshot(id, entry.name(), files, Instant.now());
    }

    /**
     * Removes the given files (relative to the project root) from a snapshot,
     * without disk access. Used for deletions marked in a
     * transaction that still exist on disk.
     */
    public ProjectSnapshot removeFiles(ProjectEntry entry, ProjectSnapshot base,
                                       Set<Path> relativeFiles, long sequence) {
        Map<Path, FileSnapshot> files = new LinkedHashMap<>(base.files());
        relativeFiles.forEach(files::remove);
        String rootHash = hasher.rootHash(extractHashes(files));
        SnapshotId id = new SnapshotId(entry.name(), sequence, rootHash);
        return new ProjectSnapshot(id, entry.name(), files, Instant.now());
    }

    protected Map<Path, FileSnapshot> readAllFiles(Path root) throws IOException {
        Map<Path, FileSnapshot> files = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Ignorierte Verzeichnisse (target, .git, node_modules, ...) gar nicht erst betreten
                return !dir.equals(root) && IgnoreRules.defaults().isIgnored(root, dir)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(ProjectLayout.JAVA_EXTENSION) && isSourceFile(file, root)) {
                    Path relative = root.relativize(file).normalize();
                    try {
                        String content = de.jare.rewisp.util.SourceFiles.readText(file);
                        String hash = hasher.sha256(content);
                        files.put(relative, new FileSnapshot(
                                relative, content, hash,
                                attrs.size(), attrs.lastModifiedTime().toMillis(),
                                false, false));
                    } catch (IOException e) {
                        // Datei kann zwischen Walk und Read geloescht worden sein
                        LOG.debug("File vanished while reading snapshot ({}): {}", file, e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /** Size in bytes (UTF-8), not in characters. */
    private static long byteSize(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    private boolean isSourceFile(Path file, Path root) {
        return !IgnoreRules.defaults().isIgnored(root, file);
    }

    private Map<Path, String> extractHashes(Map<Path, FileSnapshot> files) {
        Map<Path, String> hashes = new LinkedHashMap<>();
        files.forEach((p, fs) -> hashes.put(p, fs.contentHash()));
        return hashes;
    }

    /**
     * Delivers the file content for a type.
     */
    public String getFileContentForType(ProjectSnapshot snapshot, String qualifiedName) {
        if (snapshot == null || qualifiedName == null || qualifiedName.isBlank()) return null;
        // Segmentweiser Vergleich: plattformunabhaengig und ohne Treffer auf "MyFoo.java" fuer "Foo"
        String[] parts = qualifiedName.split("\\.");
        parts[parts.length - 1] = parts[parts.length - 1] + ProjectLayout.JAVA_EXTENSION;
        Path suffix = Path.of(parts[0], Arrays.copyOfRange(parts, 1, parts.length));
        for (Path path : snapshot.files().keySet()) {
            if (path.endsWith(suffix)) {
                return snapshot.files().get(path).content();
            }
        }
        return null;
    }
}

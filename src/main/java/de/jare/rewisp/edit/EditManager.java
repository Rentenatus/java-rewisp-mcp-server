/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jare.rewisp.edit;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.tools.BaseEditTool;
import de.jare.rewisp.util.PathUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages file-write operations with optimistic locking, fingerprint re-set,
 * backup-on-first-edit, and line-ending preservation.
 *
 * <p>This is the central write pipeline for all edit tools. It enforces the
 * safety guarantees from the edit-tools concept Section 7.
 *
 * @author Janusch Rentenatus
 */
public class EditManager {

    private static final Logger LOG = LoggerFactory.getLogger(EditManager.class);

    private final Path backupDir;
    private final Map<String, Boolean> backedUp = new HashMap<>();
    private final TransactionState transactionState = new TransactionState();
    private final BackupConfig backupConfig = new BackupConfig();

    /**
     * Creates an EditManager with backup directory under the user home.
     */
    public EditManager() {
        this(Path.of(System.getProperty("user.home"), ".rewisp", "backups"));
    }

    /**
     * Creates an EditManager with a custom backup directory (for testing).
     */
    public EditManager(Path backupDir) {
        this.backupDir = backupDir;
    }

    /**
     * Writes content to a file with the full safety pipeline:
     * <ol>
     *   <li>Backup on first edit (copies all source files)</li>
     *   <li>Optimistic lock check (fingerprint validation)</li>
     *   <li>Detect and preserve existing line-ending convention</li>
     *   <li>Write to temp file, then atomic rename</li>
     *   <li>Mark the edited file dirty (per-file model staleness) and track it for fingerprint skipping</li>
     * </ol>
     *
     * @param entry              the loaded project entry
     * @param file               the target file on disk
     * @param newContent         the new file content (may use any line ending)
     * @param expectedFingerprint optional fingerprint; null means not provided
     * @return the updated project entry with the edited file added to dirtyFiles
     * @throws IOException if the write fails
     * @throws IllegalArgumentException if the optimistic lock check fails
     */
    public ProjectEntry writeFile(ProjectEntry entry, Path file, String newContent, String expectedFingerprint)
            throws IOException {
        // 1. Backup on first edit
        backupOnFirstEdit(entry);

        // 2. Optimistic lock check
        BaseEditTool.validateFingerprint(entry, file, expectedFingerprint);

        // If in transaction, accumulate changes in memory
        if (transactionState.isActive()) {
            transactionState.addPendingChange(file, newContent);
            LOG.info("writeFile: DEFERRED (transaction active) — {} ({} bytes buffered in memory)", file.normalize(), newContent.length());
            return entry; // no fingerprint update until commit
        }

        // 3. Detect existing line ending and preserve it
        String existingContent = Files.exists(file) ? Files.readString(file) : "";
        String lineEnding = LineEndings.detectLineEnding(existingContent);
        String adaptedContent = LineEndings.preserveOnWrite(newContent, lineEnding);

        // 4. Write to temp file, then atomic rename
        Path tempFile = file.resolveSibling(file.getFileName() + ".mcp-tmp");
        try {
            Files.writeString(tempFile, adaptedContent);
            Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOG.info("writeFile: IMMEDIATE write - {} ({} bytes, lineEnding={})", file, adaptedContent.length(), lineEnding.equals("\r\n") ? "CRLF" : "LF");
        } catch (IOException e) {
            // Cleanup temp file on failure
            Files.deleteIfExists(tempFile);
            throw e;
        }

        // 5. Mark the edited file dirty (per-file model staleness) and track it
        //    for per-file fingerprint skipping (editedFiles).
        Set<Path> updatedDirty = new HashSet<>(entry.dirtyFiles());
        updatedDirty.add(file.normalize());
        Set<Path> updatedEdited = new HashSet<>(entry.editedFiles());
        updatedEdited.add(file.normalize());
        return new ProjectEntry(
                entry.name(), entry.alias(), entry.expiryDate(),
                entry.projectDir(), entry.launcher(), entry.model(),
                entry.buildType(), entry.delomboked(), entry.lombokVersion(),
                entry.originalProjectDir(), entry.originalSource(),
                entry.sourceFingerprints(), entry.expired(), entry.editable(),
                entry.modulesDetected(), entry.modulesLoaded(),
                java.util.Collections.unmodifiableSet(updatedDirty),
                java.util.Collections.unmodifiableSet(updatedEdited));
    }

    /**
     * Copies all source files to the backup directory before the first edit.
     * This runs automatically — no agent request needed.
     */
    private void backupOnFirstEdit(ProjectEntry entry) {
        if (backedUp.getOrDefault(entry.name(), false)) return;
        if (entry.originalProjectDir() == null || !Files.isDirectory(entry.originalProjectDir())) return;

        // Projektnamen pfadsicher machen (illegale Zeichen → '_')
        String safeName = entry.name().replaceAll("[\\\\/:*?\"<>|]", "_");
        Path projectDir = backupDir.resolve(safeName);
        Path projectBackup = projectDir.resolve(String.valueOf(System.currentTimeMillis()));
        try {
            Files.createDirectories(projectBackup);
            try (var stream = Files.walk(entry.originalProjectDir())) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> backupConfig.shouldBackup(PathUtils.getFileName(p)))
                      .forEach(p -> {
                          try {
                              Path rel = entry.originalProjectDir().relativize(p);
                              Path dest = projectBackup.resolve(rel);
                              Files.createDirectories(dest.getParent());
                              Files.copy(p, dest);
                          } catch (IOException e) {
                              LOG.warn("Backup failed for {}: {}", p, e.getMessage());
                          }
                      });
            }
            LOG.info("Backup created at {} for project '{}'", projectBackup, entry.name());
            pruneBackups(projectDir);
        } catch (IOException e) {
            LOG.warn("Backup directory creation failed: {}", e.getMessage());
        }
        backedUp.put(entry.name(), true);
    }

    /** Anzahl der Backup-Stände, die pro Projekt behalten werden. */
    private static final int BACKUPS_TO_KEEP = 5;

    /**
     * Behält pro Projekt nur die {@value #BACKUPS_TO_KEEP} neuesten
     * Backup-Stände (nach Zeitstempel absteigend) und löscht die älteren.
     * Best effort: Fehler beim Löschen werden geloggt, blockieren nichts.
     */
    private void pruneBackups(Path projectDir) {
        try (var stream = Files.list(projectDir)) {
            List<Path> stands = stream
                    .filter(Files::isDirectory)
                    .sorted((a, b) -> Long.compare(
                            parseTimestamp(b.getFileName().toString()),
                            parseTimestamp(a.getFileName().toString())))
                    .toList();
            for (int i = BACKUPS_TO_KEEP; i < stands.size(); i++) {
                Path old = stands.get(i);
                deleteRecursive(old);
                LOG.info("Pruned old backup: {}", old);
            }
        } catch (IOException e) {
            LOG.debug("Backup pruning skipped for {}: {}", projectDir, e.getMessage());
        }
    }

    /** Löscht ein Verzeichnis rekursiv (best effort). */
    private void deleteRecursive(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warn("Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warn("Could not walk {} for pruning: {}", dir, e.getMessage());
        }
    }

    /** Zeitstempel-Ordnungszahl aus einem Stand-Namen; 0 wenn unparsebar. */
    private static long parseTimestamp(String name) {
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Resets the backup tracking for a project (called on reload).
     */
    public void resetBackup(String projectName) {
        backedUp.remove(projectName);
    }

    // --- Transaction Layer ---

    public void beginTransaction() {
        transactionState.begin();
    }

    public boolean isInTransaction() {
        return transactionState.isActive();
    }

    /**
     * Commits all pending changes atomically: writes to temp files, renames all,
     * then deletes all pending deletions. On any failure, everything mutated so
     * far is restored (renamed files get their original content back, deleted
     * files are re-created from in-memory snapshots) and the transaction
     * remains open.
     */
    public boolean commitTransaction() throws IOException {
        if (!transactionState.isActive()) return true;
        // A file with both a pending write and a pending deletion is deleted —
        // the write is skipped (deletion wins).
        Set<Path> deletions = transactionState.getPendingDeletions();
        // Snapshot contents of files to delete BEFORE any mutation, so a failed
        // commit can restore them.
        Map<Path, String> deletionContents = new HashMap<>(); // null = did not exist
        for (Path file : deletions) {
            deletionContents.put(file.normalize(), Files.exists(file) ? Files.readString(file) : null);
        }
        // Write all pending files to temp, then rename all
        List<Path> tempFiles = new ArrayList<>();
        Map<Path, String> originalContents = new HashMap<>(); // null = new file
        List<Path> renamedFiles = new ArrayList<>();
        List<Path> deletedFiles = new ArrayList<>();
        try {
            for (var entry : transactionState.getPendingChanges().entrySet()) {
                Path file = entry.getKey();
                if (transactionState.hasPendingDeletion(file)) continue; // deletion wins
                Path temp = file.resolveSibling(file.getFileName() + ".mcp-tmp");
                // Preserve existing line ending
                boolean isNew = !Files.exists(file);
                originalContents.put(file, isNew ? null : Files.readString(file));
                String existingContent = isNew ? "" : originalContents.get(file);
                String lineEnding = LineEndings.detectLineEnding(existingContent);
                String adaptedContent = LineEndings.preserveOnWrite(entry.getValue(), lineEnding);
                Files.writeString(temp, adaptedContent);
                tempFiles.add(temp);
            }
            // All temp files written successfully — now rename all
            for (var entry : transactionState.getPendingChanges().entrySet()) {
                Path file = entry.getKey();
                if (transactionState.hasPendingDeletion(file)) continue; // deletion wins
                Path temp = file.resolveSibling(file.getFileName() + ".mcp-tmp");
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                renamedFiles.add(file);
            }
            // All writes applied — now delete all pending deletions
            for (Path file : deletions) {
                if (Files.deleteIfExists(file)) {
                    deletedFiles.add(file);
                }
            }
        } catch (IOException e) {
            // Restore already-deleted files from the in-memory snapshot
            for (Path file : deletedFiles) {
                String content = deletionContents.get(file);
                try {
                    if (content != null) {
                        Files.writeString(file, content);
                    } else {
                        Files.deleteIfExists(file); // did not exist before commit
                    }
                } catch (IOException restoreEx) {
                    LOG.warn("Rollback failed for deleted file {}: {}", file, restoreEx.getMessage());
                }
            }
            // Restore already-renamed files to their original content
            for (Path file : renamedFiles) {
                String original = originalContents.get(file);
                try {
                    if (original != null) {
                        Files.writeString(file, original);
                    } else {
                        Files.deleteIfExists(file); // was a new file — undo creation
                    }
                } catch (IOException restoreEx) {
                    LOG.warn("Rollback failed for {}: {}", file, restoreEx.getMessage());
                }
            }
            // Cleanup remaining temp files (those not yet renamed)
            for (Path temp : tempFiles) {
                try { Files.deleteIfExists(temp); } catch (IOException delEx) { /* best effort */ }
            }
            // Transaction remains open
            return false;
        }
        Set<Path> committedFiles = new HashSet<>(transactionState.getPendingChanges().keySet());
        committedFiles.removeAll(deletions);
        Set<Path> committedDeletions = new HashSet<>(deletedFiles);
        LOG.info("commitTransaction: {} file(s) committed atomically, {} file(s) deleted: {}",
                committedFiles.size(), committedDeletions.size(), committedDeletions);
        transactionState.commit(committedFiles, committedDeletions);
        return true;
    }

    /**
     * Returns the set of files written by the last successful commitTransaction.
     * The caller (e.g. TransactionTools.Commit) uses this to update project
     * entries so that subsequent edits skip fingerprint validation for these
     * files — without this, the next edit to a committed file would fail with
     * a "modified since project load" error.
     */
    public Set<Path> getLastCommittedFiles() {
        return transactionState.getLastCommittedFiles();
    }

    public void rollbackTransaction() {
        transactionState.rollback();
    }

    // --- Edit Summary ---

    public void logEdit(String description) {
        transactionState.logEdit(description);
    }

    public List<String> getEditLog() {
        return transactionState.getEditLog();
    }

    public int getPendingChangeCount() {
        return transactionState.getPendingChangeCount();
    }

    /**
     * Liefert die in einer aktiven Transaktion gepufferten Dateiinhalte
     * (absolute, normalisierte Pfade). Außerhalb einer Transaktion ist die
     * Map leer. Edit-Tools nutzen das, um den Snapshot-Refresh mit den
     * gepufferten Inhalten zu überlagern — sonst würde jeder folgende Edit
     * einer Transaktion seine Vorgänger mit dem Platten-Zustand
     * überschreiben.
     */
    public Map<Path, String> getPendingChanges() {
        return transactionState.isActive()
                ? transactionState.getPendingChanges()
                : Map.of();
    }

    /**
     * Performs a hard reset of all transaction and edit state: discards any
     * pending transaction, clears the edit log, and resets the backup

     * tracking. Used by the reboot_spoon tool to recover from a corrupted
     * state without restarting the JVM.
     */
    public void hardReset() {
        transactionState.hardReset();
        backedUp.clear();
        LOG.info("EditManager hard reset: all transactions, backups, and edit log cleared");
    }

    // --- File Operations (Centralized for Three-Layer Architecture) ---

    /**
     * Creates all parent directories for a given path.
     * This is the centralized method for directory creation in the edit layer.
     *
     * @param directory the directory to create
     * @throws IOException if directory creation fails
     */
    public void createDirectories(Path directory) throws IOException {
        Files.createDirectories(directory);
        LOG.debug("createDirectories: {}", directory);
    }

    /**
     * Deletes a file with validation.
     * This is the centralized method for file deletion in the edit layer.
     *
     * <p>Transaction-aware: inside an active transaction the deletion is
     * buffered and executed at commit; a rollback discards it without touching
     * the file. Outside a transaction the file is deleted immediately.</p>
     *
     * @param file the file to delete
     * @throws IOException if deletion fails
     */
    public void deleteFile(Path file) throws IOException {
        if (transactionState.isActive()) {
            transactionState.addPendingDeletion(file);
            LOG.info("deleteFile: DEFERRED (transaction active) — {} deleted at commit", file.normalize());
            return;
        }
        if (!Files.exists(file)) {
            LOG.warn("deleteFile: file does not exist: {}", file);
            return;
        }
        Files.delete(file);
        LOG.info("deleteFile: {}", file);
    }

    /**
     * Returns the set of files deleted by the last successful commitTransaction.
     * The caller (e.g. TransactionTools.Commit) uses this to update project
     * entries so deleted files are tracked as dirty/stale.
     */
    public java.util.Set<Path> getLastCommittedDeletions() {
        return transactionState.getLastCommittedDeletions();
    }

    /**
     * Moves a file atomically.
     * This is the centralized method for file moves/renames in the edit layer.
     *
     * @param source the source file
     * @param target the target file
     * @throws IOException if move fails
     */
    public void moveFile(Path source, Path target) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException("Source file does not exist: " + source);
        }
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        LOG.info("moveFile: {} -> {}", source, target);
    }

    /**
     * Checks if a file exists in the snapshot (REWRITE layer).
     * Three-layer compliant: reads from snapshot, not filesystem.
     *
     * @param snapshotFiles the files map from ProjectSnapshot
     * @param relativePath the path relative to project root
     * @return true if the file exists in the snapshot
     */
    public static boolean fileExistsInSnapshot(java.util.Map<Path, ?> snapshotFiles, Path relativePath) {
        if (snapshotFiles == null || relativePath == null) return false;
        return snapshotFiles.containsKey(relativePath.normalize());
    }

    /**
     * Gets file content from the snapshot (REWRITE layer).
     * Three-layer compliant: reads from snapshot, not filesystem.
     *
     * @param snapshotFiles the files map from ProjectSnapshot
     * @param relativePath the path relative to project root
     * @return the file content, or null if not found
     */
    @SuppressWarnings("unchecked")
    public static String getFileContentFromSnapshot(java.util.Map<Path, ?> snapshotFiles, Path relativePath) {
        if (snapshotFiles == null || relativePath == null) return null;
        Object fileSnapshot = snapshotFiles.get(relativePath.normalize());
        if (fileSnapshot == null) return null;
        try {
            java.lang.reflect.Method contentMethod = fileSnapshot.getClass().getMethod("content");
            return (String) contentMethod.invoke(fileSnapshot);
        } catch (Exception e) {
            LOG.warn("Failed to get content from snapshot for {}: {}", relativePath, e.getMessage());
            return null;
        }
    }
}

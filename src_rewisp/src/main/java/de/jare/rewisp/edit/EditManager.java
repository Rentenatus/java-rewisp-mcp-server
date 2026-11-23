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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import java.util.Collections;
import java.util.Optional;


import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.util.PathUtils;
import de.jare.rewisp.util.SourceFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
public class EditManager implements de.jare.rewisp.snapshot.TransactionOverlay {

    private static final Logger LOG = LoggerFactory.getLogger(EditManager.class);

    private final BackupService backups;
    private final TransactionState transactionState = new TransactionState();
    /**
     * Disk state of each file at the moment it was first touched in the current
     * transaction (empty = file did not exist). The commit compares against this
     * to detect external changes between buffering and commit.
     */
    private final Map<Path, Optional<String>> txBaselines = new HashMap<>();
    private volatile String lastCommitError;
    /**
     * Files this manager itself deleted or moved (canonical paths).
     * Their fingerprint in the entry is stale; a later write to this path
     * (re-creation) must not fail on "deleted since project load".
     */
    /** Charset default for target files that do not exist yet (e.g. after move_class), keys canonical. */
    private final Map<Path, Charset> charsetHints = new ConcurrentHashMap<>();
    private final Set<Path> removedFiles = ConcurrentHashMap.newKeySet();

    /** true when the file was removed by an edit of this manager and not written again yet. */
    public boolean isRemovedByEdit(Path file) {
        return file != null && removedFiles.contains(PathUtils.canonical(file));
    }

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
        this.backups = new BackupService(backupDir);
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
        long start = de.jare.rewisp.observability.Phases.start();
        try {
            return writeFileTimed(entry, file, newContent, expectedFingerprint);
        } finally {
            de.jare.rewisp.observability.Phases.end("write", start);
        }
    }

    private ProjectEntry writeFileTimed(ProjectEntry entry, Path file, String newContent, String expectedFingerprint)
            throws IOException {
        // 1. Backup on first edit
        backups.backupOnFirstEdit(entry);

        // 2. Optimistic lock check
        // Von uns selbst entfernte Datei (delete/move): alter Fingerprint ist bewusst veraltet
        if (!isRemovedByEdit(file)) {
            FingerprintValidator.validateAgainstDisk(entry, file, expectedFingerprint);
        }

        // If in transaction, accumulate changes in memory
        if (transactionState.isActive()) {
            recordBaseline(file);
            // Spaeteres Schreiben nimmt eine vorgemerkte Loeschung am selben Pfad zurueck
            transactionState.removePendingDeletion(file);
            transactionState.addPendingChange(file, newContent);
            LOG.info("writeFile: DEFERRED (transaction active) — {} ({} bytes buffered in memory)", file.normalize(), newContent.length());
            return entry; // no fingerprint update until commit
        }

        // 3. Detect existing line ending and preserve it
        Charset charset = Files.exists(file) ? SourceFiles.charsetOf(file) // ISO-8859-1-Dateien bleiben ISO-8859-1
                : charsetHints.getOrDefault(PathUtils.canonical(file), StandardCharsets.UTF_8);
        String lineEnding = Files.exists(file)
                ? LineEndings.detectFromFile(file) : LineEndings.detectForNewFile(file);
        String adaptedContent = LineEndings.preserveOnWrite(newContent, lineEnding);

        // 4. Write to temp file, then atomic rename
        Path tempFile = AtomicFiles.tempSibling(file);
        try {
            SourceFiles.writeText(tempFile, adaptedContent, charset);
            AtomicFiles.moveReplacing(tempFile, file);
            LOG.info("writeFile: IMMEDIATE write - {} ({} bytes, lineEnding={})", file, adaptedContent.length(), lineEnding.equals("\r\n") ? "CRLF" : "LF");
        } catch (IOException e) {
            // Cleanup temp file on failure
            Files.deleteIfExists(tempFile);
            throw e;
        }

        // Datei existiert wieder: Fingerprint wird unten neu gesetzt
        removedFiles.remove(PathUtils.canonical(file));

        // 5. Mark the edited file dirty (per-file model staleness) and track it
        //    for per-file fingerprint skipping (editedFiles).
        Set<Path> updatedDirty = new HashSet<>(entry.dirtyFiles());
        // Nur die kanonische Form ablegen (ProjectEntry.isFileDirty/isFileEdited pruefen beide Formen)
        updatedDirty.add(PathUtils.canonical(file));
        Set<Path> updatedEdited = new HashSet<>(entry.editedFiles());
        updatedEdited.add(PathUtils.canonical(file));
        // Fingerprint nach dem eigenen Write aktualisieren (statt die Pruefung fuer
        // bearbeitete Dateien zu ueberspringen): externe Aenderungen bleiben erkennbar.
        Map<Path, de.jare.rewisp.model.Fingerprint> updatedFingerprints = new HashMap<>(entry.sourceFingerprints());
        updatedFingerprints.put(PathUtils.canonical(file), new de.jare.rewisp.model.Fingerprint(
                Files.getLastModifiedTime(file).toMillis(), Files.size(file)));
        return new ProjectEntry(
                entry.name(), entry.alias(), entry.expiryDate(),
                entry.projectDir(), entry.launcher(), entry.model(),
                entry.buildType(), entry.delomboked(), entry.lombokVersion(),
                entry.originalProjectDir(), entry.originalSource(),
                updatedFingerprints, entry.expired(), entry.editable(),
                entry.modulesDetected(), entry.modulesLoaded(),
                Collections.unmodifiableSet(updatedDirty),
                Collections.unmodifiableSet(updatedEdited));
    }

    /**
     * Resets the backup tracking for a project (called on reload).
     */
    public void resetBackup(String projectName) {
        backups.reset(projectName);
    }

    // --- Transaction Layer ---

    public void beginTransaction() {
        txBaselines.clear();
        lastCommitError = null;
        transactionState.begin();
    }

    /** Cause of the last failed commit (null when no error). */
    public String getLastCommitError() {
        return lastCommitError;
    }

    private void recordBaseline(Path file) throws IOException {
        Path key = PathUtils.canonical(file);
        if (!txBaselines.containsKey(key)) {
            txBaselines.put(key, Files.exists(file)
                    ? Optional.of(SourceFiles.readText(file)) : Optional.empty());
        }
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
        lastCommitError = null;
        TransactionCommitter.Result result = new TransactionCommitter(transactionState, txBaselines, charsetHints).commit();
        if (!result.success()) {
            lastCommitError = result.error(); // Transaktion bleibt offen
            return false;
        }
        transactionState.commit(result.committedFiles(), result.committedDeletions());
        txBaselines.clear();
        result.committedDeletions().forEach(f -> removedFiles.add(PathUtils.canonical(f)));
        result.committedFiles().forEach(f -> removedFiles.remove(PathUtils.canonical(f)));
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
        txBaselines.clear();
        lastCommitError = null;
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
     * Returns the file contents buffered in an active transaction
     * (absolute, normalized paths). Outside a transaction the
     * map is empty. Edit tools use this to overlay the snapshot refresh with the
     * buffered contents — otherwise every following edit
     * of a transaction would overwrite its predecessors with the
     * disk state.
     */
    public Map<Path, String> getPendingChanges() {
        return transactionState.isActive()
                ? transactionState.getPendingChanges()
                : Map.of();
    }

    /**
     * Frozen state of the files touched in the active transaction: content at first access
     * ({@code Optional.empty()} = file did not exist). Empty outside a transaction.
     */
    public Map<Path, Optional<String>> getTransactionBaselines() {
        return transactionState.isActive() ? Map.copyOf(txBaselines) : Map.of();
    }

    /** New directories marked in an active transaction; otherwise empty. */
    public List<Path> getPendingDirectories() {
        return transactionState.isActive() ? transactionState.getPendingDirectories() : List.of();
    }

    /** Deletions marked in an active transaction (absolute, normalized); otherwise empty. */
    public Set<Path> getPendingDeletions() {
        return transactionState.isActive()
                ? transactionState.getPendingDeletions()
                : Set.of();
    }

    /**
     * Performs a hard reset of all transaction and edit state: discards any
     * pending transaction, clears the edit log, and resets the backup

     * tracking. Used by the reboot_spoon tool to recover from a corrupted
     * state without restarting the JVM.
     */
    public void hardReset() {
        transactionState.hardReset();
        txBaselines.clear();
        removedFiles.clear();
        lastCommitError = null;
        backups.clear();
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
        if (transactionState.isActive()) {
            // Die Platte bleibt bis zum Commit unberuehrt (ein Rollback hinterlaesst keine Ordner)
            transactionState.addPendingDirectory(directory);
            LOG.debug("createDirectories: DEFERRED (transaction active) - {}", directory);
            return;
        }
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
            recordBaseline(file);
            transactionState.addPendingDeletion(file);
            LOG.info("deleteFile: DEFERRED (transaction active) — {} deleted at commit", file.normalize());
            return;
        }
        if (!Files.exists(file)) {
            LOG.warn("deleteFile: file does not exist: {}", file);
            return;
        }
        Files.delete(file);
        removedFiles.add(PathUtils.canonical(file));
        LOG.info("deleteFile: {}", file);
    }

    /**
     * Returns the set of files deleted by the last successful commitTransaction.
     * The caller (e.g. TransactionTools.Commit) uses this to update project
     * entries so deleted files are tracked as dirty/stale.
     */
    public Set<Path> getLastCommittedDeletions() {
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
    /**
     * The (not yet existing) target file {@code target} is written in the charset of {@code source},
     * so a moved ISO-8859-1 source text does not end up as UTF-8.
     */
    public void inheritCharset(Path source, Path target) throws IOException {
        if (Files.exists(source) && !Files.exists(target)) {
            charsetHints.put(PathUtils.canonical(target), SourceFiles.charsetOf(source));
        }
    }

    /**
     * Removes empty package directories after a move, at most {@code levels} levels
     * upwards (= number of package segments), so a source root is never deleted.
     * Inside a transaction nothing happens (the disk stays untouched until commit).
     *
     * @return number of removed directories
     */
    public int pruneEmptyPackageDirs(Path dir, int levels) throws IOException {
        if (transactionState.isActive()) return 0;
        int removed = 0;
        Path p = dir;
        for (int i = 0; i < levels && p != null && Files.isDirectory(p); i++, p = p.getParent()) {
            try (var s = Files.list(p)) {
                if (s.findAny().isPresent()) break;
            }
            Files.delete(p);
            removed++;
            LOG.info("pruned empty package directory: {}", p);
        }
        return removed;
    }

    public void moveFile(Path source, Path target) throws IOException {
        if (transactionState.isActive()) {
            // Transaktionsbewusst: gepufferten (sonst Platten-)Inhalt unter neuem Pfad
            // puffern und die Quelle zur Loeschung vormerken; die Platte bleibt bis zum Commit unberuehrt.
            if (transactionState.hasPendingDeletion(source)) {
                throw new IOException("Source file is already marked for deletion: " + source);
            }
            String content = transactionState.getPendingChange(source);
            if (content == null) {
                if (!Files.exists(source)) {
                    throw new IOException("Source file does not exist: " + source);
                }
                content = SourceFiles.readText(source);
            }
            boolean targetTaken = transactionState.hasPendingChange(target)
                    || (Files.exists(target) && !transactionState.hasPendingDeletion(target));
            if (targetTaken) {
                throw new IOException("Target file already exists: " + target);
            }
            recordBaseline(source);
            recordBaseline(target);
            // Ziel war zur Loeschung vorgemerkt: wird jetzt mit dem verschobenen Inhalt neu belegt
            transactionState.removePendingDeletion(target);
            transactionState.removePendingChange(source);
            transactionState.addPendingChange(target, content);
            transactionState.addPendingDeletion(source);
            LOG.info("moveFile: DEFERRED (transaction active) — {} -> {}", source.normalize(), target.normalize());
            return;
        }
        if (!Files.exists(source)) {
            throw new IOException("Source file does not exist: " + source);
        }
        if (Files.exists(target)) {
            throw new IOException("Target file already exists: " + target);
        }
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        removedFiles.add(PathUtils.canonical(source));
        removedFiles.remove(PathUtils.canonical(target));
        LOG.info("moveFile: {} -> {}", source, target);
    }

    /**
     * Checks whether a file is in the snapshot (reads from Rewrite, not from disk).
     *
     * @param snapshotFiles the files of a {@code ProjectSnapshot}
     * @param relativePath path relative to the project root
     */
    public static boolean fileExistsInSnapshot(Map<Path, de.jare.rewisp.snapshot.FileSnapshot> snapshotFiles,
                                               Path relativePath) {
        if (snapshotFiles == null || relativePath == null) return false;
        return snapshotFiles.containsKey(relativePath.normalize());
    }

    /**
     * Returns the file content from the snapshot (reads from Rewrite, not from disk).
     *
     * @return the content or {@code null} when the file is not in the snapshot
     */
    public static String getFileContentFromSnapshot(Map<Path, de.jare.rewisp.snapshot.FileSnapshot> snapshotFiles,
                                                    Path relativePath) {
        if (snapshotFiles == null || relativePath == null) return null;
        var file = snapshotFiles.get(relativePath.normalize());
        return file != null ? file.content() : null;
    }
}
/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.util.PathUtils;
import de.jare.rewisp.util.SourceFiles;

/**
 * Atomically writes the buffered changes of a transaction to disk: first all
 * temp files, then all renames, then the deletions. On any error everything
 * changed is rolled back (renamed files get their old content back, deleted ones are
 * restored from in-memory copies); the transaction then stays open.
 *
 * <p>External changes since the first buffering are detected up front (baselines); in this
 * case nothing is written.</p>
 *
 * @author Janusch Rentenatus
 */
final class TransactionCommitter {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionCommitter.class);

    /** Result of a commit attempt. */
    record Result(boolean success, String error, Set<Path> committedFiles, Set<Path> committedDeletions) {
        static Result failure(String error) {
            return new Result(false, error, Set.of(), Set.of());
        }
    }

    private final TransactionState tx;
    private final Map<Path, Optional<String>> baselines;
    private final Map<Path, Charset> charsetHints;

    /**
     * @param baselines disk state of each file when first touched in the transaction
     *                  (empty = file did not exist), keys canonical
     */
    TransactionCommitter(TransactionState tx, Map<Path, Optional<String>> baselines) {
        this(tx, baselines, Map.of());
    }

    /** @param charsetHints charset for new files (keys canonical); otherwise UTF-8 */
    TransactionCommitter(TransactionState tx, Map<Path, Optional<String>> baselines,
                         Map<Path, Charset> charsetHints) {
        this.tx = tx;
        this.baselines = baselines;
        this.charsetHints = charsetHints;
    }

    /** Disk state read during conflict checking (null = file does not exist); the commit does not read again. */
    private final Map<Path, String> currentOnDisk = new HashMap<>();

    /** Content of the file on disk (null = does not exist); each file is read at most once. */
    private String readCached(Path file) throws IOException {
        if (!currentOnDisk.containsKey(file)) {
            currentOnDisk.put(file, Files.exists(file) ? SourceFiles.readText(file) : null);
        }
        return currentOnDisk.get(file);
    }

    Result commit() throws IOException {
        List<Path> conflicts = findExternalConflicts();
        if (!conflicts.isEmpty()) {
            String error = "External change detected since the file was first buffered: " + conflicts
                    + ". Nothing was written. Roll back the transaction and redo the edits.";
            LOG.warn("commitTransaction aborted: {}", error);
            return Result.failure(error);
        }

        // Eine Datei mit Schreib- und Loeschvormerkung wird geloescht (Loeschen gewinnt).
        Set<Path> deletions = tx.getPendingDeletions();
        // Inhalte der zu loeschenden Dateien VOR jeder Mutation sichern (Wiederherstellung bei Fehler)
        Map<Path, String> deletionContents = new HashMap<>(); // null = existierte nicht
        Map<Path, Charset> charsets = new HashMap<>(); // Charset der Originale fuer die Wiederherstellung
        for (Path file : deletions) {
            deletionContents.put(PathUtils.canonical(file), readCached(file));
            charsets.put(PathUtils.canonical(file), SourceFiles.charsetOf(file));
        }
        // Aenderungszeit der betroffenen Dateien vor jeder Mutation: nach einem Restore soll der Fingerprint
        // (mtime + Groesse) des Projekts wieder passen, sonst meldet der naechste Edit "modified since project load" (N35)
        Map<Path, FileTime> mtimes = new HashMap<>();
        for (Path file : deletions) rememberMtime(file, mtimes);
        for (Path file : tx.getPendingChanges().keySet()) rememberMtime(file, mtimes);
        List<Path> tempFiles = new ArrayList<>();
        Map<Path, String> originalContents = new HashMap<>(); // null = neue Datei
        List<Path> renamedFiles = new ArrayList<>();
        List<Path> deletedFiles = new ArrayList<>();
        List<Path> createdDirs = new ArrayList<>();
        try {
            for (Path dir : tx.getPendingDirectories()) {
                createDirectoriesTracked(dir, createdDirs);
            }
            for (var entry : tx.getPendingChanges().entrySet()) {
                Path file = entry.getKey();
                if (tx.hasPendingDeletion(file)) continue;
                Path temp = AtomicFiles.tempSibling(file);
                // Zielverzeichnis anlegen (z.B. nach moveFile in ein neues Paket)
                if (file.getParent() != null) {
                    createDirectoriesTracked(file.getParent(), createdDirs);
                }
                boolean isNew = !Files.exists(file);
                originalContents.put(file, isNew ? null : readCached(file));
                Charset charset = isNew
                        ? charsetHints.getOrDefault(PathUtils.canonical(file), StandardCharsets.UTF_8)
                        : SourceFiles.charsetOf(file);
                charsets.put(file, charset);
                String existingContent = isNew ? "" : originalContents.get(file);
                String lineEnding = isNew ? LineEndings.detectForNewFile(file) : LineEndings.detectLineEnding(existingContent);
                SourceFiles.writeText(temp, LineEndings.preserveOnWrite(entry.getValue(), lineEnding), charset);
                tempFiles.add(temp);
            }
            for (var entry : tx.getPendingChanges().entrySet()) {
                Path file = entry.getKey();
                if (tx.hasPendingDeletion(file)) continue;
                AtomicFiles.moveReplacing(AtomicFiles.tempSibling(file), file);
                renamedFiles.add(file);
            }
            for (Path file : deletions) {
                if (Files.deleteIfExists(file)) {
                    deletedFiles.add(file);
                }
            }
        } catch (IOException e) {
            LOG.error("commitTransaction failed", e);
            restore(deletedFiles, deletionContents, renamedFiles, originalContents, charsets, tempFiles, createdDirs, mtimes);
            return Result.failure("I/O error during commit (changes rolled back, transaction still open): " + e);
        }

        Set<Path> committedFiles = new HashSet<>(tx.getPendingChanges().keySet());
        committedFiles.removeAll(deletions);
        Set<Path> committedDeletions = new HashSet<>(deletedFiles);
        LOG.info("commitTransaction: {} file(s) committed atomically, {} file(s) deleted: {}",
                committedFiles.size(), committedDeletions.size(), committedDeletions);
        return new Result(true, null, committedFiles, committedDeletions);
    }

    /** Files whose disk state changed since first being touched in the transaction. */
    private List<Path> findExternalConflicts() throws IOException {
        List<Path> conflicts = new ArrayList<>();
        for (Map.Entry<Path, Optional<String>> b : baselines.entrySet()) {
            Path file = b.getKey();
            String current = readCached(file);
            if (!Objects.equals(current, b.getValue().orElse(null))) {
                conflicts.add(file);
            }
        }
        return conflicts;
    }

    /** Creates directories and remembers the ones actually newly created (for the rollback). */
    private static void createDirectoriesTracked(Path dir, List<Path> created) throws IOException {
        List<Path> missing = new ArrayList<>();
        for (Path p = dir; p != null && !Files.exists(p); p = p.getParent()) {
            missing.add(0, p);
        }
        Files.createDirectories(dir);
        created.addAll(missing);
    }

    private static void rememberMtime(Path file, Map<Path, FileTime> mtimes) {
        try {
            if (Files.exists(file)) {
                mtimes.put(file, Files.getLastModifiedTime(file));
            }
        } catch (IOException e) {
            LOG.debug("Could not read mtime of {}: {}", file, e.getMessage());
        }
    }

    private static void restoreMtime(Path file, Map<Path, FileTime> mtimes) {
        FileTime original = mtimes.get(file);
        if (original == null) return;
        try {
            Files.setLastModifiedTime(file, original);
        } catch (IOException e) {
            LOG.debug("Could not restore mtime of {}: {}", file, e.getMessage());
        }
    }

    private static void restore(List<Path> deletedFiles, Map<Path, String> deletionContents,
                                List<Path> renamedFiles, Map<Path, String> originalContents, Map<Path, Charset> charsets,
                                List<Path> tempFiles, List<Path> createdDirs,
                                Map<Path, FileTime> mtimes) {
        for (Path file : deletedFiles) {
            String content = deletionContents.get(file);
            try {
                if (content != null) {
                    SourceFiles.writeText(file, content, charsets.getOrDefault(file, StandardCharsets.UTF_8));
                    restoreMtime(file, mtimes);
                } else {
                    Files.deleteIfExists(file); // existierte vor dem Commit nicht
                }
            } catch (IOException restoreEx) {
                LOG.warn("Rollback failed for deleted file {}: {}", file, restoreEx.getMessage());
            }
        }
        for (Path file : renamedFiles) {
            String original = originalContents.get(file);
            try {
                if (original != null) {
                    SourceFiles.writeText(file, original, charsets.getOrDefault(file, StandardCharsets.UTF_8));
                    restoreMtime(file, mtimes);
                } else {
                    Files.deleteIfExists(file); // war neu: Anlegen rueckgaengig machen
                }
            } catch (IOException restoreEx) {
                LOG.warn("Rollback failed for {}: {}", file, restoreEx.getMessage());
            }
        }
        for (Path temp : tempFiles) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException delEx) {
                LOG.debug("Could not delete temp file {}: {}", temp, delEx.getMessage());
            }
        }
        // neu angelegte Verzeichnisse (tiefste zuerst) entfernen, soweit leer
        for (int i = createdDirs.size() - 1; i >= 0; i--) {
            try {
                Files.deleteIfExists(createdDirs.get(i));
            } catch (IOException e) {
                LOG.debug("Could not remove created directory {}: {}", createdDirs.get(i), e.getMessage());
            }
        }
    }
}

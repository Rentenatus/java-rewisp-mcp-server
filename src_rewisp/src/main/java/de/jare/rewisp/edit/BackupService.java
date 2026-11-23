/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import java.io.UncheckedIOException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.IgnoreRules;
import de.jare.rewisp.util.PathUtils;

/**
 * Backs up all source files of a project into a backup directory before the first edit
 * (automatically, without prompting) and keeps only the newest states.
 *
 * @author Janusch Rentenatus
 */
final class BackupService {

    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);

    /** Number of backup states kept per project. */
    private static final int BACKUPS_TO_KEEP = 5;

    private final Path backupDir;
    private final BackupConfig backupConfig = new BackupConfig();
    private final Map<String, Boolean> backedUp = new HashMap<>();

    BackupService(Path backupDir) {
        this.backupDir = backupDir;
    }

    /** Forgets that the project was backed up (e.g. after reload): the next edit backs up again. */
    void reset(String projectName) {
        backedUp.remove(projectName);
    }

    void clear() {
        backedUp.clear();
    }

    /** Copies all source files if no backup of this session exists yet for the project. */
    void backupOnFirstEdit(ProjectEntry entry) {
        if (backedUp.getOrDefault(entry.name(), false)) return;
        if (entry.originalProjectDir() == null || !Files.isDirectory(entry.originalProjectDir())) return;

        // Projektnamen pfadsicher machen (illegale Zeichen -> '_')
        String safeName = entry.name().replaceAll("[\\\\/:*?\"<>|]", "_");
        Path projectDir = backupDir.resolve(safeName);
        Path projectBackup = projectDir.resolve(String.valueOf(System.currentTimeMillis()));
        try {
            Files.createDirectories(projectBackup);
            try (var stream = Files.walk(entry.originalProjectDir())) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> !IgnoreRules.defaults().isIgnored(entry.originalProjectDir(), p))
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
        } catch (UncheckedIOException e) {
            // Files.walk meldet Traversierungsfehler (z.B. gesperrtes Verzeichnis) ungeprueft
            LOG.warn("Backup walk failed for project '{}': {}", entry.name(), e.getMessage());
        } catch (IOException e) {
            LOG.warn("Backup directory creation failed: {}", e.getMessage());
        }
        backedUp.put(entry.name(), true);
    }

    /**
     * Keeps only the {@value #BACKUPS_TO_KEEP} newest states per project (by timestamp
     * descending) and deletes the older ones. Best effort: errors are logged, block nothing.
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

    private void deleteRecursive(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
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

    /** Timestamp sequence number from a state name; 0 when unparseable. */
    private static long parseTimestamp(String name) {
        try {
            return Long.parseLong(name);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

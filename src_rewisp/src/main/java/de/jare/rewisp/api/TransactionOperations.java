/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.api.RewispDriver.CommitResult;
import de.jare.rewisp.api.RewispDriver.FileChange;
import de.jare.rewisp.api.RewispDriver.TransactionDiff;
import de.jare.rewisp.edit.DryRunPreview;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.Fingerprint;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.util.PathUtils;

/**
 * Transactions: begin, commit, rollback, diff and edit log.
 *
 * @author Janusch Rentenatus
 */
final class TransactionOperations {

    private TransactionOperations() {
    }

    static void begin(EditManager editManager) {
        if (editManager.isInTransaction()) {
            throw new RewispException(ErrorType.DOMAIN_ERROR, "A transaction is already active. "
                    + "Call commit_transaction or rollback_transaction first.");
        }
        editManager.beginTransaction();
    }

    static CommitResult commit(ProjectManager manager, EditManager editManager) {
        if (!editManager.isInTransaction()) {
            throw new RewispException(ErrorType.DOMAIN_ERROR, "No active transaction. Call begin_transaction first.");
        }
        int pending = editManager.getPendingChangeCount();
        boolean success;
        try {
            success = editManager.commitTransaction();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (!success) {
            String cause = editManager.getLastCommitError();
            throw new RewispException(ErrorType.DOMAIN_ERROR, "Commit failed"
                    + (cause != null ? ": " + cause : ".")
                    + " Transaction is still open — fix the issue and retry, or call rollback_transaction.");
        }
        // Update editedFiles (fingerprint skipping) and dirtyFiles (model staleness) with the committed files and
        // committed deletions - aber nur auf dem Projekt, dem die Dateien tatsaechlich gehoeren (Pfad-Praefix gegen
        // den Projekt-Root). Ohne den Filter wuerde der Commit bei mehreren geladenen Projekten jedem Projekt die
        // Dateien aller anderen unterschieben - dirty-Warnungen luegen dann und check_project_dirty verlangt
        // unnoetige Reloads.
        Set<Path> committed = editManager.getLastCommittedFiles();
        Set<Path> deleted = editManager.getLastCommittedDeletions();
        // toRealPath ist ein Dateisystem-Zugriff: jeden committeten Pfad nur einmal aufloesen
        Map<Path, Path> realPaths = new HashMap<>();
        for (var entry : manager.list()) {
            Path root = entry.originalProjectDir() != null ? entry.originalProjectDir() : entry.projectDir();
            // Windows-Falle: originalProjectDir kann als 8.3-Kurzname (ADMINI~1) vorliegen, committete Dateien mit
            // Langnamen. normalize() loest das nicht auf - beide Seiten per toRealPath() vergleichen.
            Path rootReal = toRealPath(root);
            Predicate<Path> mine = p -> rootReal != null
                    && realPaths.computeIfAbsent(p, TransactionOperations::toRealPath).startsWith(rootReal);
            Set<Path> myCommitted = committed.stream().filter(mine).collect(Collectors.toSet());
            Set<Path> myDeleted = deleted.stream().filter(mine).collect(Collectors.toSet());
            Set<Path> updatedEdited = new HashSet<>(entry.editedFiles());
            updatedEdited.addAll(myCommitted);
            Set<Path> updatedDirty = new HashSet<>(entry.dirtyFiles());
            updatedDirty.addAll(myCommitted);
            updatedDirty.addAll(myDeleted);
            // Fingerprints der committeten Dateien aktualisieren, geloeschte entfernen
            Map<Path, Fingerprint> fps = new HashMap<>(entry.sourceFingerprints());
            for (Path f : myCommitted) {
                try {
                    fps.put(PathUtils.canonical(f),
                            new Fingerprint(Files.getLastModifiedTime(f).toMillis(), Files.size(f)));
                } catch (IOException ignored) {
                    // Datei nicht lesbar: alten Fingerprint entfernen
                    fps.remove(PathUtils.canonical(f));
                }
            }
            for (Path f : myDeleted) {
                fps.remove(PathUtils.canonical(f));
                fps.remove(f.normalize());
            }
            manager.updateEntry(new ProjectEntry(
                    entry.name(), entry.alias(), entry.expiryDate(),
                    entry.projectDir(), entry.launcher(), entry.model(),
                    entry.buildType(), entry.delomboked(), entry.lombokVersion(),
                    entry.originalProjectDir(), entry.originalSource(),
                    fps, entry.expired(), entry.editable(),
                    entry.modulesDetected(), entry.modulesLoaded(),
                    Collections.unmodifiableSet(updatedDirty),
                    Collections.unmodifiableSet(updatedEdited)));
        }
        return new CommitResult(pending, deleted.size());
    }

    static void rollback(EditPipeline pipeline, EditManager editManager) {
        if (!editManager.isInTransaction()) {
            throw new RewispException(ErrorType.DOMAIN_ERROR, "No active transaction to roll back.");
        }
        // Auch vorgemerkte Loeschungen/Verschiebungen: ihre Dateien wurden im Snapshot-Refresh entfernt und
        // muessen von der Platte zurueckgelesen werden
        Set<Path> discarded = new HashSet<>(editManager.getPendingChanges().keySet());
        discarded.addAll(editManager.getPendingDeletions());
        editManager.rollbackTransaction();
        // Snapshot-Overlay der verworfenen Inhalte zuruecknehmen (AP-1.2)
        try {
            pipeline.restoreSnapshotFromDisk(discarded);
        } catch (IOException e) {
            throw new IllegalStateException("Could not restore the snapshot after rollback: " + e.getMessage(), e);
        }
    }

    static TransactionDiff diff(ProjectManager manager, EditManager editManager, boolean statOnly) {
        if (!editManager.isInTransaction()) {
            throw new RewispException(ErrorType.DOMAIN_ERROR, "No active transaction. Call begin_transaction first.");
        }
        Map<Path, Optional<String>> baselines = editManager.getTransactionBaselines();
        Map<Path, String> pending = editManager.getPendingChanges();
        Set<Path> deletions = editManager.getPendingDeletions();

        TreeMap<String, Path> ordered = new TreeMap<>();
        Set<Path> touched = new HashSet<>(pending.keySet());
        touched.addAll(deletions);
        for (Path p : touched) ordered.put(display(manager, p), p);

        List<FileChange> changes = new ArrayList<>();
        int files = 0;
        int totalAdded = 0;
        int totalRemoved = 0;
        int restored = 0;
        for (var e : ordered.entrySet()) {
            Path p = e.getValue();
            String shown = e.getKey();
            Optional<String> base = baselines.getOrDefault(p, Optional.empty());
            if (deletions.contains(p)) {
                if (base.isEmpty()) continue; // existierte nie: nichts zu loeschen
                int lines = lineCount(base.get());
                files++;
                totalRemoved += lines;
                changes.add(new FileChange("delete", shown, 0, lines, null));
                continue;
            }
            String now = pending.get(p);
            if (base.isEmpty()) {
                int lines = lineCount(now);
                files++;
                totalAdded += lines;
                String diff = null;
                if (!statOnly) {
                    StringBuilder sb = new StringBuilder();
                    DryRunPreview.appendDiff(sb, "", now);
                    diff = sb.toString();
                }
                changes.add(new FileChange("create", shown, lines, 0, diff));
            } else {
                StringBuilder diff = new StringBuilder();
                int[] c = DryRunPreview.appendDiff(diff, base.get(), now);
                if (c[0] == 0 && c[1] == 0) {
                    restored++; // beruehrt, aber wieder auf dem eingefrorenen Stand
                    continue;
                }
                files++;
                totalAdded += c[0];
                totalRemoved += c[1];
                changes.add(new FileChange("modify", shown, c[0], c[1], statOnly ? null : diff.toString()));
            }
        }
        for (Path dir : editManager.getPendingDirectories()) {
            if (Files.isDirectory(dir)) continue; // existiert schon: kein Commit-Effekt
            changes.add(new FileChange("mkdir", display(manager, dir), 0, 0, null));
        }
        return new TransactionDiff(changes, files, totalAdded, totalRemoved, restored);
    }

    private static int lineCount(String content) {
        if (content == null || content.isEmpty()) return 0;
        String t = content.replace("\r", "");
        int n = t.split("\n", -1).length;
        return t.endsWith("\n") ? n - 1 : n;
    }

    /** Path relative to the project it belongs to; otherwise absolute. */
    private static String display(ProjectManager manager, Path p) {
        Path real = toRealPath(p);
        for (var entry : manager.list()) {
            Path root = toRealPath(entry.originalProjectDir() != null
                    ? entry.originalProjectDir() : entry.projectDir());
            if (root != null && real.startsWith(root)) {
                return root.relativize(real).toString().replace('\\', '/');
            }
        }
        return p.toString();
    }

    /**
     * Resolves a path to the real (canonical) path - including Windows 8.3 short names and symlinks.
     * If that fails (e.g. file no longer exists), the absolutely normalized path is used.
     */
    private static Path toRealPath(Path p) {
        if (p == null) return null;
        try {
            return p.toAbsolutePath().toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}

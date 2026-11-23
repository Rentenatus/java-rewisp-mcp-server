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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.api.RewispDriver.DirtyFlags;
import de.jare.rewisp.api.RewispDriver.DirtyReport;
import de.jare.rewisp.api.RewispDriver.ProjectMetadata;
import de.jare.rewisp.api.RewispDriver.RebootResult;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.Fingerprint;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.model.ProjectLoadException;
import de.jare.rewisp.snapshot.DirtyCheckResult;
import de.jare.rewisp.snapshot.DirtyChecker;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.ProjectState;
import de.jare.rewisp.util.PathUtils;

/**
 * State queries and changes at project level: reload, metadata, reboot and the dirty check.
 *
 * @author Janusch Rentenatus
 */
final class ProjectOperations {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectOperations.class);

    private ProjectOperations() {
    }

    // ==================== reload ====================

    /** Names of the newly loaded projects (in load order). */
    static List<String> reload(ProjectManager manager, String name, boolean expired) {
        List<String> reloaded = new ArrayList<>();
        if (expired) {
            try {
                for (ProjectEntry e : manager.reloadExpired()) reloaded.add(e.name());
                // Auch das genannte Projekt neu laden, falls es nicht schon im abgelaufenen Satz war
                if (name != null && !name.isBlank()) {
                    var existing = manager.find(name);
                    if (existing != null && !existing.expired() && !reloaded.contains(existing.name())) {
                        reloaded.add(manager.reload(name).name());
                    }
                }
            } catch (ProjectLoadException e) {
                throw new RewispException(ErrorType.DOMAIN_ERROR, "Reload failed: " + e.getMessage());
            }
        } else {
            if (name == null || name.isBlank()) {
                throw new RewispException(ErrorType.DOMAIN_ERROR,
                        "Parameter 'name' is required when expired is false or not set.");
            }
            try {
                reloaded.add(manager.reload(name).name());
            } catch (ProjectLoadException e) {
                throw new RewispException(ErrorType.DOMAIN_ERROR, "Reload failed: " + e.getMessage());
            }
        }
        return reloaded;
    }

    // ==================== project_metadata ====================

    static ProjectMetadata metadata(ProjectEntry entry) {
        var model = entry.model();
        long classCount = 0, interfaceCount = 0, enumCount = 0, methodCount = 0, fieldCount = 0;
        for (var t : model.getAllTypes()) { // ein Durchlauf statt fuenf
            if (t.isClass()) classCount++;
            if (t.isInterface()) interfaceCount++;
            if (t.isEnum()) enumCount++;
            methodCount += t.getMethods().size();
            fieldCount += t.getFields().size();
        }
        return new ProjectMetadata(entry.name(), entry.alias(), entry.buildType(),
                String.valueOf(entry.originalProjectDir()),
                entry.delomboked() ? String.valueOf(entry.projectDir()) : null,
                entry.modulesLoaded(), entry.modulesDetected(), model.getAllPackages().size(),
                classCount, interfaceCount, enumCount, methodCount, fieldCount);
    }

    // ==================== reboot_spoon ====================

    static RebootResult reboot(ProjectManager manager, EditManager editManager) {
        int pendingTx = editManager.isInTransaction() ? editManager.getPendingChangeCount() : 0;
        List<String> removed = manager.removeAll();
        editManager.hardReset();
        LOG.info("reboot_spoon: removed {} project(s), pending transaction with {} change(s) discarded ",
                removed.size(), pendingTx);
        return new RebootResult(List.copyOf(removed), pendingTx);
    }

    // ==================== check_project_dirty ====================

    static DirtyReport checkDirty(ProjectManager manager, ProjectEntry entry, String name) {
        ProjectState state = manager.getProjectState(name);

        ProjectSnapshot currentSnapshot = captureFreshSnapshot(manager, entry);
        if (currentSnapshot == null) {
            throw new RewispException(ErrorType.SNAPSHOT_NOT_AVAILABLE,
                    "Snapshot capture failed - unable to check the dirty state of project '" + entry.name() + "'.");
        }
        String previousId = state != null && state.snapshotId() != null ? state.snapshotId().toString() : null;

        // Build-Dateien getrennt hashen (der Snapshot enthaelt nur .java-Dateien)
        List<String> changedBuildFiles = manager.changedBuildFiles(entry);
        if (!changedBuildFiles.isEmpty()) {
            if (state != null) {
                state.dirtyState().markBuildConfiguration();
            }
            manager.invalidateBuildContext(name);
            if (changedBuildFiles.contains("parent-pom")) {
                manager.invalidateProjectsSharingParentPom(entry);
            }
        }

        // Ohne verwertbare Baseline ist der Zustand "unbekannt", nicht "sauber"
        ProjectSnapshot baseline = state != null ? state.snapshot() : null;
        if (baseline == null || baseline.files().isEmpty()) {
            return new DirtyReport(entry.name(), previousId, currentSnapshot.id().toString(), true, 0, List.of(),
                    List.of(), List.of(), 0, changedBuildFiles, null);
        }

        DirtyCheckResult diff = DirtyChecker.checkDirtyAgainstSnapshot(currentSnapshot, baseline);

        // Eigene Edits (ueber rewisp geschrieben, noch nicht in den Snapshot uebernommen) sind keine externen Aenderungen
        Path root = entry.originalProjectDir() != null ? entry.originalProjectDir() : entry.projectDir();
        Set<String> own = new HashSet<>();
        // Nur editedFiles zaehlen als eigene Edits; dirtyFiles kann das ganze Projekt umfassen (markDirty nach
        // move_class/add_package setzt jede Quelldatei auf dirty) und wuerde die Anzeige aufblaehen (N38).
        Set<String> edited = new HashSet<>();
        for (Path p : entry.editedFiles()) edited.add(relative(root, p));
        own.addAll(edited);
        for (Path p : entry.dirtyFiles()) own.add(relative(root, p));
        List<String> changed = external(diff.changedFiles(), own);
        List<String> deleted = external(diff.deletedFiles(), own);
        List<String> added = external(diff.newFiles(), own);
        addExternalChangesToOwnFiles(manager, entry, root, changed, deleted);

        DirtyFlags flags = state == null ? null : new DirtyFlags(state.dirtyState().isBuildModelDirty(),
                state.dirtyState().isClasspathDirty(), !changedBuildFiles.isEmpty(),
                state.dirtyState().requiresFullSemanticRefresh());
        return new DirtyReport(entry.name(), previousId, currentSnapshot.id().toString(), false, diff.filesChecked(), changed, deleted, added,
                edited.size(), changedBuildFiles, flags);
    }

    /**
     * Files edited via rewisp can also have external changes: the disk state
     * is compared with the fingerprint rewisp recorded after its own write. A mismatch is an
     * external change; a missing file (that rewisp did not delete itself) is an external deletion.
     */
    private static void addExternalChangesToOwnFiles(ProjectManager manager, ProjectEntry entry, Path root,
            List<String> changed, List<String> deleted) {
        var removing = manager.editManager();
        if (removing != null) {
            // Von rewisp selbst geloeschte/verschobene Dateien sind keine externe Loeschung
            deleted.removeIf(rel -> removing.isRemovedByEdit(root != null ? root.resolve(rel) : Path.of(rel)));
        }
        Set<Path> ownFiles = new HashSet<>(entry.editedFiles());
        ownFiles.addAll(entry.dirtyFiles());
        for (Path p : ownFiles) {
            Path abs = p.isAbsolute() ? p : (root != null ? root.resolve(p) : p);
            String rel = relative(root, p);
            try {
                if (!Files.exists(abs)) {
                    var editManager = manager.editManager();
                    boolean removedByUs = editManager != null && editManager.isRemovedByEdit(abs);
                    if (!removedByUs && !deleted.contains(rel)) deleted.add(rel);
                    continue;
                }
                Fingerprint stored = entry.sourceFingerprints().get(PathUtils.canonical(abs));
                if (stored == null) stored = entry.sourceFingerprints().get(abs.normalize());
                if (stored == null) continue;
                Fingerprint onDisk = new Fingerprint(Files.getLastModifiedTime(abs).toMillis(), Files.size(abs));
                if (!stored.equals(onDisk) && !changed.contains(rel)) changed.add(rel);
            } catch (IOException e) {
                // nicht lesbar: kein Urteil
            }
        }
    }

    private static String relative(Path root, Path file) {
        Path r = PathUtils.relativizeFromRoot(root, file);
        return r == null ? file.toString() : r.toString();
    }

    private static List<String> external(List<String> files, Set<String> own) {
        List<String> out = new ArrayList<>();
        for (String f : files) {
            String normalized = f.endsWith(" (read error)") ? f : Path.of(f).normalize().toString();
            if (!own.contains(normalized)) out.add(f);
        }
        return out;
    }

    /** Takes a fresh snapshot through the REWRITE layer. */
    private static ProjectSnapshot captureFreshSnapshot(ProjectManager manager, ProjectEntry entry) {
        try {
            long sequence = manager.getSnapshotSequence().incrementAndGet();
            return manager.getSnapshotService().captureStable(entry, sequence);
        } catch (IOException e) {
            LOG.warn("Failed to capture fresh snapshot for dirty check: {}", e.getMessage());
            return null;
        }
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.BuildDetector;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.util.PathUtils;

/**
 * Holds the rewrite state per project ({@link ProjectState}) and composes the
 * <em>effective</em> state: snapshot, supplemented by dirty files (fresh from the
 * disk) and by the overlay of an active transaction. From the outside it behaves as if
 * "written to disk", without anything being written. This is the only place
 * that composes this state.
 *
 * @author Janusch Rentenatus
 */
public final class RewriteStateService {

    private static final Logger LOG = LoggerFactory.getLogger(RewriteStateService.class);

    private final SnapshotService snapshotService = new SnapshotService();
    private final AtomicLong sequence = new AtomicLong(0);
    private final ConcurrentHashMap<String, ProjectState> states = new ConcurrentHashMap<>();
    private volatile TransactionOverlay overlay;

    public SnapshotService snapshotService() {
        return snapshotService;
    }

    public AtomicLong sequence() {
        return sequence;
    }

    public void setOverlay(TransactionOverlay overlay) {
        this.overlay = overlay;
    }

    /** State of the project (exact project name) or {@code null}. */
    public ProjectState state(String projectName) {
        return projectName != null ? states.get(projectName) : null;
    }

    public void remove(String projectName) {
        states.remove(projectName);
    }

    public void clear() {
        states.clear();
    }

    /**
     * Takes the first snapshot during load. The freshly built model corresponds by
     * definition to this snapshot; {@code sourceDirs} are the source roots of the model.
     */
    public ProjectState captureInitial(ProjectEntry entry, BuildDetector.BuildInfo buildInfo,
                                       List<Path> sourceDirs) {
        ProjectState state = new ProjectState();
        state.setBuildInfo(buildInfo);
        try {
            var snapshot = snapshotService.captureStable(entry, sequence.incrementAndGet());
            state.setSnapshotId(snapshot.id());
            state.setSnapshot(snapshot);
            state.setSpoonSource(entry.model(), snapshot.id().rootHash());
            state.setSpoonSourceDirs(sourceDirs);
            LOG.info("Initial snapshot captured: {}", snapshot.id());
        } catch (IOException e) {
            LOG.warn("Initial snapshot failed for project '{}': {}", entry.name(), e.getMessage());
        }
        state.setBuildFileBaseline(BuildFileHasher.hash(entry.originalProjectDir()));
        states.put(entry.name(), state);
        return state;
    }

    /** Build files that changed since the state of the build context (resp. since load). */
    public List<String> changedBuildFiles(ProjectEntry entry) {
        ProjectState state = states.get(entry.name());
        if (state == null) return List.of();
        return BuildFileHasher.changed(state.buildFileBaseline(), BuildFileHasher.hash(entry.originalProjectDir()));
    }

    /** Adopts the current state of the build files as baseline (after successful resolution). */
    public void acceptBuildFiles(ProjectEntry entry) {
        ProjectState state = states.get(entry.name());
        if (state != null) state.setBuildFileBaseline(BuildFileHasher.hash(entry.originalProjectDir()));
    }

    /** true when dirty files or a transaction overlay could have overtaken the snapshot. */
    public boolean hasUnsyncedChanges(ProjectEntry entry) {
        if (!entry.dirtyFiles().isEmpty()) return true;
        TransactionOverlay tx = overlay;
        return tx != null && tx.isInTransaction()
                && (!tx.getPendingChanges().isEmpty() || !tx.getPendingDeletions().isEmpty());
    }

    /** Effective snapshot without full rebuild. */
    public ProjectSnapshot effectiveSnapshot(ProjectEntry entry) throws IOException {
        return effectiveSnapshot(entry, false);
    }

    /**
     * @param broadScope true: rebuild the snapshot completely fresh from the disk (instead of only
     *                   replacing the dirty files)
     * @return the effective snapshot or {@code null} when no snapshot exists
     */
    public ProjectSnapshot effectiveSnapshot(ProjectEntry entry, boolean broadScope) throws IOException {
        ProjectState state = states.get(entry.name());
        if (state == null || state.snapshot() == null) return null;
        ProjectSnapshot snap = broadScope
                ? snapshotService.captureStable(entry, sequence.incrementAndGet())
                : state.snapshot();
        Path root = entry.originalProjectDir();

        if (!broadScope && !entry.dirtyFiles().isEmpty()) {
            Set<Path> rel = new HashSet<>();
            for (Path dirty : entry.dirtyFiles()) {
                Path r = PathUtils.relativizeFromRoot(root, dirty);
                if (isInsideProject(r)) rel.add(r);
            }
            if (!rel.isEmpty()) {
                snap = snapshotService.refreshFiles(entry, snap, rel, sequence.incrementAndGet());
            }
        }

        TransactionOverlay tx = overlay;
        if (tx != null && tx.isInTransaction()) {
            Map<Path, String> contents = new LinkedHashMap<>();
            for (Map.Entry<Path, String> e : tx.getPendingChanges().entrySet()) {
                Path r = PathUtils.relativizeFromRoot(root, e.getKey());
                if (isInsideProject(r)) contents.put(r, e.getValue());
            }
            if (!contents.isEmpty()) {
                snap = snapshotService.applyContents(entry, snap, contents, sequence.incrementAndGet());
            }
            Set<Path> deleted = new HashSet<>();
            for (Path p : tx.getPendingDeletions()) {
                Path r = PathUtils.relativizeFromRoot(root, p);
                if (isInsideProject(r)) deleted.add(r);
            }
            if (!deleted.isEmpty()) {
                snap = snapshotService.removeFiles(entry, snap, deleted, sequence.incrementAndGet());
            }
        }
        return snap;
    }

    private static boolean isInsideProject(Path relative) {
        return relative != null && !relative.isAbsolute() && !relative.startsWith("..");
    }
}

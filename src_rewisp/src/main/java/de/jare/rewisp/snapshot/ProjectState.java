package de.jare.rewisp.snapshot;

import java.util.List;
import java.util.Map;

import java.nio.file.Path;

/**
 * Extended project state data, managed in parallel to ProjectEntry.
 * Contains snapshot and dirty state information needed for the
 * OpenRewrite pipeline.
 */
public final class ProjectState {

    private volatile SnapshotId snapshotId;
    private volatile ProjectSnapshot snapshot;
    private final ProjectDirtyState dirtyState;
    private volatile de.jare.rewisp.BuildDetector.BuildInfo buildInfo;

    public ProjectState() {
        this.dirtyState = new ProjectDirtyState();
    }

    public SnapshotId snapshotId() { return snapshotId; }
    public void setSnapshotId(SnapshotId id) { this.snapshotId = id; }

    public ProjectSnapshot snapshot() { return snapshot; }
    public void setSnapshot(ProjectSnapshot snapshot) { this.snapshot = snapshot; }

    /** Hashes of the build files the current build context originates from (see {@link BuildFileHasher}). */
    private volatile Map<String, String> buildFileBaseline = Map.of();

    public Map<String, String> buildFileBaseline() { return buildFileBaseline; }
    public void setBuildFileBaseline(Map<String, String> baseline) {
        this.buildFileBaseline = Map.copyOf(baseline);
    }

    /** Spoon model last built from the rewrite state (identity comparison). */
    private volatile spoon.reflect.CtModel spoonModel;
    /** Hash of the rewrite state (snapshot + transaction overlay) from which {@link #spoonModel} was built. */
    private volatile String spoonSourceKey;

    /** Source directories of the launcher built during load (determine which snapshot files belong into the model). */
    private volatile List<Path> spoonSourceDirs = List.of();

    public List<Path> spoonSourceDirs() { return spoonSourceDirs; }
    public void setSpoonSourceDirs(List<Path> dirs) { this.spoonSourceDirs = List.copyOf(dirs); }

    public spoon.reflect.CtModel spoonModel() { return spoonModel; }
    public String spoonSourceKey() { return spoonSourceKey; }
    public void setSpoonSource(spoon.reflect.CtModel model, String sourceKey) {
        this.spoonModel = model;
        this.spoonSourceKey = sourceKey;
        this.spoonRebuildError = null;
    }

    /** Error of the last failed rebuild (then the old model still applies), otherwise {@code null}. */
    private volatile String spoonRebuildError;

    public String spoonRebuildError() { return spoonRebuildError; }
    public void setSpoonRebuildError(String error) { this.spoonRebuildError = error; }

    public ProjectDirtyState dirtyState() { return dirtyState; }

    public de.jare.rewisp.BuildDetector.BuildInfo buildInfo() { return buildInfo; }
    public void setBuildInfo(de.jare.rewisp.BuildDetector.BuildInfo info) { this.buildInfo = info; }
}

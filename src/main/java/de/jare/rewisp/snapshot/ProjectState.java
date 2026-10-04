package de.jare.rewisp.snapshot;

import java.nio.file.Path;

/**
 * Erweiterte Projekt-Zustandsdaten, die parallel zu ProjectEntry verwaltet werden.
 * Enthaelt Snapshot- und Dirty-State-Informationen, die fuer die
 * OpenRewrite-Pipeline benoetigt werden.
 */
public final class ProjectState {

    private SnapshotId snapshotId;
    private ProjectSnapshot snapshot;
    private ProjectDirtyState dirtyState;
    private de.jare.rewisp.BuildDetector.BuildInfo buildInfo;

    public ProjectState() {
        this.dirtyState = new ProjectDirtyState();
    }

    public SnapshotId snapshotId() { return snapshotId; }
    public void setSnapshotId(SnapshotId id) { this.snapshotId = id; }

    public ProjectSnapshot snapshot() { return snapshot; }
    public void setSnapshot(ProjectSnapshot snapshot) { this.snapshot = snapshot; }

    public ProjectDirtyState dirtyState() { return dirtyState; }

    public de.jare.rewisp.BuildDetector.BuildInfo buildInfo() { return buildInfo; }
    public void setBuildInfo(de.jare.rewisp.BuildDetector.BuildInfo info) { this.buildInfo = info; }
}

package de.jare.rewisp.snapshot;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Build-dirty state of a project: marked by the {@link ChangeTracker} when the build configuration
 * changes, and reset after a successful resolution.
 */
public final class ProjectDirtyState {

    private final AtomicBoolean buildModelDirty = new AtomicBoolean(false);
    private final AtomicBoolean classpathDirty = new AtomicBoolean(false);

    /** Counts every marking of the build configuration; allows limiting a reset to the observed state. */
    private final AtomicLong buildDirtyVersion = new AtomicLong();

    public long buildDirtyVersion() {
        return buildDirtyVersion.get();
    }

    public void markBuildConfiguration() {
        buildDirtyVersion.incrementAndGet();
        buildModelDirty.set(true);
        classpathDirty.set(true);
    }

    /**
     * Resets the build-dirty state only if no new
     * change of the build configuration has been marked since {@code observedVersion}
     * (no lost update during a running resolution).
     *
     * @return true if it was reset
     */
    public synchronized boolean clearBuildDirtyIfUnchanged(long observedVersion) {
        if (buildDirtyVersion.get() != observedVersion) return false;
        buildModelDirty.set(false);
        classpathDirty.set(false);
        return true;
    }

    public boolean isBuildModelDirty() { return buildModelDirty.get(); }
    public boolean isClasspathDirty() { return classpathDirty.get(); }
    public boolean requiresFullSemanticRefresh() {
        return buildModelDirty.get() || classpathDirty.get();
    }
}

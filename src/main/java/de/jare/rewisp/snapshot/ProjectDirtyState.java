package de.jare.rewisp.snapshot;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProjectDirtyState {

    private final ConcurrentHashMap<Path, DirtyEntry> sourceDirty = new ConcurrentHashMap<>();
    private final AtomicBoolean buildModelDirty = new AtomicBoolean(false);
    private final AtomicBoolean classpathDirty = new AtomicBoolean(false);
    private final AtomicBoolean generatedSourcesDirty = new AtomicBoolean(false);

    public void markSource(DirtyEntry entry) {
        sourceDirty.merge(entry.relativePath(), entry,
                (oldValue, newValue) ->
                        newValue.observedSequence() >= oldValue.observedSequence()
                                ? newValue : oldValue);
    }

    public void markBuildConfiguration() {
        buildModelDirty.set(true);
        classpathDirty.set(true);
    }

    public void markClasspath() {
        classpathDirty.set(true);
    }

    public void clearSourceDirty(Path path) {
        sourceDirty.remove(path);
    }

    public void clearBuildDirty() {
        buildModelDirty.set(false);
        classpathDirty.set(false);
    }

    public void clearAll() {
        sourceDirty.clear();
        buildModelDirty.set(false);
        classpathDirty.set(false);
        generatedSourcesDirty.set(false);
    }

    public Map<Path, DirtyEntry> sourceDirty() {
        return Map.copyOf(sourceDirty);
    }

    public boolean isBuildModelDirty() { return buildModelDirty.get(); }
    public boolean isClasspathDirty() { return classpathDirty.get(); }
    public boolean isGeneratedSourcesDirty() { return generatedSourcesDirty.get(); }

    public boolean requiresFullSemanticRefresh() {
        return buildModelDirty.get() || classpathDirty.get() || generatedSourcesDirty.get();
    }

    public boolean hasSourceChanges() {
        return !sourceDirty.isEmpty();
    }
}

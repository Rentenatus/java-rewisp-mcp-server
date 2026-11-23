/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import de.jare.rewisp.util.ProjectLayout;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates filesystem events of the {@link WorkspaceWatcher} into the build-dirty state.
 *
 * <p>Since decision D4 (plan 2.8) the watcher only watches the build configuration ({@code pom.xml},
 * {@code build.gradle*}, {@code settings.gradle*}, {@code gradle.properties}). Changes to source files are
 * deliberately not tracked: the snapshot-based dirty check ({@code check_project_dirty}) detects them via content hashes,
 * a second, never-read state ({@code sourceDirty}) would only cost memory.</p>
 */
public final class ChangeTracker implements FileChangeListener {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ChangeTracker.class);

    private final Map<String, ProjectDirtyState> dirtyStates = new ConcurrentHashMap<>();
    private final Map<String, Path> projectRoots = new ConcurrentHashMap<>();

    /**
     * Registers a project for tracking.
     *
     * @param projectName the project name
     * @param projectRoot the project root path
     * @param dirtyState the dirty state of the project
     */
    public void registerProject(String projectName, Path projectRoot, ProjectDirtyState dirtyState) {
        projectRoots.put(projectName, projectRoot);
        dirtyStates.put(projectName, dirtyState);
    }

    /**
     * Called by the WorkspaceWatcher when a file changes. Only build configuration files within the
     * project mark the build state as stale; everything else is ignored.
     */
    @Override
    public void onFileChanged(String projectName, Path absolutePath, String eventKind) {
        Path projectRoot = projectRoots.get(projectName);
        ProjectDirtyState state = dirtyStates.get(projectName);
        if (projectRoot == null || state == null) return;
        if (!absolutePath.startsWith(projectRoot)) return; // ausserhalb des Projekt-Roots
        Path name = absolutePath.getFileName();
        if (name != null && ProjectLayout.isBuildConfigFile(name.toString())
                && !IgnoreRules.defaults().isIgnoredRelative(projectRoot.relativize(absolutePath).normalize())) {
            LOG.debug("Build configuration changed ({}): {}", eventKind, absolutePath);
            state.markBuildConfiguration();
        }
    }

    /** Deregisters a project. */
    public void unregisterProject(String projectName) {
        dirtyStates.remove(projectName);
        projectRoots.remove(projectName);
    }
}

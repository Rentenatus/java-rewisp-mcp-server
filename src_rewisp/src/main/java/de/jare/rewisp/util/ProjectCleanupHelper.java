/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import de.jare.rewisp.lombok.LombokDelomboker;


import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.ProjectLoader;
import de.jare.rewisp.snapshot.ChangeTracker;
import de.jare.rewisp.snapshot.WorkspaceWatcher;
import de.jare.rewisp.snapshot.WorkspaceRegistry;

/**
 * Static utility methods for cleaning up project resources.
 *
 * <p>Centralizes the cleanup logic for project removal and replacement.
 * All methods are static and take their dependencies as parameters.
 */
public final class ProjectCleanupHelper {

    private ProjectCleanupHelper() {}

    /**
     * Performs a complete cleanup of a project entry.
     *
     * <p>This method:
     * <ol>
     *   <li>Cleans up the project directory via ProjectLoader</li>
     *   <li>Cleans up the delomboked directory if the project was delomboked</li>
     *   <li>Resets the backup for the project via EditManager</li>
     *   <li>Unregisters the project from ChangeTracker, WorkspaceWatcher, and WorkspaceRegistry</li>
     * </ol>
     *
     * @param entry the project entry to clean up
     * @param projectLoader the project loader for directory cleanup
     * @param lombokDelomboker the delomboker for delomboked directory cleanup
     * @param editManager the edit manager for backup cleanup (may be null)
     * @param changeTracker the change tracker for project deregistration (may be null)
     * @param workspaceWatcher the workspace watcher for stopping file watching (may be null)
     * @param workspaceRegistry the workspace registry for project unregistration (may be null)
     */
    public static void cleanupProject(
            ProjectEntry entry,
            ProjectLoader projectLoader,
            LombokDelomboker lombokDelomboker,
            EditManager editManager,
            ChangeTracker changeTracker,
            WorkspaceWatcher workspaceWatcher,
            WorkspaceRegistry workspaceRegistry
    ) {
        if (entry == null) return;

        cleanupProjectDirectory(entry, projectLoader, lombokDelomboker);
        cleanupBackup(entry, editManager);
        unregisterFromTracking(entry, changeTracker, workspaceWatcher, workspaceRegistry);
    }

    /**
     * Cleans up the project directory and delomboked directory if applicable.
     *
     * @param entry the project entry
     * @param projectLoader the project loader
     * @param lombokDelomboker the delomboker
     */
    public static void cleanupProjectDirectory(
            ProjectEntry entry,
            ProjectLoader projectLoader,
            LombokDelomboker lombokDelomboker
    ) {
        cleanupAnalyzedDirectory(entry, projectLoader, lombokDelomboker);
        cleanupOriginalDirectory(entry, projectLoader);
    }

    /** The analyzed directory (for Delombok the delombok result). */
    public static void cleanupAnalyzedDirectory(
            ProjectEntry entry,
            ProjectLoader projectLoader,
            LombokDelomboker lombokDelomboker
    ) {
        if (entry.projectDir() != null) {
            projectLoader.cleanup(entry.projectDir());
        }
        if (entry.delomboked() && entry.projectDir() != null) {
            lombokDelomboker.cleanup(entry.projectDir());
        }
    }

    /**
     * The original source directory (clone/unpacking). For Delombok it is not the analyzed
     * directory and would otherwise remain in the temp directory.
     */
    public static void cleanupOriginalDirectory(ProjectEntry entry, ProjectLoader projectLoader) {
        if (entry.originalProjectDir() != null) {
            projectLoader.cleanup(entry.originalProjectDir());
        }
    }

    /**
     * Resets the backup for the project.
     *
     * @param entry the project entry
     * @param editManager the edit manager (may be null)
     */
    public static void cleanupBackup(ProjectEntry entry, EditManager editManager) {
        if (editManager != null && entry.name() != null) {
            editManager.resetBackup(entry.name());
        }
    }

    /**
     * Unregisters the project from tracking components.
     *
     * @param entry the project entry
     * @param changeTracker the change tracker (may be null)
     * @param workspaceWatcher the workspace watcher (may be null)
     * @param workspaceRegistry the workspace registry (may be null)
     */
    public static void unregisterFromTracking(
            ProjectEntry entry,
            ChangeTracker changeTracker,
            WorkspaceWatcher workspaceWatcher,
            WorkspaceRegistry workspaceRegistry
    ) {
        String name = entry.name();
        if (name == null) return;

        if (changeTracker != null) {
            changeTracker.unregisterProject(name);
        }
        if (workspaceWatcher != null) {
            workspaceWatcher.stopWatching(name);
        }
        if (workspaceRegistry != null) {
            workspaceRegistry.unregister(name);
        }
    }

    /**
     * Resets the backup for a specific project.
     *
     * @param projectName the name of the project
     * @param editManager the edit manager (may be null)
     */
    public static void resetBackup(String projectName, EditManager editManager) {
        if (editManager != null && projectName != null) {
            editManager.resetBackup(projectName);
        }
    }

    /**
     * Unregisters a project from all tracking components.
     *
     * @param projectName the name of the project to unregister
     * @param changeTracker the change tracker (may be null)
     * @param workspaceWatcher the workspace watcher (may be null)
     * @param workspaceRegistry the workspace registry (may be null)
     */
    public static void unregisterProject(
            String projectName,
            ChangeTracker changeTracker,
            WorkspaceWatcher workspaceWatcher,
            WorkspaceRegistry workspaceRegistry
    ) {
        if (projectName == null) return;

        if (changeTracker != null) {
            changeTracker.unregisterProject(projectName);
        }
        if (workspaceWatcher != null) {
            workspaceWatcher.stopWatching(projectName);
        }
        if (workspaceRegistry != null) {
            workspaceRegistry.unregister(projectName);
        }
    }
}

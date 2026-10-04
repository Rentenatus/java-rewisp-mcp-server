/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import de.jare.rewisp.util.CollectionUtils;
import de.jare.rewisp.util.PathUtils;
import spoon.Launcher;
import spoon.reflect.CtModel;

/**
 * Factory for creating and modifying {@link ProjectEntry} instances.
 *
 * <p>Centralizes the creation of modified ProjectEntry instances to avoid
 * code duplication when updating dirty/edited files or expiration status.
 * All methods return new immutable ProjectEntry instances.
 */
public final class ProjectEntryFactory {

    private ProjectEntryFactory() {}

    /**
     * Creates a new ProjectEntry with updated dirty files.
     * Used by markDirty() operations.
     *
     * @param entry the original entry
     * @param dirtyFiles the new set of dirty files (normalized)
     * @return a new ProjectEntry with updated dirty files
     */
    public static ProjectEntry withDirtyFiles(ProjectEntry entry, Set<Path> dirtyFiles) {
        return new ProjectEntry(
                entry.name(),
                entry.alias(),
                entry.expiryDate(),
                entry.projectDir(),
                entry.launcher(),
                entry.model(),
                entry.buildType(),
                entry.delomboked(),
                entry.lombokVersion(),
                entry.originalProjectDir(),
                entry.originalSource(),
                entry.sourceFingerprints(),
                entry.expired(),
                entry.editable(),
                entry.modulesDetected(),
                entry.modulesLoaded(),
                normalizeDirtyFiles(dirtyFiles),
                entry.editedFiles()
        );
    }

    /**
     * Creates a new ProjectEntry with updated edited files.
     *
     * @param entry the original entry
     * @param editedFiles the new set of edited files (normalized)
     * @return a new ProjectEntry with updated edited files
     */
    public static ProjectEntry withEditedFiles(ProjectEntry entry, Set<Path> editedFiles) {
        return new ProjectEntry(
                entry.name(),
                entry.alias(),
                entry.expiryDate(),
                entry.projectDir(),
                entry.launcher(),
                entry.model(),
                entry.buildType(),
                entry.delomboked(),
                entry.lombokVersion(),
                entry.originalProjectDir(),
                entry.originalSource(),
                entry.sourceFingerprints(),
                entry.expired(),
                entry.editable(),
                entry.modulesDetected(),
                entry.modulesLoaded(),
                entry.dirtyFiles(),
                normalizeDirtyFiles(editedFiles)
        );
    }

    /**
     * Creates a new ProjectEntry with updated dirty AND edited files.
     *
     * @param entry the original entry
     * @param dirtyFiles the new set of dirty files
     * @param editedFiles the new set of edited files
     * @return a new ProjectEntry with updated dirty and edited files
     */
    public static ProjectEntry withDirtyAndEditedFiles(
            ProjectEntry entry,
            Set<Path> dirtyFiles,
            Set<Path> editedFiles
    ) {
        return new ProjectEntry(
                entry.name(),
                entry.alias(),
                entry.expiryDate(),
                entry.projectDir(),
                entry.launcher(),
                entry.model(),
                entry.buildType(),
                entry.delomboked(),
                entry.lombokVersion(),
                entry.originalProjectDir(),
                entry.originalSource(),
                entry.sourceFingerprints(),
                entry.expired(),
                entry.editable(),
                entry.modulesDetected(),
                entry.modulesLoaded(),
                normalizeDirtyFiles(dirtyFiles),
                normalizeDirtyFiles(editedFiles)
        );
    }

    /**
     * Creates a new ProjectEntry with updated expiration status.
     * Used by markExpired() operations.
     *
     * @param entry the original entry
     * @param expired true to mark as expired
     * @return a new ProjectEntry with updated expiration status
     */
    public static ProjectEntry withExpired(ProjectEntry entry, boolean expired) {
        return new ProjectEntry(
                entry.name(),
                entry.alias(),
                entry.expiryDate(),
                entry.projectDir(),
                entry.launcher(),
                entry.model(),
                entry.buildType(),
                entry.delomboked(),
                entry.lombokVersion(),
                entry.originalProjectDir(),
                entry.originalSource(),
                entry.sourceFingerprints(),
                expired,
                entry.editable(),
                entry.modulesDetected(),
                entry.modulesLoaded(),
                entry.dirtyFiles(),
                entry.editedFiles()
        );
    }

    /**
     * Creates a new ProjectEntry with all source files marked as dirty.
     * This is used when the entire model is considered stale.
     *
     * @param entry the original entry
     * @return a new ProjectEntry with all source files in dirtyFiles
     */
    public static ProjectEntry withAllFilesDirty(ProjectEntry entry) {
        Set<Path> allFiles = entry.sourceFingerprints() != null
                ? PathUtils.normalizeSet(entry.sourceFingerprints().keySet())
                : Collections.emptySet();
        return withDirtyFiles(entry, allFiles);
    }

    /**
     * Creates a copy of a ProjectEntry with a single additional dirty file.
     *
     * @param entry the original entry
     * @param file the file to add to dirty files
     * @return a new ProjectEntry with the file added to dirty files
     */
    public static ProjectEntry addDirtyFile(ProjectEntry entry, Path file) {
        Set<Path> updatedDirty = CollectionUtils.addToCopy(entry.dirtyFiles(), file);
        return withDirtyFiles(entry, updatedDirty);
    }

    /**
     * Creates a copy of a ProjectEntry with a single additional edited file.
     *
     * @param entry the original entry
     * @param file the file to add to edited files
     * @return a new ProjectEntry with the file added to edited files
     */
    public static ProjectEntry addEditedFile(ProjectEntry entry, Path file) {
        Set<Path> updatedEdited = CollectionUtils.addToCopy(entry.editedFiles(), file);
        return withEditedFiles(entry, updatedEdited);
    }

    /**
     * Normalizes a set of dirty/edited files by normalizing each path.
     *
     * @param files the set of files to normalize
     * @return an unmodifiable set of normalized paths
     */
    private static Set<Path> normalizeDirtyFiles(Set<Path> files) {
        return files != null
                ? PathUtils.normalizeSet(files)
                : Collections.emptySet();
    }

    // ==================== SPECIALIZED FACTORIES ====================

    /**
     * Creates a minimal ProjectEntry for testing purposes.
     *
     * @param name the project name
     * @param projectDir the project directory
     * @return a new minimal ProjectEntry
     */
    public static ProjectEntry createTestEntry(String name, Path projectDir) {
        return new ProjectEntry(
                name,
                name,
                Instant.now().plusSeconds(3600),
                projectDir,
                null,
                null,
                "RAW",
                false,
                null,
                projectDir,
                projectDir.toString(),
                Map.of(),
                false,
                true,
                1,
                1,
                Set.of(),
                Set.of()
        );
    }
}

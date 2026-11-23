/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import de.jare.rewisp.util.PathUtils;

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

}

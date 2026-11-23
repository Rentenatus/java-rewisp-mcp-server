/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.util.List;

/**
 * Result of a dirty check for a project.
 * Contains information about changed, deleted and new files.
 */
public record DirtyCheckResult(
        int filesChecked,
        List<String> changedFiles,
        List<String> deletedFiles,
        List<String> newFiles
) {
    /**
     * Checks whether the project is dirty (changes present).
     */
    public boolean isDirty() {
        return !changedFiles.isEmpty() || !deletedFiles.isEmpty() || !newFiles.isEmpty();
    }
}

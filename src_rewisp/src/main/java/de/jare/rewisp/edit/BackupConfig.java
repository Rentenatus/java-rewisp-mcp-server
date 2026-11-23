/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import java.util.Set;

/**
 * Configuration for backup operations in EditManager.
 *
 * <p>Specifies which file extensions should be included in backups.
 * By default, includes Java source files and common build/configuration files.
 */
public final class BackupConfig {

    /**
     * Default file extensions to backup.
     * Includes Java source files, build files, and configuration files.
     */
    public static final Set<String> DEFAULT_EXTENSIONS = Set.of(
            // Java source files
            ".java",
            // Kotlin source files
            ".kt", ".kts",
            // Groovy source files
            ".groovy",
            // Build configuration files
            "pom.xml",
            "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts",
            "build.xml",
            // Configuration files
            ".properties",
            ".yml", ".yaml",
            ".json",
            ".xml",
            // Other
            ".md",
            ".txt"
    );

    /**
     * Minimal backup configuration (Java files only).
     */
    public static final Set<String> MINIMAL_EXTENSIONS = Set.of(
            ".java"
    );

    private final Set<String> extensions;

    /**
     * Creates a BackupConfig with the default extensions.
     */
    public BackupConfig() {
        this(DEFAULT_EXTENSIONS);
    }

    /**
     * Creates a BackupConfig with the specified extensions.
     *
     * @param extensions the set of file extensions to include in backups
     */
    public BackupConfig(Set<String> extensions) {
        this.extensions = Set.copyOf(extensions);
    }

    /**
     * Creates a BackupConfig with minimal extensions (Java only).
     *
     * @return a BackupConfig with minimal extensions
     */
    public static BackupConfig minimal() {
        return new BackupConfig(MINIMAL_EXTENSIONS);
    }

    /**
     * Creates a BackupConfig with all extensions.
     *
     * @return a BackupConfig with all default extensions
     */
    public static BackupConfig all() {
        return new BackupConfig(DEFAULT_EXTENSIONS);
    }

    /**
     * Checks if a file with the given name should be backed up.
     *
     * @param fileName the file name to check
     * @return true if the file should be backed up
     */
    public boolean shouldBackup(String fileName) {
        if (fileName == null) return false;
        for (String ext : extensions) {
            if (fileName.endsWith(ext) || fileName.equals(ext)) {
                return true;
            }
        }
        return false;
    }

}

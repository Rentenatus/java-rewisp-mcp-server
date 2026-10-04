/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import java.nio.file.Path;
import java.util.Set;

/**
 * Detects build configuration files (Maven, Gradle, etc.).
 *
 * <p>Configurable set of build file patterns for extensibility.
 */
public final class BuildFileDetector {

    public static final BuildFileDetector INSTANCE = new BuildFileDetector();

    private final Set<String> buildFilePatterns;

    /**
     * Creates a BuildFileDetector with default patterns for Maven and Gradle.
     */
    public BuildFileDetector() {
        this(Set.of(
                "pom.xml",
                "build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts",
                "build.xml",
                "gradle.properties"
        ));
    }

    /**
     * Creates a BuildFileDetector with custom patterns.
     *
     * @param patterns the set of file name patterns to recognize as build files
     */
    public BuildFileDetector(Set<String> patterns) {
        this.buildFilePatterns = Set.copyOf(patterns);
    }

    /**
     * Checks if a path represents a build configuration file.
     *
     * @param path the path to check, may be null
     * @return true if the path is a recognized build file
     */
    public boolean isBuildFile(Path path) {
        if (path == null) return false;
        String fileName = path.getFileName() != null
                ? path.getFileName().toString().toLowerCase()
                : "";
        return buildFilePatterns.contains(fileName);
    }

    /**
     * Checks if a file name represents a build configuration file.
     *
     * @param fileName the file name to check, may be null
     * @return true if the file name is a recognized build file
     */
    public boolean isBuildFile(String fileName) {
        if (fileName == null) return false;
        return buildFilePatterns.contains(fileName.toLowerCase());
    }

    /**
     * Returns the set of recognized build file patterns.
     *
     * @return an unmodifiable set of build file patterns
     */
    public Set<String> getPatterns() {
        return Set.copyOf(buildFilePatterns);
    }
}

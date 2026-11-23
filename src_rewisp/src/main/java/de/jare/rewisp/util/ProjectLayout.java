/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import java.util.List;

/**
 * Central constants for project structure, build files and auxiliary files.
 * Previously scattered as string literals in about 25 classes.
 *
 * @author Janusch Rentenatus
 */
public final class ProjectLayout {

    /** Dateiendung von Java-Quelldateien. */
    public static final String JAVA_EXTENSION = ".java";

    /** Maven: build file. */
    public static final String POM_FILE = "pom.xml";
    /** Gradle: build files (Groovy and Kotlin DSL). */
    public static final List<String> GRADLE_BUILD_FILES = List.of("build.gradle", "build.gradle.kts");

    /** Gradle: Settings-Dateien. */
    public static final List<String> GRADLE_SETTINGS_FILES = List.of("settings.gradle", "settings.gradle.kts");
    /** Gradle: properties file. */
    public static final String GRADLE_PROPERTIES_FILE = "gradle.properties";
    /** All Gradle configuration files whose change invalidates the build context. */
    public static final List<String> GRADLE_CONFIG_FILES = List.of(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", GRADLE_PROPERTIES_FILE);

    /** Standard source directory (Maven and Gradle), relative to the project. */
    public static final String MAIN_JAVA_DIR = "src/main/java";
    /** Kotlin/Groovy source directories (Gradle), relative to the project. */
    public static final String MAIN_KOTLIN_DIR = "src/main/kotlin";
    public static final String MAIN_GROOVY_DIR = "src/main/groovy";

    /** Standard test directory (Maven and Gradle), relative to the project. */
    public static final String TEST_JAVA_DIR = "src/test/java";

    /** Suffix of the temp file for atomic writing (EditManager). */
    public static final String TEMP_FILE_SUFFIX = ".mcp-tmp";

    /** Name that Spoon delivers for the default package. */
    public static final String UNNAMED_PACKAGE = "unnamed package";

    /** true for pom.xml and the Gradle configuration files (file name without path). */
    public static boolean isBuildConfigFile(String fileName) {
        return POM_FILE.equals(fileName) || GRADLE_CONFIG_FILES.contains(fileName);
    }

    private ProjectLayout() {
    }

    /** Number of package segments; the default package (empty or {@link #UNNAMED_PACKAGE}) has 0. */
    public static int packageSegments(String packageName) {
        if (packageName == null || packageName.isEmpty() || UNNAMED_PACKAGE.equals(packageName)) {
            return 0;
        }
        return packageName.split("\\.").length;
    }
}

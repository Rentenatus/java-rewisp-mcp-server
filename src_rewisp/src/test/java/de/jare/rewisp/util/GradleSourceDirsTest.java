/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.BuildDetector;
import spoon.Launcher;

/**
 * 5.6: Gradle-Projekte laden nur die Main-Quellen der Module (kein {@code src/test}, kein {@code buildSrc}, keine
 * Build-Ausgabe); {@code addGradleSourceDirs} ist erreichbar.
 *
 * @author Janusch Rentenatus
 */
class GradleSourceDirsTest {

    private static void java(Path file, String cls) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package p;\npublic class " + cls + " {}\n");
    }

    private static Path project(Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }\n");
        Files.writeString(dir.resolve("settings.gradle"), "include 'module-a'\n");
        java(dir.resolve("src/main/java/p/Root.java"), "Root");
        java(dir.resolve("src/test/java/p/RootTest.java"), "RootTest");
        Files.createDirectories(dir.resolve("module-a"));
        Files.writeString(dir.resolve("module-a/build.gradle"), "plugins { id 'java' }\n");
        java(dir.resolve("module-a/src/main/java/p/ModA.java"), "ModA");
        java(dir.resolve("module-a/src/test/java/p/ModATest.java"), "ModATest");
        java(dir.resolve("buildSrc/src/main/java/p/BuildHelper.java"), "BuildHelper");
        java(dir.resolve("build/generated/p/Generated.java"), "Generated");
        Files.createDirectories(dir.resolve("docs/src/main/java")); // kein Modul: keine Build-Datei
        return dir;
    }

    @Test
    void sourceDirsAreTheMainDirsOfTheRootAndOfModulesWithABuildFile(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);

        List<Path> dirs = ProjectBuildHelpers.gradleSourceDirs(dir);

        assertEquals(Set.of(dir.resolve("src/main/java"), dir.resolve("module-a/src/main/java")), Set.copyOf(dirs), dirs.toString());
    }

    @Test
    void theNoClasspathLauncherLoadsMainSourcesOnly(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        Launcher launcher = ProjectBuildHelpers.createLauncher(dir, new BuildDetector().detect(dir));

        Set<String> types = launcher.buildModel().getAllTypes().stream()
                .map(t -> t.getSimpleName()).collect(Collectors.toSet());

        assertTrue(types.containsAll(Set.of("Root", "ModA")), types.toString());
        assertFalse(types.contains("RootTest") || types.contains("ModATest"), "test sources must not be loaded: " + types);
        assertFalse(types.contains("BuildHelper") || types.contains("Generated"), types.toString());
    }

    @Test
    void withoutAStandardLayoutTheWholeProjectIsLoaded(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("build.gradle"), "plugins { id 'java' }\n");
        java(tmp.resolve("custom/Thing.java"), "Thing");
        Launcher launcher = ProjectBuildHelpers.createLauncher(tmp, new BuildDetector().detect(tmp));

        assertTrue(launcher.buildModel().getAllTypes().stream().anyMatch(t -> t.getSimpleName().equals("Thing")));
    }
}

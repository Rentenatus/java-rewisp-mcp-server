/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-5.21: Der Gradle-Resolver lehnte jeden Aufruf mit {@code --configuration compileClasspath} als
 * {@code GOAL_NOT_ALLOWED} ab, las die {@code properties}-Ausgabe im falschen Format und waehlte
 * die Executable nicht betriebssystemgerecht.
 *
 * @author Janusch Rentenatus
 */
class GradleBuildResolverFixesTest {

    @TempDir
    Path tempDir;

    @Test
    void mavenLanguageLevel_comesFromEffectivePom() {
        assertEquals("21", MavenBuildResolver.languageLevel("<properties><maven.compiler.release>21</maven.compiler.release></properties>"));
        assertEquals("8", MavenBuildResolver.languageLevel("<source>1.8</source>"));
        assertEquals("17", MavenBuildResolver.languageLevel("<project/>"));
        assertEquals("17", MavenBuildResolver.languageLevel(null));
    }

    @Test
    void validateArgs_acceptsTheResolversOwnCommands() {
        assertDoesNotThrow(() -> GradleBuildResolver.validateArgs(
                List.of("properties", "--quiet", "--no-daemon")));
        assertDoesNotThrow(() -> GradleBuildResolver.validateArgs(
                List.of("dependencies", "--configuration", "compileClasspath", "--quiet", "--no-daemon")));
    }

    @Test
    void validateArgs_rejectsUnknownTasksConfigurationsAndFlags() {
        assertEquals(BuildResolutionErrorCode.GOAL_NOT_ALLOWED, assertThrows(BuildResolutionException.class,
                () -> GradleBuildResolver.validateArgs(List.of("build"))).errorCode());
        assertEquals(BuildResolutionErrorCode.GOAL_NOT_ALLOWED, assertThrows(BuildResolutionException.class,
                () -> GradleBuildResolver.validateArgs(List.of("dependencies", "--configuration", "evil"))).errorCode());
        assertEquals(BuildResolutionErrorCode.GOAL_NOT_ALLOWED, assertThrows(BuildResolutionException.class,
                () -> GradleBuildResolver.validateArgs(List.of("properties", "--init-script", "x.gradle"))).errorCode());
        assertEquals(BuildResolutionErrorCode.GOAL_NOT_ALLOWED, assertThrows(BuildResolutionException.class,
                () -> GradleBuildResolver.validateArgs(List.of("dependencies", "--configuration"))).errorCode());
    }

    @Test
    void propertyValue_readsGradlePropertiesFormat() {
        String out = "rootProject: root project 'demo'\nname: demo\nsourceCompatibility: 21\nversion: unspecified\n";

        assertEquals("demo", GradleBuildResolver.propertyValue(out, "name"));
        assertEquals("21", GradleBuildResolver.propertyValue(out, "sourceCompatibility", "targetCompatibility"));
        assertEquals("x", GradleBuildResolver.propertyValue("name=x\n", "name"));
        assertEquals(null, GradleBuildResolver.propertyValue(out, "missing"));
        assertEquals(null, GradleBuildResolver.propertyValue(null, "name"));
    }

    @Test
    void findExecutable_prefersWrapperForOsAndFallsBackToGradleOnPath() throws Exception {
        Path root = tempDir.resolve("p");
        Files.createDirectories(root);
        assertEquals("gradle.bat", GradleBuildResolver.findExecutable(root, true));
        assertEquals("gradle", GradleBuildResolver.findExecutable(root, false));

        Files.createFile(root.resolve("gradlew.bat"));
        assertEquals(root.resolve("gradlew.bat").toAbsolutePath().toString(),
                GradleBuildResolver.findExecutable(root, true));
        // Unter Unix zaehlt gradlew.bat nicht
        assertEquals("gradle", GradleBuildResolver.findExecutable(root, false));
    }

    @Test
    void filterSourceRoots_usesPathsRelativeToTheProjectRoot() {
        // Das Projekt liegt in einem Pfad, der selbst "test" enthaelt - darf main nicht beeinflussen
        Path root = tempDir.resolve("test").resolve("proj");
        Set<Path> roots = new LinkedHashSet<>(List.of(
                root.resolve("src/main/java"), root.resolve("src/test/java"),
                root.resolve("buildSrc/src/main/java")));

        assertEquals(Set.of(root.resolve("src/main/java"), root.resolve("buildSrc/src/main/java")),
                GradleBuildResolver.filterSourceRoots(root, roots, "main"));
        assertEquals(Set.of(root.resolve("src/test/java")),
                GradleBuildResolver.filterSourceRoots(root, roots, "test"));
        assertTrue(GradleBuildResolver.filterSourceRoots(root, Set.of(), "main").isEmpty());
    }
}

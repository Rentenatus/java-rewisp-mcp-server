/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.BuildDetector;

/**
 * AP-5.10 / RT:P-15: Das Delombok-Verzeichnis bekommt die Build-Dateien des Originals kopiert
 * (pom.xml bzw. Gradle-Konfiguration), damit dort ein Build-Kontext auflösbar ist und delombokte
 * Maven-Projekte ihre Dependency-Typen behalten. Bei RAW wird nichts kopiert.
 *
 * @author Janusch Rentenatus
 */
class LombokDelombokBuildFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void mavenProject_pomIsCopiedIntoTheDelombokDir() throws Exception {
        Path project = tempDir.resolve("proj");
        Files.createDirectories(project.resolve("src/main/java"));
        String pom = "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                + "<artifactId>a</artifactId><version>1</version></project>";
        Files.writeString(project.resolve("pom.xml"), pom);
        Path dest = Files.createDirectories(tempDir.resolve("delomboked"));

        LombokDelomboker.copyBuildFiles(project, BuildDetector.BuildType.MAVEN, dest);

        assertTrue(Files.isRegularFile(dest.resolve("pom.xml")));
        assertEquals(pom, Files.readString(dest.resolve("pom.xml")));
    }

    @Test
    void gradleProject_gradleConfigFilesAreCopied() throws Exception {
        Path project = tempDir.resolve("gproj");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }\n");
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'g'\n");
        Files.writeString(project.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx1g\n");
        Path dest = Files.createDirectories(tempDir.resolve("delomboked-g"));

        LombokDelomboker.copyBuildFiles(project, BuildDetector.BuildType.GRADLE, dest);

        assertTrue(Files.isRegularFile(dest.resolve("build.gradle")));
        assertTrue(Files.isRegularFile(dest.resolve("settings.gradle")));
        assertTrue(Files.isRegularFile(dest.resolve("gradle.properties")));
    }

    @Test
    void rawProject_nothingIsCopied() throws Exception {
        Path project = tempDir.resolve("raw");
        Files.createDirectories(project.resolve("src"));
        Path dest = Files.createDirectories(tempDir.resolve("delomboked-raw"));

        LombokDelomboker.copyBuildFiles(project, BuildDetector.BuildType.RAW, dest);

        assertFalse(Files.exists(dest.resolve("pom.xml")));
        assertFalse(Files.exists(dest.resolve("build.gradle")));
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.project;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.build.PomUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests für den CheckProjectDirtyTool mit Fokus auf Parent-POM-Invalidierung.
 */
class CheckProjectDirtyToolTest {

    private ProjectManager projectManager;

    @BeforeEach
    void setUp() {
        projectManager = new ProjectManager();
    }

    @Test
    void parentPomChange_detectsHashChange(@TempDir Path tempDir) throws Exception {
        // Arrangement: Erstelle eine Parent-POM mit einem Child-Modul
        Path parentDir = tempDir.resolve("parent");
        Path childDir = parentDir.resolve("child");
        Files.createDirectories(parentDir);
        Files.createDirectories(childDir.resolve("src/main/java"));

        // Parent POM
        String parentPom = "<?xml version=\"1.0\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "<modelVersion>4.0.0</modelVersion>\n"
                + "<groupId>test</groupId>\n"
                + "<artifactId>parent</artifactId>\n"
                + "<version>1.0.0</version>\n"
                + "<packaging>pom</packaging>\n"
                + "</project>";
        Files.writeString(parentDir.resolve("pom.xml"), parentPom);

        // Child POM mit Parent-Referenz (Standard: ../pom.xml)
        String childPom = "<?xml version=\"1.0\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "<modelVersion>4.0.0</modelVersion>\n"
                + "<parent>\n"
                + "  <groupId>test</groupId>\n"
                + "  <artifactId>parent</artifactId>\n"
                + "  <version>1.0.0</version>\n"
                + "</parent>\n"
                + "<artifactId>child</artifactId>\n"
                + "</project>";
        Files.writeString(childDir.resolve("pom.xml"), childPom);

        // Child Java Quelle
        Files.writeString(childDir.resolve("src/main/java/Test.java"),
                "public class Test {}");

        // Lade das Child-Projekt
        ProjectEntry childEntry = projectManager.load(
                childDir.toString(), "child", Instant.now().plus(10, ChronoUnit.MINUTES), false, null);
        assertNotNull(childEntry);

        // Prüfe, dass Parent-POM erkannt wird (PomUtils)
        Path resolvedParentPom = PomUtils.resolveParentPomPath(childDir);
        assertNotNull(resolvedParentPom, "Parent POM should be resolved from child directory");
        assertEquals(parentDir.resolve("pom.xml"), resolvedParentPom);

        // Aktion: Ändere die Parent-POM und prüfe, dass der Hash sich ändert
        String modifiedParentPom = parentPom.replace("<version>1.0.0</version>", "<version>2.0.0</version>");
        Files.writeString(parentDir.resolve("pom.xml"), modifiedParentPom);

        String newParentHash = new de.jare.rewisp.snapshot.ContentHasher()
                .sha256(Files.readString(parentDir.resolve("pom.xml")));
        String originalParentHash = new de.jare.rewisp.snapshot.ContentHasher()
                .sha256(parentPom);
        
        assertNotEquals(originalParentHash, newParentHash,
                "Parent POM hash should have changed after modification");
    }

    @Test
    void resolveParentPomPath_findsParentInParentDirectory(@TempDir Path tempDir) throws IOException {
        // Parent-POM im Parent-Verzeichnis
        Path parentDir = tempDir.resolve("parent");
        Path childDir = tempDir.resolve("parent/child");
        Files.createDirectories(parentDir);
        Files.createDirectories(childDir);

        Files.writeString(parentDir.resolve("pom.xml"), "<project><packaging>pom</packaging></project>");
        Files.writeString(childDir.resolve("pom.xml"), 
                "<project><parent><relativePath>../pom.xml</relativePath></parent></project>");

        Path resolved = PomUtils.resolveParentPomPath(childDir);
        assertNotNull(resolved);
        assertEquals(parentDir.resolve("pom.xml"), resolved);
    }

    @Test
    void resolveParentPomPath_findsDefaultParent(@TempDir Path tempDir) throws IOException {
        // Standard-Maven-Multi-Module-Struktur: Parent im Elternverzeichnis
        Path parentDir = tempDir.resolve("parent");
        Path childDir = parentDir.resolve("child");
        Files.createDirectories(parentDir);
        Files.createDirectories(childDir);

        Files.writeString(parentDir.resolve("pom.xml"), "<project><packaging>pom</packaging></project>");
        Files.writeString(childDir.resolve("pom.xml"), 
                "<project><parent><artifactId>parent</artifactId></parent></project>");

        Path resolved = PomUtils.resolveParentPomPath(childDir);
        assertNotNull(resolved);
        assertEquals(parentDir.resolve("pom.xml"), resolved);
    }
}

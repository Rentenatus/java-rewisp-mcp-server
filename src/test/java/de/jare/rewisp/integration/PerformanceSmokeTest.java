/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.integration;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.*;
import de.jare.rewisp.snapshot.DirtyChecker;
import de.jare.rewisp.snapshot.DirtyCheckResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance-Smoke-Test mit einer Fixture mit >200 Dateien.
 * Prüft, dass das System innerhalb akzeptabler Limits funktioniert.
 */
@Isolated
class PerformanceSmokeTest extends AbstractIntegrationTestBase {

    private String projectName;
    private Path fixturePath;

    @BeforeEach
    void setUp() throws Exception {
        super.setUpBase();
        
        // Große Fixture erstellen
        fixturePath = createLargeFixture(250);
        
        // Projekt laden
        projectName = loadMavenProject(fixturePath);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void testLargeFixtureLoadsWithinTimeLimit() {
        ProjectEntry entry = projectManager.find(projectName);
        assertNotNull(entry, "Großes Projekt sollte geladen werden");
        assertNotNull(entry.model(), "CtModel sollte vorhanden sein");
        assertTrue(entry.model().getAllTypes().size() >= 200,
                "Sollte mindestens 200 Typen enthalten");
    }

    @Test
    void testSnapshotCaptureOnLargeFixture() throws Exception {
        ProjectEntry entry = projectManager.find(projectName);
        
        long start = System.currentTimeMillis();
        ProjectSnapshot snapshot = snapshotService.captureStable(entry, 1);
        long duration = System.currentTimeMillis() - start;
        
        assertNotNull(snapshot, "Snapshot sollte erstellt werden");
        assertTrue(snapshot.files().size() >= 200,
                "Snapshot sollte mindestens 200 Dateien enthalten");
        
        // Sollte innerhalb von 10 Sekunden funktionieren
        assertTrue(duration < 10000,
                "Snapshot sollte in < 10 Sekunden erstellt werden (dauerte " + duration + "ms)");
    }

    @Test
    void testProjectDirtyCheckOnLargeFixture() throws Exception {
        ProjectEntry entry = projectManager.find(projectName);
        
        long start = System.currentTimeMillis();
        DirtyCheckResult result = DirtyChecker.checkDirtyFullScan(entry);
        long duration = System.currentTimeMillis() - start;
        
        assertNotNull(result, "Dirty-Check sollte funktionieren");
        
        // Sollte innerhalb von 5 Sekunden funktionieren
        assertTrue(duration < 5000,
                "Dirty-Check sollte in < 5 Sekunden funktionieren (dauerte " + duration + "ms)");
    }

    /**
     * Erstellt eine große Fixture mit der angegebenen Anzahl von Java-Dateien.
     */
    private Path createLargeFixture(int fileCount) throws IOException {
        Path fixtureDir = tempDir.resolve("large-fixture");
        Files.createDirectories(fixtureDir);
        
        // pom.xml erstellen
        Path pomPath = fixtureDir.resolve("pom.xml");
        Files.writeString(pomPath, createLargePom());
        
        // src-Verzeichnis erstellen
        Path srcDir = fixtureDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        
        // Viele Java-Dateien erstellen
        for (int i = 0; i < fileCount; i++) {
            Path javaFile = srcDir.resolve("Class" + i + ".java");
            Files.writeString(javaFile, createSimpleClass("Class" + i));
        }
        
        return fixtureDir;
    }

    private String createLargePom() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
               "  <groupId>com.example</groupId>\n" +
               "  <artifactId>large-fixture</artifactId>\n" +
               "  <version>1.0</version>\n" +
               "  <properties>\n" +
               "    <maven.compiler.source>17</maven.compiler.source>\n" +
               "    <maven.compiler.target>17</maven.compiler.target>\n" +
               "  </properties>\n" +
               "</project>";
    }

    private String createSimpleClass(String className) {
        return "package com.example;\n\n" +
               "public class " + className + " {\n" +
               "    private String value;\n\n" +
               "    public String getValue() { return value; }\n\n" +
               "    public void setValue(String value) { this.value = value; }\n" +
               "}";
    }
}

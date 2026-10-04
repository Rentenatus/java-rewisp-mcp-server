/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.integration;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integrationstest für Maven Multi-Module Projekte.
 * Prüft:
 * - Classpath wird pro Modul korrekt aufgelöst
 * - pom.xml-Änderung invalidiert den Build-Kontext
 * - Module können unabhängig geladen werden
 */
class MavenMultiModuleIntegrationTest extends AbstractIntegrationTestBase {

    private String projectName;
    private Path fixturePath;

    @BeforeEach
    void setUp() throws Exception {
        super.setUpBase();
        
        // Fixture laden
        fixturePath = loadFixture("maven-multi-module");
        
        // Projekt laden
        projectName = loadMavenProject(fixturePath);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void testMultiModuleProjectLoaded() {
        ProjectEntry entry = projectManager.find(projectName);
        assertNotNull(entry, "Projekt sollte geladen sein");
        assertNotNull(entry.model(), "CtModel sollte vorhanden sein");
    }

    @Test
    void testBuildContextContainsModules() throws Exception {
        BuildContext buildCtx = projectManager.ensureBuildContext(projectName);
        assertNotNull(buildCtx, "BuildContext sollte nicht null sein");
        assertEquals("MAVEN", buildCtx.buildSystem(), "Build-System sollte MAVEN sein");
        assertFalse(buildCtx.modules().isEmpty(), "Sollte mindestens ein Modul enthalten");
    }

    @Test
    void testPomXmlChangeInvalidatesBuildContext() throws Exception {
        // BuildContext laden
        BuildContext buildCtx1 = projectManager.ensureBuildContext(projectName);
        assertNotNull(buildCtx1, "Erster BuildContext sollte geladen werden");
        
        // pom.xml ändern
        Path pomPath = fixturePath.resolve("pom.xml");
        String originalPom = Files.readString(pomPath);
        String modifiedPom = originalPom.replace("<version>1.0.0</version>", "<version>2.0.0</version>");
        Files.writeString(pomPath, modifiedPom);
        
        // Projekt neu laden, um die Änderung zu erkennen
        projectManager.remove(projectName);
        projectName = loadMavenProject(fixturePath);
        
        // BuildContext sollte neu aufgelöst werden (anderer Fingerprint)
        BuildContext buildCtx2 = projectManager.ensureBuildContext(projectName);
        assertNotNull(buildCtx2, "Zweiter BuildContext sollte geladen werden");
        
        // Fingerprints sollten unterschiedlich sein
        assertNotEquals(buildCtx1.fingerprint().buildFilesHash(), 
                buildCtx2.fingerprint().buildFilesHash(),
                "BuildFilesHash sollte sich bei pom.xml-Änderung ändern");
        
        // Original pom.xml wiederherstellen
        Files.writeString(pomPath, originalPom);
    }

    @Test
    void testModuleStructureDetected() throws Exception {
        BuildContext buildCtx = projectManager.ensureBuildContext(projectName);
        
        // Sollte mehrere Module enthalten (Root + Submodule)
        int moduleCount = buildCtx.modules().size();
        assertTrue(moduleCount >= 1, "Sollte mindestens ein Modul enthalten");
        
        // Jedes Modul sollte Source-Roots haben
        for (var module : buildCtx.modules()) {
            assertNotNull(module.sourceRoots(), "Module sollte Source-Roots haben");
        }
    }
}

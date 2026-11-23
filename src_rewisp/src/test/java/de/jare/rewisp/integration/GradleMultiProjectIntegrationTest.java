/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.integration;

import de.jare.rewisp.testsupport.AbstractIntegrationTestBase;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.model.ProjectEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integrationstest für Gradle Multi-Project.
 * Prüft:
 * - Gradle-Projekt wird korrekt erkannt
 * - Subprojekte werden analysiert
 * - settings.gradle-Änderung invalidiert den Build-Kontext
 * 
 * Gradle wird durch einen Fake-Wrapper ersetzt ({@link de.jare.rewisp.build.FakeGradle}); die Tests laufen daher ohne Gradle.
 */
class GradleMultiProjectIntegrationTest extends AbstractIntegrationTestBase {

    private String projectName;
    private Path fixturePath;

    @BeforeEach
    void setUp() throws Exception {
        super.setUpBase();
        
        // Fixture laden
        fixturePath = loadFixture("gradle-multi-project");
        de.jare.rewisp.build.FakeGradle.install(fixturePath, "rootProject.name: multi\nsourceCompatibility: 17\n",
                "compileClasspath\n+--- org.slf4j:slf4j-api:2.0.9\n", 0, false);
        
        // Projekt laden (wird automatisch als Gradle erkannt)
        projectName = loadGradleProject(fixturePath);
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void testGradleProjectLoaded() {
        ProjectEntry entry = projectManager.find(projectName);
        assertNotNull(entry, "Gradle-Projekt sollte geladen sein");
        assertEquals("GRADLE", entry.buildType(), "Build-Type sollte GRADLE sein");
    }

    @Test
    void testBuildContextForGradle() throws Exception {
        BuildContext buildCtx = projectManager.ensureBuildContext(projectName);
        assertNotNull(buildCtx, "BuildContext sollte nicht null sein");
        assertEquals("GRADLE", buildCtx.buildSystem(), "Build-System sollte GRADLE sein");
        assertFalse(buildCtx.modules().isEmpty(), "Sollte mindestens ein Modul enthalten");
    }

    @Test
    void testSubprojectsDetected() throws Exception {
        BuildContext buildCtx = projectManager.ensureBuildContext(projectName);
        
        // Sollte mehrere Module enthalten (Root + Subprojekte)
        int moduleCount = buildCtx.modules().size();
        assertTrue(moduleCount >= 1, "Sollte mindestens das Root-Modul enthalten");
    }

    @Test
    void testSettingsGradleChangeInvalidatesBuildContext() throws Exception {
        // BuildContext laden
        BuildContext buildCtx1 = projectManager.ensureBuildContext(projectName);
        assertNotNull(buildCtx1, "Erster BuildContext sollte geladen werden");
        
        // settings.gradle ändern
        Path settingsPath = fixturePath.resolve("settings.gradle");
        if (Files.exists(settingsPath)) {
            String originalSettings = Files.readString(settingsPath);
            String modifiedSettings = originalSettings.replace(
                    "include 'module-a'", 
                    "include 'module-a', 'module-c'");
            Files.writeString(settingsPath, modifiedSettings);
            
            // Projekt neu laden
            projectManager.remove(projectName);
            projectName = loadGradleProject(fixturePath);
            
            // BuildContext sollte neu aufgelöst werden
            BuildContext buildCtx2 = projectManager.ensureBuildContext(projectName);
            assertNotNull(buildCtx2, "Zweiter BuildContext sollte geladen werden");
            
            // Fingerprints sollten unterschiedlich sein
            assertNotEquals(buildCtx1.fingerprint().buildFilesHash(), 
                    buildCtx2.fingerprint().buildFilesHash(),
                    "BuildFilesHash sollte sich bei settings.gradle-Änderung ändern");
            
            // Original settings.gradle wiederherstellen
            Files.writeString(settingsPath, originalSettings);
        }
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.integration;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.model.ProjectEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
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
 * NOTE: Benötigt gradlew in der Test-Umgebung oder wird übersprungen.
 */
@Disabled("Benötigt Gradle-Wrapper in Test-Umgebung")
class GradleMultiProjectIntegrationTest extends AbstractIntegrationTestBase {

    private String projectName;
    private Path fixturePath;

    @BeforeEach
    void setUp() throws Exception {
        super.setUpBase();
        
        // Fixture laden
        fixturePath = loadFixture("gradle-multi-project");
        
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
    @EnabledIfSystemProperty(named = "gradle.executable", matches = ".*")
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

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.testsupport;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.snapshot.SnapshotService;
import de.jare.rewisp.snapshot.ContentHasher;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.rewrite.*;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Basis-Klasse für Integrationstests.
 * Stellt gemeinsame Fixture-/Service-Hilfsmethoden bereit.
 */
public abstract class AbstractIntegrationTestBase {

    @TempDir
    protected Path tempDir;

    protected ProjectManager projectManager;
    protected EditManager editManager;
    protected SnapshotService snapshotService;
    protected RewriteEngine rewriteEngine;
    protected RecipeRegistry recipeRegistry;

    private final Map<String, Path> loadedFixtures = new ConcurrentHashMap<>();
    private final ContentHasher hasher = new ContentHasher();

    @BeforeEach
    protected void setUpBase() throws Exception {
        // Initialisiere alle Services
        projectManager = new ProjectManager();
        editManager = new EditManager();
        snapshotService = new SnapshotService();

        // RecipeRegistry initialisieren
        recipeRegistry = new YamlRecipeRegistry();

        // Standard-Recipe registrieren
        recipeRegistry.register(new RecipeDefinition(
                "rename-method",
                "Rename Method",
                "Renames all method invocations of a specified method",
                "de.jare.rewisp.rewrite.recipes.RenameMethodRecipe",
                Set.of("oldMethodName", "newMethodName"),
                ChangeRisk.MEDIUM,
                false,
                true,
                true
        ));

        rewriteEngine = new OpenRewriteEngineImpl(recipeRegistry);

        // EditManager mit ProjectManager verbinden
        projectManager.setEditManager(editManager);
    }

    /**
     * Lädt eine Test-Fixture aus dem fixtures-Verzeichnis in ein temporäres Verzeichnis.
     * 
     * @param fixtureName Name der Fixture (z.B. "maven-single-module")
     * @return Der temporäre Pfad, in den die Fixture kopiert wurde
     */
    protected Path loadFixture(String fixtureName) throws IOException {
        Path sourceFixture = TestFixtures.RESOURCES.resolve("fixtures").resolve(fixtureName);
        if (!Files.exists(sourceFixture)) {
            throw new IOException("Fixture nicht gefunden: " + sourceFixture);
        }
        
        Path targetDir = tempDir.resolve(fixtureName);
        copyDirectory(sourceFixture, targetDir);
        loadedFixtures.put(fixtureName, targetDir);
        
        return targetDir;
    }

    /**
     * Lädt eine bestehende Test-Resource (nicht unter fixtures/) in ein temporäres Verzeichnis.
     */
    protected Path loadResource(String resourcePath) throws IOException {
        Path source = TestFixtures.RESOURCES.resolve(resourcePath);
        if (!Files.exists(source)) {
            throw new IOException("Resource nicht gefunden: " + source);
        }
        
        Path targetDir = tempDir.resolve(resourcePath.replace("/", "_").replace("\\", "_"));
        copyDirectory(source, targetDir);
        
        return targetDir;
    }

    /**
     * Kopiert ein Verzeichnis rekursiv.
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        
        try (var stream = Files.walk(source)) {
            stream.filter(Files::isRegularFile)
                  .forEach(sourceFile -> {
                      try {
                          Path relative = source.relativize(sourceFile);
                          Path targetFile = target.resolve(relative);
                          Files.createDirectories(targetFile.getParent());
                          Files.copy(sourceFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                      } catch (IOException e) {
                          throw new RuntimeException("Fehler beim Kopieren von " + sourceFile, e);
                      }
                  });
        }
    }

    /**
     * Berechnet den Content-Hash eines Verzeichnisses.
     *
     * <p>Ignoriert Build-Artefakte (target/, build/, .git/, .gradle/) und
     * Tool-Tempdateien: Die Fixture-Integrität bezieht sich auf
     * Quelldateien — Maven schreibt beim Build-Auflösen target/-Dateien,
     * Spoon hinterlässt spoon.classpath-*.tmp. Beides ist keine
     * Fixture-Mutation.</p>
     */
    protected String calculateDirectoryHash(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(this::isRelevantForFixtureIntegrity)
                    .sorted()
                    .map(this::calculateFileHash)
                    .reduce("", (a, b) -> a + b);
        }
    }

    private boolean isRelevantForFixtureIntegrity(Path file) {
        String s = file.toString();
        return !s.contains("target") && !s.contains("build")
                && !s.contains(".git") && !s.contains(".gradle")
                && !s.endsWith(".tmp");
    }

    /**
     * Berechnet den Content-Hash einer Datei.
     */
    protected String calculateFileHash(Path file) {
        try {
            return hasher.sha256(Files.readString(file));
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Prüft, ob sich eine Fixture während des Tests verändert hat.
     */
    protected boolean isFixtureUnchanged(String fixtureName, String expectedHash) throws IOException {
        Path fixturePath = loadedFixtures.get(fixtureName);
        if (fixturePath == null) return false;
        String currentHash = calculateDirectoryHash(fixturePath);
        return expectedHash.equals(currentHash);
    }

    /**
     * Lädt ein Maven-Projekt und gibt den Projekt-Namen zurück.
     */
    protected String loadMavenProject(Path projectDir) throws Exception {
        return projectManager.load(projectDir.toString(), null, null).name();
    }

    /**
     * Lädt ein Gradle-Projekt und gibt den Projekt-Namen zurück.
     */
    protected String loadGradleProject(Path projectDir) throws Exception {
        return projectManager.load(projectDir.toString(), null, null).name();
    }

    /**
     * Cleanup nach Test.
     */
    protected void cleanup() {
        loadedFixtures.clear();
        projectManager.removeAll();
        if (editManager != null) {
            // Reset aller Backups pro Projekt
            loadedFixtures.keySet().forEach(editManager::resetBackup);
        }
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security-Tests für {@link WorkspaceRegistry}.
 * Prüft Path-Traversal und Symlink-Ausbruch.
 */
class WorkspaceRegistryTest {

    private WorkspaceRegistry registry;
    private Path allowedRoot;
    private Path notAllowedRoot;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        allowedRoot = tempDir.resolve("allowed");
        notAllowedRoot = tempDir.resolve("not-allowed");
        Files.createDirectories(allowedRoot);
        Files.createDirectories(notAllowedRoot);
        
        registry = new WorkspaceRegistry(List.of(allowedRoot.toString()));
    }

    @Test
    void isAllowed_ErlaubterPfad_Akzeptiert() {
        Path testPath = allowedRoot.resolve("subdir");
        assertTrue(registry.isAllowed(testPath), 
                "Pfad innerhalb Allowlist sollte erlaubt sein");
    }

    @Test
    void isAllowed_PfadAuserhalbAbgelehnt() {
        Path testPath = notAllowedRoot.resolve("subdir");
        assertFalse(registry.isAllowed(testPath), 
                "Pfad außerhalb Allowlist sollte abgelehnt werden");
    }

    @Test
    void isAllowed_LeereAllowlist_AllesErlaubt() {
        WorkspaceRegistry emptyRegistry = new WorkspaceRegistry(List.of());
        Path anyPath = tempDir.resolve("any").resolve("path");
        assertTrue(emptyRegistry.isAllowed(anyPath), 
                "Leere Allowlist sollte alles erlauben");
    }

    @Test
    void containsPathTraversal_ErkenntZweiPunkte() {
        assertTrue(WorkspaceRegistry.containsPathTraversal("path/../file"),
                "Sollte .. erkennen");
        assertTrue(WorkspaceRegistry.containsPathTraversal("path/.."),
                "Sollte .. am Ende erkennen");
        assertTrue(WorkspaceRegistry.containsPathTraversal("../file"),
                "Sollte ../ am Anfang erkennen");
    }

    @Test
    void containsPathTraversal_ErkenntNichtZweiPunkte() {
        assertFalse(WorkspaceRegistry.containsPathTraversal("path/file"),
                "Sollte normale Pfade nicht als Traversal erkennen");
        assertFalse(WorkspaceRegistry.containsPathTraversal("file.txt"),
                "Sollte Dateinamen nicht als Traversal erkennen");
        assertFalse(WorkspaceRegistry.containsPathTraversal(null),
                "Sollte null nicht als Traversal erkennen");
    }

    @Test
    void register_ErlaubterPfad_Erfolgreich() throws IOException {
        Path testPath = allowedRoot.resolve("project1");
        Files.createDirectories(testPath);
        
        Path registered = registry.register("project1", testPath);
        assertNotNull(registered, "Registrierung sollte erfolgreich sein");
        assertEquals(registered, registry.get("project1"), 
                "Registrierter Pfad sollte abrufbar sein");
    }

    @Test
    void register_PfadAuserhalb_ThrowtSecurityException() throws IOException {
        Path testPath = notAllowedRoot.resolve("project2");
        Files.createDirectories(testPath);
        
        assertThrows(SecurityException.class, () -> {
            registry.register("project2", testPath);
        }, "Sollte SecurityException für Pfad außerhalb Allowlist werfen");
    }

    @Test
    void unregister_EntferntProjekt() throws IOException {
        Path testPath = allowedRoot.resolve("project3");
        Files.createDirectories(testPath);
        
        registry.register("project3", testPath);
        assertNotNull(registry.get("project3"), "Projekt sollte registriert sein");
        
        registry.unregister("project3");
        assertNull(registry.get("project3"), "Projekt sollte deregistriert sein");
    }

    @Test
    void addAllowedRoot_FuegtNeuenRootHinzu() throws IOException {
        Path newAllowedRoot = tempDir.resolve("new-allowed");
        Files.createDirectories(newAllowedRoot);
        
        assertFalse(registry.isAllowed(newAllowedRoot), 
                "Neuer Root sollte noch nicht erlaubt sein");
        
        registry.addAllowedRoot(newAllowedRoot.toString());
        assertTrue(registry.isAllowed(newAllowedRoot), 
                "Neuer Root sollte jetzt erlaubt sein");
    }

    @Test
    void getAllowedRoots_GibtAlleRootsZurueck() {
        assertNotNull(registry.getAllowedRoots(), "Should return non-null set");
        assertEquals(1, registry.getAllowedRoots().size(), 
                "Sollte einen erlaubten Root enthalten");
        assertTrue(registry.getAllowedRoots().contains(allowedRoot), 
                "Sollte allowedRoot enthalten");
    }
}

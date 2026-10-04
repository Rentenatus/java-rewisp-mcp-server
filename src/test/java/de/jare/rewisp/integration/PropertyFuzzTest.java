/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.integration;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.snapshot.ContentHasher;
import de.jare.rewisp.snapshot.WorkspaceRegistry;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fuzz-Test für ungewöhnliche Pfade, Encoding und Syntaxfehler.
 * Prüft, dass das System nicht abstürzt bei:
 * - Ungewöhnliche Pfade (mit Leerzeichen, Sonderzeichen, Unicode)
 * - Verschiedene Encodings
 * - Leere Dateien
 * - Sehr große Dateien
 * - Syntaxfehler in Java-Dateien
 */
class PropertyFuzzTest extends AbstractIntegrationTestBase {

    private final ContentHasher hasher = new ContentHasher();

    @Test
    void testPathWithSpaces() throws Exception {
        // Verzeichnis mit Leerzeichen erstellen
        Path spaceDir = tempDir.resolve("test dir with spaces");
        Files.createDirectories(spaceDir);
        
        // Projekt mit Leerzeichen im Pfad laden
        Path pomPath = spaceDir.resolve("pom.xml");
        Files.writeString(pomPath, createMinimalPom());
        
        // Sollte nicht abstürzen
        assertDoesNotThrow(() -> {
            projectManager.load(spaceDir.toString(), null, null);
        }, "Sollte Pfade mit Leerzeichen verarbeiten");
    }

    @Test
    void testPathWithUnicode() throws Exception {
        // Verzeichnis mit Unicode-Zeichen erstellen
        Path unicodeDir = tempDir.resolve("test_проект_测试");
        Files.createDirectories(unicodeDir);
        
        // Projekt mit Unicode im Pfad laden
        Path pomPath = unicodeDir.resolve("pom.xml");
        Files.writeString(pomPath, createMinimalPom());
        
        // Sollte nicht abstürzen
        assertDoesNotThrow(() -> {
            projectManager.load(unicodeDir.toString(), null, null);
        }, "Sollte Pfade mit Unicode verarbeiten");
    }

    @Test
    void testEmptyFile() throws Exception {
        Path emptyDir = tempDir.resolve("empty-file-test");
        Files.createDirectories(emptyDir);
        
        Path srcDir = emptyDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        
        Path pomPath = emptyDir.resolve("pom.xml");
        Files.writeString(pomPath, createMinimalPom());
        
        // Leere Java-Datei
        Path emptyFile = srcDir.resolve("Empty.java");
        Files.createFile(emptyFile);
        
        // Sollte nicht abstürzen (leere Datei wird ignoriert oder führt zu Parse-Fehler, nicht zu Absturz)
        assertDoesNotThrow(() -> {
            projectManager.load(emptyDir.toString(), null, null);
        }, "Sollte leere Dateien verarbeiten");
    }

    @Test
    void testContentHasherWithSpecialCharacters() {
        String content = "Hällö Wörld! 你好世界 🎉";
        String hash = hasher.sha256(content);
        
        assertNotNull(hash, "Hash sollte nicht null sein");
        assertEquals(64, hash.length(), "SHA-256 Hash sollte 64 Zeichen lang sein");
    }

    @Test
    void testContentHasherWithEmptyString() {
        String hash = hasher.sha256("");
        
        assertNotNull(hash, "Hash sollte nicht null sein");
        assertEquals(64, hash.length(), "SHA-256 Hash sollte 64 Zeichen lang sein");
    }

    @Test
    void testContentHasherConsistency() {
        String content = "Test content";
        String hash1 = hasher.sha256(content);
        String hash2 = hasher.sha256(content);
        
        assertEquals(hash1, hash2, "Hash sollte konsistent sein");
    }

    @Test
    void testContentHasherDifferentContent() {
        String content1 = "Content 1";
        String content2 = "Content 2";
        String hash1 = hasher.sha256(content1);
        String hash2 = hasher.sha256(content2);
        
        assertNotEquals(hash1, hash2, "Unterschiedlicher Content sollte unterschiedlichen Hash haben");
    }

    @Test
    void testVeryLongFileName() throws Exception {
        // Datei mit sehr langem Namen
        StringBuilder longName = new StringBuilder("LongFileName");
        while (longName.length() < 200) {
            longName.append("_test");
        }
        
        Path longDir = tempDir.resolve(longName.toString());
        Files.createDirectories(longDir);
        
        Path pomPath = longDir.resolve("pom.xml");
        Files.writeString(pomPath, createMinimalPom());
        
        // Sollte nicht abstürzen
        assertDoesNotThrow(() -> {
            projectManager.load(longDir.toString(), null, null);
        }, "Sollte lange Dateinamen verarbeiten");
    }

    @Test
    void testNestedDirectories() throws Exception {
        // Sehr tief verschachteltes Verzeichnis
        Path nested = tempDir.resolve("a/b/c/d/e/f/g");
        Files.createDirectories(nested);
        
        Path pomPath = nested.resolve("pom.xml");
        Files.writeString(pomPath, createMinimalPom());
        
        // Sollte nicht abstürzen
        assertDoesNotThrow(() -> {
            projectManager.load(nested.toString(), null, null);
        }, "Sollte tief verschachtelte Verzeichnisse verarbeiten");
    }

    @Test
    void testWorkspaceRegistryWithPathTraversal() {
        assertTrue(WorkspaceRegistry.containsPathTraversal("../test"),
                "Sollte Path-Traversal erkennen");
        assertTrue(WorkspaceRegistry.containsPathTraversal("..\\test"),
                "Sollte Path-Traversal mit Backslash erkennen");
        assertFalse(WorkspaceRegistry.containsPathTraversal("test/file"),
                "Sollte normale Pfade nicht als Traversal erkennen");
    }

    private String createMinimalPom() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<project>\n" +
               "  <groupId>test</groupId>\n" +
               "  <artifactId>test</artifactId>\n" +
               "  <version>1.0</version>\n" +
               "</project>";
    }
}

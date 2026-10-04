/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.nio.file.Path;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-Tests für RewriteSyncManager.
 */
class RewriteSyncManagerTest {

    private RewriteSyncManager syncManager;

    @BeforeEach
    void setUp() {
        syncManager = new RewriteSyncManager();
    }

    @Test
    void initialState_hasNoStaleFiles() {
        assertFalse(syncManager.needsRebuild());
        assertFalse(syncManager.needsRebuild(Path.of("src/test/MyClass.java")));
        assertEquals(0, syncManager.getStaleFileCount());
    }

    @Test
    void onRewriteComplete_addsChangedFiles() {
        // Erstelle ein RewriteResult mit geaenderten Dateien
        Map<Path, RewrittenFile> changedFiles = new HashMap<>();
        changedFiles.put(Path.of("src/test/MyClass.java"), 
                new RewrittenFile("neuer Inhalt", "hash1", "MODIFY"));
        changedFiles.put(Path.of("src/test/OtherClass.java"), 
                new RewrittenFile("anderer Inhalt", "hash2", "MODIFY"));
        
        Set<Path> deletedFiles = new HashSet<>();
        deletedFiles.add(Path.of("src/test/OldClass.java"));
        Set<Path> createdFiles = new HashSet<>();
        createdFiles.add(Path.of("src/test/NewClass.java"));
        
        List<RewriteDiagnostic> diagnostics = new ArrayList<>();
        
        RewriteResult result = new RewriteResult(
                changedFiles, deletedFiles, createdFiles, diagnostics, false);

        // Aufruf von onRewriteComplete
        syncManager.onRewriteComplete(result);

        // Pruefe, dass alle Dateien als stale markiert sind
        assertTrue(syncManager.needsRebuild());
        assertTrue(syncManager.needsRebuild(Path.of("src/test/MyClass.java")));
        assertTrue(syncManager.needsRebuild(Path.of("src/test/OtherClass.java")));
        assertTrue(syncManager.needsRebuild(Path.of("src/test/OldClass.java")));
        assertTrue(syncManager.needsRebuild(Path.of("src/test/NewClass.java")));
        assertFalse(syncManager.needsRebuild(Path.of("src/test/Unchanged.java")));
        
        assertEquals(4, syncManager.getStaleFileCount());
    }

    @Test
    void onRewriteComplete_handlesNullResult() {
        // Null-Result sollte keine Exception werfen
        assertDoesNotThrow(() -> syncManager.onRewriteComplete(null));
        
        // State sollte unveraendert bleiben
        assertFalse(syncManager.needsRebuild());
    }

    @Test
    void onRewriteComplete_handlesEmptyCollections() {
        // RewriteResult mit leeren Kollektionen
        RewriteResult result = new RewriteResult(
                Map.of(), Set.of(), Set.of(), List.of(), false);
        
        assertDoesNotThrow(() -> syncManager.onRewriteComplete(result));
        // Mit leeren Kollektionen sollten keine Dateien als stale markiert werden
        assertFalse(syncManager.needsRebuild());
    }

    @Test
    void markSynced_clearsStaleFiles() {
        // Zuerst Dateien als stale markieren
        Map<Path, RewrittenFile> changedFiles = new HashMap<>();
        changedFiles.put(Path.of("src/test/MyClass.java"), 
                new RewrittenFile("neuer Inhalt", "hash1", "MODIFY"));
        
        List<RewriteDiagnostic> diagnostics = new ArrayList<>();
        RewriteResult result = new RewriteResult(changedFiles, new HashSet<>(), new HashSet<>(), diagnostics, false);
        syncManager.onRewriteComplete(result);
        
        assertTrue(syncManager.needsRebuild());
        
        // Jetzt zuruecksetzen
        syncManager.markSynced();
        
        assertFalse(syncManager.needsRebuild());
        assertFalse(syncManager.needsRebuild(Path.of("src/test/MyClass.java")));
    }

    @Test
    void reset_clearsAllStaleFiles() {
        // Dateien markieren
        Map<Path, RewrittenFile> changedFiles = new HashMap<>();
        changedFiles.put(Path.of("file1.java"), new RewrittenFile("content1", "hash1", "MODIFY"));
        changedFiles.put(Path.of("file2.java"), new RewrittenFile("content2", "hash2", "MODIFY"));
        
        List<RewriteDiagnostic> diagnostics = new ArrayList<>();
        RewriteResult result = new RewriteResult(changedFiles, new HashSet<>(), new HashSet<>(), diagnostics, false);
        syncManager.onRewriteComplete(result);
        
        assertEquals(2, syncManager.getStaleFileCount());
        
        // Reset
        syncManager.reset();
        
        assertEquals(0, syncManager.getStaleFileCount());
        assertFalse(syncManager.needsRebuild());
    }

    @Test
    void getStaleFiles_returnsImmutableCopy() {
        Map<Path, RewrittenFile> changedFiles = new HashMap<>();
        changedFiles.put(Path.of("file1.java"), new RewrittenFile("content1", "hash1", "MODIFY"));
        
        List<RewriteDiagnostic> diagnostics = new ArrayList<>();
        RewriteResult result = new RewriteResult(changedFiles, new HashSet<>(), new HashSet<>(), diagnostics, false);
        syncManager.onRewriteComplete(result);
        
        Set<Path> staleFiles = syncManager.getStaleFiles();
        assertEquals(1, staleFiles.size());
        assertTrue(staleFiles.contains(Path.of("file1.java")));
        
        // Aendere die zurueckgegebenen Set - sollte Exception werfen (immutable)
        assertThrows(UnsupportedOperationException.class, () -> {
            staleFiles.add(Path.of("file2.java"));
        });
        
        // Original sollte unveraendert sein
        assertEquals(1, syncManager.getStaleFileCount());
    }

    @Test
    void multipleRewriteOperations_accumulateStaleFiles() {
        // Erste REWRITE-Operation
        Map<Path, RewrittenFile> changedFiles1 = new HashMap<>();
        changedFiles1.put(Path.of("file1.java"), new RewrittenFile("content1", "hash1", "MODIFY"));
        List<RewriteDiagnostic> diagnostics1 = new ArrayList<>();
        RewriteResult result1 = new RewriteResult(changedFiles1, new HashSet<>(), new HashSet<>(), diagnostics1, false);
        syncManager.onRewriteComplete(result1);
        
        assertEquals(1, syncManager.getStaleFileCount());
        
        // Zweite REWRITE-Operation
        Map<Path, RewrittenFile> changedFiles2 = new HashMap<>();
        changedFiles2.put(Path.of("file2.java"), new RewrittenFile("content2", "hash2", "MODIFY"));
        List<RewriteDiagnostic> diagnostics2 = new ArrayList<>();
        RewriteResult result2 = new RewriteResult(changedFiles2, new HashSet<>(), new HashSet<>(), diagnostics2, false);
        syncManager.onRewriteComplete(result2);
        
        // Sollte 2 Dateien haben (keine Duplikate)
        assertEquals(2, syncManager.getStaleFileCount());
        assertTrue(syncManager.needsRebuild(Path.of("file1.java")));
        assertTrue(syncManager.needsRebuild(Path.of("file2.java")));
    }

    @Test
    void sameFileChangedMultipleTimes_stillOneStaleEntry() {
        // Erstes Mal: Datei aendern
        Map<Path, RewrittenFile> changedFiles1 = new HashMap<>();
        changedFiles1.put(Path.of("file1.java"), new RewrittenFile("content1", "hash1", "MODIFY"));
        List<RewriteDiagnostic> diagnostics1 = new ArrayList<>();
        RewriteResult result1 = new RewriteResult(changedFiles1, new HashSet<>(), new HashSet<>(), diagnostics1, false);
        syncManager.onRewriteComplete(result1);
        
        // Zweites Mal: dieselbe Datei aendern
        Map<Path, RewrittenFile> changedFiles2 = new HashMap<>();
        changedFiles2.put(Path.of("file1.java"), new RewrittenFile("content2", "hash2", "MODIFY"));
        List<RewriteDiagnostic> diagnostics2 = new ArrayList<>();
        RewriteResult result2 = new RewriteResult(changedFiles2, new HashSet<>(), new HashSet<>(), diagnostics2, false);
        syncManager.onRewriteComplete(result2);
        
        // Sollte immer noch nur 1 Datei haben (Set entfernt Duplikate)
        assertEquals(1, syncManager.getStaleFileCount());
        assertTrue(syncManager.needsRebuild(Path.of("file1.java")));
    }
}

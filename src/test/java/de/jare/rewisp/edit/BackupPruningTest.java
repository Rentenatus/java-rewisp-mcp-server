/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.jare.rewisp.model.Fingerprint;
import de.jare.rewisp.model.ProjectEntry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests für das Backup-Layout und -Pruning:
 * {@code <backupDir>/<projekt>/<timestamp>/} mit maximal
 * {@value EditManager#BACKUPS_TO_KEEP_CHECK} behaltenen Ständen pro Projekt.
 */
class BackupPruningTest {

    @TempDir
    Path tempDir;

    private static final int KEEP = 5;
    /** Sichtbar für Tests, damit die Erwartung mit der Konstante koppelt. */
    static final int BACKUPS_TO_KEEP_CHECK = 5;

    private ProjectEntry makeEntry(Path projectDir) throws Exception {
        Fingerprint fp = new Fingerprint(Files.getLastModifiedTime(projectDir.resolve("A.java")).toMillis(),
                Files.size(projectDir.resolve("A.java")));
        return new ProjectEntry(
                "test", "test", Instant.now().plusSeconds(600),
                projectDir, null, null, "RAW",
                false, null, projectDir, "test",
                new HashMap<>(Map.of(projectDir.resolve("A.java").normalize(), fp)),
                false, true, 1, 1, java.util.Set.of(), java.util.Set.of());
    }

    @Test
    void backupLandsInPerProjectSubdirectory() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}\n");

        EditManager mgr = new EditManager(tempDir.resolve("backups"));
        mgr.writeFile(makeEntry(src), src.resolve("A.java"), "class A { int x; }\n", null);

        Path projectDir = tempDir.resolve("backups").resolve("test");
        assertTrue(Files.isDirectory(projectDir), "Backup muss unter <backupDir>/test/ liegen");
        List<Path> stands;
        try (var s = Files.list(projectDir)) {
            stands = s.toList();
        }
        assertEquals(1, stands.size(), "Genau ein Stand nach dem ersten Edit");
        assertTrue(Files.exists(stands.get(0).resolve("A.java")),
                "Die Quelldatei muss im Backup liegen");
    }

    @Test
    void pruningKeepsOnlyNewestFiveStands() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}\n");

        Path backups = tempDir.resolve("backups");
        // Sieben alte Stände simulieren (Zeitstempel-Ordnungszahlen)
        for (long ts = 1000; ts <= 7000; ts += 1000) {
            Path stand = backups.resolve("test").resolve(String.valueOf(ts));
            Files.createDirectories(stand);
            Files.writeString(stand.resolve("A.java"), "old " + ts + "\n");
        }

        EditManager mgr = new EditManager(backups);
        mgr.writeFile(makeEntry(src), src.resolve("A.java"), "class A { int x; }\n", null);
        // Der Schreibvorgang erzeugt einen neuen Stand (aktueller Zeitstempel)
        // und triggert Pruning: 8 Stände -> die 5 neuesten bleiben.

        List<Path> stands;
        try (var s = Files.list(backups.resolve("test"))) {
            stands = s.sorted().toList();
        }
        assertEquals(KEEP, stands.size(), "Pruning muss auf " + KEEP + " Stände reduzieren");

        // Die aeltesten (1000, 2000, 3000) muessen weg sein, der neue muss da sein
        for (long ts = 1000; ts <= 3000; ts += 1000) {
            assertFalse(Files.exists(backups.resolve("test").resolve(String.valueOf(ts))),
                    "Alter Stand " + ts + " muss gepruned sein");
        }
        assertTrue(Files.exists(stands.get(stands.size() - 1).resolve("A.java")),
                "Der neueste Stand muss die Datei enthalten");
    }

    @Test
    void unsafeProjectNamesAreSanitized() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}\n");

        ProjectEntry entry = makeEntry(src);
        // Projektnamen mit Pfad-Sonderzeichen konstruieren
        ProjectEntry evil = new ProjectEntry(
                "a:b*c", "test", entry.expiryDate(),
                entry.projectDir(), null, null, "RAW",
                false, null, entry.originalProjectDir(), "test",
                entry.sourceFingerprints(), false, true, 1, 1,
                java.util.Set.of(), java.util.Set.of());

        EditManager mgr = new EditManager(tempDir.resolve("backups"));
        mgr.writeFile(evil, src.resolve("A.java"), "class A { int x; }\n", null);

        assertTrue(Files.isDirectory(tempDir.resolve("backups").resolve("a_b_c")),
                "Illegale Zeichen muessen im Backup-Pfad ersetzt sein");
    }
}

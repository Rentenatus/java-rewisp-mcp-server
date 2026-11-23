/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jare.rewisp.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.model.Fingerprint;
import de.jare.rewisp.model.ProjectEntry;

/**
 * Tests for the transaction layer in {@link EditManager}.
 *
 * @author Janusch Rentenatus
 */
class TransactionTest {

    @TempDir
    Path tempDir;
    private EditManager mgr;
    private Path file;

    @BeforeEach
    void setup() throws Exception {
        mgr = new EditManager(tempDir.resolve("backups"));
        file = tempDir.resolve("A.java");
        Files.writeString(file, "class A {\nint x;\n}\n");
    }

    private ProjectEntry makeEntry() throws Exception {
        Fingerprint fp = new Fingerprint(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
        return new ProjectEntry(
            "test", "test", Instant.now().plusSeconds(600),
            tempDir, null, null, "RAW",
            false, null, tempDir, "test",
            new HashMap<>(Map.of(file.normalize(), fp)), false, true, 1, 1, java.util.Set.of(), java.util.Set.of());
    }

    @Test
    void beginTransactionAccumulatesWithoutWriting() throws Exception {
        ProjectEntry entry = makeEntry();
        mgr.beginTransaction();
        assertTrue(mgr.isInTransaction());

        mgr.writeFile(entry, file, "class A { int y; }", null);

        // File on disk should NOT have changed yet
        assertTrue(Files.readString(file).contains("int x"));
        assertFalse(Files.readString(file).contains("int y"));
        assertEquals(1, mgr.getPendingChangeCount());
    }

    @Test
    void commitWritesAllChanges() throws Exception {
        ProjectEntry entry = makeEntry();
        mgr.beginTransaction();
        mgr.writeFile(entry, file, "class A { int y; }", null);

        boolean success = mgr.commitTransaction();

        assertTrue(success);
        assertFalse(mgr.isInTransaction());
        assertTrue(Files.readString(file).contains("int y"));
    }

    @Test
    void rollbackDiscardsChanges() throws Exception {
        ProjectEntry entry = makeEntry();
        mgr.beginTransaction();
        mgr.writeFile(entry, file, "class A { int y; }", null);

        mgr.rollbackTransaction();

        assertFalse(mgr.isInTransaction());
        assertTrue(Files.readString(file).contains("int x"));
        assertFalse(Files.readString(file).contains("int y"));
    }

    @Test
    void commitWithoutTransactionReturnsTrue() throws Exception {
        boolean success = mgr.commitTransaction();
        assertTrue(success);
    }

    @Test
    void rollbackWithoutTransactionIsNoop() throws Exception {
        mgr.rollbackTransaction();
        assertFalse(mgr.isInTransaction());
    }

    @Test
    void editLogAccumulates() throws Exception {
        mgr.logEdit("rename_symbol: foo -> bar");
        mgr.logEdit("add_method: getValue");

        var log = mgr.getEditLog();
        assertEquals(2, log.size());
        assertTrue(log.get(0).contains("rename"));
        assertTrue(log.get(1).contains("add_method"));
    }

    @Test
    void editLogEmptyByDefault() {
        assertTrue(mgr.getEditLog().isEmpty());
    }

    @Test
    void deleteFileDeferredUntilCommit() throws Exception {
        ProjectEntry entry = makeEntry();
        Path newFile = tempDir.resolve("B.java");
        mgr.beginTransaction();
        mgr.writeFile(entry, newFile, "class B {}\n", null);
        mgr.deleteFile(file);

        // Deletion buffered — file still on disk before commit
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(newFile));

        boolean success = mgr.commitTransaction();

        assertTrue(success);
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(newFile));
        assertTrue(mgr.getLastCommittedDeletions().contains(de.jare.rewisp.util.PathUtils.canonical(file)));
        assertTrue(mgr.getLastCommittedFiles().contains(de.jare.rewisp.util.PathUtils.canonical(newFile)));
    }

    @Test
    void rollbackDiscardsPendingDeletion() throws Exception {
        mgr.beginTransaction();
        mgr.deleteFile(file);

        // Deletion buffered — file still on disk
        assertTrue(Files.exists(file));

        mgr.rollbackTransaction();

        assertFalse(mgr.isInTransaction());
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("int x"));
    }

    @Test
    void deleteFileImmediateOutsideTransaction() throws Exception {
        mgr.deleteFile(file);
        assertFalse(Files.exists(file));
    }

    /** Review M2: Ziel war zur Loeschung vorgemerkt, dann moveFile darauf -> Inhalt darf nicht verloren gehen. */
    @Test
    void moveFileOntoPendingDeletionTarget_keepsMovedContent() throws Exception {
        Path target = tempDir.resolve("B.java");
        Files.writeString(target, "class B {}\n");
        mgr.beginTransaction();
        mgr.deleteFile(target);
        mgr.moveFile(file, target);

        assertTrue(mgr.commitTransaction());

        assertFalse(Files.exists(file));
        assertTrue(Files.readString(target).contains("class A"), "verschobener Inhalt muss erhalten bleiben");
    }

    @Test
    void moveFileInTransaction_createsMissingTargetDirectoryAtCommit() throws Exception {
        Path target = tempDir.resolve("new/pkg/A.java");
        mgr.beginTransaction();
        mgr.moveFile(file, target);

        assertTrue(mgr.commitTransaction());

        assertTrue(Files.exists(target));
        assertFalse(Files.exists(file));
    }

    /** Review M3: Datei selbst geloescht/verschoben -> spaeteres Schreiben am alten Pfad scheitert nicht am alten Fingerprint. */
    @Test
    void writeBackToPathWeRemovedOurselves_isNotReportedAsExternalDeletion() throws Exception {
        ProjectEntry entry = makeEntry();
        mgr.deleteFile(file); // nicht in Transaktion

        ProjectEntry after = mgr.writeFile(entry, file, "class A { int back; }\n", null);

        assertTrue(Files.readString(file).contains("int back;"));
        assertTrue(after != null);
    }

    /** AP-1.17 / ED:E-23: Backup kopiert keine Build-Ausgabe und kein .git. */
    @Test
    void backupSkipsBuildOutputAndGitDirs() throws Exception {
        Files.createDirectories(tempDir.resolve("target/classes"));
        Files.writeString(tempDir.resolve("target/classes/Gen.java"), "class Gen {}\n");
        Files.createDirectories(tempDir.resolve(".git/objects"));
        Files.writeString(tempDir.resolve(".git/objects/blob.java"), "x");
        ProjectEntry entry = makeEntry();

        mgr.writeFile(entry, file, "class A { int y; }\n", null);

        try (var stream = Files.walk(tempDir.resolve("backups"))) {
            var names = stream.filter(Files::isRegularFile).map(p -> p.toString().replace('\\', '/')).toList();
            assertTrue(names.stream().anyMatch(n -> n.endsWith("/A.java")), "A.java muss gesichert sein: " + names);
            assertFalse(names.stream().anyMatch(n -> n.contains("/target/") || n.contains("/.git/")), names.toString());
        }
    }

    /** AP-1.8 / ED:E-09 + AP-1.10 / ED:E-10: externe Aenderung vor dem Commit wird erkannt, Ursache gemeldet. */
    @Test
    void commitDetectsExternalChangeAndReportsCause() throws Exception {
        ProjectEntry entry = makeEntry();
        mgr.beginTransaction();
        mgr.writeFile(entry, file, "class A { int buffered; }\n", null);
        Files.writeString(file, "class A { int external; }\n"); // externe Aenderung nach dem Puffern

        assertFalse(mgr.commitTransaction());

        assertTrue(Files.readString(file).contains("int external;"), "externe Aenderung darf nicht ueberschrieben werden");
        assertTrue(mgr.getLastCommitError() != null && mgr.getLastCommitError().contains("A.java"),
                String.valueOf(mgr.getLastCommitError()));
        assertTrue(mgr.isInTransaction(), "Transaktion bleibt offen");
    }

    /** AP-1.5 / ED:E-05: write + move in einer Transaktion -> nur die Zieldatei existiert, mit neuem Inhalt. */
    @Test
    void moveFileInTransaction_isDeferredAndCarriesPendingContent() throws Exception {
        ProjectEntry entry = makeEntry();
        Path target = tempDir.resolve("Renamed.java");
        mgr.beginTransaction();
        mgr.writeFile(entry, file, "class Renamed { int moved; }\n", null);
        mgr.moveFile(file, target);

        assertTrue(Files.exists(file), "vor dem Commit bleibt die Platte unveraendert");
        assertFalse(Files.exists(target));

        assertTrue(mgr.commitTransaction());

        assertFalse(Files.exists(file), "alte Datei darf nicht (neu) existieren");
        assertTrue(Files.readString(target).contains("int moved;"));
    }

    @Test
    void moveFileInTransaction_rollbackLeavesBothUntouched() throws Exception {
        Path target = tempDir.resolve("Other.java");
        mgr.beginTransaction();
        mgr.moveFile(file, target);
        mgr.rollbackTransaction();

        assertTrue(Files.exists(file));
        assertFalse(Files.exists(target));
    }

    @Test
    void moveFileRefusesExistingTarget() throws Exception {
        Path target = tempDir.resolve("Existing.java");
        Files.writeString(target, "class Existing {}\n");

        assertThrows(java.io.IOException.class, () -> mgr.moveFile(file, target));
        mgr.beginTransaction();
        assertThrows(java.io.IOException.class, () -> mgr.moveFile(file, target));
        assertTrue(Files.readString(target).contains("class Existing"));
    }

    /** AP-1.4 / ED:E-08: Loeschen und danach neu schreiben am selben Pfad -> Datei existiert mit neuem Inhalt. */
    @Test
    void deleteThenWriteSamePath_writeWins() throws Exception {
        ProjectEntry entry = makeEntry();
        mgr.beginTransaction();
        mgr.deleteFile(file);
        mgr.writeFile(entry, file, "class A { int fresh; }\n", null);

        assertTrue(mgr.commitTransaction());

        assertTrue(Files.exists(file), "Datei darf nicht geloescht sein");
        assertTrue(Files.readString(file).contains("int fresh;"));
        assertTrue(mgr.getLastCommittedFiles().contains(de.jare.rewisp.util.PathUtils.canonical(file)));
        assertFalse(mgr.getLastCommittedDeletions().contains(de.jare.rewisp.util.PathUtils.canonical(file)));
    }

    @Test
    void commitDeletionWinsOverPendingWrite() throws Exception {
        ProjectEntry entry = makeEntry();
        mgr.beginTransaction();
        mgr.writeFile(entry, file, "class A { int y; }\n", null);
        mgr.deleteFile(file);

        boolean success = mgr.commitTransaction();

        assertTrue(success);
        // Deletion wins: file is gone, not written
        assertFalse(Files.exists(file));
        assertFalse(mgr.getLastCommittedFiles().contains(de.jare.rewisp.util.PathUtils.canonical(file)));
        assertTrue(mgr.getLastCommittedDeletions().contains(de.jare.rewisp.util.PathUtils.canonical(file)));
    }

    @Test
    void commitEmptyDeletionListIsEmpty() throws Exception {
        ProjectEntry entry = makeEntry();
        mgr.beginTransaction();
        mgr.writeFile(entry, file, "class A { int y; }", null);
        mgr.commitTransaction();
        assertTrue(mgr.getLastCommittedDeletions().isEmpty());
    }
}

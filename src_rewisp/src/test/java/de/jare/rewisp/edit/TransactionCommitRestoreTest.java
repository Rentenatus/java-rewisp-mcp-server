/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * N35: schlaegt ein Commit mitten im Schreiben fehl, stellt der Restore Inhalt UND Aenderungszeit der Datei wieder her.
 * Sonst passt der Fingerprint (mtime + Groesse) des Projekts nicht mehr, und der naechste Edit meldet faelschlich
 * "modified since project load".
 *
 * @author Janusch Rentenatus
 */
class TransactionCommitRestoreTest {

    @TempDir
    Path tempDir;

    @Test
    void failedCommitRestoresContentAndModificationTime() throws Exception {
        Path edited = tempDir.resolve("A.java");
        Path stubborn = tempDir.resolve("D.java");
        Files.writeString(edited, "class A {}\n");
        Files.writeString(stubborn, "class D {}\n");
        FileTime old = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(edited, old);
        DosFileAttributeView dos = Files.getFileAttributeView(stubborn, DosFileAttributeView.class);
        assumeTrue(dos != null, "needs a DOS read-only attribute to make the delete fail");
        dos.setReadOnly(true); // Loeschen schlaegt fehl, nachdem A bereits ersetzt wurde

        TransactionState tx = new TransactionState();
        tx.begin();
        tx.addPendingChange(edited, "class A { int x; }\n");
        tx.addPendingDeletion(stubborn);
        var baselines = new HashMap<Path, Optional<String>>();
        baselines.put(edited, Optional.of("class A {}\n"));
        baselines.put(stubborn, Optional.of("class D {}\n"));

        var result = new TransactionCommitter(tx, baselines).commit();

        dos.setReadOnly(false);
        assertFalse(result.success(), "the delete of the read-only file must fail the commit");
        assertEquals("class A {}\n", Files.readString(edited), "content restored");
        assertEquals(old, Files.getLastModifiedTime(edited), "modification time restored (N35)");
        assertTrue(Files.exists(stubborn));
    }
}
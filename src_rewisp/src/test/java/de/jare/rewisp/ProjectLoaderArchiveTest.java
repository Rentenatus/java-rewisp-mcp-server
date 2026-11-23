/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.model.ProjectLoadException;

/**
 * AP-5.1: Archive werden begrenzt entpackt, ein Wrapper-Ordner wird mit aufgeraeumt,
 * {@code file:}-URIs und URLs mit Query-String werden korrekt verstanden.
 *
 * @author Janusch Rentenatus
 */
class ProjectLoaderArchiveTest {

    @TempDir
    Path tempDir;

    private Path zip(String name, String... namesAndContents) throws Exception {
        Path zip = tempDir.resolve(name);
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                z.putNextEntry(new ZipEntry(namesAndContents[i]));
                z.write(namesAndContents[i + 1].getBytes());
                z.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void zipBombIsRejected() throws Exception {
        Path bomb = zip("bomb.zip", "proj/A.java", "x".repeat(5000));
        ProjectLoader loader = new ProjectLoader(1000, 100);

        ProjectLoadException e = assertThrows(ProjectLoadException.class, () -> loader.resolveSource(bomb.toString()));

        assertEquals("EXTRACT_ERROR", e.getType());
    }

    @Test
    void tooManyEntriesAreRejected() throws Exception {
        Path many = zip("many.zip", "p/A.java", "a", "p/B.java", "b", "p/C.java", "c");
        ProjectLoader loader = new ProjectLoader(1_000_000, 2);

        assertThrows(ProjectLoadException.class, () -> loader.resolveSource(many.toString()));
    }

    @Test
    void wrapperFolderIsReturnedAndCleanupRemovesTheWholeExtraction() throws Exception {
        Path archive = zip("wrapped.zip", "wrapped-main/src/A.java", "class A {}");
        ProjectLoader loader = new ProjectLoader();

        Path root = loader.resolveSource(archive.toString());
        Path extractionRoot = root.getParent();

        assertTrue(Files.exists(root.resolve("src/A.java")));
        loader.cleanup(root);
        assertFalse(Files.exists(extractionRoot), "wrapper directory must be removed too");
    }

    @Test
    void fileUriWithTripleSlashIsResolved() throws Exception {
        Path dir = tempDir.resolve("local");
        Files.createDirectories(dir);

        Path resolved = new ProjectLoader().resolveSource(dir.toUri().toString());

        assertEquals(dir.toRealPath(), resolved.toRealPath());
    }

    @Test
    void archiveUrlWithQueryStringIsRecognized() {
        assertTrue(ProjectLoader.isArchiveUrl("https://host/a/b.zip?token=1"));
        assertTrue(ProjectLoader.isArchiveUrl("https://host/a/b.tar.gz#frag"));
        assertFalse(ProjectLoader.isArchiveUrl("https://host/a/repo.git"));
        assertEquals("b.zip", ProjectLoader.fileNameOf("https://host/a/b.zip?token=1"));
    }
}

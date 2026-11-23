/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.snapshot.SnapshotService;

/**
 * AP-1.15: ISO-8859-1-Quelldateien werden nicht still verworfen oder verfaelscht.
 *
 * @author Janusch Rentenatus
 */
class SourceFilesTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsUtf8AndLatin1() throws IOException {
        Path utf8 = tempDir.resolve("U.java");
        Path latin1 = tempDir.resolve("L.java");
        Files.write(utf8, "class U { String s = \"Größe\"; }".getBytes(StandardCharsets.UTF_8));
        Files.write(latin1, "class L { String s = \"Größe\"; }".getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(StandardCharsets.UTF_8, SourceFiles.charsetOf(utf8));
        assertEquals(StandardCharsets.ISO_8859_1, SourceFiles.charsetOf(latin1));
        assertEquals("class L { String s = \"Größe\"; }", SourceFiles.readText(latin1));
        assertEquals(StandardCharsets.UTF_8, SourceFiles.charsetOf(tempDir.resolve("missing.java")));
    }

    @Test
    void writeKeepsTheOriginalBytesEncoding() throws IOException {
        Path latin1 = tempDir.resolve("L.java");
        Files.write(latin1, "// Größe\n".getBytes(StandardCharsets.ISO_8859_1));

        SourceFiles.writeText(latin1, "// Größe!\n", SourceFiles.charsetOf(latin1));

        assertArrayEquals("// Größe!\n".getBytes(StandardCharsets.ISO_8859_1), Files.readAllBytes(latin1));
    }

    @Test
    void unencodableTextIsRejectedInsteadOfCorrupted() throws IOException {
        Path latin1 = tempDir.resolve("L.java");
        Files.write(latin1, "// Größe\n".getBytes(StandardCharsets.ISO_8859_1));

        IOException e = assertThrows(IOException.class,
                () -> SourceFiles.writeText(latin1, "// Preis: 5 €\n", StandardCharsets.ISO_8859_1));

        assertTrue(e.getMessage().contains("ISO-8859-1"), e.getMessage());
    }

    @Test
    void latin1FilesAreIncludedInTheSnapshot() throws IOException {
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Files.write(src.resolve("L.java"), "class L { String s = \"Größe\"; }".getBytes(StandardCharsets.ISO_8859_1));
        Files.writeString(src.resolve("U.java"), "class U {}");

        var files = new SnapshotService() {
            java.util.Map<Path, de.jare.rewisp.snapshot.FileSnapshot> read() throws IOException {
                return readAllFiles(tempDir);
            }
        }.read();

        assertEquals(2, files.size(), files.keySet().toString());
        assertTrue(files.get(Path.of("src/L.java")).content().contains("Größe"));
        assertEquals(Files.size(src.resolve("L.java")), files.get(Path.of("src/L.java")).size());
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-7.11: Zeilenende wird aus dem Dateikopf erkannt.
 *
 * @author Janusch Rentenatus
 */
class LineEndingsHeadTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsCrlfAndLfFromHead() throws Exception {
        Path crlf = tempDir.resolve("a.java");
        Files.writeString(crlf, "class A {\r\n}\r\n");
        Path lf = tempDir.resolve("b.java");
        Files.writeString(lf, "class B {\n}\n");
        Path empty = tempDir.resolve("c.java");
        Files.writeString(empty, "");

        assertEquals("\r\n", LineEndings.detectFromFile(crlf));
        assertEquals("\n", LineEndings.detectFromFile(lf));
        assertEquals("\n", LineEndings.detectFromFile(empty));
    }
}
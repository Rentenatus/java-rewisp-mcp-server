/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reading and writing of source files with charset preservation. Source files are UTF-8; older projects
 * (e.g. Windows development) often contain ISO-8859-1/Windows-1252. Such files are read as
 * ISO-8859-1 (lossless, every byte is a character) and written back in the original
 * charset, instead of silently discarding them or corrupting them to UTF-8.
 *
 * @author Janusch Rentenatus
 */
public final class SourceFiles {

    private SourceFiles() {}

    /** The charset of the file: UTF-8 when the bytes are valid UTF-8, otherwise ISO-8859-1. */
    public static Charset charsetOf(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** Charset of an existing file; UTF-8 for non-existing (new) files. */
    public static Charset charsetOf(Path file) throws IOException {
        return Files.exists(file) ? charsetOf(Files.readAllBytes(file)) : StandardCharsets.UTF_8;
    }

    /** Reads the file with the detected charset. */
    public static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return new String(bytes, charsetOf(bytes));
    }

    /**
     * Writes the text in the given charset.
     *
     * @throws IOException when the text is not losslessly representable in the charset (e.g. a character
     *                     outside ISO-8859-1 in an ISO-8859-1 file)
     */
    public static void writeText(Path file, String text, Charset charset) throws IOException {
        if (!charset.newEncoder().canEncode(text)) {
            throw new IOException("The content cannot be written to '" + file.getFileName() + "' without loss: "
                    + "the file is encoded as " + charset.name() + " but the new text contains characters outside it.");
        }
        Files.write(file, text.getBytes(charset));
    }
}

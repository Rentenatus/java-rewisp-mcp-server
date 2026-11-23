/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * AP-1.15 Ende-zu-Ende: Eine ISO-8859-1-Datei wird beim Bearbeiten nicht in UTF-8 verfaelscht.
 *
 * @author Janusch Rentenatus
 */
class Latin1EditTest {

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    @Test
    void editLineKeepsIso88591Encoding() throws Exception {
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Path file = src.resolve("L.java");
        String original = "public class L {\n    String s = \"Größe\";\n    int v = 1;\n}\n";
        Files.write(file, original.getBytes(StandardCharsets.ISO_8859_1));
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(src.toString(), null, null, true, true);

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("file", file.toString());
        args.put("lineNumber", 3);
        args.put("newContent", "    int v = 2;");
        var result = new EditLineTool(mgr, new EditManager(backups)).handle(null, new CallToolRequest("edit_line", args));

        assertFalse(result.isError(), result.content().toString());
        String expected = "public class L {\n    String s = \"Größe\";\n    int v = 2;\n}\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.ISO_8859_1), Files.readAllBytes(file));
        mgr.remove(entry.name());
    }
}

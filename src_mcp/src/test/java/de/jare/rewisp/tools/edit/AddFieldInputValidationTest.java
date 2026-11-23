/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Praxistest-Fund: add_field lehnt ungueltige Typen, Namen und Modifikatoren mit klarer Meldung ab
 * und laesst die Datei unveraendert.
 *
 * @author Janusch Rentenatus
 */
class AddFieldInputValidationTest {

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    @Test
    void invalidInputIsRejectedWithoutTouchingTheFile() throws Exception {
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Path file = src.resolve("A.java");
        Files.writeString(file, "public class A {\n}\n");
        byte[] before = Files.readAllBytes(file);
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(src.toString(), null, null, true, true);
        AddFieldTool tool = new AddFieldTool(mgr, new EditManager(backups), RewriteTestSupport.newEngine());

        String[][] bad = {
                {"f", "java.util.List&lt;String&gt;", null, "Not a valid Java type"},
                {"f", "int; int hacked", null, "Not a valid Java type"},
                {"bad name", "int", null, "Not a valid Java field name"},
                {"class", "int", null, "Not a valid Java field name"},
                {"f", "int", "private; static", "Invalid modifiers"},
        };
        for (String[] b : bad) {
            Map<String, Object> args = new HashMap<>();
            args.put("name", entry.name());
            args.put("className", "A");
            args.put("fieldName", b[0]);
            args.put("type", b[1]);
            if (b[2] != null) args.put("modifiers", b[2]);
            var result = tool.handle(null, new CallToolRequest("add_field", args));
            assertTrue(result.isError(), b[1]);
            String text = result.content().toString();
            assertTrue(text.contains(b[3]), text);
        }
        assertArrayEquals(before, Files.readAllBytes(file));
        mgr.remove(entry.name());
    }
}
/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Praxistest-Fund: edit_line darf keine Dateien ausserhalb des Projekts aendern
 * (absoluter Pfad oder ../-Pfad).
 *
 * @author Janusch Rentenatus
 */
class EditLineContainmentTest {

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    private String edit(ProjectManager mgr, String name, String file) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("file", file);
        args.put("lineNumber", 1);
        args.put("newContent", "class Hacked {}");
        var result = new EditLineTool(mgr, new EditManager(backups)).handle(null, new CallToolRequest("edit_line", args));
        assertTrue(result.isError(), "edit outside the project must be rejected: " + result.content());
        return result.content().toString();
    }

    @Test
    void filesOutsideTheProjectAreNotWritable() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("proj/src"));
        Files.writeString(project.resolve("A.java"), "public class A {}\n");
        Path outside = tempDir.resolve("outside.java");
        Files.writeString(outside, "class Outside {}\n");
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(project.toString(), null, null, true, true);

        String abs = edit(mgr, entry.name(), outside.toString());
        String rel = edit(mgr, entry.name(), "../../outside.java");

        assertTrue(abs.contains("SECURITY_ERROR"), abs);
        assertTrue(rel.contains("SECURITY_ERROR") || rel.contains("not found"), rel);
        assertEquals("class Outside {}\n", Files.readString(outside));
        mgr.remove(entry.name());
    }
}
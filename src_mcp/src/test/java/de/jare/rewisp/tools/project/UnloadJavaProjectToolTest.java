/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */

package de.jare.rewisp.tools.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Tests for {@link UnloadJavaProjectTool}.
 *
 * @author Janusch Rentenatus
 */
class UnloadJavaProjectToolTest {

    @TempDir
    Path tempDir;

    private CallToolResult unload(UnloadJavaProjectTool tool, String name) throws Exception {
        return tool.handle(null, CallToolRequest.builder()
                .name("unload_java_project").arguments(Map.of("name", name)).build());
    }

    /** AP-3.14 / RD:R-07: Entladen ueber den Alias muss funktionieren. */
    @Test
    void unloadByAlias_removesProject() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}\n");
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(src.toString(), "myalias", null, true, true);
        assertNotNull(mgr.find(entry.name()));

        CallToolResult result = unload(new UnloadJavaProjectTool(mgr), "myalias");

        assertFalse(result.isError());
        assertTrue(result.content().toString().contains("unloaded successfully"), result.content().toString());
        assertNull(mgr.find(entry.name()), "Projekt muss entladen sein");
    }

    /** AP-3.16 / RD:R-06: Ausgabe darf keine literalen Backslash-n-Sequenzen enthalten. */
    @Test
    void reloadOutput_hasRealNewlines() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}\n");
        ProjectManager mgr = new ProjectManager();
        mgr.load(src.toString(), "rl", null, true, true);

        CallToolResult result = new ReloadJavaProjectTool(mgr).handle(null,
                CallToolRequest.builder().name("reload_java_project").arguments(Map.of("name", "rl")).build());

        String text = result.content().toString();
        assertFalse(text.contains("\\\\n"), "literale \\n in der Ausgabe: " + text);
        assertTrue(text.contains("Project Reload Complete"), text);
        mgr.removeAll();
    }

    @Test
    void unloadByName_stillWorks() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}\n");
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(src.toString(), "other", null, true, true);

        unload(new UnloadJavaProjectTool(mgr), entry.name());

        assertNull(mgr.find(entry.name()));
    }
}

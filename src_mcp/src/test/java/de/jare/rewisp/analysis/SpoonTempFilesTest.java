/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.tools.edit.EditLineTool;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * Praxistest-Fund: Spoon-Temp-Kopien werden nach jedem Neuaufbau des Modells entfernt.
 *
 * @author Janusch Rentenatus
 */
class SpoonTempFilesTest {

    @TempDir
    Path dir;
    @TempDir
    Path project;
    @TempDir
    Path backups;

    @Test
    void deletesOnlyNewCopiesOfOurSources() throws Exception {
        Files.writeString(dir.resolve("Old.java111.java"), "x");
        Set<String> before = SpoonTempFiles.snapshot(dir);
        Files.writeString(dir.resolve("Foo.java123.java"), "x");   // neue Kopie von Foo.java
        Files.writeString(dir.resolve("Other.java456.java"), "x"); // fremde Datei
        Files.writeString(dir.resolve("Foo.java.java"), "x");      // keine Zahl: kein Spoon-Muster

        int deleted = SpoonTempFiles.deleteNewSince(dir, before, Set.of("Foo.java"));

        assertEquals(1, deleted);
        assertFalse(Files.exists(dir.resolve("Foo.java123.java")));
        assertTrue(Files.exists(dir.resolve("Old.java111.java")));
        assertTrue(Files.exists(dir.resolve("Other.java456.java")));
        assertTrue(Files.exists(dir.resolve("Foo.java.java")));
    }

    @Test
    void modelRebuildLeavesNoTempCopies() throws Exception {
        Path src = Files.createDirectories(project.resolve("src"));
        Path file = src.resolve("TempProbeUnique.java");
        Files.writeString(file, "public class TempProbeUnique {\n    int a;\n}\n");
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(src.toString(), null, null, true, true);

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("file", file.toString());
        args.put("lineNumber", 2);
        args.put("newContent", "    int b;");
        assertFalse(new EditLineTool(mgr, new EditManager(backups)).handle(null, new CallToolRequest("edit_line", args)).isError());
        mgr.ensureSpoonModel(entry.name()); // loest den Neuaufbau aus

        Set<String> leftovers = SpoonTempFiles.snapshot(Path.of(System.getProperty("java.io.tmpdir")));
        assertTrue(leftovers.stream().noneMatch(n -> n.startsWith("TempProbeUnique.java")), leftovers.toString());
        mgr.remove(entry.name());
    }
}
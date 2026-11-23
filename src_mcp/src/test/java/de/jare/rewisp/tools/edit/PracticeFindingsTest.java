/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import de.jare.rewisp.tools.read.FindReferencesTool;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * Praxistest-Funde: find_references ohne positionslose Treffer, rename_symbol zaehlt Deklarationen
 * und Aufrufer getrennt, keine "Model is dirty"-Warnung nach Edits.
 *
 * @author Janusch Rentenatus
 */
class PracticeFindingsTest {

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    private ProjectManager mgr;
    private ProjectEntry entry;

    private void setup() throws Exception {
        Path src = Files.createDirectories(tempDir.resolve("src/p"));
        Files.writeString(src.resolve("I.java"), "package p;\npublic interface I {\n    void m();\n}\n");
        Files.writeString(src.resolve("A.java"), "package p;\npublic class A implements I {\n    public void m() {}\n}\n");
        Files.writeString(src.resolve("U.java"),
                "package p;\npublic class U {\n    void f(I[] a) {\n        a[0].m();\n    }\n}\n");
        mgr = new ProjectManager();
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
    }

    @Test
    void findReferencesHasNoPositionlessHits() throws Exception {
        setup();
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "p.I");
        var result = new FindReferencesTool(mgr).handle(null, new CallToolRequest("find_references", args));

        String text = result.content().toString();
        assertFalse(result.isError(), text);
        assertFalse(text.contains("(no position)"), text);
        assertTrue(text.contains("**Total references:** 3"), text); // A implements I, I[] parameter, a[0].m()
        mgr.remove(entry.name());
    }

    @Test
    void renameReportsDeclarationsAndCallersSeparatelyAndNoStaleBanner() throws Exception {
        setup();
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "p.I");
        args.put("oldName", "m");
        args.put("newName", "n");
        var tool = new RenameSymbolTool(mgr, new EditManager(backups), RewriteTestSupport.newEngine());
        var result = tool.handle(null, new CallToolRequest("rename_symbol", args));

        String text = result.content().toString();
        assertFalse(result.isError(), text);
        assertTrue(text.contains("Declarations renamed: 1"), text);
        assertTrue(text.contains("Callers updated: 1"), text);
        assertFalse(text.contains("Model is dirty"), text);
        mgr.remove(entry.name());
    }
}
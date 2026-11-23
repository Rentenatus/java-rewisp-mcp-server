/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import de.jare.rewisp.edit.DryRunPreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Q2: Dry-Run zeigt den Diff der geplanten Aenderung, nicht nur die Dateinamen.
 *
 * @author Janusch Rentenatus
 */
class DryRunDiffTest {

    @TempDir
    Path tempDir;

    @Test
    void diffShowsChangedLineWithContext() {
        StringBuilder sb = new StringBuilder();
        DryRunPreview.appendDiff(sb, "a\nb\nc\nd\ne\nf\n", "a\nb\nc\nX\ne\nf\n");
        String d = sb.toString();
        assertTrue(d.contains("- d"), d);
        assertTrue(d.contains("+ X"), d);
        assertTrue(d.contains("      c"), "context line before: " + d);
        assertTrue(d.contains("      e"), "context line after: " + d);
        assertFalse(d.contains("      a"), "context limited to 2 lines: " + d);
        assertTrue(d.contains("@@ line 2 @@"), d);
    }

    @Test
    void diffShowsInsertionAndDeletion() {
        StringBuilder sb = new StringBuilder();
        DryRunPreview.appendDiff(sb, "x\ny\n", "x\nnew\ny\n");
        assertTrue(sb.toString().contains("+ new"), sb.toString());
        assertFalse(sb.toString().contains("- "), sb.toString());

        StringBuilder del = new StringBuilder();
        DryRunPreview.appendDiff(del, "x\ngone\ny\n", "x\ny\n");
        assertTrue(del.toString().contains("- gone"), del.toString());
        assertFalse(del.toString().contains("+ "), del.toString());
    }

    @Test
    void identicalTextIsReported() {
        StringBuilder sb = new StringBuilder();
        DryRunPreview.appendDiff(sb, "a\r\nb\r\n", "a\nb\n");
        assertTrue(sb.toString().contains("no textual difference"), sb.toString());
    }

    @Test
    void renameDryRunShowsEveryChangedLine() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Path file = src.resolve("Caller.java");
        String original = "class Caller {\n  int getValue() { return 1; }\n  int twice() { return getValue() + getValue(); }\n}\n";
        Files.writeString(file, original);
        ProjectManager mgr = new ProjectManager();
        EditManager em = new EditManager(tempDir.resolve("backups"));
        ProjectEntry entry = mgr.load(src.toString(), null, null, true, true);

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "Caller");
        args.put("oldName", "getValue");
        args.put("newName", "fetchValue");
        args.put("scope", "method");
        args.put("dryRun", true);
        CallToolResult r = new RenameSymbolTool(mgr, em, RewriteTestSupport.newEngine())
                .handle(null, new CallToolRequest("rename_symbol", args));
        String text = r.content().toString();

        assertFalse(r.isError(), text);
        assertTrue(text.contains("- ") && text.contains("int getValue() { return 1; }"), text);
        assertTrue(text.contains("+ ") && text.contains("int fetchValue() { return 1; }"), text);
        assertTrue(text.contains("return fetchValue() + fetchValue();"), text);
        assertEquals(original, Files.readString(file), "dry run must not write");
        mgr.remove(entry.name());
    }

    @Test
    void addClassDryRunShowsNewContent() throws Exception {
        Path src = tempDir.resolve("src2");
        Files.createDirectories(src);
        ProjectManager mgr = new ProjectManager();
        EditManager em = new EditManager(tempDir.resolve("backups2"));
        ProjectEntry entry = mgr.load(src.toString(), null, null, true, true);

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("packageName", "com.example");
        args.put("className", "NewDry");
        args.put("type", "class");
        args.put("dryRun", true);
        CallToolResult r = new AddClassTool(mgr, em, RewriteTestSupport.newEngine())
                .handle(null, new CallToolRequest("add_class", args));
        String text = r.content().toString();

        assertFalse(r.isError(), text);
        assertTrue(text.contains("+ package com.example;"), text);
        assertTrue(text.contains("class NewDry"), text);
        assertFalse(Files.exists(src.resolve("com/example/NewDry.java")));
        mgr.remove(entry.name());
    }
}

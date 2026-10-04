/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jare.rewisp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Tests for the optional dryRun parameter: with dryRun=true the tools only
 * run the OpenRewrite preview (or generate the content) and report what
 * would change — nothing is written to disk.
 *
 * @author Janusch Rentenatus
 */
class DryRunToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    @Test
    void addAnnotationDryRunWritesNothing() throws Exception {
        Path file = srcDir.resolve("Svc.java");
        Files.writeString(file, "class Svc {\n  void handle() {}\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        AddAnnotationTool tool = new AddAnnotationTool(mgr, editMgr,
                RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("targetType", "class");
        args.put("className", "Svc");
        args.put("annotation", "Deprecated");
        args.put("dryRun", true);
        CallToolResult result = tool.handle(null, new CallToolRequest("add_annotation", args));

        String content = result.content().toString();
        assertFalse(result.isError());
        assertTrue(content.contains("DRY RUN"));
        assertTrue(content.contains("no files written"));
        assertTrue(content.contains("Svc.java"));
        // File unchanged on disk
        assertFalse(Files.readString(file).contains("Deprecated"));
        mgr.remove(entry.name());
    }

    @Test
    void addAnnotationWithoutDryRunStillWrites() throws Exception {
        Path file = srcDir.resolve("Svc2.java");
        Files.writeString(file, "class Svc2 {\n  void handle() {}\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        AddAnnotationTool tool = new AddAnnotationTool(mgr, editMgr,
                RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("targetType", "class");
        args.put("className", "Svc2");
        args.put("annotation", "Deprecated");
        CallToolResult result = tool.handle(null, new CallToolRequest("add_annotation", args));

        assertFalse(result.isError());
        assertTrue(Files.readString(file).contains("Deprecated"));
        mgr.remove(entry.name());
    }

    @Test
    void renameSymbolDryRunKeepsCallersUnchanged() throws Exception {
        Path file = srcDir.resolve("Caller.java");
        Files.writeString(file, "class Caller {\n  int getValue() { return 1; }\n  int twice() { return getValue() + getValue(); }\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        RenameSymbolTool tool = new RenameSymbolTool(mgr, editMgr,
                RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "Caller");
        args.put("oldName", "getValue");
        args.put("newName", "fetchValue");
        args.put("scope", "method");
        args.put("updateCallers", true);
        args.put("dryRun", true);
        CallToolResult result = tool.handle(null, new CallToolRequest("rename_symbol", args));

        String content = result.content().toString();
        assertFalse(result.isError());
        assertTrue(content.contains("DRY RUN"));
        String onDisk = Files.readString(file);
        assertTrue(onDisk.contains("getValue"));
        assertFalse(onDisk.contains("fetchValue"));
        mgr.remove(entry.name());
    }

    @Test
    void editLineDryRunShowsDiff() throws Exception {
        Path file = srcDir.resolve("Line.java");
        Files.writeString(file, "class Line {\nint x;\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        EditLineTool tool = new EditLineTool(mgr, editMgr);

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("file", "Line.java");
        args.put("lineNumber", 2);
        args.put("newContent", "int y;");
        args.put("dryRun", true);
        CallToolResult result = tool.handle(null, new CallToolRequest("edit_line", args));

        String content = result.content().toString();
        assertFalse(result.isError());
        assertTrue(content.contains("DRY RUN"));
        assertTrue(content.contains("- int x;"));
        assertTrue(content.contains("+ int y;"));
        // File unchanged on disk
        assertTrue(Files.readString(file).contains("int x;"));
        mgr.remove(entry.name());
    }

    @Test
    void addClassDryRunDoesNotCreateFile() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        AddClassTool tool = new AddClassTool(mgr, editMgr,
                RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("packageName", "com.example");
        args.put("className", "NewDry");
        args.put("type", "class");
        args.put("dryRun", true);
        CallToolResult result = tool.handle(null, new CallToolRequest("add_class", args));

        String content = result.content().toString();
        assertFalse(result.isError());
        assertTrue(content.contains("DRY RUN"));
        Path created = srcDir.resolve("com").resolve("example").resolve("NewDry.java");
        assertFalse(Files.exists(created), "dryRun must not create the file");
        mgr.remove(entry.name());
    }
}

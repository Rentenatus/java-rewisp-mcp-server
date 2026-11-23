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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * AP-4.5: {@code add_annotation} und {@code rewrite_signature} fuegen die benoetigten Imports
 * tatsaechlich in die Datei ein.
 *
 * @author Janusch Rentenatus
 */
class RecipeImportsTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private Path target;
    private ProjectEntry entry;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("a"));
        Files.createDirectories(src.resolve("b"));
        Files.writeString(src.resolve("a/MyAnno.java"),
                "package a;\npublic @interface MyAnno {}\n");
        Files.writeString(src.resolve("a/Data.java"),
                "package a;\npublic class Data {}\n");
        target = src.resolve("b/Target.java");
        Files.writeString(target,
                "package b;\n"
              + "public class Target {\n"
              + "    public void m() {}\n"
              + "}\n");
        entry = mgr.load(src.toString(), null, null, true, true);
    }

    @Test
    void addAnnotation_addsImportForQualifiedAnnotation() throws Exception {
        AddAnnotationTool tool = new AddAnnotationTool(mgr, editMgr, RewriteTestSupport.newEngine());
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("targetType", "method");
        args.put("className", "b.Target");
        args.put("targetName", "m");
        args.put("annotation", "a.MyAnno");

        CallToolResult r = tool.handle(null, new CallToolRequest("add_annotation", args));

        assertFalse(r.isError(), r.content().toString());
        String content = Files.readString(target);
        assertTrue(content.contains("@MyAnno"), content);
        assertTrue(content.contains("import a.MyAnno;"), content);
        mgr.remove(entry.name());
    }

    @Test
    void rewriteSignature_rejectsUnresolvableType_withoutEditing() throws Exception {
        RewriteSignatureTool tool = new RewriteSignatureTool(mgr, editMgr, RewriteTestSupport.newEngine());
        String before = Files.readString(target);
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "b.Target");
        args.put("methodName", "m");
        args.put("newParameters", "NoSuchType x");
        args.put("mode", "signature_only");

        CallToolResult r = tool.handle(null, new CallToolRequest("rewrite_signature", args));

        assertTrue(r.isError());
        assertTrue(r.content().toString().contains("UNRESOLVED_TYPES"), r.content().toString());
        assertTrue(before.equals(Files.readString(target)), "file must stay unchanged");
        mgr.remove(entry.name());
    }

    @Test
    void rewriteSignature_addsImportForNewTypes() throws Exception {
        RewriteSignatureTool tool = new RewriteSignatureTool(mgr, editMgr, RewriteTestSupport.newEngine());
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "b.Target");
        args.put("methodName", "m");
        args.put("newParameters", "Data d");
        args.put("mode", "signature_only");

        CallToolResult r = tool.handle(null, new CallToolRequest("rewrite_signature", args));

        assertFalse(r.isError(), r.content().toString());
        String content = Files.readString(target);
        assertTrue(content.contains("m(Data d)"), content);
        assertTrue(content.contains("import a.Data;"), content);
        mgr.remove(entry.name());
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
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
 * Tests for {@link EditAnnotationTool} with both Spoon and OpenRewrite implementations.
 * 
 * @author Janusch Rentenatus
 */
class EditAnnotationToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private EditAnnotationTool editAnno;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        // Use legacy constructor for Spoon-based tests
        editAnno = new EditAnnotationTool(mgr, editMgr, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    @Test
    void editAnnotationAttributesOnClass() throws Exception {
        Path file = srcDir.resolve("U.java");
        Files.writeString(file, "@Deprecated(value=false)\nclass U {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = editAnno.handle(null, reqEdit(entry.name(), "class",
                "U", "Deprecated", "value=true"));

        assertFalse(result.isError());
        assertTrue(Files.readString(file).replaceAll("\s+", "").contains("value=true"));
        mgr.remove(entry.name());
    }

    @Test
    void editAnnotationAttributesOnMethod() throws Exception {
        Path file = srcDir.resolve("M.java");
        Files.writeString(file, "class M {\n  @Transactional(value=false)\n  void handle() {}\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = editAnno.handle(null, reqEditMethod(entry.name(), "method",
                "M", "handle", "Transactional", "value=true"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.replaceAll("\s+", "").contains("value=true"));
        assertFalse(written.contains("value=false"));
        mgr.remove(entry.name());
    }

    @Test
    void editAnnotationAttributesOnField() throws Exception {
        Path file = srcDir.resolve("F.java");
        Files.writeString(file, "class F {\n  @NotNull(message=\"old message\")\n  String name;\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = editAnno.handle(null, reqEditField(entry.name(), "field",
                "F", "name", "NotNull", "message=\"new message\""));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.replaceAll("\s+=\s+", "=").contains("message=\"new message\""));
        assertFalse(written.replaceAll("\s+=\s+", "=").contains("message=\"old message\""));
        mgr.remove(entry.name());
    }

    @Test
    void editAnnotationNotPresent() throws Exception {
        Path file = srcDir.resolve("V.java");
        Files.writeString(file, "class V {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = editAnno.handle(null, reqEdit(entry.name(), "class",
                "V", "Override", "value=true"));

        assertTrue(result.isError());
        assertTrue(result.content().toString().contains("isDomainError"));
        mgr.remove(entry.name());
    }

    @Test
    void editAnnotationOnOverloadedMethodErrors() throws Exception {
        Path file = srcDir.resolve("Over.java");
        Files.writeString(file, "class Over {\n  @Transactional(value=false)\n  int process(int x) { return x; }\n  String process(String s) { return s; }\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = editAnno.handle(null, reqEditMethod(entry.name(), "method",
                "Over", "process", "Transactional", "value=true"));

        assertTrue(result.isError());
        assertTrue(result.content().toString().contains("isDomainError"));
        assertTrue(result.content().toString().contains("Multiple methods"));
        mgr.remove(entry.name());
    }

    @Test
    void editAnnotationRemoveAttributes() throws Exception {
        Path file = srcDir.resolve("R.java");
        Files.writeString(file, "@Deprecated(value=\"old\", since=\"1.0\")\nclass R {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // Edit to remove attributes (empty string)
        CallToolResult result = editAnno.handle(null, reqEdit(entry.name(), "class",
                "R", "Deprecated", ""));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("@Deprecated"));
        mgr.remove(entry.name());
    }

    @Test
    void editAnnotationAddAttributes() throws Exception {
        Path file = srcDir.resolve("A.java");
        Files.writeString(file, "@Deprecated\nclass A {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // Edit to add attributes
        CallToolResult result = editAnno.handle(null, reqEdit(entry.name(), "class",
                "A", "Deprecated", "value=\"new\""));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.replaceAll("\s+", "").contains("@Deprecated(value=\"new\")"));
        mgr.remove(entry.name());
    }

    @Test
    void editAnnotationMultipleAttributes() throws Exception {
        Path file = srcDir.resolve("Multi.java");
        Files.writeString(file, "@Transactional\nclass Multi {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // Edit to add multiple attributes
        CallToolResult result = editAnno.handle(null, reqEdit(entry.name(), "class",
                "Multi", "Transactional", "value=true, rollbackFor=Exception.class"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.replaceAll("\s+", "").contains("value=true"));
        assertTrue(written.replaceAll("\s+", "").contains("rollbackFor=Exception.class"));
        mgr.remove(entry.name());
    }

    private static CallToolRequest reqEdit(String name, String targetType, String className,
            String annotation, String newAttributes) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("targetType", targetType);
        args.put("className", className);
        args.put("annotation", annotation);
        args.put("newAttributes", newAttributes);
        return new CallToolRequest("edit_annotation", args);
    }

    private static CallToolRequest reqEditMethod(String name, String targetType, String className,
            String targetName, String annotation, String newAttributes) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("targetType", targetType);
        args.put("className", className);
        args.put("targetName", targetName);
        args.put("annotation", annotation);
        args.put("newAttributes", newAttributes);
        return new CallToolRequest("edit_annotation", args);
    }

    private static CallToolRequest reqEditField(String name, String targetType, String className,
            String targetName, String annotation, String newAttributes) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("targetType", targetType);
        args.put("className", className);
        args.put("targetName", targetName);
        args.put("annotation", annotation);
        args.put("newAttributes", newAttributes);
        return new CallToolRequest("edit_annotation", args);
    }
}

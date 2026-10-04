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
import java.util.List;
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
 * Tests for {@link AddFieldTool} with both Spoon and OpenRewrite implementations.
 * Focuses on migration-specific tests.
 * 
 * @author Janusch Rentenatus
 */
class AddFieldToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private AddFieldTool addField;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        // Use legacy constructor for Spoon-based tests
        addField = new AddFieldTool(mgr, editMgr, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    @Test
    void addSimpleField() throws Exception {
        Path file = srcDir.resolve("A.java");
        Files.writeString(file, "class A {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addField.handle(null, fieldReq(
                entry.name(), "A", "count", "int", null, null));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("int count;"));
        mgr.remove(entry.name());
    }

    @Test
    void addFieldWithInitializer() throws Exception {
        Path file = srcDir.resolve("B.java");
        Files.writeString(file, "class B {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addField.handle(null, fieldReq(
                entry.name(), "B", "name", "String", null, "\"default\""));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("String name = \"default\";"));
        mgr.remove(entry.name());
    }

    @Test
    void addFieldWithModifiers() throws Exception {
        Path file = srcDir.resolve("C.java");
        Files.writeString(file, "class C {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addField.handle(null, fieldReq(
                entry.name(), "C", "INSTANCE", "C", "public static final", null));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("public static final C INSTANCE;"));
        mgr.remove(entry.name());
    }

    @Test
    void addPrivateField() throws Exception {
        Path file = srcDir.resolve("D.java");
        Files.writeString(file, "class D {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addField.handle(null, fieldReq(
                entry.name(), "D", "secret", "String", "private", "\"hidden\""));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("private String secret = \"hidden\";"));
        mgr.remove(entry.name());
    }

    @Test
    void addFieldWithExistingFieldFails() throws Exception {
        Path file = srcDir.resolve("E.java");
        Files.writeString(file, "class E {\n  int existing;\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addField.handle(null, fieldReq(
                entry.name(), "E", "existing", "String", null, null));

        assertTrue(result.isError());
        assertTrue(result.content().toString().contains("isDomainError"));
        assertTrue(result.content().toString().contains("already exists"));
        mgr.remove(entry.name());
    }

    @Test
    void addFieldWithGenericType() throws Exception {
        Path file = srcDir.resolve("F.java");
        Files.writeString(file, "class F {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // Provide imports explicitly to resolve List and ArrayList
        CallToolResult result = addField.handle(null, fieldReqWithImports(
                entry.name(), "F", "items", "List<String>", null, "new ArrayList<>()",
                List.of("java.util.List", "java.util.ArrayList")));

        assertFalse(result.isError());
        String written = Files.readString(file);
        // Check that the field was added with the correct type and initializer
        assertTrue(written.contains("List<String> items"));
        assertTrue(written.contains("new ArrayList<>()"));
        mgr.remove(entry.name());
    }

    @Test
    void addFieldWithImports() throws Exception {
        Path file = srcDir.resolve("G.java");
        Files.writeString(file, "class G {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // With imports provided, List and ArrayList should be resolved
        CallToolResult result = addField.handle(null, fieldReqWithImports(
                entry.name(), "G", "list", "List", null, null,
                List.of("java.util.List")));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("import java.util.List;"));
        assertTrue(written.contains("List list"));
        mgr.remove(entry.name());
    }

    @Test
    void addStaticFinalField() throws Exception {
        Path file = srcDir.resolve("H.java");
        Files.writeString(file, "class H {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addField.handle(null, fieldReq(
                entry.name(), "H", "CONSTANT", "int", "static final", "42"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("static final int CONSTANT = 42;"));
        mgr.remove(entry.name());
    }

    @Test
    void addFieldToClassWithExistingFields() throws Exception {
        Path file = srcDir.resolve("I.java");
        Files.writeString(file, "class I {\n  String field1;\n  int field2;\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addField.handle(null, fieldReq(
                entry.name(), "I", "field3", "boolean", null, "true"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("String field1;"));
        assertTrue(written.contains("int field2;"));
        assertTrue(written.contains("boolean field3 = true;"));
        mgr.remove(entry.name());
    }

    private static CallToolRequest fieldReq(String name, String className, String fieldName,
            String type, String modifiers, String initializer) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", className);
        args.put("fieldName", fieldName);
        args.put("type", type);
        if (modifiers != null) args.put("modifiers", modifiers);
        if (initializer != null) args.put("initializer", initializer);
        return new CallToolRequest("add_field", args);
    }

    private static CallToolRequest fieldReqWithImports(String name, String className, String fieldName,
            String type, String modifiers, String initializer, List<String> imports) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", className);
        args.put("fieldName", fieldName);
        args.put("type", type);
        if (modifiers != null) args.put("modifiers", modifiers);
        if (initializer != null) args.put("initializer", initializer);
        if (imports != null && !imports.isEmpty()) args.put("imports", imports);
        return new CallToolRequest("add_field", args);
    }
}

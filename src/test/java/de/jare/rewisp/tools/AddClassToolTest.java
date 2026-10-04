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
 * Tests for {@link AddClassTool} with both Spoon and OpenRewrite implementations.
 * Focuses on migration-specific tests.
 * 
 * @author Janusch Rentenatus
 */
class AddClassToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private AddClassTool addClass;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        // Use legacy constructor for Spoon-based tests
        addClass = new AddClassTool(mgr, editMgr, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    private CallToolRequest classReq(String name, String packageName, String className,
                                      String type, String body, List<String> imports) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("packageName", packageName);
        args.put("className", className);
        if (type != null) args.put("type", type);
        if (body != null) args.put("body", body);
        if (imports != null && !imports.isEmpty()) args.put("imports", imports);
        return new CallToolRequest("add_class", args);
    }

    @Test
    void addSimpleClass() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example", "MyClass", "class", null, null));

        assertFalse(result.isError());
        Path file = srcDir.resolve("com/example/MyClass.java");
        assertTrue(Files.exists(file));
        String written = Files.readString(file);
        assertTrue(written.contains("package com.example;"));
        assertTrue(written.contains("class MyClass {"));
        assertTrue(written.contains("}"));
        mgr.remove(entry.name());
    }

    @Test
    void addClassWithBody() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example", "MyClass", "class",
                "private int field;\npublic void method() {}", null));

        assertFalse(result.isError());
        Path file = srcDir.resolve("com/example/MyClass.java");
        assertTrue(Files.exists(file));
        String written = Files.readString(file);
        assertTrue(written.contains("package com.example;"));
        assertTrue(written.contains("class MyClass {"));
        assertTrue(written.contains("    private int field;"));
        assertTrue(written.contains("    public void method() {}"));
        mgr.remove(entry.name());
    }

    @Test
    void addAbstractClass() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example", "AbstractClass", "abstract",
                "public abstract void method();", null));

        assertFalse(result.isError());
        Path file = srcDir.resolve("com/example/AbstractClass.java");
        assertTrue(Files.exists(file));
        String written = Files.readString(file);
        assertTrue(written.contains("abstract class AbstractClass {"));
        mgr.remove(entry.name());
    }

    @Test
    void addInterface() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example", "MyInterface", "interface",
                "void method1();\nint method2();", null));

        assertFalse(result.isError());
        Path file = srcDir.resolve("com/example/MyInterface.java");
        assertTrue(Files.exists(file));
        String written = Files.readString(file);
        assertTrue(written.contains("interface MyInterface {"));
        assertTrue(written.contains("    void method1();"));
        mgr.remove(entry.name());
    }

    @Test
    void addEnum() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example", "MyEnum", "enum",
                "VALUE1, VALUE2, VALUE3", null));

        assertFalse(result.isError());
        Path file = srcDir.resolve("com/example/MyEnum.java");
        assertTrue(Files.exists(file));
        String written = Files.readString(file);
        assertTrue(written.contains("enum MyEnum {"));
        assertTrue(written.contains("    VALUE1, VALUE2, VALUE3"));
        mgr.remove(entry.name());
    }

    @Test
    void addClassWithDefaultType() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // type is optional, should default to "class"
        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example", "DefaultClass", null, null, null));

        assertFalse(result.isError());
        Path file = srcDir.resolve("com/example/DefaultClass.java");
        assertTrue(Files.exists(file));
        String written = Files.readString(file);
        assertTrue(written.contains("class DefaultClass {"));
        mgr.remove(entry.name());
    }

    @Test
    void addClassWithImports() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example", "MyClass", "class",
                "private java.util.List<String> list;", List.of("java.util.List")));

        assertFalse(result.isError());
        Path file = srcDir.resolve("com/example/MyClass.java");
        assertTrue(Files.exists(file));
        String written = Files.readString(file);
        assertTrue(written.contains("import java.util.List;"));
        assertTrue(written.contains("private java.util.List<String> list;"));
        mgr.remove(entry.name());
    }

    @Test
    void addClassCreatesPackageDirectories() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example.deep.package", "MyClass", "class", null, null));

        assertFalse(result.isError());
        Path file = srcDir.resolve("com/example/deep/package/MyClass.java");
        assertTrue(Files.exists(file));
        mgr.remove(entry.name());
    }

    @Test
    void addClassFailsWhenClassExists() throws Exception {
        Path packageDir = srcDir.resolve("com/example");
        Files.createDirectories(packageDir);
        Path file = packageDir.resolve("ExistingClass.java");
        Files.writeString(file, "class ExistingClass {\n}\n");
        
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example", "ExistingClass", "class", null, null));

        // Domain errors have content containing the error message
        assertTrue(result.content().toString().contains("already exists"));
        mgr.remove(entry.name());
    }

    @Test
    void addClassWithInvalidType() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example", "MyClass", "invalid", null, null));

        // Domain errors have content containing the error message
        assertTrue(result.content().toString().contains("INVALID_TYPE"));
        mgr.remove(entry.name());
    }

    @Test
    void addClassWithMultilineBody() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "com.example", "MyClass", "class",
                "private int x;\n\npublic int getX() {\n    return x;\n}\n\npublic void setX(int x) {\n    this.x = x;\n}", null));

        assertFalse(result.isError());
        Path file = srcDir.resolve("com/example/MyClass.java");
        assertTrue(Files.exists(file));
        String written = Files.readString(file);
        assertTrue(written.contains("private int x;"));
        assertTrue(written.contains("public int getX() {"));
        assertTrue(written.contains("    return x;"));
        assertTrue(written.contains("public void setX(int x) {"));
        assertTrue(written.contains("    this.x = x;"));
        mgr.remove(entry.name());
    }

    @Test
    void addClassInDefaultPackage() throws Exception {
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // Note: In Java, you can't have classes in the default package in standard directory structure
        // But we can test that it doesn't crash
        CallToolResult result = addClass.handle(null, classReq(
                entry.name(), "", "DefaultPackageClass", "class", null, null));

        // This should either work or fail gracefully
        // The implementation might reject empty package names
        mgr.remove(entry.name());
    }
}

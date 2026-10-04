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
 * Tests for {@link AddMethodTool} with both Spoon and OpenRewrite implementations.
 * Focuses on migration-specific tests.
 * 
 * @author Janusch Rentenatus
 */
class AddMethodToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private AddMethodTool addMethod;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        // Use legacy constructor for Spoon-based tests
        addMethod = new AddMethodTool(mgr, editMgr, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    @Test
    void addMethodWithReturnTypeAndBody() throws Exception {
        Path file = srcDir.resolve("A.java");
        Files.writeString(file, "class A {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addMethod.handle(null, methodReq(
                entry.name(), "A", "compute", "int", null, "public", "return 42;"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("public int compute()"));
        assertTrue(written.contains("return 42;"));
        mgr.remove(entry.name());
    }

    @Test
    void addMethodWithParameters() throws Exception {
        Path file = srcDir.resolve("B.java");
        Files.writeString(file, "class B {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addMethod.handle(null, methodReq(
                entry.name(), "B", "greet", "String", "String name", "public", "return \"Hello \" + name;"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("public String greet(String name)"));
        mgr.remove(entry.name());
    }

    @Test
    void addMethodToInterface() throws Exception {
        Path file = srcDir.resolve("I.java");
        Files.writeString(file, "interface I {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // Interface method without body should be abstract
        CallToolResult result = addMethod.handle(null, methodReq(
                entry.name(), "I", "process", "void", "int x", null, null));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("void process(int x);"));
        mgr.remove(entry.name());
    }

    @Test
    void addDefaultMethodToInterface() throws Exception {
        Path file = srcDir.resolve("J.java");
        Files.writeString(file, "interface J {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // Interface method with body should be default
        CallToolResult result = addMethod.handle(null, methodReq(
                entry.name(), "J", "defaultMethod", "void", null, null, "System.out.println(\"default\");"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("default void defaultMethod()"));
        assertTrue(written.contains("System.out.println(\"default\");"));
        mgr.remove(entry.name());
    }

    @Test
    void addConstructor() throws Exception {
        Path file = srcDir.resolve("C.java");
        Files.writeString(file, "class C {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // Constructor: method name equals class name, return type equals class name
        CallToolResult result = addMethod.handle(null, methodReq(
                entry.name(), "C", "C", "C", "String name", "public", "this();"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("public C(String name)"));
        mgr.remove(entry.name());
    }

    @Test
    void addStaticMethod() throws Exception {
        Path file = srcDir.resolve("D.java");
        Files.writeString(file, "class D {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addMethod.handle(null, methodReq(
                entry.name(), "D", "main", "void", "String[] args", "public static", "System.out.println(\"main\");"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("public static void main(String[] args)"));
        mgr.remove(entry.name());
    }

    @Test
    void addMethodWithImports() throws Exception {
        Path file = srcDir.resolve("E.java");
        Files.writeString(file, "class E {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addMethod.handle(null, methodReqWithImports(
                entry.name(), "E", "process", "List<String>", "int count", "public", 
                "return new ArrayList<>();", List.of("java.util.List", "java.util.ArrayList")));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("import java.util.ArrayList;"));
        assertTrue(written.contains("import java.util.List;"));
        assertTrue(written.contains("public List<String> process(int count)"));
        mgr.remove(entry.name());
    }

    @Test
    void addMethodWithoutBody() throws Exception {
        Path file = srcDir.resolve("F.java");
        Files.writeString(file, "class F {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addMethod.handle(null, methodReq(
                entry.name(), "F", "abstractMethod", "void", null, "public", null));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("public void abstractMethod() {"));
        assertTrue(written.contains("}"));
        mgr.remove(entry.name());
    }

    @Test
    void addMethodWithPrivateModifier() throws Exception {
        Path file = srcDir.resolve("G.java");
        Files.writeString(file, "class G {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addMethod.handle(null, methodReq(
                entry.name(), "G", "privateMethod", "void", null, "private", "System.out.println(\"private\");"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("private void privateMethod()"));
        mgr.remove(entry.name());
    }

    private static CallToolRequest methodReq(String name, String className, String methodName,
            String returnType, String parameters, String modifiers, String body) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", className);
        args.put("methodName", methodName);
        args.put("returnType", returnType);
        if (parameters != null) args.put("parameters", parameters);
        if (modifiers != null) args.put("modifiers", modifiers);
        if (body != null) args.put("body", body);
        return new CallToolRequest("add_method", args);
    }

    private static CallToolRequest methodReqWithImports(String name, String className, String methodName,
            String returnType, String parameters, String modifiers, String body, List<String> imports) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", className);
        args.put("methodName", methodName);
        args.put("returnType", returnType);
        if (parameters != null) args.put("parameters", parameters);
        if (modifiers != null) args.put("modifiers", modifiers);
        if (body != null) args.put("body", body);
        if (imports != null && !imports.isEmpty()) args.put("imports", imports);
        return new CallToolRequest("add_method", args);
    }
}

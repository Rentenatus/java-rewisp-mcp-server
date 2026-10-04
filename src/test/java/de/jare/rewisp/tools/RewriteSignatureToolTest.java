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
 * Tests for {@link RewriteSignatureTool} with both Spoon and OpenRewrite implementations.
 * 
 * @author Janusch Rentenatus
 */
class RewriteSignatureToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private RewriteSignatureTool tool;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        // Use legacy constructor for Spoon-based tests
        tool = new RewriteSignatureTool(mgr, editMgr, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    @Test
    void changeReturnTypeOnly() throws Exception {
        Path file = srcDir.resolve("A.java");
        Files.writeString(file, "class A {\n  String getValue() { return \"test\"; }\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, req(entry.name(), "A", "getValue",
                "int", null, "signature_only"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("int getValue()"));
        assertFalse(written.contains("String getValue()"));
        mgr.remove(entry.name());
    }

    @Test
    void changeParametersOnly() throws Exception {
        Path file = srcDir.resolve("B.java");
        Files.writeString(file, "class B {\n  void process(int x) {}\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, req(entry.name(), "B", "process",
                null, "String name, int count", "signature_only"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("void process(String name, int count)"));
        assertFalse(written.contains("void process(int x)"));
        mgr.remove(entry.name());
    }

    @Test
    void changeReturnTypeAndParameters() throws Exception {
        Path file = srcDir.resolve("C.java");
        Files.writeString(file, "class C {\n  String get(int id) { return \"x\"; }\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, req(entry.name(), "C", "get",
                "List<String>", "String key, int index", "signature_only"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("List<String> get(String key, int index)"));
        mgr.remove(entry.name());
    }

    @Test
    void changeToNoParameters() throws Exception {
        Path file = srcDir.resolve("D.java");
        Files.writeString(file, "class D {\n  void process(String input) {}\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, req(entry.name(), "D", "process",
                null, "", "signature_only"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("void process()"));
        mgr.remove(entry.name());
    }

    @Test
    void signatureAndCallersMode() throws Exception {
        Path file = srcDir.resolve("E.java");
        Files.writeString(file, "class E {\n  void handle(int x) {}\n  void caller() { handle(42); }\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, req(entry.name(), "E", "handle",
                null, "String s", "signature_and_callers"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("void handle(String s)"));
        // Should have TODO comment for caller
        assertTrue(written.contains("// TODO: signature of handle changed"));
        mgr.remove(entry.name());
    }

    @Test
    void signatureOnlyModeNoCallerUpdates() throws Exception {
        Path file = srcDir.resolve("F.java");
        Files.writeString(file, "class F {\n  void handle(int x) {}\n  void caller() { handle(42); }\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, req(entry.name(), "F", "handle",
                null, "String s", "signature_only"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("void handle(String s)"));
        // Should NOT have TODO comment for caller in signature_only mode
        assertFalse(written.contains("// TODO: signature of handle changed"));
        mgr.remove(entry.name());
    }

    @Test
    void methodNotFound() throws Exception {
        Path file = srcDir.resolve("G.java");
        Files.writeString(file, "class G {\n  void existing() {}\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, req(entry.name(), "G", "nonexistent",
                "void", null, "signature_only"));

        assertTrue(result.isError());
        assertTrue(result.content().toString().contains("isDomainError"));
        assertTrue(result.content().toString().contains("not found"));
        mgr.remove(entry.name());
    }

    @Test
    void multipleMethodsWithSameName() throws Exception {
        Path file = srcDir.resolve("H.java");
        Files.writeString(file, "class H {\n  void process(int x) {}\n  void process(String s) {}\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, req(entry.name(), "H", "process",
                null, "double d", "signature_only"));

        assertTrue(result.isError());
        assertTrue(result.content().toString().contains("isDomainError"));
        assertTrue(result.content().toString().contains("Multiple methods"));
        mgr.remove(entry.name());
    }

    @Test
    void noChangesWhenSameSignature() throws Exception {
        Path file = srcDir.resolve("I.java");
        Files.writeString(file, "class I {\n  void test() {}\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, req(entry.name(), "I", "test",
                "void", "", "signature_only"));

        assertFalse(result.isError());
        assertTrue(result.content().toString().contains("Signature unchanged"));
        mgr.remove(entry.name());
    }

    private static CallToolRequest req(String name, String className, String methodName,
            String newReturnType, String newParameters, String mode) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", className);
        args.put("methodName", methodName);
        if (newReturnType != null) args.put("newReturnType", newReturnType);
        if (newParameters != null) args.put("newParameters", newParameters);
        args.put("mode", mode);
        return new CallToolRequest("rewrite_signature", args);
    }
}

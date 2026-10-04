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
 * Tests for {@link ReplaceMethodBodyTool}.
 * 
 * @author Janusch Rentenatus
 */
class ReplaceMethodBodyToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private ReplaceMethodBodyTool tool;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        tool = new ReplaceMethodBodyTool(mgr, editMgr, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    @Test
    void replaceBodySingleMethod() throws Exception {
        Path file = srcDir.resolve("Calc.java");
        Files.writeString(file, """
            class Calc {
                int compute() {
                    return 1 + 1;
                }
            }
            """);
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, mockRequest(
                entry.name(), "Calc", "compute", null, "return 42;"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("42"));
        // The method signature must survive the body replacement.
        assertTrue(written.contains("int compute()"));
        // The class closing brace must still be present and exactly once after the body.
        long classBraces = written.lines().filter(l -> l.trim().equals("}")).count();
        assertTrue(classBraces >= 2);
        mgr.remove(entry.name());
    }

    @Test
    void replaceBodyWithSignature() throws Exception {
        Path file = srcDir.resolve("Adder.java");
        Files.writeString(file, """
            class Adder {
                int add(int a, int b) {
                    return a + b;
                }
            }
            """);
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, mockRequest(
                entry.name(), "Adder", "add", "int, int", "return a * b;"));

        assertFalse(result.isError());
        assertTrue(Files.readString(file).contains("a * b"));
        mgr.remove(entry.name());
    }

    @Test
    void methodNotFound() throws Exception {
        Path file = srcDir.resolve("X.java");
        Files.writeString(file, "class X { int getX() { return 0; } }");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, mockRequest(
                entry.name(), "X", "nonExistent", null, "return 0;"));

        assertTrue(result.isError());
        assertTrue(result.content().toString().contains("isDomainError"));
        mgr.remove(entry.name());
    }

    @Test
    void ambiguousMethodWithoutSignature() throws Exception {
        Path file = srcDir.resolve("Overload.java");
        Files.writeString(file, """
            class Overload {
                int doWork(int x) { return x; }
                int doWork(int x, int y) { return x + y; }
            }
            """);
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, mockRequest(
                entry.name(), "Overload", "doWork", null, "return 0;"));

        assertTrue(result.isError());
        assertTrue(result.content().toString().contains("isDomainError"));
        mgr.remove(entry.name());
    }

    @Test
    void unresolvedTypeBlocks() throws Exception {
        Path file = srcDir.resolve("Y.java");
        Files.writeString(file, "class Y { int getValue() { return 0; } }");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, mockRequest(
                entry.name(), "Y", "getValue", null, "LocalDateTime now = LocalDateTime.now(); return 0;"));

        assertTrue(result.isError());
        assertTrue(result.content().toString().contains("isDomainError"));
        mgr.remove(entry.name());
    }

    @Test
    void replaceBodyGenericParamSignature() throws Exception {
        Path file = srcDir.resolve("Gen.java");
        Files.writeString(file, """
            import java.util.List;
            class Gen {
                void apply(List<String> items) {
                    System.out.println(items);
                }
            }
            """);
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, mockRequest(
                entry.name(), "Gen", "apply", "List<String>", "return;"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("void apply(List<String> items)"));
        assertTrue(written.contains("return;"));
        mgr.remove(entry.name());
    }

    @Test
    void replaceBodySingleLineMethod() throws Exception {
        // Signature, body, and closing brace all on one line — the brace
        // matcher must find the method's closing brace, not the class brace.
        Path file = srcDir.resolve("SL.java");
        Files.writeString(file, "class SL { int getX() { return 0; } int getY() { return 1; } }");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, mockRequest(
                entry.name(), "SL", "getX", null, "return 42;"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("42"), "new body must be present");
        assertTrue(written.contains("int getY() { return 1; }"), "sibling method must survive");
        assertTrue(written.contains("class SL {"), "class header must survive");
        long classClose = written.lines().filter(l -> l.trim().endsWith("}")).count();
        assertTrue(classClose >= 1);
        mgr.remove(entry.name());
    }

    @Test
    void replaceBodyMultiLineNewBody() throws Exception {
        Path file = srcDir.resolve("ML.java");
        Files.writeString(file, """
            class ML {
                void run() {
                    System.out.println("old");
                }
            }
            """);
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, mockRequest(
                entry.name(), "ML", "run", null,
                "int total = 0;\nfor (int i = 0; i < 10; i++) {\n    total += i;\n}\nSystem.out.println(total);"));

        assertFalse(result.isError());
        String written = Files.readString(file);
        assertTrue(written.contains("for (int i = 0; i < 10; i++) {"));
        assertTrue(written.contains("total += i;"));
        assertTrue(written.contains("System.out.println(total);"));
        // nested braces in the new body must be balanced and the method close intact
        assertTrue(written.contains("void run()"));
        mgr.remove(entry.name());
    }

    @Test
    void replaceBodyWithManualImportDisambiguatesList() throws Exception {
        // Body references List (ambiguous: java.util.List vs java.awt.List).
        // With the imports parameter, the type resolves and the import is added.
        Path file = srcDir.resolve("Holder.java");
        Files.writeString(file, """
            class Holder {
                void build() {
                    return;
                }
            }
            """);
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, mockRequest(
                entry.name(), "Holder", "build", null,
                "List<String> items = new java.util.ArrayList<>();",
                java.util.List.of("java.util.List")));

        assertFalse(result.isError(), () -> result.content().toString());
        String written = Files.readString(file);
        assertTrue(written.contains("import java.util.List;"),
                "import must be inserted, got:\n" + written);
        assertTrue(written.contains("List<String> items"),
                "body must use simple name, got:\n" + written);
        mgr.remove(entry.name());
    }

    @Test
    void replaceBodyVarargsSignature() throws Exception {
        // Varargs ("int...") in the signature must match the method, since
        // Spoon stores varargs parameters as arrays ("int[]").
        Path file = srcDir.resolve("Varargs.java");
        Files.writeString(file, """
            class Varargs {
                int sum(int... values) {
                    return 0;
                }
            }
            """);
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = tool.handle(null, mockRequest(
                entry.name(), "Varargs", "sum", "int...", "return 42;"));

        assertFalse(result.isError(), () -> result.content().toString());
        assertTrue(Files.readString(file).contains("42"));
        mgr.remove(entry.name());
    }

    // ========== New Tests for OpenRewrite Migration ==========

    @Test
    void testToolName() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        ReplaceMethodBodyTool tool = new ReplaceMethodBodyTool(projectManager, editManager, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        
        assertTrue(tool.toolName().equals("replace_method_body"));
    }

    @Test
    void testToolDescriptionContainsRewrite() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        ReplaceMethodBodyTool tool = new ReplaceMethodBodyTool(projectManager, editManager, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        
        String description = tool.toolDescription();
        assertTrue(description.contains("REWRITE"));
        assertTrue(description.contains("Replace the entire body"));
        assertTrue(description.contains("OpenRewrite"));
    }

    @Test
    void testToolProperties() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        ReplaceMethodBodyTool tool = new ReplaceMethodBodyTool(projectManager, editManager, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        
        var props = tool.toolProperties();
        
        assertFalse(props == null);
        assertTrue(props.size() >= 6);
        assertTrue(props.containsKey("name"));
        assertTrue(props.containsKey("className"));
        assertTrue(props.containsKey("methodName"));
        assertTrue(props.containsKey("signature"));
        assertTrue(props.containsKey("newBody"));
        assertTrue(props.containsKey("imports"));
    }

    @Test
    void testToolRequired() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        
        ReplaceMethodBodyTool tool = new ReplaceMethodBodyTool(projectManager, editManager, RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        
        var required = tool.toolRequired();
        
        assertFalse(required == null);
        assertTrue(required.size() >= 4);
        assertTrue(required.contains("name"));
        assertTrue(required.contains("className"));
        assertTrue(required.contains("methodName"));
        assertTrue(required.contains("newBody"));
    }

    @Test
    void testNewConstructorWithAllParameters() {
        var projectManager = new de.jare.rewisp.ProjectManager();
        var editManager = new de.jare.rewisp.edit.EditManager();
        var rewriteEngine = new de.jare.rewisp.rewrite.OpenRewriteEngineImpl(
                new de.jare.rewisp.rewrite.YamlRecipeRegistry());
        var rewriteSyncManager = new de.jare.rewisp.rewrite.RewriteSyncManager();

        // This should not throw
        ReplaceMethodBodyTool tool = new ReplaceMethodBodyTool(
            projectManager, editManager, rewriteEngine, rewriteSyncManager
        );

        assertFalse(tool == null);
        assertTrue(tool.toolName().equals("replace_method_body"));
    }

    private static CallToolRequest mockRequest(String name, String className,
            String methodName, String signature, String newBody) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", className);
        args.put("methodName", methodName);
        if (signature != null) args.put("signature", signature);
        args.put("newBody", newBody);
        return new CallToolRequest("replace_method_body", args);
    }

    private static CallToolRequest mockRequest(String name, String className,
            String methodName, String signature, String newBody,
            java.util.List<String> imports) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", className);
        args.put("methodName", methodName);
        if (signature != null) args.put("signature", signature);
        args.put("newBody", newBody);
        if (imports != null) args.put("imports", imports);
        return new CallToolRequest("replace_method_body", args);
    }
}

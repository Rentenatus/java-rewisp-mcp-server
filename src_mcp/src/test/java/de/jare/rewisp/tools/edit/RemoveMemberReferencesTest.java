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
 * AP-3.8: Safe Mode von remove_member nutzt die gemeinsame Referenzsuche: Initializer/Felder zaehlen,
 * andere Ueberladungen und Selbstreferenzen blockieren nicht.
 *
 * @author Janusch Rentenatus
 */
class RemoveMemberReferencesTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private RemoveMemberTool tool;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        tool = new RemoveMemberTool(mgr, new EditManager(tempDir.resolve("backups")),
                RewriteTestSupport.newEngine());
        srcDir = Files.createDirectories(tempDir.resolve("src"));
    }

    private CallToolResult remove(ProjectEntry entry, String cls, String member, String scope, String signature) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", cls);
        args.put("memberName", member);
        args.put("scope", scope);
        args.put("mode", "safe");
        if (signature != null) args.put("signature", signature);
        return tool.handle(null, new CallToolRequest("remove_member", args));
    }

    @Test
    void referenceInFieldInitializerBlocksRemoval() throws Exception {
        Files.writeString(srcDir.resolve("A.java"),
                "class A {\n  static int base() { return 1; }\n  int v = base();\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = remove(entry, "A", "base", "method", null);

        assertTrue(result.isError(), "field initializer call must count: " + result.content());
        assertTrue(result.content().toString().contains("A.java:3"), result.content().toString());
        mgr.remove(entry.name());
    }

    @Test
    void callsToAnotherOverloadDoNotBlockRemoval() throws Exception {
        Files.writeString(srcDir.resolve("B.java"),
                "class B {\n  void run(String s) {}\n  void run(int i) {}\n  void use() { run(1); }\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = remove(entry, "B", "run", "method", "String");

        assertFalse(result.isError(), result.content().toString());
        mgr.remove(entry.name());
    }

    @Test
    void recursionInsideTheRemovedMethodDoesNotBlockRemoval() throws Exception {
        Files.writeString(srcDir.resolve("C.java"),
                "class C {\n  int fact(int n) { return n <= 1 ? 1 : n * fact(n - 1); }\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = remove(entry, "C", "fact", "method", null);

        assertFalse(result.isError(), result.content().toString());
        mgr.remove(entry.name());
    }
}

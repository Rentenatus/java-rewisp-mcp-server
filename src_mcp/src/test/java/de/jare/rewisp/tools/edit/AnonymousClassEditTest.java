/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * AP-4.6: Edits gelten nur fuer die direkt umschliessende Klasse; eine anonyme Klasse im Rumpf
 * einer Methode wird nicht mit der aeusseren Klasse verwechselt.
 *
 * @author Janusch Rentenatus
 */
class AnonymousClassEditTest {

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

    private static final String OUTER =
            "public class Outer {\n"
          + "    void run() {}\n"
          + "    Runnable make() {\n"
          + "        return new Runnable() {\n"
          + "            public void run() {}\n"
          + "        };\n"
          + "    }\n"
          + "}\n";

    @Test
    void renameMethod_doesNotRenameSameNamedMethodInAnonymousClass() throws Exception {
        Path f = srcDir.resolve("Outer.java");
        Files.writeString(f, OUTER);
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        RenameSymbolTool rename = new RenameSymbolTool(mgr, editMgr, RewriteTestSupport.newEngine());

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "Outer");
        args.put("oldName", "run");
        args.put("newName", "execute");
        args.put("scope", "method");
        CallToolResult r = rename.handle(null, new CallToolRequest("rename_symbol", args));

        assertFalse(r.isError(), r.content().toString());
        String src = Files.readString(f);
        assertTrue(src.contains("void execute() {}"), src);
        assertTrue(src.contains("public void run() {}"), "anonymous override must stay: " + src);
        mgr.remove(entry.name());
    }

    @Test
    void addMethod_addsOnlyToOuterClass() throws Exception {
        Path f = srcDir.resolve("Outer.java");
        Files.writeString(f, OUTER);
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        AddMethodTool add = new AddMethodTool(mgr, editMgr, RewriteTestSupport.newEngine());

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "Outer");
        args.put("methodName", "helper");
        args.put("returnType", "int");
        args.put("parameters", "");
        args.put("modifiers", "public");
        args.put("body", "return 1;");
        CallToolResult r = add.handle(null, new CallToolRequest("add_method", args));

        assertFalse(r.isError(), r.content().toString());
        String src = Files.readString(f);
        assertEquals(1, src.split("int helper\\(\\)", -1).length - 1, "exactly one helper(): " + src);
        mgr.remove(entry.name());
    }
}

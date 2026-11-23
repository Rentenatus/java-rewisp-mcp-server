/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * AP-1.7 / ED:E-03: Mehrdatei-Edits validieren vorab alle Fingerprints; ein
 * extern geaenderter Aufrufer darf nicht zu einem halb geschriebenen Rename fuehren.
 *
 * @author Janusch Rentenatus
 */
class MultiFileWriteTest {

    @TempDir
    Path tempDir;

    @Test
    void renameWithExternallyChangedCaller_writesNothing() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Path a = src.resolve("A.java");
        Path b = src.resolve("B.java");
        String aOriginal = "class A {\n  void foo() {}\n}\n";
        Files.writeString(a, aOriginal);
        Files.writeString(b, "class B {\n  void call(A a) { a.foo(); }\n}\n");

        ProjectManager mgr = new ProjectManager();
        EditManager editMgr = new EditManager(tempDir.resolve("backups"));
        mgr.setEditManager(editMgr);
        ProjectEntry entry = mgr.load(src.toString(), null, null, true, true);
        RenameSymbolTool tool = new RenameSymbolTool(mgr, editMgr,
                RewriteTestSupport.newEngine());

        // Externe Aenderung nach dem Laden (andere Groesse -> Fingerprint-Konflikt)
        Files.writeString(b, "class B {\n  void call(A a) { a.foo(); /* extern geaendert */ }\n}\n");

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "A");
        args.put("oldName", "foo");
        args.put("newName", "bar");
        args.put("scope", "method");
        args.put("updateCallers", true);
        try {
            CallToolResult result = tool.handle(null, new CallToolRequest("rename_symbol", args));
            assertTrue(result.isError(), "Rename muss am Fingerprint-Konflikt scheitern");
        } catch (IllegalArgumentException expected) {
            // Fingerprint-Konflikt wird als IllegalArgumentException gemeldet
        }

        assertTrue(Files.readString(a).equals(aOriginal),
                "A.java darf nicht halb umbenannt auf der Platte liegen: " + Files.readString(a));
        mgr.remove(entry.name());
    }
}

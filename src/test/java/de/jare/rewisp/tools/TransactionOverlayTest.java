/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressionstest für den Transaktions-Overlay: In einer aktiven Transaktion
 * liegen gepufferte Änderungen nur im Speicher. Vor dem Fix las jeder
 * folgende Edit der Transaktion seinen Preview-Zustand von der Platte und
 * überschrieb damit die gepufferten Vorgänger-Änderungen (letzter
 * Schreiber gewinnt) — add_field + replace_method_body auf dieselbe
 * Datei ließ am Ende nur den Body-Ersatz übrig.
 *
 * <p>Gefunden am 2026-10-03 im Live-Dogfooding der Spielfixtures.</p>
 */
class TransactionOverlayTest {

    @TempDir
    Path tempDir;

    @Test
    void chainedEditsInTransactionBothSurvive() throws Exception {
        ProjectManager mgr = new ProjectManager();
        mgr.setEditManager(new EditManager());
        EditManager editMgr = mgr.editManager();
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("P.java"),
                "class P {\n    void m() {\n        int x = 1;\n    }\n}\n");
        ProjectEntry entry = mgr.load(src.toString(), null, null, true, true);

        AddFieldTool addField = new AddFieldTool(mgr, editMgr,
                RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        ReplaceMethodBodyTool replaceBody = new ReplaceMethodBodyTool(mgr, editMgr,
                RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());

        editMgr.beginTransaction();
        try {
            CallToolResult r1 = addField.handle(null, new CallToolRequest("add_field", Map.of(
                    "name", entry.name(), "className", "P",
                    "fieldName", "counter", "type", "int", "modifiers", "private")));
            assertFalse(r1.isError(), "add_field muss in der Transaktion funktionieren: " + r1.content());

            CallToolResult r2 = replaceBody.handle(null, new CallToolRequest("replace_method_body", Map.of(
                    "name", entry.name(), "className", "P",
                    "methodName", "m", "newBody", "counter = 42;")));
            assertFalse(r2.isError(), "replace_method_body muss den gepufferten Vorgänger sehen: " + r2.content());

            assertTrue(editMgr.commitTransaction(), "Commit muss gelingen");
        } finally {
            if (editMgr.isInTransaction()) editMgr.rollbackTransaction();
        }

        String onDisk = Files.readString(src.resolve("P.java"));
        assertTrue(onDisk.contains("counter"), "Das gepufferte Feld muss den Commit überleben:\n" + onDisk);
        assertTrue(onDisk.contains("counter = 42"), "Der Body-Ersatz muss vorhanden sein:\n" + onDisk);
        assertFalse(onDisk.contains("int x = 1"), "Der alte Body muss ersetzt sein:\n" + onDisk);
    }

    @Test
    void editOutsideTransactionUnaffected() throws Exception {
        ProjectManager mgr = new ProjectManager();
        EditManager editMgr = new EditManager();
        mgr.setEditManager(editMgr);
        Path src = tempDir.resolve("src2");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Q.java"),
                "class Q {\n    void m() {\n        int x = 1;\n    }\n}\n");
        ProjectEntry entry = mgr.load(src.toString(), null, null, true, true);

        AddFieldTool addField = new AddFieldTool(mgr, editMgr,
                RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        CallToolResult r = addField.handle(null, new CallToolRequest("add_field", Map.of(
                "name", entry.name(), "className", "Q",
                "fieldName", "size", "type", "int", "modifiers", "private")));
        assertFalse(r.isError());
        assertTrue(Files.readString(src.resolve("Q.java")).contains("size"),
                "Ohne Transaktion schreibt add_field direkt");
    }
}

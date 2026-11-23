/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.txn;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import de.jare.rewisp.tools.edit.AddFieldTool;
import de.jare.rewisp.tools.edit.MoveClassTool;
import de.jare.rewisp.tools.read.InspectClassTool;

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

/**
 * AP-1.2 / ED:E-02: Nach einem Rollback darf der Snapshot keine verworfenen
 * Transaktionsinhalte mehr enthalten, sonst werden sie vom naechsten Edit
 * wieder auf die Platte geschrieben.
 *
 * @author Janusch Rentenatus
 */
class TransactionRollbackSnapshotTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private AddFieldTool addField;
    private TransactionTools.Begin begin;
    private TransactionTools.Rollback rollback;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        mgr.setEditManager(editMgr);
        addField = new AddFieldTool(mgr, editMgr, RewriteTestSupport.newEngine());
        begin = new TransactionTools.Begin(mgr, editMgr);
        rollback = new TransactionTools.Rollback(mgr, editMgr);
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    @Test
    void editAfterRollback_doesNotResurrectDiscardedChanges() throws Exception {
        Path file = srcDir.resolve("R.java");
        Files.writeString(file, "class R {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        begin.handle(null, new CallToolRequest("begin_transaction", Map.of()));
        assertFalse(addField.handle(null, field(entry.name(), "discarded1")).isError());
        // zweiter Aufruf erzwingt den Snapshot-Refresh mit Overlay der gepufferten Aenderung
        assertFalse(addField.handle(null, field(entry.name(), "discarded2")).isError());
        assertFalse(rollback.handle(null, new CallToolRequest("rollback_transaction", Map.of())).isError());

        assertFalse(addField.handle(null, field(entry.name(), "kept")).isError());

        String written = Files.readString(file);
        assertTrue(written.contains("int kept;"), written);
        assertFalse(written.contains("discarded1"), "verworfene Aenderung wieder aufgetaucht: " + written);
        assertFalse(written.contains("discarded2"), written);
        mgr.remove(entry.name());
    }

    /** AP-1.11 / ED:E-12: move_class in der Transaktion, danach Edit an der verschobenen Klasse, Commit. */
    @Test
    void moveClassThenEditInTransaction_commitsConsistently() throws Exception {
        Path oldDir = srcDir.resolve("com/oldpkg");
        Files.createDirectories(oldDir);
        Files.writeString(oldDir.resolve("Old.java"), "package com.oldpkg;\n\nclass Old {\n}\n");
        Files.writeString(oldDir.resolve("Stay.java"), "package com.oldpkg;\n\nclass Stay {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        MoveClassTool move = new MoveClassTool(mgr, editMgr,
                RewriteTestSupport.newEngine());
        TransactionTools.Commit commit = new TransactionTools.Commit(mgr, editMgr);

        begin.handle(null, new CallToolRequest("begin_transaction", Map.of()));
        Map<String, Object> mv = new HashMap<>();
        mv.put("name", entry.name());
        mv.put("className", "com.oldpkg.Old");
        mv.put("newPackage", "com.newpkg");
        assertFalse(move.handle(null, new CallToolRequest("move_class", mv)).isError());
        Map<String, Object> fld = new HashMap<>();
        fld.put("name", entry.name());
        // Spoon liest nur von Rewrite: die in der Transaktion verschobene Klasse ist sofort editierbar
        fld.put("className", "com.newpkg.Old");
        fld.put("fieldName", "added");
        fld.put("type", "int");
        var fieldResult = addField.handle(null, new CallToolRequest("add_field", fld));
        assertFalse(fieldResult.isError(), fieldResult.content().toString());
        var commitResult = commit.handle(null, new CallToolRequest("commit_transaction", Map.of()));
        assertFalse(commitResult.isError(), commitResult.content().toString());

        assertFalse(Files.exists(oldDir.resolve("Old.java")), "alte Datei muss weg sein");
        String moved = Files.readString(srcDir.resolve("com/newpkg/Old.java"));
        assertTrue(moved.contains("package com.newpkg;"), moved);
        assertTrue(moved.contains("int added;"), moved);
        mgr.remove(entry.name());
    }

    /**
     * Architektur-Invariante: Spoon liest nur von Rewrite. Innerhalb einer Transaktion sehen
     * Lesewerkzeuge und Edit-Vorabanalyse den gepufferten Zustand (BUGS.md N32), nach dem
     * Rollback wieder den Plattenzustand.
     */
    @Test
    void spoonSeesTransactionOverlay_andRollbackRestoresIt() throws Exception {
        Path oldDir = srcDir.resolve("com/oldpkg");
        Files.createDirectories(oldDir);
        Files.writeString(oldDir.resolve("Old.java"), "package com.oldpkg;\n\nclass Old {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        MoveClassTool move = new MoveClassTool(mgr, editMgr,
                RewriteTestSupport.newEngine());
        InspectClassTool inspect = new InspectClassTool(mgr);

        begin.handle(null, new CallToolRequest("begin_transaction", Map.of()));
        Map<String, Object> mv = new HashMap<>();
        mv.put("name", entry.name());
        mv.put("className", "com.oldpkg.Old");
        mv.put("newPackage", "com.newpkg");
        assertFalse(move.handle(null, new CallToolRequest("move_class", mv)).isError());

        // Lesen in der Transaktion: neue Klasse sichtbar, alte weg - obwohl die Platte unveraendert ist
        assertTrue(Files.exists(oldDir.resolve("Old.java")));
        assertFalse(inspect.handle(null, inspectReq(entry.name(), "com.newpkg.Old")).isError(),
                "verschobene Klasse muss in der Transaktion sichtbar sein");
        assertTrue(inspect.handle(null, inspectReq(entry.name(), "com.oldpkg.Old")).isError(),
                "alter Ort darf in der Transaktion nicht mehr sichtbar sein");

        // Edit an der verschobenen Klasse (Vorabanalyse braucht das Spoon-Modell)
        Map<String, Object> fld = new HashMap<>();
        fld.put("name", entry.name());
        fld.put("className", "com.newpkg.Old");
        fld.put("fieldName", "moved");
        fld.put("type", "int");
        var added = addField.handle(null, new CallToolRequest("add_field", fld));
        assertFalse(added.isError(), added.content().toString());

        assertFalse(rollback.handle(null, new CallToolRequest("rollback_transaction", Map.of())).isError());
        assertFalse(inspect.handle(null, inspectReq(entry.name(), "com.oldpkg.Old")).isError(),
                "nach Rollback ist die Klasse wieder am alten Ort");
        assertTrue(inspect.handle(null, inspectReq(entry.name(), "com.newpkg.Old")).isError());
        mgr.remove(entry.name());
    }

    private static CallToolRequest inspectReq(String name, String className) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", className);
        return new CallToolRequest("inspect_class", args);
    }

    /** Review M1: Rollback nach move_class darf die Quelldatei nicht aus dem Snapshot verlieren. */
    @Test
    void rollbackAfterMoveClass_restoresMovedFileInSnapshot() throws Exception {
        Path oldDir = srcDir.resolve("com/oldpkg");
        Files.createDirectories(oldDir);
        Path oldFile = oldDir.resolve("Old.java");
        Files.writeString(oldFile, "package com.oldpkg;\n\nclass Old {\n}\n");
        Files.writeString(oldDir.resolve("Stay.java"), "package com.oldpkg;\n\nclass Stay {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        MoveClassTool move = new MoveClassTool(mgr, editMgr,
                RewriteTestSupport.newEngine());

        begin.handle(null, new CallToolRequest("begin_transaction", Map.of()));
        Map<String, Object> mv = new HashMap<>();
        mv.put("name", entry.name());
        mv.put("className", "com.oldpkg.Old");
        mv.put("newPackage", "com.newpkg");
        assertFalse(move.handle(null, new CallToolRequest("move_class", mv)).isError());
        Map<String, Object> fld = new HashMap<>();
        fld.put("name", entry.name());
        fld.put("className", "com.oldpkg.Stay");
        fld.put("fieldName", "x");
        fld.put("type", "int");
        // loest den Snapshot-Refresh aus (entfernt die vorgemerkt geloeschte Old.java aus dem Snapshot)
        assertFalse(addField.handle(null, new CallToolRequest("add_field", fld)).isError());
        assertFalse(rollback.handle(null, new CallToolRequest("rollback_transaction", Map.of())).isError());

        assertTrue(Files.exists(oldFile));
        var snapshot = mgr.getProjectState(entry.name()).snapshot();
        boolean present = snapshot.files().keySet().stream()
                .anyMatch(p -> p.toString().replace('\\', '/').endsWith("com/oldpkg/Old.java"));
        assertTrue(present, "Old.java muss nach dem Rollback wieder im Snapshot sein: " + snapshot.files().keySet());
        mgr.remove(entry.name());
    }

    /** AP-1.3 / ED:E-11: begin bei aktiver Transaktion darf nichts stillschweigend verwerfen. */
    @Test
    void beginWhileActive_isRejectedAndKeepsPendingChanges() throws Exception {
        Path file = srcDir.resolve("R.java");
        Files.writeString(file, "class R {\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        begin.handle(null, new CallToolRequest("begin_transaction", Map.of()));
        addField.handle(null, field(entry.name(), "pending"));
        int before = editMgr.getPendingChangeCount();

        assertTrue(begin.handle(null, new CallToolRequest("begin_transaction", Map.of())).isError());
        assertTrue(editMgr.isInTransaction());
        assertTrue(editMgr.getPendingChangeCount() == before && before > 0);
        mgr.remove(entry.name());
    }

    private static CallToolRequest field(String name, String fieldName) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", "R");
        args.put("fieldName", fieldName);
        args.put("type", "int");
        return new CallToolRequest("add_field", args);
    }
}

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
 * Regressionstest für Cross-Projekt-Dirty-Kontamination: {@code commit_transaction}
 * aktualisiert dirtyFiles/editedFiles nur auf dem Projekt, dem die committeten
 * Dateien per Pfad-Präfix tatsächlich gehören. Vor dem Fix bekam bei mehreren
 * geladenen Projekten jedes Projekt die Dateien aller anderen unterschoben —
 * dirty-Warnungen logen und check_project_dirty verlangte unnötige Reloads.
 *
 * <p>Gefunden am 2026-10-03 im Live-Dogfooding: ttt/mm listeten Battleship.java
 * als dirty, obwohl sie in einem anderen Projekt liegen.</p>
 */
class CrossProjectCommitDirtyTest {

    @TempDir
    Path tempDir;

    @Test
    void commitDoesNotContaminateOtherProjects() throws Exception {
        ProjectManager mgr = new ProjectManager();
        EditManager editMgr = new EditManager();
        mgr.setEditManager(editMgr);

        Path srcA = tempDir.resolve("projA");
        Files.createDirectories(srcA);
        Files.writeString(srcA.resolve("A.java"),
                "class A {\n    void m() {\n        int x = 1;\n    }\n}\n");
        ProjectEntry entryA = mgr.load(srcA.toString(), null, null, true, true);

        Path srcB = tempDir.resolve("projB");
        Files.createDirectories(srcB);
        Files.writeString(srcB.resolve("B.java"),
                "class B {\n    void n() {\n        int y = 2;\n    }\n}\n");
        ProjectEntry entryB = mgr.load(srcB.toString(), null, null, true, true);

        AddFieldTool addField = new AddFieldTool(mgr, editMgr,
                RewriteToolTestSupport.newEngine(), RewriteToolTestSupport.newSyncManager());
        TransactionTools.Commit commit = new TransactionTools.Commit(mgr, editMgr);

        editMgr.beginTransaction();
        try {
            CallToolResult r = addField.handle(null, new CallToolRequest("add_field", Map.of(
                    "name", entryA.name(), "className", "A",
                    "fieldName", "counter", "type", "int", "modifiers", "private")));
            assertFalse(r.isError(), "add_field in der Transaktion muss gelingen: " + r.content());

            CallToolResult c = commit.handle(null, new CallToolRequest("commit_transaction", Map.of()));
            assertFalse(c.isError(), "Commit muss gelingen: " + c.content());
        } finally {
            if (editMgr.isInTransaction()) editMgr.rollbackTransaction();
        }

        ProjectEntry afterA = mgr.list().stream()
                .filter(e -> e.projectDir().equals(entryA.projectDir())).findFirst().orElseThrow();
        ProjectEntry afterB = mgr.list().stream()
                .filter(e -> e.projectDir().equals(entryB.projectDir())).findFirst().orElseThrow();

        assertTrue(afterA.dirtyFiles().stream().anyMatch(p -> p.getFileName().toString().equals("A.java")),
                "Projekt A muss die committete Datei als dirty führen, got: " + afterA.dirtyFiles()
                        + ", committed: " + editMgr.getLastCommittedFiles()
                        + ", rootA: " + afterA.originalProjectDir() + " / " + afterA.projectDir());
        assertFalse(afterB.dirtyFiles().stream().anyMatch(p -> p.getFileName().toString().equals("A.java")),
                "Projekt B darf die fremde Datei NICHT als dirty führen, got: " + afterB.dirtyFiles());
        assertTrue(afterB.dirtyFiles().isEmpty(), "Projekt B muss komplett sauber sein, got: " + afterB.dirtyFiles());
        assertFalse(afterB.editedFiles().stream().anyMatch(p -> p.getFileName().toString().equals("A.java")),
                "Projekt B darf die fremde Datei NICHT als edited führen, got: " + afterB.editedFiles());
    }
}

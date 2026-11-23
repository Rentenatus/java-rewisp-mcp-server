/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.txn;

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
import de.jare.rewisp.tools.edit.AddPackageTool;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * AP-1.13 / ED:E-49: Verzeichnisse entstehen erst beim Commit; ein Rollback hinterlaesst nichts.
 *
 * @author Janusch Rentenatus
 */
class TransactionDirectoryTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private AddPackageTool addPackage;
    private TransactionTools.Begin begin;
    private TransactionTools.Commit commit;
    private TransactionTools.Rollback rollback;
    private Path srcDir;
    private ProjectEntry entry;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        mgr.setEditManager(editMgr);
        addPackage = new AddPackageTool(mgr, editMgr);
        begin = new TransactionTools.Begin(mgr, editMgr);
        commit = new TransactionTools.Commit(mgr, editMgr);
        rollback = new TransactionTools.Rollback(mgr, editMgr);
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("a"));
        Files.writeString(srcDir.resolve("a/A.java"), "package a;\nclass A {}\n");
        entry = mgr.load(srcDir.toString(), null, null, true, true);
    }

    private CallToolRequest addPackageRequest(String pkg) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("packageName", pkg);
        args.put("generatePackageInfo", false);
        return new CallToolRequest("add_package", args);
    }

    @Test
    void emptyPackageInTransaction_notOnDiskUntilCommit() throws Exception {
        begin.handle(null, new CallToolRequest("begin_transaction", Map.of()));
        var result = addPackage.handle(null, addPackageRequest("x.y.z"));
        assertFalse(result.isError(), result.content().toString());

        assertFalse(Files.exists(srcDir.resolve("x")), "Verzeichnis darf vor dem Commit nicht existieren");

        assertFalse(commit.handle(null, new CallToolRequest("commit_transaction", Map.of())).isError());
        assertTrue(Files.isDirectory(srcDir.resolve("x/y/z")));
        mgr.remove(entry.name());
    }

    @Test
    void rollback_leavesNoDirectoriesBehind() throws Exception {
        begin.handle(null, new CallToolRequest("begin_transaction", Map.of()));
        assertFalse(addPackage.handle(null, addPackageRequest("p.q")).isError());
        assertFalse(rollback.handle(null, new CallToolRequest("rollback_transaction", Map.of())).isError());

        assertFalse(Files.exists(srcDir.resolve("p")));
        mgr.remove(entry.name());
    }
}

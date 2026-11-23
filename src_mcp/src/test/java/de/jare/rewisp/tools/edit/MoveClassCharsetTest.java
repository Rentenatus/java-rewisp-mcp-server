/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.tools.txn.TransactionTools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * move_class behaelt die Kodierung (ISO-8859-1) der verschobenen Datei bei.
 *
 * @author Janusch Rentenatus
 */
class MoveClassCharsetTest {

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    private Path setup() throws Exception {
        Path oldDir = Files.createDirectories(tempDir.resolve("src/com/oldpkg"));
        Path old = oldDir.resolve("Old.java");
        Files.write(old, "package com.oldpkg;\n\nclass Old {\n    String s = \"Größe\";\n}\n"
                .getBytes(StandardCharsets.ISO_8859_1));
        return old;
    }

    private Map<String, Object> moveArgs(String name) {
        Map<String, Object> mv = new HashMap<>();
        mv.put("name", name);
        mv.put("className", "com.oldpkg.Old");
        mv.put("newPackage", "com.newpkg");
        return mv;
    }

    private static final byte[] EXPECTED = "package com.newpkg;\n\nclass Old {\n    String s = \"Größe\";\n}\n"
            .getBytes(StandardCharsets.ISO_8859_1);

    @Test
    void immediateMoveKeepsIso88591() throws Exception {
        setup();
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
        var move = new MoveClassTool(mgr, new EditManager(backups), RewriteTestSupport.newEngine());

        var result = move.handle(null, new CallToolRequest("move_class", moveArgs(entry.name())));

        assertFalse(result.isError(), result.content().toString());
        assertArrayEquals(EXPECTED, Files.readAllBytes(tempDir.resolve("src/com/newpkg/Old.java")));
        mgr.remove(entry.name());
    }

    @Test
    void transactionalMoveKeepsIso88591() throws Exception {
        setup();
        ProjectManager mgr = new ProjectManager();
        EditManager em = new EditManager(backups);
        ProjectEntry entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
        var move = new MoveClassTool(mgr, em, RewriteTestSupport.newEngine());
        var begin = new TransactionTools.Begin(mgr, em);
        var commit = new TransactionTools.Commit(mgr, em);

        begin.handle(null, new CallToolRequest("begin_transaction", Map.of()));
        assertFalse(move.handle(null, new CallToolRequest("move_class", moveArgs(entry.name()))).isError());
        var committed = commit.handle(null, new CallToolRequest("commit_transaction", Map.of()));

        assertFalse(committed.isError(), committed.content().toString());
        assertArrayEquals(EXPECTED, Files.readAllBytes(tempDir.resolve("src/com/newpkg/Old.java")));
        mgr.remove(entry.name());
    }
}
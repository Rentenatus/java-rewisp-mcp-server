/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.testsupport.RewriteTestSupport;
import de.jare.rewisp.tools.base.BaseTool;
import de.jare.rewisp.tools.edit.EditLineTool;
import de.jare.rewisp.tools.edit.MoveClassTool;
import de.jare.rewisp.tools.txn.TransactionTools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * N38: nach einem Transaktions-Commit zaehlt check_project_dirty nur wirklich geschriebene Dateien
 * ("Edited through rewisp since load"), nicht das ganze Projekt, das nach move_class als dirty markiert wird.
 *
 * @author Janusch Rentenatus
 */
class CheckProjectDirtyAfterTransactionTest {

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    private ProjectManager mgr;
    private EditManager em;
    private ProjectEntry entry;
    private Path stay;

    @BeforeEach
    void setUp() throws Exception {
        mgr = new ProjectManager();
        em = new EditManager(backups);
        mgr.setEditManager(em);
        Path root = Files.createDirectories(tempDir.resolve("proj"));
        Files.writeString(root.resolve("pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>t</artifactId><version>1</version>"
                + "<properties><maven.compiler.source>21</maven.compiler.source>"
                + "<maven.compiler.target>21</maven.compiler.target></properties></project>");
        Path pkg = Files.createDirectories(root.resolve("src/main/java/com/oldpkg"));
        stay = pkg.resolve("Stay.java");
        Files.writeString(stay, "package com.oldpkg;\n\npublic class Stay {\n    int v = 1;\n}\n");
        Files.writeString(pkg.resolve("Old.java"), "package com.oldpkg;\n\npublic class Old {\n}\n");
        entry = mgr.load(root.toString(), null, null, true, true);
    }

    @AfterEach
    void tearDown() {
        mgr.remove(entry.name());
    }

    private static CallToolResult call(BaseTool tool, String toolName, Object... kv) throws Exception {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        CallToolResult r = tool.handle(null, new CallToolRequest(toolName, args));
        assertFalse(r.isError(), r.content().toString());
        return r;
    }

    private String check() throws Exception {
        return call(new CheckProjectDirtyTool(mgr), "check_project_dirty", "name", entry.name()).content().toString();
    }

    @Test
    void transactionWithEditAndMoveCountsEachFileOnce() throws Exception {
        call(new TransactionTools.Begin(mgr, em), "begin_transaction");
        call(new EditLineTool(mgr, em), "edit_line", "name", entry.name(), "file", stay.toString(),
                "lineNumber", 4, "newContent", "    int v = 2;");
        call(new MoveClassTool(mgr, em, RewriteTestSupport.newEngine()), "move_class",
                "name", entry.name(), "className", "com.oldpkg.Old", "newPackage", "com.newpkg");
        call(new TransactionTools.Commit(mgr, em), "commit_transaction");

        String out = check();

        assertTrue(out.contains("No external changes"), out);
        // Stay (geaendert) und com/newpkg/Old (neu); markDirty nach move_class darf die Zahl nicht aufblaehen
        assertTrue(out.contains("Edited through rewisp since load:** 2 file(s)"), out);
    }

    @Test
    void rolledBackTransactionDoesNotCountAsEditedFiles() throws Exception {
        call(new TransactionTools.Begin(mgr, em), "begin_transaction");
        call(new EditLineTool(mgr, em), "edit_line", "name", entry.name(), "file", stay.toString(),
                "lineNumber", 4, "newContent", "    int v = 2;");
        call(new TransactionTools.Rollback(mgr, em), "rollback_transaction");
        call(new TransactionTools.Begin(mgr, em), "begin_transaction");
        call(new MoveClassTool(mgr, em, RewriteTestSupport.newEngine()), "move_class",
                "name", entry.name(), "className", "com.oldpkg.Old", "newPackage", "com.newpkg");
        call(new TransactionTools.Commit(mgr, em), "commit_transaction");

        String out = check();

        assertTrue(out.contains("No external changes"), out);
        // nur com/newpkg/Old (neu); Stay wurde zurueckgerollt
        assertTrue(out.contains("Edited through rewisp since load:** 1 file(s)"), out);
    }
}
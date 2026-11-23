/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.txn;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import de.jare.rewisp.tools.base.BaseTool;
import de.jare.rewisp.tools.edit.EditLineTool;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * 9.7: {@code edit_line} in einer Transaktion samt Rollback auf Tool-Ebene sowie Rollback nach einem
 * fehlgeschlagenen Commit (externe Aenderung).
 *
 * @author Janusch Rentenatus
 */
class TransactionToolLevelTest {

    private static final String ORIGINAL = "package p;\npublic class A {\n    int f() {\n        return 1;\n    }\n}\n";

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    private ProjectManager mgr;
    private EditManager em;
    private ProjectEntry entry;
    private Path file;

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
        file = Files.createDirectories(root.resolve("src/main/java/p")).resolve("A.java");
        Files.writeString(file, ORIGINAL);
        entry = mgr.load(root.toString(), null, null, true, true);
    }

    @AfterEach
    void tearDown() {
        mgr.remove(entry.name());
    }

    private static CallToolResult call(BaseTool tool, String toolName, Object... kv) throws Exception {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) args.put((String) kv[i], kv[i + 1]);
        return tool.handle(null, new CallToolRequest(toolName, args));
    }

    private CallToolResult editLine(String content) throws Exception {
        return call(new EditLineTool(mgr, em), "edit_line", "name", entry.name(), "file", file.toString(),
                "lineNumber", 4, "newContent", content);
    }

    private String snapshotContent() {
        Path rel = Path.of("src/main/java/p/A.java");
        return mgr.getProjectState(entry.name()).snapshot().files().get(rel).content();
    }

    @Test
    void editLineInTransactionIsBufferedAndRolledBack() throws Exception {
        assertFalse(call(new TransactionTools.Begin(mgr, em), "begin_transaction").isError());
        assertFalse(editLine("        return 99;").isError());

        assertEquals(ORIGINAL, Files.readString(file), "disk stays frozen while the transaction is open");
        String diff = call(new TransactionTools.GetDiff(mgr, em), "get_transaction_diff").content().toString();
        assertTrue(diff.contains("return 99;"), diff);

        assertFalse(call(new TransactionTools.Rollback(mgr, em), "rollback_transaction").isError());

        assertEquals(ORIGINAL, Files.readString(file));
        assertTrue(snapshotContent().contains("return 1;"), "snapshot must be restored from disk after rollback");
        assertFalse(snapshotContent().contains("return 99;"));
    }

    @Test
    void rollbackAfterFailedCommitClosesTheTransactionAndKeepsTheExternalChange() throws Exception {
        assertFalse(call(new TransactionTools.Begin(mgr, em), "begin_transaction").isError());
        assertFalse(editLine("        return 99;").isError());
        String external = "package p;\npublic class A { int external; }\n";
        Files.writeString(file, external); // externe Aenderung nach dem Puffern

        CallToolResult commit = call(new TransactionTools.Commit(mgr, em), "commit_transaction");
        assertTrue(commit.isError(), commit.content().toString());
        assertTrue(commit.content().toString().contains("still open"), commit.content().toString());
        assertEquals(external, Files.readString(file), "the external change must not be overwritten");
        assertTrue(em.isInTransaction());
        // das Diff laesst sich auch nach dem fehlgeschlagenen Commit abrufen
        assertFalse(call(new TransactionTools.GetDiff(mgr, em), "get_transaction_diff").isError());

        assertFalse(call(new TransactionTools.Rollback(mgr, em), "rollback_transaction").isError());

        assertFalse(em.isInTransaction());
        assertEquals(external, Files.readString(file));
        assertTrue(snapshotContent().contains("int external;"), "snapshot reflects the disk after rollback");
    }
}

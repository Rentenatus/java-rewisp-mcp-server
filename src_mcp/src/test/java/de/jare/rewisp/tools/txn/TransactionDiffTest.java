/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.txn;

import de.jare.rewisp.testsupport.RewriteTestSupport;

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
import de.jare.rewisp.tools.edit.AddClassTool;
import de.jare.rewisp.tools.edit.ReplaceMethodBodyTool;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * {@code get_transaction_diff}: Diff zwischen dem eingefrorenen Stand (Inhalt beim ersten Zugriff der
 * Transaktion) und dem laufenden Transaktionsstand. Es wird nichts geschrieben.
 *
 * @author Janusch Rentenatus
 */
class TransactionDiffTest {

    private static final String POM = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + "<modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>t</artifactId><version>1</version>"
            + "<properties><maven.compiler.source>21</maven.compiler.source>"
            + "<maven.compiler.target>21</maven.compiler.target></properties></project>";
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
        Files.writeString(root.resolve("pom.xml"), POM);
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

    private static String text(CallToolResult r) {
        return r.content().toString();
    }

    private CallToolResult diff(Object... kv) throws Exception {
        return call(new TransactionTools.GetDiff(mgr, em), "get_transaction_diff", kv);
    }

    private CallToolResult begin() throws Exception {
        return call(new TransactionTools.Begin(mgr, em), "begin_transaction");
    }

    private CallToolResult replaceBody(String body) throws Exception {
        return call(new ReplaceMethodBodyTool(mgr, em, RewriteTestSupport.newEngine()), "replace_method_body",
                "name", entry.name(), "className", "p.A", "methodName", "f", "newBody", body);
    }

    @Test
    void withoutTransactionItIsAnError() throws Exception {
        CallToolResult r = diff();
        assertTrue(r.isError(), text(r));
        assertTrue(text(r).contains("No active transaction"), text(r));
    }

    @Test
    void emptyTransactionHasNoChanges() throws Exception {
        assertFalse(begin().isError());
        CallToolResult r = diff();
        assertFalse(r.isError(), text(r));
        assertTrue(text(r).contains("no net changes"), text(r));
    }

    @Test
    void modifiedFileShowsDiffAndDiskStaysFrozen() throws Exception {
        assertFalse(begin().isError());
        assertFalse(replaceBody("return 2;").isError());

        String t = text(diff());

        assertTrue(t.contains("1 file(s), +1 -1 lines"), t);
        assertTrue(t.contains("modify src/main/java/p/A.java (+1 -1)"), t);
        assertTrue(t.contains("- ") && t.contains("return 1;"), t);
        assertTrue(t.contains("+ ") && t.contains("return 2;"), t);
        assertEquals(ORIGINAL, Files.readString(file), "the diff must not write anything");
    }

    @Test
    void newFileIsListedAsCreate() throws Exception {
        assertFalse(begin().isError());
        assertFalse(call(new AddClassTool(mgr, em, RewriteTestSupport.newEngine()), "add_class",
                "name", entry.name(), "packageName", "p", "className", "B", "body", "int x;").isError());

        String t = text(diff());

        assertTrue(t.contains("create src/main/java/p/B.java (+"), t);
        assertFalse(Files.exists(file.resolveSibling("B.java")));
        assertFalse(t.contains("mkdir"), "the package directory already exists: " + t);
    }

    @Test
    void newPackageDirectoryIsListedAsMkdir() throws Exception {
        assertFalse(begin().isError());
        assertFalse(call(new AddClassTool(mgr, em, RewriteTestSupport.newEngine()), "add_class",
                "name", entry.name(), "packageName", "p.sub", "className", "C", "body", "int y;").isError());

        String t = text(diff());

        assertTrue(t.contains("create src/main/java/p/sub/C.java"), t);
        assertTrue(t.contains("mkdir src/main/java/p/sub"), t);
    }

    @Test
    void deletedFileIsListedAsDelete() throws Exception {
        assertFalse(begin().isError());
        em.deleteFile(file);

        String t = text(diff());

        assertTrue(t.contains("delete src/main/java/p/A.java (-6)"), t);
        assertTrue(t.contains("-6 lines") || t.contains("+0 -6 lines"), t);
        assertTrue(Files.exists(file), "deletion happens only at commit");
    }

    @Test
    void editRevertedToTheFrozenContentIsNoNetChange() throws Exception {
        assertFalse(begin().isError());
        assertFalse(replaceBody("return 2;").isError());
        assertFalse(replaceBody("return 1;").isError());

        String t = text(diff());

        assertTrue(t.contains("no net changes"), t);
        assertTrue(t.contains("back at their original content"), t);
    }

    @Test
    void statOnlyOmitsDiffLines() throws Exception {
        assertFalse(begin().isError());
        assertFalse(replaceBody("return 2;").isError());

        String t = text(diff("statOnly", true));

        assertTrue(t.contains("modify src/main/java/p/A.java (+1 -1)"), t);
        assertFalse(t.contains("@@"), t);
    }

    @Test
    void diffIsGoneAfterRollbackAndCommit() throws Exception {
        assertFalse(begin().isError());
        assertFalse(replaceBody("return 2;").isError());
        assertFalse(call(new TransactionTools.Rollback(mgr, em), "rollback_transaction").isError());
        assertTrue(diff().isError());

        assertFalse(begin().isError());
        assertFalse(replaceBody("return 3;").isError());
        assertFalse(call(new TransactionTools.Commit(mgr, em), "commit_transaction").isError());
        assertTrue(diff().isError());
        assertTrue(Files.readString(file).contains("return 3;"));
    }
}

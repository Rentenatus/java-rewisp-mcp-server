package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

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
 * Global rename works with few and with many dirty files (AP-8.3: the former stale threshold
 * guard was removed because Spoon is always rebuilt from the effective Rewrite state).
 */
class StaleThresholdTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private EditManager editMgr;
    private RenameSymbolTool rename;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        editMgr = new EditManager(tempDir.resolve("backups"));
        rename = new RenameSymbolTool(mgr, editMgr, RewriteTestSupport.newEngine());
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    /** One dirty file (well under threshold): global rename works and updates the caller in the dirty file.
     *  Spoon reads only from Rewrite, so the dirty file needs no separate re-parse advisory any more. */
    @Test
    void globalRenameWithFewDirtyFilesUpdatesCallerInDirtyFile() throws Exception {
        Path svc = srcDir.resolve("Service.java");
        Files.writeString(svc,
                "class Service {\n" +
                "    String getName() { return \"n\"; }\n" +
                "}\n");
        Path cli = srcDir.resolve("Client.java");
        Files.writeString(cli,
                "class Client {\n" +
                "    String fetch() {\n" +
                "        Service s = new Service();\n" +
                "        return s.getName();\n" +
                "    }\n" +
                "}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // Touch Client.java via EditManager so it becomes the single dirty file.
        ProjectEntry touched = editMgr.writeFile(entry, cli, Files.readString(cli) + "\n// touched\n", null);
        mgr.updateEntry(touched);
        entry = mgr.find(entry.name());
        assertTrue(entry.isFileDirty(cli), "precondition: Client.java should be dirty");
        assertFalse(entry.isFileDirty(svc), "precondition: Service.java should be clean");

        CallToolResult r = rename.handle(null, req(entry.name(), "Service", "getName", "getDisplayName", "method"));

        assertFalse(r.isError(), "rename should succeed with 1 stale file");
        // Caller in the stale file (Client.java) must be updated despite staleness
        assertTrue(Files.readString(cli).contains("getDisplayName"),
                "caller in stale Client.java should be renamed");
        mgr.remove(entry.name());
    }

    /** Many dirty files no longer block a global rename (the old stale guard is gone, since the
     *  Spoon model is always rebuilt from the effective Rewrite state). */
    @Test
    void globalRenameWithManyDirtyFilesStillSucceeds() throws Exception {
        // Create 21 source files (formerly above the stale threshold of 20).
        Path svc = srcDir.resolve("Service.java");
        Files.writeString(svc,
                "class Service {\n" +
                "    String getName() { return \"n\"; }\n" +
                "}\n");
        for (int i = 0; i < 20; i++) {
            Files.writeString(srcDir.resolve("File" + i + ".java"),
                    "class File" + i + " { int v = " + i + "; }\n");
        }
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        // markDirty marks ALL source files stale (simulating a disruptive op).
        mgr.markDirty(entry.name());
        entry = mgr.find(entry.name());
        assertTrue(entry.dirtyFiles().size() > 20,
                "precondition: more than 20 files should be dirty, got " + entry.dirtyFiles().size());

        CallToolResult r = rename.handle(null, req(entry.name(), "Service", "getName", "getDisplayName", "method"));

        assertFalse(r.isError(), r.content().toString());
        assertTrue(Files.readString(svc).contains("getDisplayName"),
                "Service.java must be renamed even with many dirty files");
        mgr.remove(entry.name());
    }

    private static CallToolRequest req(String name, String className,
            String oldName, String newName, String scope) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", className);
        args.put("oldName", oldName);
        args.put("newName", newName);
        args.put("scope", scope);
        return new CallToolRequest("rename_symbol", args);
    }
}

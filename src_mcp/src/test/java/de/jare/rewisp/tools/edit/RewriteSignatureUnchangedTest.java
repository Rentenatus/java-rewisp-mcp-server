/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
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
 * AP-4.17: Eine nur anders formatierte oder mit Generics/Packages geschriebene, inhaltlich gleiche
 * Signatur gilt als unveraendert und fuehrt zu keinem Edit.
 *
 * @author Janusch Rentenatus
 */
class RewriteSignatureUnchangedTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private RewriteSignatureTool tool;
    private Path file;
    private ProjectEntry entry;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        tool = new RewriteSignatureTool(mgr, new EditManager(tempDir.resolve("backups")),
                RewriteTestSupport.newEngine());
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        file = src.resolve("Svc.java");
        Files.writeString(file,
                "import java.util.List;\n"
              + "import java.util.Map;\n"
              + "public class Svc {\n"
              + "    public List<String> find(Map<String, Integer> index, int limit) { return null; }\n"
              + "}\n");
        entry = mgr.load(src.toString(), null, null, true, true);
    }

    private CallToolResult run(String returnType, String params) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "Svc");
        args.put("methodName", "find");
        if (returnType != null) args.put("newReturnType", returnType);
        if (params != null) args.put("newParameters", params);
        args.put("mode", "signature_only");
        return tool.handle(null, new CallToolRequest("rewrite_signature", args));
    }

    @Test
    void sameSignatureWithDifferentSpacingAndGenerics_isUnchanged() throws Exception {
        String before = Files.readString(file);

        CallToolResult r = run("java.util.List<String>", "Map<String,Integer> index,int limit");

        assertFalse(r.isError(), r.content().toString());
        assertTrue(r.content().toString().contains("Signature unchanged"), r.content().toString());
        assertTrue(before.equals(Files.readString(file)));
        mgr.remove(entry.name());
    }

    @Test
    void differentParameterName_isAChange() throws Exception {
        CallToolResult r = run(null, "Map<String, Integer> idx, int limit");

        assertFalse(r.content().toString().contains("Signature unchanged"), r.content().toString());
        mgr.remove(entry.name());
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.read;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.tools.edit.EditLineTool;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * AP-3.13: get_file_content liefert nummerierte Zeilen, Pfad und Fingerprint (speist edit_line),
 * liest vom originalProjectDir und findet geschachtelte Typen in der Datei ihres Aussentyps.
 *
 * @author Janusch Rentenatus
 */
class GetFileContentToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private ProjectEntry entry;
    private Path file;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        Path pkg = Files.createDirectories(tempDir.resolve("src/p"));
        file = pkg.resolve("Outer.java");
        Files.writeString(file, "package p;\npublic class Outer {\n    public static class Inner {}\n    int v = 1;\n}\n");
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
    }

    @AfterEach
    void cleanup() {
        mgr.remove(entry.name());
    }

    private CallToolResult get(String className, String... kv) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", className);
        for (int i = 0; i < kv.length; i += 2) args.put(kv[i], kv[i + 1]);
        return new GetFileContentTool(mgr).handle(null, new CallToolRequest("get_file_content", args));
    }

    @Test
    void outputIsNumberedAndCarriesPathAndFingerprint() {
        CallToolResult r = get("p.Outer");
        String out = r.content().toString();

        assertFalse(r.isError(), out);
        assertTrue(out.contains("1 | package p;"), out);
        assertTrue(out.contains("4 |     int v = 1;"), out);
        assertTrue(out.contains("**Lines:** 5"), out);
        assertTrue(out.contains("p/Outer.java"), out);
        assertTrue(Pattern.compile("Fingerprint:\\*\\* `\\d+\\|\\d+`").matcher(out).find(), out);
    }

    @Test
    void nestedTypeResolvesToTheOuterFile() {
        String out = get("p.Outer.Inner").content().toString();

        assertTrue(out.contains("public static class Inner"), out);
    }

    @Test
    void lineRangeLimitsTheOutput() {
        String out = get("p.Outer", "startLine", "2", "endLine", "3").content().toString();

        assertTrue(out.contains("(showing 2-3)"), out);
        assertTrue(out.contains("2 | public class Outer"), out);
        assertFalse(out.contains("1 | package p;"), out);
        assertFalse(out.contains("4 |"), out);
        assertThrows(IllegalArgumentException.class, () -> get("p.Outer", "startLine", "0"));
    }

    @Test
    void fingerprintFromTheOutputIsAcceptedByEditLine() throws Exception {
        Matcher m = Pattern.compile("Fingerprint:\\*\\* `([^`]+)`").matcher(get("p.Outer").content().toString());
        assertTrue(m.find());
        String fingerprint = m.group(1);
        EditLineTool editLine = new EditLineTool(mgr, new EditManager(tempDir.resolve("backups")));

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("file", file.toString());
        args.put("lineNumber", 4);
        args.put("newContent", "    int v = 2;");
        args.put("expectedFingerprint", fingerprint);
        CallToolResult result = editLine.handle(null, new CallToolRequest("edit_line", args));

        assertFalse(result.isError(), result.content().toString());
        assertTrue(Files.readString(file).contains("int v = 2;"));
    }
}

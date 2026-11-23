/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.read;

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
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * AP-3.11: get_element_position - exakter Match, Mehrdeutigkeit als Fehler mit Kandidaten,
 * ungueltiger elementType wird abgelehnt, Konstruktoren sind auffindbar.
 *
 * @author Janusch Rentenatus
 */
class GetElementPositionToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private ProjectEntry entry;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        Path pkg = Files.createDirectories(tempDir.resolve("src/p"));
        Files.writeString(pkg.resolve("T.java"),
                "package p;\n"                                   // 1
                + "public class T {\n"                           // 2
                + "    int size;\n"                              // 3
                + "    public T() {}\n"                          // 4
                + "    public T(int x) {}\n"                     // 5
                + "    void getTarget(Target t) {}\n"                  // 6
                + "    void run(String s) {}\n"                  // 7
                + "    void run(int i) {}\n"                     // 8
                + "    int size() { return size; }\n"            // 9
                + "}\n");
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
    }

    @AfterEach
    void cleanup() {
        mgr.remove(entry.name());
    }

    private CallToolResult position(String element, String elementType) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "p.T");
        args.put("element", element);
        if (elementType != null) args.put("elementType", elementType);
        return new GetElementPositionTool(mgr).execute(null, new CallToolRequest("get_element_position", args));
    }

    private static String text(CallToolResult r) {
        return r.content().toString();
    }

    @Test
    void partialNameDoesNotMatchBySubstring() {
        CallToolResult r = position("get", null);
        assertTrue(r.isError(), "'get' must not match getTarget(Target) by substring: " + text(r));
        assertTrue(text(r).contains("ELEMENT_NOT_FOUND"), text(r));
    }

    @Test
    void overloadsAreReportedAsAmbiguousWithCandidates() {
        CallToolResult r = position("run", null);

        assertTrue(r.isError());
        String out = text(r);
        assertTrue(out.contains("AMBIGUOUS_ELEMENT"), out);
        assertTrue(out.contains("run(java.lang.String)") && out.contains("run(int)"), out);
        assertTrue(out.contains("line 7") && out.contains("line 8"), out);
    }

    @Test
    void signatureSelectsOneOverload() {
        CallToolResult r = position("run(int)", null);

        assertFalse(r.isError(), text(r));
        assertTrue(text(r).contains("| **Line** | `8` |"), text(r));
        assertTrue(text(position("run(String)", "METHOD")).contains("| **Line** | `7` |"));
    }

    @Test
    void fieldAndMethodWithSameNameAreAmbiguousUnlessTypeGiven() {
        assertTrue(text(position("size", null)).contains("AMBIGUOUS_ELEMENT"));
        assertTrue(text(position("size", "FIELD")).contains("| **Line** | `3` |"));
        assertTrue(text(position("size", "METHOD")).contains("| **Line** | `9` |"));
    }

    @Test
    void constructorsAreFound() {
        assertTrue(text(position("<init>(int)", null)).contains("| **Line** | `5` |"));
        assertTrue(text(position("T()", "CONSTRUCTOR")).contains("| **Line** | `4` |"));
        assertTrue(text(position("<init>", null)).contains("AMBIGUOUS_ELEMENT"));
    }

    @Test
    void classNameStillResolvesToTheClass() {
        CallToolResult r = position("T", null);
        assertFalse(r.isError(), text(r));
        assertTrue(text(r).contains("| **Line** | `2` |"), text(r));
    }

    @Test
    void invalidElementTypeIsRejected() {
        assertTrue(position("run(int)", "BOGUS").isError());
    }
}

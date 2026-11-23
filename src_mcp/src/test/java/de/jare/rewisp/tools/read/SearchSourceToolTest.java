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

/**
 * AP-3.12: search_source durchsucht den Quelltext aus dem Rewrite-Zustand (Felder, Konstruktoren,
 * Kommentare, Annotationen, Nested), liefert Datei:Zeile und zaehlt jedes Vorkommen.
 *
 * @author Janusch Rentenatus
 */
class SearchSourceToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private ProjectEntry entry;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        Path pkg = Files.createDirectories(tempDir.resolve("src/p"));
        Files.writeString(pkg.resolve("A.java"),
                "package p;\n"
                + "/** Needle in the class javadoc. */\n"
                + "public class A {\n"
                + "    @Deprecated // needle in annotation line\n"
                + "    private String needleField = \"needle needle\";\n"
                + "    public A() { String x = \"NEEDLE\"; }\n"
                + "    static class Nested { void m() { /* needle */ } }\n"
                + "}\n");
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
    }

    @AfterEach
    void cleanup() {
        mgr.remove(entry.name());
    }

    private String search(String... kv) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        for (int i = 0; i < kv.length; i += 2) args.put(kv[i], kv[i + 1]);
        var result = new SearchSourceTool(mgr).handle(null, new CallToolRequest("search_source", args));
        assertFalse(result.isError(), result.content().toString());
        return result.content().toString();
    }

    @Test
    void findsMatchesOutsideMethodBodiesWithFileAndLine() {
        String out = search("query", "needle");

        assertTrue(out.contains("p/A.java:2"), "class javadoc: " + out);
        assertTrue(out.contains("p/A.java:4"), "annotation line comment: " + out);
        assertTrue(out.contains("p/A.java:5"), "field: " + out);
        assertTrue(out.contains("p/A.java:6"), "constructor, case-insensitive: " + out);
        assertTrue(out.contains("p/A.java:7"), "nested type: " + out);
    }

    @Test
    void totalCountsEveryOccurrence() {
        // Zeile 2: 1, Zeile 4: 1, Zeile 5: needleField + 2 im Literal = 3, Zeile 6: 1 (NEEDLE), Zeile 7: 1
        assertTrue(search("query", "needle").contains("**Total matches:** 7 in 5 line(s) of 1 file(s)"));
    }

    @Test
    void caseSensitiveSearchOnlyFindsExactCase() {
        String out = search("query", "NEEDLE", "caseSensitive", "true");

        assertTrue(out.contains("p/A.java:6"), out);
        assertFalse(out.contains("p/A.java:5"), out);
        assertTrue(out.contains("**Total matches:** 1 "), out);
    }

    @Test
    void maxResultsLimitsTheListButNotTheCount() {
        String out = search("query", "needle", "maxResults", "2");

        assertTrue(out.contains("limited to 2 of 5"), out);
        assertTrue(out.contains("**Total matches:** 7"), out);
    }

    @Test
    void noHitsAreReported() {
        assertTrue(search("query", "absent-text").contains("_No results found._"));
    }
}

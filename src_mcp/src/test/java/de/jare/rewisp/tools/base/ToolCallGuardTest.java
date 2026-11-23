/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.base;

import de.jare.rewisp.tools.edit.EditLineTool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests für den ToolCallGuard: eindeutige Trace-IDs, ThreadLocal-Aufbau
 * und -Abräumung, hint-Format mit/ohne konfigurierter Log-Datei und die
 * Einbettung von traceId/logFile/logHint in Domain-Fehler-Bodies.
 */
class ToolCallGuardTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private Path srcDir;
    private String savedLogFile;

    @BeforeEach
    void setup() throws Exception {
        savedLogFile = System.getProperty("org.slf4j.simpleLogger.logFile");
        System.clearProperty("org.slf4j.simpleLogger.logFile");
        mgr = new ProjectManager();
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    @AfterEach
    void cleanup() {
        ToolCallGuard.end(ToolCallGuard.current(), false, null); // ThreadLocal sicher leeren
        if (savedLogFile == null) {
            System.clearProperty("org.slf4j.simpleLogger.logFile");
        } else {
            System.setProperty("org.slf4j.simpleLogger.logFile", savedLogFile);
        }
    }

    @Test
    void beginAssignsUniqueTraceIdsAndSetsThreadLocal() {
        ToolCallGuard.CallTrace t1 = ToolCallGuard.begin("tool_a", Map.of("x", "1"));
        ToolCallGuard.CallTrace t2 = ToolCallGuard.begin("tool_b", Map.of());
        assertNotEquals(t1.traceId(), t2.traceId(), "Trace-IDs müssen eindeutig sein");
        assertSame(t2, ToolCallGuard.current(), "current() liefert die letzte begonnene Trace");
        assertEquals("tool_b", t2.tool());
        ToolCallGuard.end(t2, false, null);
        ToolCallGuard.end(t1, false, null);
        assertNull(ToolCallGuard.current(), "end() räumt das ThreadLocal auf");
    }

    @Test
    void hintWithoutLogFileContainsOnlyTraceId() {
        String hint = ToolCallGuard.hint("t1abc");
        assertTrue(hint.contains("t1abc"));
        assertTrue(hint.contains("keine Log-Datei"), "Ohne Log-Datei muss der Hinweis das sagen");
    }

    @Test
    void hintWithLogFileIsAGrepCommand() {
        Path log = tempDir.resolve("mcp-server.log");
        System.setProperty("org.slf4j.simpleLogger.logFile", log.toString());
        String hint = ToolCallGuard.hint("t7def");
        assertTrue(hint.contains("grep 't7def'"));
        assertTrue(hint.contains(log.toString()));
    }

    @Test
    void domainErrorEmbedsActiveTrace() throws Exception {
        // edit_line mit Zeile ausserhalb der Datei liefert einen Domain-Fehler
        // als Rueckgabe (kein Wurf) — derselbe domainError-Pfad wie im Wrapper.
        Files.writeString(srcDir.resolve("L.java"), "class L {\nint x;\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);
        EditLineTool tool = new EditLineTool(mgr, new EditManager(tempDir.resolve("backups")));

        ToolCallGuard.CallTrace trace = ToolCallGuard.begin("edit_line",
                Map.of("lineNumber", "99"));
        try {
            CallToolResult result = tool.handle(null, new CallToolRequest("edit_line",
                    Map.of("name", entry.name(), "file", "L.java",
                            "lineNumber", 99, "newContent", "int y;", "dryRun", false)));

            assertTrue(result.isError(), "edit_line ausserhalb der Datei muss Domain-Fehler sein");
            String body = result.content().toString();
            assertTrue(body.contains(trace.traceId()),
                    "Fehler-Body muss die aktive Trace-ID enthalten: " + body);
            assertTrue(body.contains("logHint"),
                    "Fehler-Body muss den logHint enthalten: " + body);
        } finally {
            ToolCallGuard.end(trace, true, "test");
            mgr.remove(entry.name());
        }
        assertNull(ToolCallGuard.current());
    }

    @Test
    void longArgumentSummariesAreTruncated() {
        Map<String, Object> big = new HashMap<>();
        big.put("body", "x".repeat(5000));
        ToolCallGuard.CallTrace trace = ToolCallGuard.begin("add_method", big);
        ToolCallGuard.end(trace, false, null);
        // Kein assert auf Logausgabe (slf4j-simple schreibt in Tests nach stderr);
        // der Trunkierungspfad soll nur nicht werfen.
    }

    /** V7: ein unbekannter Typ wird als TYPE_NOT_FOUND mit className gemeldet, nicht als allgemeiner Fehler. */
    @Test
    void unknownTypeIsReportedAsTypeNotFound() {
        BaseEditTool tool = new BaseEditTool(mgr) {
            @Override protected String toolName() { return "lookup_tool"; }
            @Override protected String toolDescription() { return "looks up a type"; }
            @Override protected Map<String, Object> toolProperties() { return Map.of(); }
            @Override protected List<String> toolRequired() { return List.of(); }
            @Override public CallToolResult doHandle(McpSyncServerExchange ex, CallToolRequest req) {
                throw new de.jare.rewisp.analysis.TypeNotFoundException("Type not found: p.Missing", "p.Missing");
            }
        };

        CallToolResult result = tool.build().callHandler().apply(null, new CallToolRequest("lookup_tool", Map.of()));

        assertTrue(result.isError());
        String body = result.content().toString();
        assertTrue(body.contains("TYPE_NOT_FOUND") && body.contains("p.Missing"), body);
        assertTrue(body.contains("\"className\""), body);
    }

    @Test
    void internalErrorEmbedsLogHint() {
        // Regression: Unerwartete Exceptions liefen über error() als Klartext
        // OHNE traceId/logHint — der Stacktrace im Log war nicht auffindbar.
        // Der Catch im build()-Wrapper muss den Hinweis anhängen.
        Path log = tempDir.resolve("mcp-server.log");
        System.setProperty("org.slf4j.simpleLogger.logFile", log.toString());

        BaseEditTool boom = new BaseEditTool(mgr) {
            @Override protected String toolName() { return "boom_tool"; }
            @Override protected String toolDescription() { return "throws always"; }
            @Override protected Map<String, Object> toolProperties() { return Map.of(); }
            @Override protected List<String> toolRequired() { return List.of(); }
            @Override public CallToolResult doHandle(McpSyncServerExchange ex, CallToolRequest req) {
                throw new IllegalStateException("kaputt");
            }
        };

        CallToolResult result = boom.build().callHandler()
                .apply(null, new CallToolRequest("boom_tool", Map.of()));

        assertTrue(result.isError(), "Unerwartete Exception muss isError liefern");
        String body = result.content().toString();
        assertTrue(body.contains("internal error") && body.contains("kaputt"),
                "Body muss die Original-Message enthalten: " + body);
        assertTrue(body.contains("grep '"),
                "Body muss den logHint (grep-Befehl) enthalten: " + body);
        assertTrue(body.contains(log.toString()),
                "Body muss den Log-Datei-Pfad enthalten: " + body);
        assertNull(ToolCallGuard.current(), "end() muss das ThreadLocal aufräumen");
    }
}

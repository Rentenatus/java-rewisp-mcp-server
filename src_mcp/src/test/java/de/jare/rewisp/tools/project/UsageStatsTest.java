/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.tools.base.BaseEditTool;
import de.jare.rewisp.tools.base.UsageStats;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * {@code get_usage_stats} und die dahinterliegende {@link UsageStats}: Aufrufe, Fehler, Laufzeit, pauschale
 * Token-Schaetzung, Projekt-/Dateizahl und Zeitlimits.
 *
 * @author Janusch Rentenatus
 */
class UsageStatsTest {

    @Test
    void tokensAreEstimatedAsFourCharactersPerTokenRoundedUp() {
        assertEquals(0, UsageStats.estimateTokens(0));
        assertEquals(1, UsageStats.estimateTokens(1));
        assertEquals(1, UsageStats.estimateTokens(4));
        assertEquals(2, UsageStats.estimateTokens(5));
        assertEquals(250, UsageStats.estimateTokens(1000));
    }

    @Test
    void recordAggregatesCallsErrorsTimeAndCharacters() {
        UsageStats.registerDefinition("unit_probe", 400);
        UsageStats.record("unit_probe", 10_000_000L, false, 40, 400);   // 10 ms
        UsageStats.record("unit_probe", 30_000_000L, true, 20, 100);    // 30 ms, Fehler

        UsageStats.Row row = UsageStats.snapshot().stream().filter(r -> r.tool().equals("unit_probe")).findFirst().orElseThrow();

        assertEquals(2, row.calls());
        assertEquals(1, row.errors());
        assertEquals(20, row.avgMillis());
        assertEquals(30, row.maxMillis());
        assertEquals(15, row.tokensIn());     // 60 Zeichen
        assertEquals(125, row.tokensOut());   // 500 Zeichen
        assertEquals(100, row.tokensDefinition());
    }

    private static BaseEditTool probe(ProjectManager mgr, String toolName, boolean fail) {
        return new BaseEditTool(mgr) {
            @Override protected String toolName() { return toolName; }
            @Override protected String toolDescription() { return "probe for the usage statistics"; }
            @Override protected Map<String, Object> toolProperties() {
                return Map.of("q", Map.of("type", "string", "description", "query"));
            }
            @Override protected List<String> toolRequired() { return List.of(); }
            @Override public CallToolResult doHandle(McpSyncServerExchange ex, CallToolRequest req) {
                if (fail) throw new IllegalStateException("kaputt");
                return ok("0123456789".repeat(8)); // 80 Zeichen Antwort
            }
        };
    }

    @Test
    void theToolWrapperCountsEveryCallWithoutChangingTheResult() {
        ProjectManager mgr = new ProjectManager();
        var ok = probe(mgr, "usage_probe_ok", false).build();
        var bad = probe(mgr, "usage_probe_bad", true).build();

        CallToolResult r1 = ok.callHandler().apply(null, new CallToolRequest("usage_probe_ok", Map.of("q", "hello")));
        ok.callHandler().apply(null, new CallToolRequest("usage_probe_ok", Map.of()));
        CallToolResult r2 = bad.callHandler().apply(null, new CallToolRequest("usage_probe_bad", Map.of()));

        assertTrue(!r1.isError() && r2.isError());
        var rows = UsageStats.snapshot();
        UsageStats.Row okRow = rows.stream().filter(r -> r.tool().equals("usage_probe_ok")).findFirst().orElseThrow();
        UsageStats.Row badRow = rows.stream().filter(r -> r.tool().equals("usage_probe_bad")).findFirst().orElseThrow();
        assertEquals(2, okRow.calls());
        assertEquals(0, okRow.errors());
        assertEquals(160, okRow.outChars());
        assertTrue(okRow.inChars() > 0, "the arguments of the first call are counted");
        assertTrue(okRow.definitionChars() > 0, "the tool definition is registered at build()");
        assertEquals(1, badRow.calls());
        assertEquals(1, badRow.errors());
    }

    @Test
    void loadJavaProjectIsCountedToo() throws Exception {
        ProjectManager mgr = new ProjectManager();
        var spec = new LoadJavaProjectTool(mgr).build();
        long before = UsageStats.snapshot().stream().filter(r -> r.tool().equals("load_java_project"))
                .mapToLong(UsageStats.Row::calls).sum();
        assertTrue(UsageStats.snapshot().stream().anyMatch(r -> r.tool().equals("load_java_project")
                && r.definitionChars() > 0), "the loader is listed even before its first call");

        CallToolResult missing = spec.callHandler().apply(null, new CallToolRequest("load_java_project",
                Map.of("source", "Z:/does/not/exist")));

        assertTrue(missing.isError());
        UsageStats.Row row = UsageStats.snapshot().stream().filter(r -> r.tool().equals("load_java_project"))
                .findFirst().orElseThrow();
        assertEquals(before + 1, row.calls());
        assertTrue(row.errors() >= 1);
    }

    @Test
    void theToolReportsProjectsFilesTimeoutsAndTheUsageTable() throws Exception {
        ProjectManager mgr = new ProjectManager();
        probe(mgr, "usage_probe_table", false).build().callHandler()
                .apply(null, new CallToolRequest("usage_probe_table", Map.of("q", "x")));
        Path project = de.jare.rewisp.testsupport.TestFixtures.RESOURCES.resolve("no-lombok-sample").toAbsolutePath();
        ProjectEntry entry = mgr.load(project.toString(), "usage-sample", null, false);
        try {
            int files = mgr.getProjectState(entry.name()).snapshot().files().size();

            String text = new UsageStatsTool(mgr).handle(null, new CallToolRequest("get_usage_stats", Map.of()))
                    .content().toString();

            assertTrue(text.contains("**Loaded projects:** 1 (0 expired)"), text);
            assertTrue(text.contains("**source files in snapshots:** " + files), text);
            assertTrue(files > 0);
            assertTrue(text.contains("| " + entry.name() + " | MAVEN | " + files + " |"), text);
            assertTrue(text.contains("| Default project lifetime | 60 min |"), text);
            assertTrue(text.contains("| Build resolution timeout (mvn/gradle) | 120 s |"), text);
            assertTrue(text.contains("project.evictionGraceMinutes"), text);
            assertTrue(text.contains("| `usage_probe_table` | 1 | 0 |"), text);
            assertTrue(text.contains("| **Total** |"), text);
            assertTrue(text.contains("4 characters per token"), text);
        } finally {
            mgr.remove(entry.name());
        }
    }
}

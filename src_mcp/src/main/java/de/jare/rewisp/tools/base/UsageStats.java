/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.base;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Usage statistics per tool since server start: calls, errors, runtime and a rough estimate of the
 * token consumption. The estimate is deliberately coarse: {@value #CHARS_PER_TOKEN} characters count as one token
 * (source text and JSON are usually between 3 and 4); counted are the characters of the arguments (input), the
 * response (output) and the tool definition (description plus schema, which the client loads once per session).
 *
 * @author Janusch Rentenatus
 */
public final class UsageStats {

    /** Flat characters per token. */
    public static final int CHARS_PER_TOKEN = 4;

    /**
     * One row of the usage table.
     *
     * @param definitionChars characters of the tool definition (description and schema)
     */
    public record Row(String tool, long calls, long errors, long totalMillis, long maxMillis,
                      long inChars, long outChars, long definitionChars) {

        public long avgMillis() {
            return calls == 0 ? 0 : totalMillis / calls;
        }

        public long tokensIn() {
            return estimateTokens(inChars);
        }

        public long tokensOut() {
            return estimateTokens(outChars);
        }

        public long tokensDefinition() {
            return estimateTokens(definitionChars);
        }
    }

    private static final class Counter {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong totalNanos = new AtomicLong();
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicLong inChars = new AtomicLong();
        final AtomicLong outChars = new AtomicLong();
        volatile long definitionChars;
    }

    private static final Map<String, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static volatile Instant startedAt = Instant.now();

    private UsageStats() {
    }

    /** Grobe Token-Schaetzung: aufgerundet {@code chars / CHARS_PER_TOKEN}. */
    public static long estimateTokens(long chars) {
        return (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** Records the size of the tool definition (when the tool is registered). */
    public static void registerDefinition(String tool, long chars) {
        COUNTERS.computeIfAbsent(tool, k -> new Counter()).definitionChars = chars;
    }

    /** Records a call. */
    public static void record(String tool, long durationNanos, boolean error, long inChars, long outChars) {
        Counter c = COUNTERS.computeIfAbsent(tool, k -> new Counter());
        c.calls.incrementAndGet();
        if (error) c.errors.incrementAndGet();
        c.totalNanos.addAndGet(durationNanos);
        c.maxNanos.accumulateAndGet(durationNanos, Math::max);
        c.inChars.addAndGet(inChars);
        c.outChars.addAndGet(outChars);
    }

    /** All known tools (even without calls), by calls descending, then by name. */
    public static List<Row> snapshot() {
        List<Row> rows = new ArrayList<>();
        COUNTERS.forEach((tool, c) -> rows.add(new Row(tool, c.calls.get(), c.errors.get(),
                c.totalNanos.get() / 1_000_000, c.maxNanos.get() / 1_000_000,
                c.inChars.get(), c.outChars.get(), c.definitionChars)));
        rows.sort(Comparator.comparingLong(Row::calls).reversed().thenComparing(Row::tool));
        return rows;
    }

    /** Point in time from which counting starts (server start or last {@link #reset()}). */
    public static Instant startedAt() {
        return startedAt;
    }

    /** Resets all counters (definition sizes remain). */
    public static void reset() {
        COUNTERS.values().forEach(c -> {
            c.calls.set(0);
            c.errors.set(0);
            c.totalNanos.set(0);
            c.maxNanos.set(0);
            c.inChars.set(0);
            c.outChars.set(0);
        });
        startedAt = Instant.now();
    }

    /**
     * Wraps a finished tool specification so that its calls are counted (for tools that do not extend
     * {@code BaseTool}, e.g. {@code load_java_project}). The response remains unchanged.
     */
    public static io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification instrument(
            io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification spec) {
        var tool = spec.tool();
        registerDefinition(tool.name(), (tool.description() == null ? 0 : tool.description().length())
                + String.valueOf(tool.inputSchema()).length() + tool.name().length());
        return new io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            long started = System.nanoTime();
            var result = spec.callHandler().apply(exchange, request);
            record(tool.name(), System.nanoTime() - started, result.isError(), argumentChars(request.arguments()),
                    resultChars(result));
            return result;
        });
    }

    /** Character count of the arguments of a call (0 when no arguments). */
    static long argumentChars(Map<String, Object> arguments) {
        return arguments == null || arguments.isEmpty() ? 0 : String.valueOf(arguments).length();
    }

    /** Character count of all text contents of a response. */
    static long resultChars(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) return 0;
        long n = 0;
        for (var c : result.content()) {
            if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent t && t.text() != null) {
                n += t.text().length();
            }
        }
        return n;
    }
}

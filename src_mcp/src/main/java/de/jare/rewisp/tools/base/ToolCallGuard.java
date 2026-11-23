/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug guard for every MCP tool call.
 *
 * <p>Every call gets a short, greppable trace ID. The guard writes one start and one
 * end line to the server log and keeps the trace in a ThreadLocal, so {@code domainError}
 * can embed it in the error body. That way an AI agent (or human) whose MCP client only
 * shows a generic protocol error immediately knows where the details are:
 * {@code logFile} + {@code logHint} (a ready-made grep command) point at the log lines
 * of this call.</p>
 *
 * <p>The log file is configured in {@code JavaMcpServer.main()}
 * (slf4j-simple, {@code org.slf4j.simpleLogger.logFile}) — by default
 * {@code ~/.rewisp/logs/mcp-server.log}. Without a configured file (e.g. in unit
 * tests) everything keeps working; the trace ID is then the only reference.</p>
 *
 * @author Janusch Rentenatus
 */
public final class ToolCallGuard {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallGuard.class);

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final ThreadLocal<CallTrace> CURRENT = new ThreadLocal<>();
    /** Project the current call refers to (for project-related hints in the response). */
    private static final ThreadLocal<String> CALL_PROJECT = new ThreadLocal<>();

    /** Maximum length of the argument summary in the log (the debug guard must not bloat). */
    private static final int MAX_ARG_SUMMARY = 400;

    static {
        // Das Backend misst Phasen ueber observability.Phases und kennt diese Klasse nicht
        de.jare.rewisp.observability.Phases.setListener(ToolCallGuard::endPhase);
    }

    private ToolCallGuard() {
    }

    /**
     * A running tool call: trace ID, tool name, start time.
     */
    public record CallTrace(String traceId, String tool, long startNanos, StringBuilder phases) {
    }

    /**
     * Starts the trace for a tool call: assigns the trace ID, stores it in the
     * ThreadLocal and writes the start line to the log.
     */
    public static CallTrace begin(String tool, Map<String, Object> arguments) {
        String traceId = nextTraceId();
        CallTrace trace = new CallTrace(traceId, tool, System.nanoTime(), new StringBuilder());
        CURRENT.set(trace);
        CALL_PROJECT.remove();
        LOG.info("CALL [{}] {} args={}", traceId, tool, summarize(arguments));
        return trace;
    }

    /**
     * Ends the trace: writes the end line (incl. duration and ok/ERROR)
     * and clears the ThreadLocal.
     */
    public static void end(CallTrace trace, boolean error, String detail) {
        end(trace, error, detail, null);
    }

    /**
     * Ends the trace with an optional throwable: on errors the stack trace is logged
     * with the same trace ID, so the AI agent can find it via the logHint in the error
     * message. Without a throwable it stays a one-line DONE message.
     */
    public static void end(CallTrace trace, boolean error, String detail, Throwable t) {
        if (trace == null) return;
        long durationMs = (System.nanoTime() - trace.startNanos()) / 1_000_000;
        if (error) {
            if (t != null) {
                LOG.warn("DONE [{}] {} ERROR ({} ms) - {}",
                        trace.traceId(), trace.tool(), durationMs,
                        detail != null ? detail : t.getMessage(), t);
            } else {
                LOG.warn("DONE [{}] {} ERROR ({} ms){}{}", trace.traceId(), trace.tool(), durationMs,
                        phaseSummary(trace), detail != null ? " - " + detail : "");
            }
        } else {
            LOG.info("DONE [{}] {} ok ({} ms){}", trace.traceId(), trace.tool(), durationMs, phaseSummary(trace));
        }
        CURRENT.remove();
        CALL_PROJECT.remove();
    }

    /** Records which project the current call refers to. */
    public static void noteProject(String projectName) {
        CALL_PROJECT.set(projectName);
    }

    /** Project of the current call or {@code null} (call without project reference). */
    public static String callProject() {
        return CALL_PROJECT.get();
    }

    /** Remembers the start time of a phase; counterpart: {@link #endPhase}. */
    public static long startPhase() {
        return System.nanoTime();
    }

    /**
     * Records the duration of a phase (e.g. {@code snapshot-refresh}, {@code rewrite-preview}, {@code write},
     * {@code spoon-rebuild}) in the current trace; the DONE line lists them. Without a running
     * call (e.g. tests) nothing happens.
     */
    public static void endPhase(String phase, long startNanos) {
        CallTrace trace = CURRENT.get();
        if (trace == null) return;
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        synchronized (trace.phases()) {
            if (trace.phases().length() > 0) trace.phases().append(", ");
            trace.phases().append(phase).append("=").append(ms).append("ms");
        }
    }

    private static String phaseSummary(CallTrace trace) {
        synchronized (trace.phases()) {
            return trace.phases().length() == 0 ? "" : " [" + trace.phases() + "]";
        }
    }

    /**
     * Returns the trace of the call running on this thread — or null.
     * domainError uses this to embed traceId/logFile/logHint in the body.
     */
    public static CallTrace current() {
        return CURRENT.get();
    }

    /**
     * The configured log file path (slf4j-simple), or null when
     * not configured (e.g. unit tests).
     */
    public static String logFile() {
        return System.getProperty("org.slf4j.simpleLogger.logFile");
    }

    /**
     * Human-readable reference for the error body: where are the details for
     * this trace? With a configured log file as a ready-made grep command,
     * otherwise just the trace ID.
     */
    public static String hint(String traceId) {
        String file = logFile();
        if (file == null || file.isBlank()) {
            return "Trace-ID " + traceId + " (keine Log-Datei konfiguriert — Server-Stderr prüfen)";
        }
        return "Details: grep '" + traceId + "' \"" + file + "\"";
    }

    private static String nextTraceId() {
        // "t" + fortlaufende Nummer + 2 Zufalls-Hex-Zeichen: kurz, eindeutig,
        // greppbar und nicht ratbar.
        return "t" + COUNTER.incrementAndGet()
                + Long.toString(ThreadLocalRandom.current().nextInt(0x100, 0x1000), 16);
    }

    private static String summarize(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return "{}";
        // Wert fuer Wert kuerzen: ein grosser Parameter (z.B. ein ganzer Methodenkoerper) wird nie komplett gerendert
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            if (sb.length() >= MAX_ARG_SUMMARY) {
                sb.append(", ...");
                break;
            }
            if (!first) sb.append(", ");
            first = false;
            String value = String.valueOf(e.getValue());
            if (value.length() > 120) value = value.substring(0, 120) + "...(" + value.length() + " chars)";
            sb.append(e.getKey()).append("=").append(value);
        }
        return sb.append("}").toString();
    }
}

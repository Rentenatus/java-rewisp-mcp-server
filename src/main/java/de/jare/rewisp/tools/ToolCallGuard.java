/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug-Guard für jeden MCP-Tool-Aufruf.
 *
 * <p>Jeder Aufruf bekommt eine kurze, greppbare Trace-ID. Der Guard schreibt
 * je eine Start- und Ende-Zeile ins Server-Log und hält die Trace im
 * ThreadLocal, sodass {@code domainError} sie in den Fehler-Body einbetten
 * kann. Damit ein Agent (oder Mensch) bei einem Fehler, den der MCP-Client
 * als generischen Protokollfehler anzeigt, sofort weiß, wo die Details
 * liegen: {@code logFile} + {@code logHint} (fertiger grep-Befehl) zeigen
 * auf die Log-zeilen dieses Aufrufs.</p>
 *
 * <p>Die Log-Datei wird in {@code JavaMcpServer.main()} konfiguriert
 * (slf4j-simple, {@code org.slf4j.simpleLogger.logFile}) — standardmäßig
 * {@code ~/.rewisp/logs/mcp-server.log}. Ohne konfigurierte
 * Datei (z.B. in Unit-Tests) bleibt alles funktionsfähig; die Trace-ID
 * ist dann der einzige Verweis.</p>
 *
 * @author Janusch Rentenatus
 */
public final class ToolCallGuard {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallGuard.class);

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final ThreadLocal<CallTrace> CURRENT = new ThreadLocal<>();

    /** Maximale Länge der Argument-Zusammenfassung im Log (Debug-Guard soll nicht aufblähen). */
    private static final int MAX_ARG_SUMMARY = 400;

    private ToolCallGuard() {
    }

    /**
     * Ein laufender Tool-Aufruf: Trace-ID, Werkzeugname, Startzeit.
     */
    public record CallTrace(String traceId, String tool, long startNanos) {
    }

    /**
     * Startet die Trace für einen Tool-Aufruf: vergibt die Trace-ID, legt
     * sie im ThreadLocal ab und schreibt die Start-Zeile ins Log.
     */
    public static CallTrace begin(String tool, Map<String, Object> arguments) {
        String traceId = nextTraceId();
        CallTrace trace = new CallTrace(traceId, tool, System.nanoTime());
        CURRENT.set(trace);
        LOG.info("CALL [{}] {} args={}", traceId, tool, summarize(arguments));
        return trace;
    }

    /**
     * Beendet die Trace: schreibt die Ende-Zeile (inkl. Dauer und ok/ERROR)
     * und räumt das ThreadLocal auf.
     */
    public static void end(CallTrace trace, boolean error, String detail) {
        end(trace, error, detail, null);
    }

    /**
     * Beendet die Trace mit optionalem Throwable: Bei Fehlern wird der
     * Stacktrace mit derselben Trace-ID geloggt, damit der Agent ihn über
     * den logHint aus der Fehlermeldung findet. Ohne Throwable bleibt
     * es bei der einzeiligen DONE-Meldung.
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
                LOG.warn("DONE [{}] {} ERROR ({} ms){}", trace.traceId(), trace.tool(), durationMs,
                        detail != null ? " - " + detail : "");
            }
        } else {
            LOG.info("DONE [{}] {} ok ({} ms)", trace.traceId(), trace.tool(), durationMs);
        }
        CURRENT.remove();
    }

    /**
     * Liefert die Trace des auf diesem Thread laufenden Aufrufs — oder null.
     * domainError nutzt das, um traceId/logFile/logHint in den Body zu betten.
     */
    public static CallTrace current() {
        return CURRENT.get();
    }

    /**
     * Der konfigurierte Log-Datei-Pfad (slf4j-simple), oder null wenn
     * nicht konfiguriert (z.B. Unit-Tests).
     */
    public static String logFile() {
        return System.getProperty("org.slf4j.simpleLogger.logFile");
    }

    /**
     * Lesbarer Verweis für den Fehler-Body: wo stehen die Details zu
     * dieser Trace? Mit konfigurierter Log-Datei als fertiger grep-Befehl,
     * sonst nur die Trace-ID.
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
        String s = arguments.toString();
        if (s.length() <= MAX_ARG_SUMMARY) return s;
        return s.substring(0, MAX_ARG_SUMMARY) + "...(" + s.length() + " chars)";
    }
}

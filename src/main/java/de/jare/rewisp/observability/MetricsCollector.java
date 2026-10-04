/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Einfacher Metriken-Collector für das rewisp.
 * Sammelt Counters und Durations in-memory ohne externe Dependencies.
 * 
 * <p>Verwendung:
 * <pre>
 * MetricsCollector collector = new MetricsCollector();
 * collector.incrementCounter("mcp_requests_total");
 * collector.recordDuration("spoon_analysis_duration_ms", 150);
 * long count = collector.getCounter("mcp_requests_total");
 * double avg = collector.getAverageDuration("spoon_analysis_duration_ms");
 * </pre>
 */
public final class MetricsCollector {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, RunningAverage> durations = new ConcurrentHashMap<>();

    /**
     * Inkrementiert einen Counter.
     * 
     * @param name Der Name des Counters
     */
    public void incrementCounter(String name) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Inkrementiert einen Counter um einen bestimmten Wert.
     * 
     * @param name Der Name des Counters
     * @param delta Der Wert, um den inkrementiert wird
     */
    public void incrementCounter(String name, long delta) {
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).addAndGet(delta);
    }

    /**
     * Zeitzaufnahme für eine Duration.
     * 
     * @param name Der Name der Duration-Metrik
     * @param millis Die Dauer in Millisekunden
     */
    public void recordDuration(String name, long millis) {
        durations.computeIfAbsent(name, k -> new RunningAverage()).add(millis);
    }

    /**
     * Gibt den aktuellen Wert eines Counters zurück.
     * 
     * @param name Der Name des Counters
     * @return Der aktuelle Wert, oder 0 wenn nicht vorhanden
     */
    public long getCounter(String name) {
        return counters.getOrDefault(name, new AtomicLong(0)).get();
    }

    /**
     * Gibt den Durchschnittswert einer Duration zurück.
     * 
     * @param name Der Name der Duration-Metrik
     * @return Der Durchschnitt in Millisekunden, oder 0.0 wenn keine Daten
     */
    public double getAverageDuration(String name) {
        RunningAverage ra = durations.get(name);
        return ra != null ? ra.average() : 0.0;
    }

    /**
     * Gibt eine Snapshot aller Counter zurück.
     * 
     * @return Map mit allen Counter-Namen und Werten
     */
    public Map<String, Long> snapshotCounters() {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        counters.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    /**
     * Gibt eine Snapshot aller Duration-Averages zurück.
     * 
     * @return Map mit allen Duration-Namen und Durchschnittswerten
     */
    public Map<String, Double> snapshotDurations() {
        Map<String, Double> result = new java.util.LinkedHashMap<>();
        durations.forEach((k, v) -> result.put(k, v.average()));
        return result;
    }

    /**
     * Löscht alle Metriken.
     */
    public void reset() {
        counters.clear();
        durations.clear();
    }

    /**
     * Thread-sichere Implementierung eines gleitenden Durchschnitts.
     */
    private static class RunningAverage {
        private long total = 0;
        private long count = 0;

        synchronized void add(long value) {
            total += value;
            count++;
        }

        synchronized double average() {
            return count > 0 ? (double) total / count : 0.0;
        }

        synchronized long count() {
            return count;
        }

        synchronized long total() {
            return total;
        }
    }
}

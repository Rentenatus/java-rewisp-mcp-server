/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.observability;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Generator und Holder für Korrelations-IDs.
 * Jeder MCP-Request erhält eine eindeutige Korrelations-ID,
 * die in Logs und Metriken zur Verfolgung verwendet wird.
 * 
 * <p>Verwendung:
 * <pre>
 * String correlationId = CorrelationId.generate();
 * // oder
 * String currentId = CorrelationId.current();
 * </pre>
 */
public final class CorrelationId {

    private static final ThreadLocal<String> current = ThreadLocal.withInitial(() -> "");

    /**
     * Erzeugt eine neue zufällige Korrelations-ID.
     * 
     * @return Eine neue 8-stellige UUID-Korrelations-ID
     */
    public static String generate() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        current.set(id);
        return id;
    }

    /**
     * Gibt die aktuelle Korrelations-ID für diesen Thread zurück.
     * Falls keine gesetzt wurde, wird "no-correlation" zurückgegeben.
     * 
     * @return Die aktuelle Korrelations-ID
     */
    public static String current() {
        String id = current.get();
        return id != null && !id.isEmpty() ? id : "no-correlation";
    }

    /**
     * Setzt die Korrelations-ID für diesen Thread explizit.
     * 
     * @param id Die zu setzende Korrelations-ID
     */
    public static void set(String id) {
        current.set(id != null ? id : "");
    }

    /**
     * Löscht die Korrelations-ID für diesen Thread.
     */
    public static void clear() {
        current.remove();
    }
}

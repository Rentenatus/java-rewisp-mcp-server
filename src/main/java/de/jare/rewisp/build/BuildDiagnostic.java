package de.jare.rewisp.build;

/**
 * Build-Diagnose (z.B. Warnung bei fehlendem Plugin, Fehler bei aufgeloester Konfiguration).
 *
 * @param code      Diagnose-Code als Enum (Datei-Stabilitaet: Enum-Wert wird nicht als String gespeichert)
 * @param severity  Schweregrad ("INFO", "WARN", "ERROR")
 * @param message   Diagnose-Text
 * @param module    Betroffenes Modul (oder null bei projektweit)
 */
public record BuildDiagnostic(
        BuildDiagnosticCode code,
        String severity,
        String message,
        String module
) {
    /**
     * Liefert den Diagnose-Code als String fuer Caller.
     */
    public String codeString() {
        return code.name();
    }
}

package de.jare.rewisp.rewrite;

/**
 * Diagnose aus der Spoon-Analyse (Parse-Fehler, Classpath-Warnung, etc.).
 *
 * @param code     Diagnose-Code
 * @param severity Schweregrad ("INFO", "WARN", "ERROR")
 * @param message  Diagnose-Text
 * @param analyzer Analyzer-Kennung
 */
public record SpoonDiagnostic(
        String code,
        String severity,
        String message,
        String analyzer
) {}

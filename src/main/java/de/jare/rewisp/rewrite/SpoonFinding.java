package de.jare.rewisp.rewrite;

import java.nio.file.Path;

/**
 * Ein einzelnes Finding aus der Spoon-Analyse.
 *
 * @param code         Finding-Code
 * @param severity     Schweregrad ("INFO", "WARN", "ERROR")
 * @param message      Beschreibung
 * @param relativePath Relativer Dateipfad
 * @param startLine    Start-Zeile
 * @param endLine      End-Zeile
 * @param analyzer     Analyzer-Kennung
 */
public record SpoonFinding(
        String code,
        String severity,
        String message,
        Path relativePath,
        int startLine,
        int endLine,
        String analyzer
) {}

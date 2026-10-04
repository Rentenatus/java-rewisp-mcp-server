package de.jare.rewisp.rewrite;

import de.jare.rewisp.snapshot.ResolutionMode;

import java.time.Duration;
import java.util.List;

/**
 * Ergebnis einer Spoon-Analyse.
 *
 * @param resolutionMode  Verwendeter Aufloesungsmodus
 * @param findings        Gefundene Issues (z.Zt. leer, erweiterbar in Folgephasen)
 * @param diagnostics     Diagnosen (Parse-Fehler, Classpath-Warnungen)
 * @param duration        Dauer der Analyse
 * @param modelBuilt      true wenn das Modell erfolgreich gebaut wurde
 */
public record SpoonAnalysis(
        ResolutionMode resolutionMode,
        List<SpoonFinding> findings,
        List<SpoonDiagnostic> diagnostics,
        Duration duration,
        boolean modelBuilt
) {}

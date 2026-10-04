package de.jare.rewisp.build;

import java.util.List;

/**
 * Strukturiertes Build-Modell eines Projekts.
 *
 * @param buildSystem   Build-System-Kennung (z.B. "MAVEN")
 * @param modules       Liste der Module (mindestens eines bei Single-Module)
 * @param fingerprint   Build-Fingerprint fuer Invalidierung
 * @param diagnostics   Build-Diagnosen (z.B. Warnungen, Fehler)
 */
public record BuildContext(
        String buildSystem,
        List<ModuleContext> modules,
        BuildFingerprint fingerprint,
        List<BuildDiagnostic> diagnostics
) {}

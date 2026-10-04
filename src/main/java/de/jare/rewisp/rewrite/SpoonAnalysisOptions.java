package de.jare.rewisp.rewrite;

import de.jare.rewisp.snapshot.ResolutionMode;

/**
 * Optionen fuer {@link SpoonEngine#analyze}.
 *
 * @param resolutionMode     Classpath-Aufloesungsmodus
 * @param complianceLevel    Java-Compliance-Level (z.B. 21)
 * @param autoImports        Auto-Imports aktivieren
 * @param commentEnabled     Kommentare im Modell aktivieren
 * @param maxDurationSeconds Maximale Dauer in Sekunden (Soft-Limit)
 */
public record SpoonAnalysisOptions(
        ResolutionMode resolutionMode,
        int complianceLevel,
        boolean autoImports,
        boolean commentEnabled,
        long maxDurationSeconds
) {
    public static SpoonAnalysisOptions defaults() {
        return new SpoonAnalysisOptions(
                ResolutionMode.FULL_CLASSPATH, 21, true, true, 180);
    }
}

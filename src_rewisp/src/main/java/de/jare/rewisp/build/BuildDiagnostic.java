package de.jare.rewisp.build;

/**
 * Build diagnostic (e.g. warning about a missing plugin, error in a resolved configuration).
 *
 * @param code      diagnostic code as enum (file stability: enum value is not stored as string)
 * @param severity  severity
 * @param message   diagnostic text
 * @param module    affected module (or null for project-wide)
 */
public record BuildDiagnostic(
        BuildDiagnosticCode code,
        Severity severity,
        String message,
        String module
) {
    /** Severity of a build diagnostic. */
    public enum Severity { INFO, WARN, ERROR }
}

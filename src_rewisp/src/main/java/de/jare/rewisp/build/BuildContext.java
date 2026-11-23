package de.jare.rewisp.build;

import java.util.List;

/**
 * Structured build model of a project.
 *
 * @param buildSystem   build system identifier (e.g. "MAVEN")
 * @param modules       list of modules (at least one for single-module)
 * @param fingerprint   build fingerprint for invalidation
 * @param diagnostics   build diagnostics (e.g. warnings, errors)
 */
public record BuildContext(
        String buildSystem,
        List<ModuleContext> modules,
        BuildFingerprint fingerprint,
        List<BuildDiagnostic> diagnostics
) {}

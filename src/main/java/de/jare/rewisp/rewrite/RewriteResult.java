/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ergebnis einer OpenRewrite-Transformation.
 */
public record RewriteResult(
        Map<Path, RewrittenFile> changedFiles,
        Set<Path> deletedFiles,
        Set<Path> createdFiles,
        List<RewriteDiagnostic> diagnostics,
        boolean requiresBuildRefresh
) {
    public RewriteResult {
        changedFiles = Map.copyOf(changedFiles);
        deletedFiles = Set.copyOf(deletedFiles);
        createdFiles = Set.copyOf(createdFiles);
        diagnostics = List.copyOf(diagnostics);
    }
}

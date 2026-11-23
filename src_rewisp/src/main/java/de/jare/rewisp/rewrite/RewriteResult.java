/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of an OpenRewrite transformation.
 */
public record RewriteResult(
        Map<Path, RewrittenFile> changedFiles,
        Set<Path> deletedFiles,
        Set<Path> createdFiles,
        List<RewriteDiagnostic> diagnostics
) {
    public RewriteResult {
        // Einfuegereihenfolge bleibt erhalten (deterministische Ausgabe und Schreibreihenfolge)
        changedFiles = Collections.unmodifiableMap(new LinkedHashMap<>(changedFiles));
        deletedFiles = Collections.unmodifiableSet(new LinkedHashSet<>(deletedFiles));
        createdFiles = Collections.unmodifiableSet(new LinkedHashSet<>(createdFiles));
        diagnostics = List.copyOf(diagnostics);
    }
}

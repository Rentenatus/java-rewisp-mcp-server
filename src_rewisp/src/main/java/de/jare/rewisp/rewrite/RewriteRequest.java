/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import de.jare.rewisp.snapshot.SnapshotId;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Request to execute an OpenRewrite recipe.
 */
public record RewriteRequest(
        String projectId,
        String recipeId,
        Map<String, String> parameters,
        Set<Path> scope,
        SnapshotId expectedSnapshot
) {
    public RewriteRequest {
        parameters = Map.copyOf(parameters);
        scope = Set.copyOf(scope);
    }
}

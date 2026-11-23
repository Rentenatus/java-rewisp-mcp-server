/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.snapshot.ProjectSnapshot;

/**
 * Engine for OpenRewrite transformations.
 * Runs recipes in memory and returns transformation results.
 */
public interface RewriteEngine {

    /**
     * Run a preview transformation.
     * 
     * @param snapshot the current ProjectSnapshot
     * @param buildContext the build context (for the classpath)
     * @param request the rewrite request
     * @return the RewriteResult with all changes
     * @throws RewriteException on errors
     */
    RewriteResult preview(
            ProjectSnapshot snapshot,
            BuildContext buildContext,
            RewriteRequest request
    ) throws RewriteException;
}

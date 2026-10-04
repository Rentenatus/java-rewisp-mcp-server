/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

/**
 * Diagnose-Nachricht von OpenRewrite-Transformationen.
 */
public record RewriteDiagnostic(
        String code,
        String severity,
        String message,
        String path
) {}

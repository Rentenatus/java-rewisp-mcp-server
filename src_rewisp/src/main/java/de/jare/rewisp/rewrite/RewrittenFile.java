/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

/**
 * Represents a file changed by OpenRewrite.
 */
public record RewrittenFile(
        String content,
        String contentHash,
        String changeType  // MODIFY, CREATE, DELETE
) {}

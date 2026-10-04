/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

/**
 * Repräsentiert eine durch OpenRewrite veränderte Datei.
 */
public record RewrittenFile(
        String content,
        String contentHash,
        String changeType  // MODIFY, CREATE, DELETE
) {}

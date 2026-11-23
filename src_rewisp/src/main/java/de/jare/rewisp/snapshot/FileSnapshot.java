package de.jare.rewisp.snapshot;

import java.nio.file.Path;

public record FileSnapshot(
        Path relativePath,
        String content,
        String contentHash,
        long size,
        long lastModifiedMillis,
        boolean generated,
        boolean deleted
) {}

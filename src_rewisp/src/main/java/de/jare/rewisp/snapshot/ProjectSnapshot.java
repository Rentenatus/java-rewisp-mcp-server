package de.jare.rewisp.snapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

public record ProjectSnapshot(
        SnapshotId id,
        String projectId,
        Map<Path, FileSnapshot> files,
        Instant capturedAt
) {
    public ProjectSnapshot {
        files = Map.copyOf(files);
    }

}

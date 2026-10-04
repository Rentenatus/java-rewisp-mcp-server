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

    /**
     * Liefert den Content-Hash einer Datei, oder null wenn nicht im Snapshot.
     */
    public String hashOf(Path relativePath) {
        FileSnapshot fs = files.get(relativePath);
        return fs != null ? fs.contentHash() : null;
    }
}

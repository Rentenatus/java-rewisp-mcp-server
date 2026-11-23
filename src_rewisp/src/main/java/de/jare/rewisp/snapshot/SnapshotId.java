package de.jare.rewisp.snapshot;

/**
 * Identifier for a consistent project snapshot.
 * Reproducible for unchanged source state.
 */
public record SnapshotId(
        String projectId,
        long sequence,
        String rootHash
) {
    @Override
    public String toString() {
        String hash = rootHash == null ? "" : rootHash.length() > 12 ? rootHash.substring(0, 12) : rootHash;
        return projectId + ":" + sequence + ":" + hash;
    }
}

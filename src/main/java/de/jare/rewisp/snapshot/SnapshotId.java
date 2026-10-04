package de.jare.rewisp.snapshot;

/**
 * Identifikator fuer einen konsistenten Projekt-Snapshot.
 * Reproduzierbar fuer unveraenderten Source-Bestand.
 */
public record SnapshotId(
        String projectId,
        long sequence,
        String rootHash
) {
    @Override
    public String toString() {
        return projectId + ":" + sequence + ":" + rootHash.substring(0, 12);
    }
}

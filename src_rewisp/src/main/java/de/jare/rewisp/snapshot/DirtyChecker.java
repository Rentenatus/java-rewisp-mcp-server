/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;




import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility class for dirty checks of projects.
 * Compares files on disk with stored fingerprints or snapshots.
 */
public final class DirtyChecker {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DirtyChecker.class);

    private DirtyChecker() {}

    /**
     * Runs a dirty check against a snapshot (without filesystem access).
     * Compares the current snapshot with the previous snapshot.
     *
     * @param currentSnapshot the current snapshot (freshly created)
     * @param previousState the previous ProjectState with snapshot
     * @return the dirty check result
     */
    public static DirtyCheckResult checkDirtyAgainstSnapshot(
            ProjectSnapshot currentSnapshot,
            ProjectState previousState) {
        
        if (currentSnapshot == null || previousState == null) {
            return new DirtyCheckResult(0, List.of(), List.of(), List.of());
        }

        return checkDirtyAgainstSnapshot(currentSnapshot, previousState.snapshot());
    }

    /**
     * Like above, but against a freely chosen baseline snapshot (e.g. the load snapshot).
     * If the baseline is missing or empty, the result is {@code filesChecked == 0}; the caller must
     * treat that as "unknown", not as "clean".
     */
    public static DirtyCheckResult checkDirtyAgainstSnapshot(
            ProjectSnapshot currentSnapshot,
            ProjectSnapshot previousSnapshot) {
        if (currentSnapshot == null || previousSnapshot == null || previousSnapshot.files().isEmpty()) {
            return new DirtyCheckResult(0, List.of(), List.of(), List.of());
        }

        Map<Path, FileSnapshot> previousFiles = previousSnapshot.files();

        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> newFiles = new ArrayList<>();

        // Dateien im aktuellen Snapshot pruefen
        for (Map.Entry<Path, FileSnapshot> entry : currentSnapshot.files().entrySet()) {
            Path relativePath = entry.getKey();
            String currentHash = entry.getValue().contentHash();
            
            FileSnapshot previous = previousFiles.get(relativePath);
            if (previous == null) {
                // Neue Datei (im aktuellen Snapshot aber nicht im vorherigen)
                newFiles.add(relativePath.toString());
            } else {
                if (!currentHash.equals(previous.contentHash())) {
                    // Datei geaendert
                    changed.add(relativePath.toString());
                }
            }
        }

        // Dateien die im vorherigen Snapshot waren aber nicht mehr im aktuellen
        for (Path path : previousFiles.keySet()) {
            if (!currentSnapshot.files().containsKey(path)) {
                deleted.add(path.toString());
            }
        }

        return new DirtyCheckResult(
                currentSnapshot.files().size(),
                changed,
                deleted,
                newFiles);
    }
}

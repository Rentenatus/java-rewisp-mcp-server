/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.model.Fingerprint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility-Klasse fuer Dirty-Checks von Projekten.
 * Vergleicht Dateien auf der Festplatte mit gespeicherten Fingerprints oder Snapshots.
 */
public final class DirtyChecker {

    private DirtyChecker() {}

    /**
     * Fuehrt einen vollstaendigen Dirty-Check durch (Filesystem-basiert).
     * Erstellt einen temporaeren Snapshot und vergleicht mit den gespeicherten Fingerprints.
     * 
     * HINWEIS: Diese Methode erstellt einen Snapshot, ist also nicht komplett ohne Filesystem-Zugriff.
     * fuer volle Drei-Ebenen-Konformitaet sollte checkDirtyAgainstSnapshot() mit ProjectState genutzt werden.
     * 
     * @param entry Das Projekt-Entry
     * @return Das Dirty-Check-Ergebnis
     * @deprecated Nutze stattdessen checkDirtyAgainstSnapshot() mit ProjectState fuer volle Drei-Ebenen-Konformitaet
     */
    @Deprecated
    public static DirtyCheckResult checkDirtyFullScan(ProjectEntry entry) {
        java.util.List<String> changed = new java.util.ArrayList<>();
        java.util.List<String> deleted = new java.util.ArrayList<>();
        java.util.List<String> newFiles = new java.util.ArrayList<>();
        Path root = entry.originalProjectDir();
        if (root == null || !Files.isDirectory(root)) {
            return new DirtyCheckResult(0, changed, deleted, newFiles);
        }
        java.util.Set<Path> onDisk = new java.util.HashSet<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".java"))
                  .forEach(p -> {
                      Path norm = p.normalize();
                      onDisk.add(norm);
                      Fingerprint stored = entry.sourceFingerprints().get(norm);
                      if (stored == null) { newFiles.add(norm.toString()); return; }
                      try {
                          Fingerprint current = new Fingerprint(
                              Files.getLastModifiedTime(norm).toMillis(),
                              Files.size(norm));
                          if (!current.equals(stored)) changed.add(norm.toString());
                      } catch (IOException e) {
                          changed.add(norm + " (read error)");
                      }
                  });
        } catch (IOException e) {
            // walk failed — return empty
        }
        for (Path stored : entry.sourceFingerprints().keySet()) {
            if (!onDisk.contains(stored)) deleted.add(stored.toString());
        }
        return new DirtyCheckResult(onDisk.size(), changed, deleted, newFiles);
    }

    /**
     * Fuehrt einen Dirty-Check gegen einen Snapshot durch (ohne Filesystem-Zugriff).
     * Vergleicht den aktuellen Snapshot mit dem vorherigen Snapshot.
     *
     * @param currentSnapshot Der aktuelle Snapshot (frisch erstellt)
     * @param previousState Der vorherige ProjectState mit snapshot
     * @return Das Dirty-Check-Ergebnis
     */
    public static DirtyCheckResult checkDirtyAgainstSnapshot(
            ProjectSnapshot currentSnapshot,
            ProjectState previousState) {
        
        if (currentSnapshot == null || previousState == null) {
            return new DirtyCheckResult(0, List.of(), List.of(), List.of());
        }

        ProjectSnapshot previousSnapshot = previousState.snapshot();
        if (previousSnapshot == null || previousSnapshot.files().isEmpty()) {
            return new DirtyCheckResult(0, List.of(), List.of(), List.of());
        }

        Map<Path, String> previousHashes = new java.util.LinkedHashMap<>();
        previousSnapshot.files().forEach((p, fs) -> previousHashes.put(p, fs.contentHash()));

        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<String> newFiles = new ArrayList<>();

        // Dateien im aktuellen Snapshot pruefen
        for (Map.Entry<Path, FileSnapshot> entry : currentSnapshot.files().entrySet()) {
            Path relativePath = entry.getKey();
            String currentHash = entry.getValue().contentHash();
            
            if (!previousHashes.containsKey(relativePath)) {
                // Neue Datei (im aktuellen Snapshot aber nicht im vorherigen)
                newFiles.add(relativePath.toString());
            } else {
                String previousHash = previousHashes.get(relativePath);
                if (!currentHash.equals(previousHash)) {
                    // Datei geaendert
                    changed.add(relativePath.toString());
                }
            }
        }

        // Dateien die im vorherigen Snapshot waren aber nicht mehr im aktuellen
        for (Path path : previousHashes.keySet()) {
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

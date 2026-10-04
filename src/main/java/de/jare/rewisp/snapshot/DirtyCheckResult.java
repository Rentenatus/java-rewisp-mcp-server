/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.util.List;

/**
 * Ergebnis eines Dirty-Checks fuer ein Projekt.
 * Enthaelt Informationen ueber geaenderte, geloeschte und neue Dateien.
 */
public record DirtyCheckResult(
        int filesChecked,
        List<String> changedFiles,
        List<String> deletedFiles,
        List<String> newFiles
) {
    /**
     * Prueft, ob das Projekt dirty ist (Aenderungen vorhanden).
     */
    public boolean isDirty() {
        return !changedFiles.isEmpty() || !deletedFiles.isEmpty() || !newFiles.isEmpty();
    }
}

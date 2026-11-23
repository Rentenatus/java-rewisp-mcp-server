/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * View of the unwritten state of an active transaction (buffered contents and
 * marked deletions). Implemented by the {@code EditManager}; the {@link RewriteStateService}
 * lays it over the snapshot without knowing the edit layer.
 *
 * @author Janusch Rentenatus
 */
public interface TransactionOverlay {

    boolean isInTransaction();

    /** Gepufferte Dateiinhalte (absolute, kanonische Pfade). */
    Map<Path, String> getPendingChanges();

    /** Marked deletions (absolute, canonical paths). */
    Set<Path> getPendingDeletions();
}

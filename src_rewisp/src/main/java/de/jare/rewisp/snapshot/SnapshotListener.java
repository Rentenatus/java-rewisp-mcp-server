/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

/**
 * Observer of the snapshot capture (diagnostics/tests). The {@link SnapshotService} only knows this
 * interface; none is registered in production.
 *
 * @author Janusch Rentenatus
 */
@FunctionalInterface
public interface SnapshotListener {

    /** Without observers. */
    SnapshotListener NONE = (project, id, snapshot, attempt, raceDetected) -> { };

    /**
     * @param snapshot the captured snapshot, {@code null} when a race condition was detected
     * @param attempt attempt number (from 1)
     * @param raceDetected true when the capture was discarded and repeated
     */
    void onSnapshotAttempt(String projectName, SnapshotId id, ProjectSnapshot snapshot,
                           int attempt, boolean raceDetected);
}
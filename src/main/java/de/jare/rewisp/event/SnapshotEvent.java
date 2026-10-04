/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.event;

import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.SnapshotId;

/**
 * Event für Snapshot-Operationen.
 * Wird ausgelöst bei Snapshot-Erstellung, Race-Erkennung und ähnlichen Operationen.
 */
public class SnapshotEvent extends EditEvent {

    private final ProjectSnapshot snapshot;
    private final int attemptCount;
    private final boolean raceDetected;

    /**
     * Erstellt ein neues SnapshotEvent.
     *
     * @param source die Quelle des Events
     * @param projectId die Projekt-ID
     * @param snapshotId die Snapshot-ID
     * @param snapshot der Projektsnapshot
     * @param attemptCount Anzahl der Versuche (für Race-Erkennung)
     * @param raceDetected ob eine Race-Condition erkannt wurde
     */
    public SnapshotEvent(Object source, String projectId, SnapshotId snapshotId,
                       ProjectSnapshot snapshot, int attemptCount, boolean raceDetected) {
        super(source, projectId, snapshotId);
        this.snapshot = snapshot;
        this.attemptCount = attemptCount;
        this.raceDetected = raceDetected;
    }

    /**
     * @return der Projektsnapshot
     */
    public ProjectSnapshot getSnapshot() {
        return snapshot;
    }

    /**
     * @return Anzahl der Versuche
     */
    public int getAttemptCount() {
        return attemptCount;
    }

    /**
     * @return ob eine Race-Condition erkannt wurde
     */
    public boolean isRaceDetected() {
        return raceDetected;
    }

    @Override
    public Type getEventType() {
        if (raceDetected) {
            return Type.SNAPSHOT_RACE_DETECTED;
        }
        return snapshot != null ? Type.SNAPSHOT_COMPLETED : Type.SNAPSHOT_BEFORE;
    }

    @Override
    public String toString() {
        return "SnapshotEvent{" +
                "projectId='" + getProjectId() + '\'' +
                ", snapshotId=" + getSnapshotId() +
                ", attemptCount=" + attemptCount +
                ", raceDetected=" + raceDetected +
                ", timestamp=" + getTimestamp() +
                '}';
    }
}

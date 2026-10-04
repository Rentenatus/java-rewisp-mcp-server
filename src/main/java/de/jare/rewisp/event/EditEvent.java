/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.event;

import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.SnapshotId;
import de.jare.rewisp.rewrite.RewriteResult;

import java.nio.file.Path;
import java.util.EventObject;

/**
 * Basis-Event-Klasse für Edit-Operationen.
 * Nutzt EventObject als Basis für standardmäßiges Java Event-Handling.
 */
public abstract class EditEvent extends EventObject {

    private final String projectId;
    private final SnapshotId snapshotId;
    private final long timestamp;

    /**
     * Erstellt ein neues EditEvent.
     *
     * @param source die Quelle des Events
     * @param projectId die Projekt-ID
     * @param snapshotId die Snapshot-ID
     */
    public EditEvent(Object source, String projectId, SnapshotId snapshotId) {
        super(source);
        this.projectId = projectId;
        this.snapshotId = snapshotId;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * @return die Projekt-ID
     */
    public String getProjectId() {
        return projectId;
    }

    /**
     * @return die Snapshot-ID
     */
    public SnapshotId getSnapshotId() {
        return snapshotId;
    }

    /**
     * @return der Zeitstempel des Events
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Basis-Event-Typen für das Edit-System.
     */
    public enum Type {
        /** Vor einem Snapshot */
        SNAPSHOT_BEFORE,
        /** Nach einem Snapshot */
        SNAPSHOT_COMPLETED,
        /** Race-Condition erkannt */
        SNAPSHOT_RACE_DETECTED,
        /** Vor der Validierung */
        VALIDATION_BEFORE,
        /** Nach der Validierung */
        VALIDATION_COMPLETED,
        /** Validierung fehlgeschlagen */
        VALIDATION_FAILED,
        /** Vor dem Rewrite */
        REWRITE_BEFORE,
        /** Nach dem Rewrite */
        REWRITE_COMPLETED,
        /** Vor dem Commit */
        COMMIT_BEFORE,
        /** Nach dem Commit */
        COMMIT_COMPLETED,
        /** Commit fehlgeschlagen */
        COMMIT_FAILED
    }

    /**
     * @return der Event-Typ
     */
    public abstract Type getEventType();
}

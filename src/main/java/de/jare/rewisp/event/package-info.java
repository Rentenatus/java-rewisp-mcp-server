/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */

/**
 * Event-System für Edit-Operationen.
 *
 * <p>Dieses Paket enthält das zentrale Event-System für die Entkopplung von
 * Snapshot-, Validation- und Rewrite-Operationen in der drei-Schichten-Architektur.
 *
 * <h2>Architektur</h2>
 * <pre>
 * ┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
 * │   EventPublisher  │────▶│   EditEventBus    │────▶│   EventListener  │
 * │   (SnapshotService)│     │   (Zentral)      │     │   (Validator)    │
 * └─────────────────┘     └─────────────────┘     └─────────────────┘
 * </pre>
 *
 * <h2>Hauptkomponenten</h2>
 * <ul>
 *   <li>{@link EditEventBus} - Zentraler Event-Bus (Singleton)</li>
 *   <li>{@link EditEvent} - Basis-Event-Klasse mit Event-Typen</li>
 *   <li>{@link SnapshotEvent} - Events für Snapshot-Operationen</li>
 *   <li>{@link ValidationEvent} - Events für Validierungs-Operationen</li>
 * </ul>
 *
 * <h2>Event-Typen</h2>
 * <ul>
 *   <li>SNAPSHOT_BEFORE - Vor Snapshot-Erstellung</li>
 *   <li>SNAPSHOT_COMPLETED - Nach erfolgreicher Snapshot-Erstellung</li>
 *   <li>SNAPSHOT_RACE_DETECTED - Race-Condition erkannt</li>
 *   <li>VALIDATION_BEFORE - Vor Validierung</li>
 *   <li>VALIDATION_COMPLETED - Validierung erfolgreich</li>
 *   <li>VALIDATION_FAILED - Validierung fehlgeschlagen</li>
 *   <li>REWRITE_BEFORE - Vor Rewrite-Operation</li>
 *   <li>REWRITE_COMPLETED - Rewrite erfolgreich</li>
 *   <li>COMMIT_BEFORE - Vor Commit</li>
 *   <li>COMMIT_COMPLETED - Commit erfolgreich</li>
 *   <li>COMMIT_FAILED - Commit fehlgeschlagen</li>
 * </ul>
 *
 * <h2>Verwendung</h2>
 * <pre>{@code
 * // Event registrieren
 * EditEventBus.getInstance().addListener(
 *     EditEvent.Type.SNAPSHOT_COMPLETED,
 *     event -> System.out.println("Snapshot completed: " + event.getProjectId())
 * );
 *
 * // Event veröffentlichen
 * EditEventBus.getInstance().publish(new SnapshotEvent(
 *     this, projectId, snapshotId, snapshot, attemptCount, raceDetected
 * ));
 * }</pre>
 *
 * @since 1.1.1-REWRITE
 */
package de.jare.rewisp.event;

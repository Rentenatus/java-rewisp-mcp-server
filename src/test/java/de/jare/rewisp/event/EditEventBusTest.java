/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.event;

import de.jare.rewisp.snapshot.SnapshotId;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests für das EditEventBus Event-System.
 */
@Isolated
class EditEventBusTest {

    private EditEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = EditEventBus.getInstance();
        // Clear alle Listener vor jedem Test
        eventBus.clear();
    }

    @AfterEach
    void tearDown() {
        // Clear alle Listener nach jedem Test
        eventBus.clear();
    }

    @Test
    void testSingletonInstance() {
        EditEventBus instance1 = EditEventBus.getInstance();
        EditEventBus instance2 = EditEventBus.getInstance();
        
        assertSame(instance1, instance2, "EditEventBus sollte Singleton sein");
    }

    @Test
    void testAddAndGetListenerCount() {
        AtomicInteger callCount = new AtomicInteger(0);
        
        eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, 
                event -> callCount.incrementAndGet());
        
        assertEquals(1, eventBus.getListenerCount(EditEvent.Type.SNAPSHOT_COMPLETED));
        assertEquals(0, eventBus.getGlobalListenerCount());
    }

    @Test
    void testAddGlobalListener() {
        AtomicInteger callCount = new AtomicInteger(0);
        
        eventBus.addGlobalListener(event -> callCount.incrementAndGet());
        
        assertEquals(1, eventBus.getGlobalListenerCount());
    }

    @Test
    void testPublishEventToSpecificListener() {
        List<EditEvent> receivedEvents = new ArrayList<>();
        
        eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, 
                receivedEvents::add);
        
        SnapshotId snapshotId = new SnapshotId("test-project", 1, "hash123");
        // Für SNAPSHOT_COMPLETED brauchen wir ein nicht-null Snapshot
        ProjectSnapshot mockSnapshot = new ProjectSnapshot(snapshotId, "test-project", 
                new java.util.HashMap<>(), java.time.Instant.now());
        SnapshotEvent event = new SnapshotEvent(
                this, "test-project", snapshotId, mockSnapshot, 1, false
        );
        
        eventBus.publish(event);
        
        assertEquals(1, receivedEvents.size());
        assertEquals("test-project", receivedEvents.get(0).getProjectId());
        assertEquals(snapshotId, receivedEvents.get(0).getSnapshotId());
        assertEquals(EditEvent.Type.SNAPSHOT_COMPLETED, receivedEvents.get(0).getEventType());
    }

    @Test
    void testPublishEventToGlobalListener() {
        List<EditEvent> receivedEvents = new ArrayList<>();
        
        eventBus.addGlobalListener(receivedEvents::add);
        
        SnapshotId snapshotId = new SnapshotId("test-project", 1, "hash123");
        // Für SNAPSHOT_COMPLETED brauchen wir ein nicht-null Snapshot
        ProjectSnapshot mockSnapshot = new ProjectSnapshot(snapshotId, "test-project", 
                new java.util.HashMap<>(), java.time.Instant.now());
        SnapshotEvent event = new SnapshotEvent(
                this, "test-project", snapshotId, mockSnapshot, 1, false
        );
        
        eventBus.publish(event);
        
        assertEquals(1, receivedEvents.size());
        assertEquals(EditEvent.Type.SNAPSHOT_COMPLETED, receivedEvents.get(0).getEventType());
    }

    @Test
    void testPublishEventToBothSpecificAndGlobal() {
        List<EditEvent> specificEvents = new ArrayList<>();
        List<EditEvent> globalEvents = new ArrayList<>();
        
        eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, specificEvents::add);
        eventBus.addGlobalListener(globalEvents::add);
        
        SnapshotId snapshotId = new SnapshotId("test-project", 1, "hash123");
        // Für SNAPSHOT_COMPLETED brauchen wir ein nicht-null Snapshot
        ProjectSnapshot mockSnapshot = new ProjectSnapshot(snapshotId, "test-project", 
                new java.util.HashMap<>(), java.time.Instant.now());
        SnapshotEvent event = new SnapshotEvent(
                this, "test-project", snapshotId, mockSnapshot, 1, false
        );
        
        eventBus.publish(event);
        
        assertEquals(1, specificEvents.size());
        assertEquals(1, globalEvents.size());
    }

    @Test
    void testPublishRaceDetectedEvent() {
        List<EditEvent> raceEvents = new ArrayList<>();
        
        eventBus.addListener(EditEvent.Type.SNAPSHOT_RACE_DETECTED, raceEvents::add);
        
        SnapshotEvent raceEvent = new SnapshotEvent(
                this, "test-project", null, null, 2, true
        );
        
        eventBus.publish(raceEvent);
        
        assertEquals(1, raceEvents.size());
        assertEquals(EditEvent.Type.SNAPSHOT_RACE_DETECTED, raceEvents.get(0).getEventType());
        
        SnapshotEvent typedEvent = (SnapshotEvent) raceEvents.get(0);
        assertTrue(typedEvent.isRaceDetected());
        assertEquals(2, typedEvent.getAttemptCount());
    }

    @Test
    void testRemoveListener() {
        AtomicInteger callCount = new AtomicInteger(0);
        Consumer<EditEvent> listener = event -> callCount.incrementAndGet();
        
        eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, listener);
        assertEquals(1, eventBus.getListenerCount(EditEvent.Type.SNAPSHOT_COMPLETED));
        
        boolean removed = eventBus.removeListener(EditEvent.Type.SNAPSHOT_COMPLETED, listener);
        assertTrue(removed);
        assertEquals(0, eventBus.getListenerCount(EditEvent.Type.SNAPSHOT_COMPLETED));
    }

    @Test
    void testRemoveGlobalListener() {
        AtomicInteger callCount = new AtomicInteger(0);
        Consumer<EditEvent> listener = event -> callCount.incrementAndGet();
        
        eventBus.addGlobalListener(listener);
        assertEquals(1, eventBus.getGlobalListenerCount());
        
        boolean removed = eventBus.removeGlobalListener(listener);
        assertTrue(removed);
        assertEquals(0, eventBus.getGlobalListenerCount());
    }

    @Test
    void testClear() {
        eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, event -> {});
        eventBus.addGlobalListener(event -> {});
        
        assertEquals(1, eventBus.getListenerCount(EditEvent.Type.SNAPSHOT_COMPLETED));
        assertEquals(1, eventBus.getGlobalListenerCount());
        
        eventBus.clear();
        
        assertEquals(0, eventBus.getListenerCount(EditEvent.Type.SNAPSHOT_COMPLETED));
        assertEquals(0, eventBus.getGlobalListenerCount());
    }

    @Test
    void testListenerExceptionDoesNotPropagate() {
        // Listener, der eine Exception wirft
        eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, event -> {
            throw new RuntimeException("Test Exception");
        });
        
        // Listener, der normal funktioniert
        AtomicInteger successCount = new AtomicInteger(0);
        eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, event -> successCount.incrementAndGet());
        
        SnapshotId snapshotId = new SnapshotId("test-project", 1, "hash123");
        // Für SNAPSHOT_COMPLETED brauchen wir ein nicht-null Snapshot
        ProjectSnapshot mockSnapshot = new ProjectSnapshot(snapshotId, "test-project", 
                new java.util.HashMap<>(), java.time.Instant.now());
        SnapshotEvent event = new SnapshotEvent(
                this, "test-project", snapshotId, mockSnapshot, 1, false
        );
        
        // Sollte keine Exception werfen
        assertDoesNotThrow(() -> eventBus.publish(event));
        
        // Der zweite Listener sollte trotzdem aufgerufen worden sein
        assertEquals(1, successCount.get());
    }

    @Test
    void testMultipleListenersForSameType() {
        AtomicInteger listener1Count = new AtomicInteger(0);
        AtomicInteger listener2Count = new AtomicInteger(0);
        
        eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, event -> listener1Count.incrementAndGet());
        eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, event -> listener2Count.incrementAndGet());
        
        SnapshotId snapshotId = new SnapshotId("test-project", 1, "hash123");
        // Für SNAPSHOT_COMPLETED brauchen wir ein nicht-null Snapshot
        ProjectSnapshot mockSnapshot = new ProjectSnapshot(snapshotId, "test-project", 
                new java.util.HashMap<>(), java.time.Instant.now());
        SnapshotEvent event = new SnapshotEvent(
                this, "test-project", snapshotId, mockSnapshot, 1, false
        );
        
        eventBus.publish(event);
        
        assertEquals(1, listener1Count.get());
        assertEquals(1, listener2Count.get());
    }

    @Test
    void testGetRegisteredEventTypes() {
        eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, event -> {});
        eventBus.addListener(EditEvent.Type.VALIDATION_COMPLETED, event -> {});
        
        assertEquals(2, eventBus.getRegisteredEventTypes().size());
        assertTrue(eventBus.getRegisteredEventTypes().contains(EditEvent.Type.SNAPSHOT_COMPLETED));
        assertTrue(eventBus.getRegisteredEventTypes().contains(EditEvent.Type.VALIDATION_COMPLETED));
    }

    @Test
    void testNullEventThrowsException() {
        assertThrows(NullPointerException.class, () -> eventBus.publish(null));
    }

    @Test
    void testNullListenerThrowsException() {
        assertThrows(NullPointerException.class, () -> 
            eventBus.addListener(EditEvent.Type.SNAPSHOT_COMPLETED, null));
    }

    @Test
    void testNullEventTypeThrowsException() {
        assertThrows(NullPointerException.class, () -> 
            eventBus.addListener(null, event -> {}));
    }
}

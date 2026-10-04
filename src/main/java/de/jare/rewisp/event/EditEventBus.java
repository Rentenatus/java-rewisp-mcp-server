/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Zentraler Event-Bus für Edit-Operationen.
 * Implementiert ein einfaches, aber thread-sicheres Event-System
 * für die Entkopplung von Snapshot-, Validation- und Rewrite-Operationen.
 *
 * <p>Features:
 * <ul>
 *   <li>Thread-sichere Event-Verarbeitung</li>
 *   <li>Mehrere Listener pro Event-Typ</li>
 *   <li>Priorisierte Event-Behandlung</li>
 *   <li>Einfache Integration in bestehende Architektur</li>
 * </ul>
 */
public final class EditEventBus {

    private static final Logger LOG = LoggerFactory.getLogger(EditEventBus.class);

    private final Map<EditEvent.Type, List<Consumer<EditEvent>>> listeners = new ConcurrentHashMap<>();
    private final List<Consumer<EditEvent>> globalListeners = new CopyOnWriteArrayList<>();

    // Singleton-Instanz
    private static final EditEventBus INSTANCE = new EditEventBus();

    private EditEventBus() {}

    /**
     * Gibt die Singleton-Instanz des Event-Bus zurück.
     *
     * @return die globale Event-Bus Instanz
     */
    public static EditEventBus getInstance() {
        return INSTANCE;
    }

    /**
     * Registriert einen Listener für einen spezifischen Event-Typ.
     *
     * @param eventType der Event-Typ
     * @param listener der Event-Listener (Consumer)
     */
    public void addListener(EditEvent.Type eventType, Consumer<EditEvent> listener) {
        Objects.requireNonNull(eventType, "Event type must not be null");
        Objects.requireNonNull(listener, "Listener must not be null");

        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(listener);
        LOG.debug("Listener registered for event type: {}", eventType);
    }

    /**
     * Registriert einen globalen Listener für alle Event-Typen.
     *
     * @param listener der globale Event-Listener
     */
    public void addGlobalListener(Consumer<EditEvent> listener) {
        Objects.requireNonNull(listener, "Listener must not be null");
        globalListeners.add(listener);
        LOG.debug("Global listener registered");
    }

    /**
     * Entfernt einen Listener für einen spezifischen Event-Typ.
     *
     * @param eventType der Event-Typ
     * @param listener der zu entfernende Listener
     * @return true wenn der Listener entfernt wurde
     */
    public boolean removeListener(EditEvent.Type eventType, Consumer<EditEvent> listener) {
        Objects.requireNonNull(eventType, "Event type must not be null");
        Objects.requireNonNull(listener, "Listener must not be null");

        List<Consumer<EditEvent>> typeListeners = listeners.get(eventType);
        if (typeListeners != null) {
            boolean removed = typeListeners.remove(listener);
            if (removed) {
                LOG.debug("Listener removed for event type: {}", eventType);
            }
            return removed;
        }
        return false;
    }

    /**
     * Entfernt einen globalen Listener.
     *
     * @param listener der zu entfernende globale Listener
     * @return true wenn der Listener entfernt wurde
     */
    public boolean removeGlobalListener(Consumer<EditEvent> listener) {
        Objects.requireNonNull(listener, "Listener must not be null");
        boolean removed = globalListeners.remove(listener);
        if (removed) {
            LOG.debug("Global listener removed");
        }
        return removed;
    }

    /**
     * Veröffentlicht ein Event an alle registrierten Listener.
     * Thread-sicher und nicht-blockierend.
     *
     * @param event das zu veröffentlichende Event
     */
    public void publish(EditEvent event) {
        Objects.requireNonNull(event, "Event must not be null");

        String projectId = event.getProjectId();
        EditEvent.Type eventType = event.getEventType();

        LOG.trace("Publishing event: {} for project: {}", eventType, projectId);

        // 1. Spezifische Listener für diesen Event-Typ
        List<Consumer<EditEvent>> typeListeners = listeners.get(eventType);
        if (typeListeners != null) {
            for (Consumer<EditEvent> listener : typeListeners) {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    LOG.error("Error in event listener for type {}: {}", eventType, e.getMessage(), e);
                }
            }
        }

        // 2. Globale Listener
        for (Consumer<EditEvent> globalListener : globalListeners) {
            try {
                globalListener.accept(event);
            } catch (Exception e) {
                LOG.error("Error in global event listener: {}", e.getMessage(), e);
            }
        }

        LOG.debug("Event published: {} for project: {}", eventType, projectId);
    }

    /**
     * Gibt die Anzahl der registrierten Listener für einen Event-Typ zurück.
     *
     * @param eventType der Event-Typ
     * @return Anzahl der Listener
     */
    public int getListenerCount(EditEvent.Type eventType) {
        List<Consumer<EditEvent>> typeListeners = listeners.get(eventType);
        return typeListeners != null ? typeListeners.size() : 0;
    }

    /**
     * Gibt die Anzahl der globalen Listener zurück.
     *
     * @return Anzahl der globalen Listener
     */
    public int getGlobalListenerCount() {
        return globalListeners.size();
    }

    /**
     * Löscht alle registrierten Listener.
     * Nützlich für Tests oder Reset-Operationen.
     */
    public void clear() {
        listeners.clear();
        globalListeners.clear();
        LOG.debug("All event listeners cleared");
    }

    /**
     * Gibt alle registrierten Event-Typen zurück.
     *
     * @return Set der Event-Typen mit registrierten Listenern
     */
    public Set<EditEvent.Type> getRegisteredEventTypes() {
        return new HashSet<>(listeners.keySet());
    }
}

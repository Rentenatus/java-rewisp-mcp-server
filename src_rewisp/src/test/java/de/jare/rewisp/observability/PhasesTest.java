/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests fuer {@link Phases}: ohne Listener ein No-op, mit Listener wird jede beendete Phase gemeldet.
 *
 * @author Janusch Rentenatus
 */
class PhasesTest {

    private final Phases.Listener original = Phases.listener();

    @AfterEach
    void restoreOriginalListener() {
        Phases.setListener(original); // der Listener der Aufrufschicht darf andere Tests nicht verlieren
    }

    @Test
    void endWithoutListenerIsANoOp() {
        Phases.setListener(null);
        assertDoesNotThrow(() -> Phases.end("write", Phases.start()));
    }

    @Test
    void listenerReceivesPhaseNameAndStart() {
        List<String> seen = new ArrayList<>();
        Phases.setListener((phase, start) -> seen.add(phase));
        long start = Phases.start();
        Phases.end("write", start);
        Phases.end("spoon-rebuild", start);
        assertEquals(List.of("write", "spoon-rebuild"), seen);
    }
}
/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.base;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * AP-7.1/7.10: Phasen werden in der Trace festgehalten; ein riesiges Argument wird nicht komplett gerendert.
 *
 * @author Janusch Rentenatus
 */
class ToolCallGuardPhasesTest {

    @Test
    void phasesAreRecordedInTheTrace() {
        var trace = ToolCallGuard.begin("demo", Map.of());
        long t = ToolCallGuard.startPhase();
        ToolCallGuard.endPhase("snapshot-refresh", t);
        ToolCallGuard.endPhase("write", ToolCallGuard.startPhase());

        String phases = trace.phases().toString();
        ToolCallGuard.end(trace, false, null);

        assertTrue(phases.startsWith("snapshot-refresh=") && phases.contains(", write="), phases);
    }

    /** Das Backend meldet ueber observability.Phases; ToolCallGuard haengt sich beim Laden als Listener an. */
    @Test
    void backendPhasesReachTheTraceThroughTheListener() {
        var trace = ToolCallGuard.begin("demo", Map.of());
        de.jare.rewisp.observability.Phases.end("write", de.jare.rewisp.observability.Phases.start());
        String phases = trace.phases().toString();
        ToolCallGuard.end(trace, false, null);

        assertTrue(phases.startsWith("write="), phases);
    }

    @Test
    void endPhaseWithoutRunningCallDoesNothing() {
        assertDoesNotThrow(() -> ToolCallGuard.endPhase("write", ToolCallGuard.startPhase()));
    }

    @Test
    void hugeArgumentsAreTruncatedPerValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("name", "p");
        args.put("newBody", "x".repeat(5_000_000));

        var trace = ToolCallGuard.begin("demo", args);
        ToolCallGuard.end(trace, false, null);

        assertFalse(ToolCallGuard.current() != null, "trace is cleaned up");
    }
}

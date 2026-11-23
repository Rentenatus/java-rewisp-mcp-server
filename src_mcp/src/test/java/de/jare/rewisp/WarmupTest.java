/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Das Vorwaermen laeuft ohne Fehler und laesst sich abschalten.
 *
 * @author Janusch Rentenatus
 */
class WarmupTest {

    @Test
    void runCompletesWithoutErrors() {
        assertTrue(Warmup.run());
    }

    @Test
    void runWithAnEngineAlsoExecutesARecipe() {
        assertTrue(Warmup.run(de.jare.rewisp.testsupport.RewriteTestSupport.newEngine()));
    }

    @Test
    void startReturnsADaemonThreadAndCanBeDisabled() throws Exception {
        String old = System.getProperty("warmup");
        try {
            System.setProperty("warmup", "false");
            assertNull(Warmup.start());
            System.clearProperty("warmup");
            Thread t = Warmup.start();
            assertNotNull(t);
            assertTrue(t.isDaemon());
            t.join(120_000);
        } finally {
            if (old == null) System.clearProperty("warmup");
            else System.setProperty("warmup", old);
        }
    }
}

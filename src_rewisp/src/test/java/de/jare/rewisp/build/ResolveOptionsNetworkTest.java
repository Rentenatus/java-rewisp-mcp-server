/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 5.27: Der Netzwerkzugriff der Build-Aufloesung ist offline, bis {@code -Dbuild.networkAccess=true} gesetzt ist.
 *
 * @author Janusch Rentenatus
 */
class ResolveOptionsNetworkTest {

    @Test
    void gradleGetsOfflineUnlessNetworkAccessIsAllowed() {
        java.util.List<String> base = java.util.List.of("properties", "--quiet", "--no-daemon");
        ResolveOptions offline = new ResolveOptions(false, 120, 1000, false);
        ResolveOptions online = new ResolveOptions(false, 120, 1000, true);

        java.util.List<String> offlineArgs = GradleBuildResolver.withNetworkPolicy(base, offline);
        assertTrue(offlineArgs.contains("--offline"), offlineArgs.toString());
        assertEquals(base, GradleBuildResolver.withNetworkPolicy(base, online));
        // die Allowlist laesst --offline zu
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> GradleBuildResolver.validateArgs(offlineArgs));
    }

    @Test
    void defaultsToOfflineAndHonorsSystemProperty() {
        String old = System.getProperty(ResolveOptions.NETWORK_PROPERTY);
        try {
            System.clearProperty(ResolveOptions.NETWORK_PROPERTY);
            assertFalse(ResolveOptions.defaults().networkAccess());
            System.setProperty(ResolveOptions.NETWORK_PROPERTY, "true");
            assertTrue(ResolveOptions.defaults().networkAccess());
        } finally {
            if (old == null) System.clearProperty(ResolveOptions.NETWORK_PROPERTY);
            else System.setProperty(ResolveOptions.NETWORK_PROPERTY, old);
        }
    }
}

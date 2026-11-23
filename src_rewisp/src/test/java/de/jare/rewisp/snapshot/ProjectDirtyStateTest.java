/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * AP-2.13 / SN:S-15/S-16: Eine waehrend der Build-Aufloesung eingegangene Aenderung der
 * Build-Konfiguration darf durch das Zuruecksetzen nicht verloren gehen.
 *
 * @author Janusch Rentenatus
 */
class ProjectDirtyStateTest {

    @Test
    void clearBuildDirtyIfUnchanged_clearsWhenNothingChangedMeanwhile() {
        ProjectDirtyState state = new ProjectDirtyState();
        state.markBuildConfiguration();
        long observed = state.buildDirtyVersion();

        assertTrue(state.clearBuildDirtyIfUnchanged(observed));
        assertFalse(state.isBuildModelDirty());
        assertFalse(state.isClasspathDirty());
    }

    @Test
    void clearBuildDirtyIfUnchanged_keepsDirtyWhenPomChangedDuringResolution() {
        ProjectDirtyState state = new ProjectDirtyState();
        state.markBuildConfiguration();
        long observed = state.buildDirtyVersion();

        state.markBuildConfiguration(); // pom.xml aendert sich waehrend die Aufloesung laeuft

        assertFalse(state.clearBuildDirtyIfUnchanged(observed));
        assertTrue(state.isBuildModelDirty());
        assertTrue(state.isClasspathDirty());
    }
}

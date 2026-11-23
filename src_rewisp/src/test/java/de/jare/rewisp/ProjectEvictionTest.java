/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import de.jare.rewisp.model.ProjectEntry;

/**
 * AP-5.16: Das Spoon-Modell abgelaufener Projekte wird nach der Karenzzeit freigegeben und beim
 * naechsten Zugriff aus dem Rewrite-Zustand neu aufgebaut; der Eintrag bleibt bestehen.
 *
 * @author Janusch Rentenatus
 */
@Isolated
class ProjectEvictionTest {

    private static final Path SAMPLE = Paths.get("src/test/resources/no-lombok-sample").toAbsolutePath();

    private static ProjectEntry loadExpiring(ProjectManager manager, String name) throws Exception {
        return manager.load(SAMPLE.toString(), name, Instant.now().plus(1, ChronoUnit.SECONDS), false);
    }

    private static void waitForExpiry() throws InterruptedException {
        Thread.sleep(1200);
    }

    @Test
    void expiredProject_releasesModelAfterGraceAndRebuildsOnAccess() throws Exception {
        ProjectManager manager = new ProjectManager();
        manager.setEvictionGrace(Duration.ZERO);
        ProjectEntry loaded = loadExpiring(manager, "evict-me");
        int types = loaded.model().getAllTypes().size();
        assertTrue(types > 0);

        waitForExpiry();
        manager.markExpired();

        ProjectEntry evicted = manager.find("evict-me");
        assertNotNull(evicted, "the entry itself must stay registered");
        assertTrue(evicted.expired());
        assertNull(evicted.model(), "the Spoon model must be released");
        assertNotNull(manager.getProjectState("evict-me"), "the Rewrite state must stay");

        ProjectEntry rebuilt = manager.ensureSpoonModel("evict-me");
        assertNotNull(rebuilt.model());
        assertEquals(types, rebuilt.model().getAllTypes().size());
        manager.remove("evict-me");
    }

    @Test
    void graceKeepsTheModelWhileItIsStillWithinGrace() throws Exception {
        ProjectManager manager = new ProjectManager();
        manager.setEvictionGrace(Duration.ofMinutes(30));
        loadExpiring(manager, "keep-me");

        waitForExpiry();
        manager.markExpired();

        assertNotNull(manager.find("keep-me").model());
        assertTrue(manager.find("keep-me").expired());
        manager.remove("keep-me");
    }

    @Test
    void recentAccessDefersEviction() throws Exception {
        ProjectManager manager = new ProjectManager();
        manager.setEvictionGrace(Duration.ofMillis(1500));
        loadExpiring(manager, "used-me");
        waitForExpiry();
        manager.markExpired();
        manager.ensureSpoonModel("used-me"); // Zugriff nach Ablauf

        manager.markExpired();

        assertFalse(manager.find("used-me").model() == null, "a project in use must not be evicted");
        manager.remove("used-me");
    }
}

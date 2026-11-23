/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.model.ProjectLoadException;
import de.jare.rewisp.snapshot.WorkspaceRegistry;

/**
 * AP-3.3 / Bug #14: Scheitert das (Neu-)Laden eines Projekts, bleibt der bisherige Eintrag samt
 * Zustand erhalten - er wird erst nach erfolgreichem Aufbau des neuen ersetzt.
 *
 * @author Janusch Rentenatus
 */
class ProjectManagerFailedLoadTest {

    @TempDir
    Path root;

    private static Path project(Path base, String dir) throws Exception {
        Path src = base.resolve(dir).resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}\n");
        return base.resolve(dir);
    }

    @Test
    void failedLoadWithSameName_keepsPreviouslyLoadedProject() throws Exception {
        Path allowed = root.resolve("allowed");
        Path forbidden = root.resolve("forbidden");
        Path good = project(allowed, "proj");
        Path bad = project(forbidden, "proj"); // gleicher Projektname, aber nicht erlaubt

        ProjectManager mgr = new ProjectManager();
        mgr.setWorkspaceRegistry(new WorkspaceRegistry(List.of(allowed.toString())));
        ProjectEntry first = mgr.load(good.toString(), null, null, false, true);
        assertNotNull(mgr.getProjectState(first.name()).snapshot());

        ProjectLoadException e = assertThrows(ProjectLoadException.class,
                () -> mgr.load(bad.toString(), null, null, false, true));
        assertEquals("SECURITY_ERROR", e.getType());

        ProjectEntry still = mgr.find(first.name());
        assertNotNull(still, "Projekt darf durch das gescheiterte Laden nicht verschwinden");
        assertSame(first, still);
        assertTrue(mgr.getProjectState(first.name()).snapshot().files().size() >= 1);
        mgr.remove(first.name());
    }

    @Test
    void successfulReload_replacesEntryAndKeepsState() throws Exception {
        Path good = project(root, "proj2");
        ProjectManager mgr = new ProjectManager();
        ProjectEntry first = mgr.load(good.toString(), null, null, false, true);

        ProjectEntry second = mgr.reload(first.name());

        assertTrue(first != second);
        assertSame(second, mgr.find(first.name()));
        assertNotNull(mgr.getProjectState(first.name()).snapshot());
        mgr.remove(first.name());
    }
}

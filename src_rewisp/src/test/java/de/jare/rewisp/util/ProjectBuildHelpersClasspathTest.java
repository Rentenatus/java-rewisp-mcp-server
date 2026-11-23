/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import spoon.Launcher;

/**
 * AP-5.10: Der Klassenpfad des Originalprojekts wird an den Launcher des delomboked Verzeichnisses
 * angehaengt (ohne Duplikate, ohne bestehende Eintraege zu verlieren, nur existierende Eintraege).
 *
 * @author Janusch Rentenatus
 */
class ProjectBuildHelpersClasspathTest {

    @TempDir
    Path tempDir;

    @Test
    void addToClasspath_appendsWithoutDuplicatesAndSkipsMissingEntries() throws Exception {
        String a = Files.createFile(tempDir.resolve("a.jar")).toString();
        String b = Files.createFile(tempDir.resolve("b.jar")).toString();
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setSourceClasspath(new String[]{a});

        ProjectBuildHelpers.addToClasspath(launcher, List.of(b, a, tempDir.resolve("missing.jar").toString()));

        assertArrayEquals(new String[]{a, b}, launcher.getEnvironment().getSourceClasspath());
    }

    @Test
    void addToClasspath_ignoresNullAndEmpty() throws Exception {
        String a = Files.createFile(tempDir.resolve("a.jar")).toString();
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setSourceClasspath(new String[]{a});

        ProjectBuildHelpers.addToClasspath(launcher, null);
        ProjectBuildHelpers.addToClasspath(launcher, List.of());

        assertEquals(1, launcher.getEnvironment().getSourceClasspath().length);
    }
}

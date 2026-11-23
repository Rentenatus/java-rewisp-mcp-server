/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-2.7 / 2.8 (D4): Dateisystem-Events markieren nur die Build-Konfiguration; Quelldateien werden nicht verfolgt.
 *
 * @author Janusch Rentenatus
 */
class ChangeTrackerTest {

    @TempDir
    Path root;
    private ChangeTracker tracker;
    private ProjectDirtyState state;

    @BeforeEach
    void setup() throws Exception {
        tracker = new ChangeTracker();
        state = new ProjectDirtyState();
        tracker.registerProject("p", root, state);
        Files.createDirectories(root.resolve("src/main/java/a"));
    }

    @Test
    void buildFile_marksBuildModelAndClasspathDirty() {
        tracker.onFileChanged("p", root.resolve("pom.xml"), "ENTRY_MODIFY");

        assertTrue(state.isBuildModelDirty());
        assertTrue(state.isClasspathDirty());
        assertTrue(state.requiresFullSemanticRefresh());
    }

    @Test
    void gradleFilesInAModuleDirectoryCount() {
        tracker.onFileChanged("p", root.resolve("module-a/build.gradle.kts"), "ENTRY_CREATE");
        assertTrue(state.isBuildModelDirty());
    }

    @Test
    void sourceFiles_areNotTracked() {
        tracker.onFileChanged("p", root.resolve("src/main/java/a/A.java"), "ENTRY_MODIFY");
        tracker.onFileChanged("p", root.resolve("src/main/java/a/A.java"), "ENTRY_DELETE");

        assertFalse(state.isBuildModelDirty());
        assertEquals(0, state.buildDirtyVersion());
    }

    @Test
    void fileOutsideProjectRoot_isIgnored() {
        tracker.onFileChanged("p", root.getParent().resolve("pom.xml"), "ENTRY_MODIFY");
        assertFalse(state.isBuildModelDirty());
    }

    @Test
    void buildFileInIgnoredDirectory_isIgnored() {
        tracker.onFileChanged("p", root.resolve("target/classes/pom.xml"), "ENTRY_MODIFY");
        tracker.onFileChanged("p", root.resolve(".git/pom.xml"), "ENTRY_MODIFY");
        assertFalse(state.isBuildModelDirty());
    }

    @Test
    void unknownProject_isIgnored() {
        tracker.onFileChanged("other", root.resolve("pom.xml"), "ENTRY_MODIFY");
        assertFalse(state.isBuildModelDirty());
    }

    @Test
    void unregisteredProject_receivesNothing() {
        tracker.unregisterProject("p");
        tracker.onFileChanged("p", root.resolve("pom.xml"), "ENTRY_MODIFY");
        assertFalse(state.isBuildModelDirty());
    }

    @Test
    void everyBuildChangeIncrementsTheVersionSoAResetCanBeLimited() {
        tracker.onFileChanged("p", root.resolve("pom.xml"), "ENTRY_MODIFY");
        long seen = state.buildDirtyVersion();
        tracker.onFileChanged("p", root.resolve("pom.xml"), "ENTRY_MODIFY");

        assertFalse(state.clearBuildDirtyIfUnchanged(seen), "a newer change must not be lost");
        assertTrue(state.clearBuildDirtyIfUnchanged(state.buildDirtyVersion()));
        assertFalse(state.isBuildModelDirty());
    }
}

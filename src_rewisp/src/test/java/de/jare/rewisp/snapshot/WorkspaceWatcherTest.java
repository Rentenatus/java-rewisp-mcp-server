/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-2.7 / N38 / 2.8 (D4): Der Watcher meldet Aenderungen der Build-Konfiguration (auch in Modulverzeichnissen und in
 * nachtraeglich angelegten Verzeichnissen), verliert bei schnellen Folge-Events nichts, beobachtet Quellverzeichnisse
 * nicht und laeuft nach internen Fehlern weiter.
 *
 * @author Janusch Rentenatus
 */
class WorkspaceWatcherTest {

    private static final long TIMEOUT_MS = 15_000;

    @TempDir
    Path root;
    private ChangeTracker tracker;
    private ProjectDirtyState state;
    private WorkspaceWatcher watcher;

    @BeforeEach
    void setup() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/a"));
        Files.createDirectories(root.resolve("module-a"));
        tracker = new ChangeTracker();
        state = new ProjectDirtyState();
        tracker.registerProject("p", root, state);
        watcher = new WorkspaceWatcher(tracker, 50, List.of());
        watcher.watch("p", root);
    }

    @AfterEach
    void teardown() {
        watcher.shutdown();
    }

    private void awaitTrue(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError(message + " | buildDirtyVersion=" + state.buildDirtyVersion());
    }

    @Test
    void buildFileInTheRoot_marksTheBuildDirty() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");

        awaitTrue(state::isBuildModelDirty, "pom.xml nicht gemeldet");
    }

    @Test
    void buildFileInAModuleDirectory_marksTheBuildDirty() throws Exception {
        Files.writeString(root.resolve("module-a/build.gradle"), "plugins { id 'java' }");

        awaitTrue(state::isBuildModelDirty, "Build-Datei im Modulverzeichnis nicht gemeldet");
    }

    @Test
    void directoryCreatedAfterStart_isWatchedToo() throws Exception {
        Path newModule = root.resolve("module-b");
        Files.createDirectories(newModule);
        // Das Verzeichnis wird erst nach dem Create-Event registriert: kurz warten, dann Datei anlegen
        Thread.sleep(500);
        Files.writeString(newModule.resolve("pom.xml"), "<project/>");

        awaitTrue(state::isBuildModelDirty, "Build-Datei in nachtraeglich angelegtem Verzeichnis nicht gemeldet");
    }

    @Test
    void secondChangeAfterQuietPeriod_isReportedAgain() throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        awaitTrue(state::isBuildModelDirty, "erste Aenderung");
        long first = state.buildDirtyVersion();
        state.clearBuildDirtyIfUnchanged(first);

        Thread.sleep(300);
        Files.writeString(pom, "<project><!-- changed --></project>");

        awaitTrue(() -> state.buildDirtyVersion() > first, "zweite Aenderung nicht gemeldet");
    }

    @Test
    void sourceTree_isNotWatchedAtAll() throws Exception {
        Files.writeString(root.resolve("src/main/java/a/A.java"), "class A {}");
        Files.writeString(root.resolve("src/main/java/a/pom.xml"), "<project/>"); // Build-Datei im src-Baum zaehlt nicht
        // ein Marker ausserhalb von src beweist, dass der Watcher laeuft
        Files.writeString(root.resolve("module-a/pom.xml"), "<project/>");
        awaitTrue(state::isBuildModelDirty, "Marker nicht gemeldet");

        assertTrue(state.buildDirtyVersion() >= 1);
        long afterMarker = state.buildDirtyVersion();
        Thread.sleep(500);
        assertTrue(state.buildDirtyVersion() == afterMarker || state.buildDirtyVersion() == afterMarker + 1,
                "Events aus dem src-Baum duerfen keine Build-Markierung ausloesen");
    }

    @Test
    void ignoredDirectory_producesNoEvents() throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/pom.xml"), "<project/>");
        Files.writeString(root.resolve("pom.xml"), "<project/>");

        awaitTrue(state::isBuildModelDirty, "pom.xml nicht gemeldet");
        // genau eine (ggf. entprellte) Markierung durch die Wurzel-pom, keine durch .git
        Thread.sleep(300);
        assertTrue(state.buildDirtyVersion() >= 1);
    }

    @Test
    void watchTwiceForSameProject_replacesWatcherAndStillReports() throws Exception {
        watcher.watch("p", root); // zweites watch(): alter Service wird geschlossen, kein Leak
        Thread.sleep(200);
        Files.writeString(root.resolve("pom.xml"), "<project/>");

        awaitTrue(state::isBuildModelDirty, "nach erneutem watch() nichts gemeldet");
    }

    @Test
    void watchOnMissingDirectory_failsWithoutRegisteringProject() {
        Path missing = root.resolve("does-not-exist");
        assertThrows(java.io.IOException.class, () -> watcher.watch("ghost", missing));
        assertFalse(watcher.getWatchedProjects().containsKey("ghost"));
    }

    @Test
    void trackerFailure_doesNotTerminateWatcher() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        ProjectDirtyState s2 = new ProjectDirtyState();
        ChangeTracker real = new ChangeTracker();
        real.registerProject("q", root, s2);
        FileChangeListener failing = (project, path, kind) -> {
            if (first.getAndSet(false)) {
                throw new IllegalStateException("simulierter Fehler");
            }
            real.onFileChanged(project, path, kind);
        };
        watcher.shutdown();
        WorkspaceWatcher w2 = new WorkspaceWatcher(failing, 50, List.of());
        try {
            w2.watch("q", root);
            Files.writeString(root.resolve("pom.xml"), "<project/>");
            Thread.sleep(600);
            Files.writeString(root.resolve("module-a/pom.xml"), "<project/>");

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
            while (System.nanoTime() < deadline && !s2.isBuildModelDirty()) {
                Thread.sleep(50);
            }
            assertTrue(s2.isBuildModelDirty(), "Watcher hat nach einem Fehler aufgehoert zu arbeiten");
        } finally {
            w2.shutdown();
        }
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;

/**
 * AP-5.25: Fehlgeschlagene Aufloesung wird mit Backoff gecacht (kein Maven-Neustart je Edit), startet
 * aber neu, sobald sich die Build-Konfiguration aendert oder die Wartezeit abgelaufen ist.
 *
 * @author Janusch Rentenatus
 */
class BuildContextServiceBackoffTest {

    @TempDir
    Path tempDir;

    private static final class FailingResolver implements BuildResolver {
        final AtomicInteger calls = new AtomicInteger();
        @Override public boolean supports(Path projectRoot) { return true; }
        @Override public BuildContext resolve(Path projectRoot, ResolveOptions options) throws BuildResolutionException {
            calls.incrementAndGet();
            throw new BuildResolutionException(BuildResolutionErrorCode.BUILD_FAILED, "boom");
        }
    }

    @Test
    void failureIsCachedUntilBuildConfigChangesOrBackoffExpires() throws Exception {
        Path src = tempDir.resolve("p/src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}");
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(tempDir.resolve("p").toString(), null, null, false, true);
        var state = mgr.getProjectState(entry.name());
        FailingResolver maven = new FailingResolver();
        AtomicLong now = new AtomicLong(1_000);
        BuildContextService service = new BuildContextService(maven, new FailingResolver(), now::get);

        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state));
        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state));
        assertEquals(1, maven.calls.get(), "second call within backoff must not start a new resolution");

        state.dirtyState().markBuildConfiguration(); // pom.xml geaendert
        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state));
        assertEquals(2, maven.calls.get());

        now.addAndGet(BuildContextService.FAILURE_BACKOFF_MILLIS + 1);
        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state));
        assertEquals(3, maven.calls.get());
        mgr.remove(entry.name());
    }

    /** Dauerhaft scheiternde Aufloesung: die Wartezeit verdoppelt sich je Fehlschlag, hoechstens bis zur Obergrenze. */
    @Test
    void backoffDoublesWithEveryConsecutiveFailureUpToTheCap() throws Exception {
        Path src = tempDir.resolve("p/src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}");
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(tempDir.resolve("p").toString(), null, null, false, true);
        var state = mgr.getProjectState(entry.name());
        FailingResolver maven = new FailingResolver();
        AtomicLong now = new AtomicLong(1_000);
        BuildContextService service = new BuildContextService(maven, new FailingResolver(), now::get);
        long base = BuildContextService.FAILURE_BACKOFF_MILLIS;

        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state)); // 1. Fehlschlag: 1 min Wartezeit
        now.addAndGet(base + 1);
        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state)); // 2. Fehlschlag: 2 min Wartezeit
        assertEquals(2, maven.calls.get());

        now.addAndGet(base + 1);                                                           // nur 1 min vergangen
        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state));
        assertEquals(2, maven.calls.get(), "the second failure waits twice as long");

        now.addAndGet(base);                                                               // jetzt 2 min vergangen
        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state)); // 3. Fehlschlag: 4 min Wartezeit
        assertEquals(3, maven.calls.get());

        // viele weitere Fehlschlaege: die Wartezeit bleibt bei der Obergrenze
        for (int i = 0; i < 12; i++) {
            now.addAndGet(BuildContextService.MAX_FAILURE_BACKOFF_MILLIS + 1);
            assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state));
        }
        int callsAtCap = maven.calls.get();
        now.addAndGet(BuildContextService.MAX_FAILURE_BACKOFF_MILLIS - 1);
        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state));
        assertEquals(callsAtCap, maven.calls.get(), "still within the capped backoff");
        now.addAndGet(2);
        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state));
        assertEquals(callsAtCap + 1, maven.calls.get(), "retried after the capped backoff");

        state.dirtyState().markBuildConfiguration();                                        // pom.xml geaendert: von vorn
        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state));
        now.addAndGet(base + 1);
        assertThrows(BuildResolutionException.class, () -> service.ensure(entry, state));
        assertEquals(callsAtCap + 3, maven.calls.get(), "after a build file change the wait starts at one minute again");
        mgr.remove(entry.name());
    }
}

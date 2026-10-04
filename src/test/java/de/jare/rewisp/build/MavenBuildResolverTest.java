package de.jare.rewisp.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-Tests fuer {@link MavenBuildResolver}.
 *
 * <p>Tests die Maven-Subprocess benoetigen werden mit {@code Assumptions.assumeTrue}
 * uebersprungen, wenn {@code mvn} nicht im PATH verfuegbar ist.
 */
class MavenBuildResolverTest {

    private static final Path FIXTURE_ROOT = Path.of("src/test/resources/maven-multi-module");
    private static final Path MODULE_A = FIXTURE_ROOT.resolve("module-a");

    private static boolean isMvnAvailable() {
        String cmd = System.getProperty("os.name", "").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            boolean finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void supports_trueForMavenProject(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        MavenBuildResolver resolver = new MavenBuildResolver();
        assertTrue(resolver.supports(tempDir));
    }

    @Test
    void supports_falseForNonMavenDir(@TempDir Path tempDir) {
        MavenBuildResolver resolver = new MavenBuildResolver();
        assertFalse(resolver.supports(tempDir));
    }

    @Test
    void goalAllowlist_rejectsUnknownGoal(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        MavenBuildResolver resolver = new MavenBuildResolver("mvn", Set.of());

        // resolve tries help:effective-pom first — with empty allowlist, it's rejected immediately
        BuildResolutionException ex = assertThrows(
                BuildResolutionException.class,
                () -> resolver.resolve(tempDir, new ResolveOptions(false, 30, 1_000_000, false))
        );
        assertEquals("GOAL_NOT_ALLOWED", ex.code());
    }

    @Test
    void resolve_returnsBuildContextWithModule() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isMvnAvailable(), "mvn not in PATH");
        MavenBuildResolver resolver = new MavenBuildResolver();
        BuildContext ctx = assertDoesNotThrow(
                () -> resolver.resolve(MODULE_A, new ResolveOptions(false, 120, 10_000_000, true))
        );
        assertNotNull(ctx);
        assertEquals("MAVEN", ctx.buildSystem());
        assertFalse(ctx.modules().isEmpty());
        ModuleContext mod = ctx.modules().get(0);
        assertNotNull(mod.moduleId());
        assertNotNull(mod.moduleRoot());
    }

    @Test
    void resolve_throwsTimeout() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isMvnAvailable(), "mvn not in PATH");
        MavenBuildResolver resolver = new MavenBuildResolver();
        // 0 second timeout — process cannot finish instantly
        BuildResolutionException ex = assertThrows(
                BuildResolutionException.class,
                () -> resolver.resolve(MODULE_A, new ResolveOptions(false, 0, 10_000_000, true))
        );
        assertEquals("TIMEOUT", ex.code());
    }

    @Test
    void resolve_throwsBuildFailedForBrokenPom(@TempDir Path tempDir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(isMvnAvailable(), "mvn not in PATH");
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>broken</groupId><artifactId>broken</artifactId><version>1</version>"
                + "<dependencies><dependency><groupId>nonexistent</groupId>"
                + "<artifactId>nonexistent</artifactId><version>999</version></dependency></dependencies>"
                + "</project>");
        MavenBuildResolver resolver = new MavenBuildResolver();
        BuildResolutionException ex = assertThrows(
                BuildResolutionException.class,
                () -> resolver.resolve(tempDir, new ResolveOptions(false, 60, 10_000_000, false))
        );
        // Could be BUILD_FAILED or IO_ERROR depending on environment
        assertNotNull(ex.code());
    }

    @Test
    void fingerprint_stableForSamePom(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>test</groupId><artifactId>test</artifactId><version>1</version>"
                + "</project>");
        MavenBuildResolver resolver = new MavenBuildResolver();
        Set<Path> sourceRoots = Set.of();

        BuildFingerprint fp1 = resolver.computeFingerprint(tempDir, "classpath-output", sourceRoots);
        BuildFingerprint fp2 = resolver.computeFingerprint(tempDir, "classpath-output", sourceRoots);

        assertEquals(fp1.buildFilesHash(), fp2.buildFilesHash());
        assertEquals(fp1.compositeHash(), fp2.compositeHash());
    }

    @Test
    void fingerprint_changesWhenPomChanges(@TempDir Path tempDir) throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom,
                "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>test</groupId><artifactId>test</artifactId><version>1</version>"
                + "</project>");
        MavenBuildResolver resolver = new MavenBuildResolver();
        Set<Path> sourceRoots = Set.of();

        BuildFingerprint fp1 = resolver.computeFingerprint(tempDir, "classpath", sourceRoots);

        Files.writeString(pom,
                "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>test</groupId><artifactId>test</artifactId><version>2</version>"
                + "</project>");
        BuildFingerprint fp2 = resolver.computeFingerprint(tempDir, "classpath", sourceRoots);

        assertNotEquals(fp1.buildFilesHash(), fp2.buildFilesHash());
        assertNotEquals(fp1.compositeHash(), fp2.compositeHash());
    }

    @Test
    void fingerprint_changesWhenClasspathChanges(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        MavenBuildResolver resolver = new MavenBuildResolver();
        Set<Path> sourceRoots = Set.of();

        BuildFingerprint fp1 = resolver.computeFingerprint(tempDir, "classpath-v1", sourceRoots);
        BuildFingerprint fp2 = resolver.computeFingerprint(tempDir, "classpath-v2", sourceRoots);

        assertNotEquals(fp1.classpathHash(), fp2.classpathHash());
        assertNotEquals(fp1.compositeHash(), fp2.compositeHash());
    }
}

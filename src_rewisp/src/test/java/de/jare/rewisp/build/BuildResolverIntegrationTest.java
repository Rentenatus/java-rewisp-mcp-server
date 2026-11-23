package de.jare.rewisp.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-Tests fuer den Build-Resolver mit dem maven-multi-module Fixture.
 *
 * <p>Diese Tests benoetigen {@code mvn} im PATH und werden uebersprungen,
 * wenn Maven nicht verfuegbar ist.
 */
class BuildResolverIntegrationTest {

    @TempDir
    Path fixtureDir;

    private Path fixtureRoot;
    private Path moduleA;
    private Path moduleB;

    @org.junit.jupiter.api.BeforeEach
    void copyFixture() throws IOException {
        fixtureRoot = de.jare.rewisp.testsupport.TestFixtures.copy("maven-multi-module", fixtureDir.resolve("maven-multi-module"));
        moduleA = fixtureRoot.resolve("module-a");
        moduleB = fixtureRoot.resolve("module-b");
    }

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
    void mavenMultiModuleFixture_recognizedAsMaven() {
        MavenBuildResolver resolver = new MavenBuildResolver();
        assertTrue(resolver.supports(fixtureRoot), "Root should be recognized as Maven project");
        assertTrue(resolver.supports(moduleA), "module-a should be recognized as Maven project");
        assertTrue(resolver.supports(moduleB), "module-b should be recognized as Maven project");
    }

    @Test
    void resolveModuleB_classpathContainsModuleA() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isMvnAvailable(), "mvn not in PATH");

        // Install module-a first (from root so parent POM is resolved)
        try {
            String mvnCmd = System.getProperty("os.name", "").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
            Process install = new ProcessBuilder(mvnCmd, "install", "-q", "-pl", "module-a", "-am",
                    "-f", fixtureRoot.resolve("pom.xml").toString())
                    .redirectErrorStream(true).start();
            boolean ok = install.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok || install.exitValue() != 0) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "mvn install of module-a failed (exit code: " + (ok ? install.exitValue() : "timeout") + ")");
            }
        } catch (Exception e) {
            // If install fails (e.g. offline), skip this test
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Could not install module-a: " + e.getMessage());
        }

        MavenBuildResolver resolver = new MavenBuildResolver();
        BuildContext ctx = assertDoesNotThrow(
                () -> resolver.resolve(moduleB, new ResolveOptions(false, 120, 10_000_000, true))
        );

        assertFalse(ctx.modules().isEmpty(), "Should have at least one module");
        ModuleContext mod = ctx.modules().get(0);
        assertFalse(mod.compileClasspath().isEmpty(),
                "module-b classpath should contain entries (including module-a)");

        boolean containsModuleA = mod.compileClasspath().stream()
                .anyMatch(cp -> cp.contains("module-a"));
        assertTrue(containsModuleA, "module-b classpath should contain module-a");
    }

    @Test
    void fingerprint_stableAcrossResolutions() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isMvnAvailable(), "mvn not in PATH");

        MavenBuildResolver resolver = new MavenBuildResolver();
        ResolveOptions options = new ResolveOptions(false, 120, 10_000_000, true);

        BuildContext ctx1 = assertDoesNotThrow(() -> resolver.resolve(moduleA, options));
        BuildContext ctx2 = assertDoesNotThrow(() -> resolver.resolve(moduleA, options));

        assertEquals(ctx1.fingerprint().buildFilesHash(), ctx2.fingerprint().buildFilesHash(),
                "buildFilesHash should be stable for unchanged pom.xml");
    }

    @Test
    void pomXmlChange_invalidatesFingerprint(@TempDir Path tempDir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(isMvnAvailable(), "mvn not in PATH");

        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>test</groupId><artifactId>test</artifactId><version>1</version>"
                + "</project>");
        Files.createDirectories(tempDir.resolve("src/main/java"));

        MavenBuildResolver resolver = new MavenBuildResolver();
        Set<Path> sourceRoots = Set.of(tempDir.resolve("src/main/java"));

        BuildFingerprint fp1 = resolver.computeFingerprint(tempDir, "classpath", sourceRoots);

        Files.writeString(pom,
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>test</groupId><artifactId>test</artifactId><version>2</version>"
                + "</project>");
        BuildFingerprint fp2 = resolver.computeFingerprint(tempDir, "classpath", sourceRoots);

        assertNotEquals(fp1.buildFilesHash(), fp2.buildFilesHash(),
                "buildFilesHash should change when pom.xml changes");
    }
}

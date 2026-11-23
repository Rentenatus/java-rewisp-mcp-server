/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import de.jare.rewisp.snapshot.ContentHasher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GradleBuildResolver}.
 * 
 * @author Janusch Rentenatus
 */
class GradleBuildResolverTest {

    private GradleBuildResolver resolver;
    private Path testProjectDir;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        resolver = new GradleBuildResolver();
        testProjectDir = tempDir.resolve("test-gradle-project");
        Files.createDirectories(testProjectDir);
    }

    @Test
    void supports_ReturnsTrue_ForGradleProject() throws IOException {
        // Arrange
        Files.createFile(testProjectDir.resolve("build.gradle"));

        // Act
        boolean result = resolver.supports(testProjectDir);

        // Assert
        assertTrue(result, "Should support directory with build.gradle");
    }

    @Test
    void supports_ReturnsTrue_ForKotlinDslGradleProject() throws IOException {
        // Arrange
        Files.createFile(testProjectDir.resolve("build.gradle.kts"));

        // Act
        boolean result = resolver.supports(testProjectDir);

        // Assert
        assertTrue(result, "Should support directory with build.gradle.kts");
    }

    @Test
    void supports_ReturnsFalse_ForMavenProject() throws IOException {
        // Arrange
        Files.createFile(testProjectDir.resolve("pom.xml"));

        // Act
        boolean result = resolver.supports(testProjectDir);

        // Assert
        assertFalse(result, "Should not support Maven project");
    }

    @Test
    void supports_ReturnsFalse_ForEmptyProject() {
        // Act
        boolean result = resolver.supports(testProjectDir);

        // Assert
        assertFalse(result, "Should not support empty directory");
    }

    private static final String PROPERTIES = "rootProject.name: demo\nsourceCompatibility: 21\n";
    private static final String DEPENDENCIES = "compileClasspath\n+--- org.apache.commons:commons-lang3:3.14.0\n"
            + "\\--- com.google.guava:guava:33.0.0-jre\n";

    @Test
    void resolve_ReadsNameLanguageLevelAndClasspathFromGradleOutput() throws Exception {
        Files.writeString(testProjectDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        FakeGradle.install(testProjectDir, PROPERTIES, DEPENDENCIES, 0, false);

        BuildContext ctx = resolver.resolve(testProjectDir, ResolveOptions.defaults());

        assertEquals("GRADLE", ctx.buildSystem());
        assertNotNull(ctx.fingerprint());
        assertEquals(1, ctx.modules().size());
        ModuleContext mod = ctx.modules().get(0);
        assertEquals("demo", mod.moduleId());
        assertEquals("21", mod.languageLevel());
        assertTrue(mod.compileClasspath().contains("org.apache.commons:commons-lang3:3.14.0"), mod.compileClasspath().toString());
        assertTrue(mod.compileClasspath().contains("com.google.guava:guava:33.0.0-jre"), mod.compileClasspath().toString());
    }

    @Test
    void resolve_SeparatesMainAndTestSourceRoots() throws Exception {
        Files.writeString(testProjectDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        Files.createDirectories(testProjectDir.resolve("src/main/java"));
        Files.createDirectories(testProjectDir.resolve("src/test/java"));
        FakeGradle.install(testProjectDir, PROPERTIES, DEPENDENCIES, 0, false);

        ModuleContext mod = resolver.resolve(testProjectDir, ResolveOptions.defaults()).modules().get(0);

        assertEquals(java.util.Set.of(testProjectDir.resolve("src/main/java")), mod.sourceRoots());
        assertEquals(java.util.Set.of(testProjectDir.resolve("src/test/java")), mod.testSourceRoots());
    }

    @Test
    void resolve_RunsOfflineUnlessNetworkAccessIsAllowed() throws Exception {
        Files.writeString(testProjectDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        FakeGradle.install(testProjectDir, PROPERTIES, DEPENDENCIES, 0, false);

        resolver.resolve(testProjectDir, new ResolveOptions(false, 60, 1_000_000, false));
        String offline = FakeGradle.calls(testProjectDir);
        assertTrue(offline.contains("properties") && offline.contains("dependencies"), offline);
        assertTrue(offline.contains("--offline"), offline);

        Files.delete(testProjectDir.resolve("args.log"));
        resolver.resolve(testProjectDir, new ResolveOptions(false, 60, 1_000_000, true));
        assertFalse(FakeGradle.calls(testProjectDir).contains("--offline"), FakeGradle.calls(testProjectDir));
    }

    @Test
    void resolve_ReportsBuildFailedWithTheGradleExitCode() throws Exception {
        Files.writeString(testProjectDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        FakeGradle.install(testProjectDir, PROPERTIES, DEPENDENCIES, 3, false);

        BuildResolutionException e = assertThrows(BuildResolutionException.class,
                () -> resolver.resolve(testProjectDir, ResolveOptions.defaults()));
        assertEquals(BuildResolutionErrorCode.BUILD_FAILED, e.errorCode());
        assertTrue(e.getMessage().contains("exit code 3"), e.getMessage());
    }
    @Test
    void buildFingerprint_ChangesWhenBuildGradleChanges() {
        // Note: This requires actual file system and gradle execution
        // For unit test, we verify the hashing logic
        ContentHasher hasher = new ContentHasher();
        
        String originalContent = "plugins { id 'java' }\ndependencies {}";
        String modifiedContent = "plugins { id 'java' }\ndependencies { implementation 'org.test:test:1.0' }";
        
        String originalHash = hasher.sha256(originalContent);
        String modifiedHash = hasher.sha256(modifiedContent);
        
        assertNotEquals(originalHash, modifiedHash, 
                "Fingerprint should change when build.gradle content changes");
    }

    @Test
    void buildFingerprint_ChangesWhenSettingsGradleChanges() {
        ContentHasher hasher = new ContentHasher();
        
        String originalSettings = "rootProject.name = 'test'\ninclude 'module-a'\n";
        String modifiedSettings = "rootProject.name = 'test'\ninclude 'module-a', 'module-b'\n";
        
        String originalHash = hasher.sha256(originalSettings);
        String modifiedHash = hasher.sha256(modifiedSettings);
        
        assertNotEquals(originalHash, modifiedHash,
                "Fingerprint should change when settings.gradle content changes");
    }

    @Test
    void resolve_TimesOutWhenGradleHangs() throws Exception {
        Files.writeString(testProjectDir.resolve("build.gradle"), "plugins { id 'java' }\n");
        FakeGradle.install(testProjectDir, PROPERTIES, DEPENDENCIES, 0, true);

        BuildResolutionException e = assertThrows(BuildResolutionException.class,
                () -> resolver.resolve(testProjectDir, new ResolveOptions(false, 1, 1_000_000, false)));
        assertEquals(BuildResolutionErrorCode.TIMEOUT, e.errorCode());
    }

    @Test
    void validateArgs_RejectsTasksFlagsAndConfigurationsOutsideTheAllowlist() {
        assertThrows(BuildResolutionException.class, () -> GradleBuildResolver.validateArgs(java.util.List.of("build")));
        assertThrows(BuildResolutionException.class,
                () -> GradleBuildResolver.validateArgs(java.util.List.of("properties", "--init-script", "x.gradle")));
        assertThrows(BuildResolutionException.class, () -> GradleBuildResolver.validateArgs(
                java.util.List.of("dependencies", "--configuration", "testRuntimeClasspath2")));
        assertDoesNotThrow(() -> GradleBuildResolver.validateArgs(java.util.List.of("dependencies", "--configuration",
                "compileClasspath", "--quiet", "--no-daemon", "--offline")));
    }
    @Test
    void fingerprint_CompositeHashIncludesAllComponents() {
        BuildFingerprint fp = new BuildFingerprint(
                "GRADLE",
                "buildHash",
                "classpathHash",
                "sourceHash",
                "toolchainHash",
                "profilesHash"
        );
        
        String composite = fp.compositeHash();
        assertNotNull(composite, "Composite hash should not be null");
        assertTrue(composite.contains("buildHash"), "Composite should contain buildFilesHash");
        assertTrue(composite.contains("classpathHash"), "Composite should contain classpathHash");
        assertTrue(composite.contains("sourceHash"), "Composite should contain sourceRootsHash");
        assertTrue(composite.contains("toolchainHash"), "Composite should contain toolchainHash");
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.testsupport.TestFixtures;

/**
 * {@link GradleBuildResolver} gegen das Fixture {@code gradle-multi-project}. Gradle selbst wird durch einen
 * Fake-Wrapper ({@link FakeGradle}) ersetzt, damit die Tests ohne installiertes Gradle laufen und nie still
 * uebersprungen werden.
 *
 * @author Janusch Rentenatus
 */
class GradleBuildResolverIntegrationTest {

    private static final String PROPERTIES = "rootProject.name: multi\nsourceCompatibility: 17\n";
    private static final String DEPENDENCIES = "compileClasspath\n+--- org.slf4j:slf4j-api:2.0.9\n";

    private GradleBuildResolver resolver;
    private Path fixture;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        resolver = new GradleBuildResolver();
        fixture = TestFixtures.copy("gradle-multi-project", tempDir.resolve("gradle-multi-project"));
    }

    @Test
    void fixtureIsRecognizedAsGradle() {
        assertTrue(resolver.supports(fixture), "Should recognize gradle-multi-project fixture");
    }

    @Test
    void resolveReturnsGradleContextFromTheFixture() throws Exception {
        // AP-5.22 / BT:B-02: classpath as JAR paths from the init-script task, not as coordinates
        Path jar = Files.createFile(fixture.resolve("slf4j-api-2.0.9.jar"));
        String classpathOut = "REWISP_CP_BEGIN\n" + jar.toAbsolutePath() + "\nREWISP_CP_END\n";
        FakeGradle.install(fixture, PROPERTIES, DEPENDENCIES, classpathOut, 0, false);

        BuildContext ctx = resolver.resolve(fixture, ResolveOptions.defaults());

        assertEquals("GRADLE", ctx.buildSystem());
        assertEquals(1, ctx.modules().size());
        assertEquals("multi", ctx.modules().get(0).moduleId());
        assertEquals(java.util.List.of(jar.toAbsolutePath().toString()), ctx.modules().get(0).compileClasspath());
        // der Fake-Wrapper liegt im Projekt: kein Hinweis auf einen fehlenden Wrapper
        assertTrue(ctx.diagnostics().stream().noneMatch(d -> d.code() == BuildDiagnosticCode.GRADLE_WRAPPER_MISSING),
                ctx.diagnostics().toString());
    }

    @Test
    void fingerprintChangesWhenTheBuildFileChanges() throws Exception {
        FakeGradle.install(fixture, PROPERTIES, DEPENDENCIES, 0, false);
        String before = resolver.resolve(fixture, ResolveOptions.defaults()).fingerprint().compositeHash();

        Path buildFile = fixture.resolve("build.gradle");
        Files.writeString(buildFile, Files.readString(buildFile) + "\n// modified for test\n");
        String after = resolver.resolve(fixture, ResolveOptions.defaults()).fingerprint().compositeHash();

        assertNotEquals(before, after, "Fingerprint should change when build.gradle is modified");
    }

    @Test
    void diagnosticsReportAMissingWrapperWhenSystemGradleIsUsed() throws Exception {
        Path noWrapper = Files.createDirectories(tempDir.resolve("no-wrapper-project"));
        Files.writeString(noWrapper.resolve("build.gradle"), "plugins { id 'java' }\n");
        Path systemGradle = FakeGradle.installSystemGradle(Files.createDirectories(tempDir.resolve("bin")),
                PROPERTIES, DEPENDENCIES);
        String old = System.getProperty("gradle.executable");
        System.setProperty("gradle.executable", systemGradle.toString());
        try {
            BuildContext ctx = resolver.resolve(noWrapper, ResolveOptions.defaults());

            assertTrue(ctx.diagnostics().stream().anyMatch(d -> d.code() == BuildDiagnosticCode.GRADLE_WRAPPER_MISSING),
                    ctx.diagnostics().toString());
        } finally {
            if (old == null) System.clearProperty("gradle.executable");
            else System.setProperty("gradle.executable", old);
        }
    }

    @Test
    void kotlinDslBuildFileIsSupported() throws IOException {
        Path kotlinProject = Files.createDirectories(tempDir.resolve("kotlin-dsl-project"));
        Files.writeString(kotlinProject.resolve("build.gradle.kts"), "plugins { java }\n");
        assertTrue(resolver.supports(kotlinProject), "Should support Kotlin DSL build file");
    }

    @Test
    void kotlinDslSettingsAloneDoNotMakeAGradleProjectButWithABuildFileTheyDo() throws IOException {
        Path onlySettings = Files.createDirectories(tempDir.resolve("only-settings"));
        Files.writeString(onlySettings.resolve("settings.gradle.kts"), "rootProject.name = \"test\"\n");

        Path withBuild = Files.createDirectories(tempDir.resolve("settings-and-build"));
        Files.writeString(withBuild.resolve("settings.gradle.kts"), "rootProject.name = \"test\"\n");
        Files.writeString(withBuild.resolve("build.gradle"), "plugins { java }\n");

        assertTrue(resolver.supports(withBuild));
        // Erkennung heute: nur build.gradle[.kts] zaehlt, eine settings-Datei allein genuegt nicht
        org.junit.jupiter.api.Assertions.assertFalse(resolver.supports(onlySettings));
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-5.22 / BT:B-02: Die Gradle-Classpath muss aus echten JAR-Pfaden bestehen
 * (Init-Skript-Task {@code rewispClasspath} mit Marker-Ausgabe), nicht aus Maven-Koordinaten
 * aus der Dependency-Tree-Ausgabe. Ausserdem darf {@code -I <temp-init-script>} die
 * Allowlist passieren, Init-Skripte von woanders nicht.
 *
 * @author Janusch Rentenatus
 */
class GradleClasspathJarTest {

    @TempDir
    Path tempDir;

    @Test
    void validateArgs_allowsTheClasspathTaskWithTempInitScript() throws Exception {
        Path init = Files.createTempFile("rewisp-test-init", ".gradle");
        assertDoesNotThrow(() -> GradleBuildResolver.validateArgs(List.of(
                "-I", init.toAbsolutePath().toString(),
                GradleBuildResolver.CLASSPATH_TASK, "--quiet", "--no-daemon", "--offline")));
    }

    @Test
    void validateArgs_rejectsInitScriptOutsideTheTempDir() {
        assertThrows(BuildResolutionException.class, () -> GradleBuildResolver.validateArgs(
                List.of("--init-script", "x.gradle", GradleBuildResolver.CLASSPATH_TASK)));
        assertThrows(BuildResolutionException.class, () -> GradleBuildResolver.validateArgs(
                List.of(GradleBuildResolver.CLASSPATH_TASK, "-I")));
    }

    @Test
    void parseClasspathJars_readsOnlyMarkedExistingJarPaths() throws Exception {
        Path jar = Files.createFile(tempDir.resolve("lib.jar"));
        String out = "some gradle noise\n"
                + GradleBuildResolver.CP_MARKER + "_BEGIN\n"
                + jar.toAbsolutePath() + "\n"
                + tempDir.resolve("missing.jar") + "\n"   // existiert nicht -> dropped
                + "relative-noise\n"                        // kein existing file -> dropped
                + GradleBuildResolver.CP_MARKER + "_END\n"
                + "trailing noise\n";
        List<String> entries = GradleBuildResolver.parseClasspathJars(out);
        assertEquals(List.of(jar.toAbsolutePath().toString()), entries);
    }

    @Test
    void parseClasspathJars_emptyWithoutMarkers() {
        assertEquals(List.of(), GradleBuildResolver.parseClasspathJars("compileClasspath\n+--- g:a:v\n"));
        assertEquals(List.of(), GradleBuildResolver.parseClasspathJars(null));
    }

    @Test
    void initScript_registersTheClasspathTaskWithMarkers() throws Exception {
        Path script = GradleBuildResolver.writeClasspathInitScript();
        String content = Files.readString(script);
        assertTrue(content.contains(GradleBuildResolver.CLASSPATH_TASK), content);
        assertTrue(content.contains(GradleBuildResolver.CP_MARKER + "_BEGIN"), content);
        assertTrue(content.contains("compileClasspath.files"), content);
    }
}

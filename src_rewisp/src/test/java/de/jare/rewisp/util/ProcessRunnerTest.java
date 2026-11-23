/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-5.23: Zeitlimit greift auch bei schweigendem Prozess, Ausgabelimit, Exit-Code, Env-Bereinigung.
 *
 * @author Janusch Rentenatus
 */
class ProcessRunnerTest {

    @TempDir
    Path tempDir;

    private List<String> java(String source, String... args) throws Exception {
        Path file = tempDir.resolve("P.java");
        Files.writeString(file, source);
        List<String> cmd = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), file.toString()));
        cmd.addAll(List.of(args));
        return cmd;
    }

    @Test
    void capturesOutputAndExitCode() throws Exception {
        var r = ProcessRunner.run(java("public class P { public static void main(String[] a) {"
                + " System.out.println(\"hi\"); System.err.println(\"err\"); System.exit(3); } }"),
                tempDir, 60, 10_000, List.of());

        assertEquals(3, r.exitCode());
        assertTrue(r.output().contains("hi") && r.output().contains("err"), r.output());
        assertFalse(r.success());
    }

    @Test
    void timeoutKillsASilentProcess() throws Exception {
        long start = System.nanoTime();
        var r = ProcessRunner.run(java("public class P { public static void main(String[] a) throws Exception {"
                + " Thread.sleep(60000); } }"), tempDir, 3, 10_000, List.of());

        assertTrue(r.timedOut());
        assertTrue((System.nanoTime() - start) / 1_000_000_000L < 30, "must not wait for the process");
    }

    @Test
    void outputLimitStopsTheProcess() throws Exception {
        var r = ProcessRunner.run(java("public class P { public static void main(String[] a) {"
                + " while (true) System.out.println(\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"); } }"),
                tempDir, 60, 5_000, List.of());

        assertTrue(r.outputLimitExceeded());
        assertFalse(r.success());
    }

    @Test
    void removesEnvironmentVariablesAndClosesStdin() throws Exception {
        var r = ProcessRunner.run(java("public class P { public static void main(String[] a) throws Exception {"
                + " System.out.println(\"opts=\" + System.getenv(\"GRADLE_OPTS\"));"
                + " System.out.println(\"stdin=\" + System.in.read()); } }"),
                tempDir, 60, 10_000, List.of("GRADLE_OPTS"));

        assertTrue(r.output().contains("opts=null"), r.output());
        assertTrue(r.output().contains("stdin=-1"), r.output());
    }
}

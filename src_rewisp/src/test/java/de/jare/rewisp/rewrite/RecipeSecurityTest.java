/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.util.ProcessRunner;

/**
 * Sicherheitstests fuer das Starten von Kindprozessen: Argumente erreichen das Programm unveraendert (keine Shell),
 * und Umgebungsvariablen koennen dem Kindprozess vorenthalten werden. Die Allowlists von Maven und Gradle werden in
 * {@code MavenBuildResolverTest} und {@code GradleBuildResolverTest} geprueft.
 */
class RecipeSecurityTest {

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    /** Letzte Ausgabezeile (die JVM kann vorher "Picked up _JAVA_OPTIONS" melden). */
    private static String last(String output) {
        String[] lines = output.strip().split("\\R");
        return lines[lines.length - 1].trim();
    }

    private static Path source(Path dir, String body) throws Exception {
        Path file = dir.resolve("Probe.java");
        Files.writeString(file, "public class Probe { public static void main(String[] a) { " + body + " } }\n");
        return file;
    }

    @Test
    void shellMetacharactersInArgumentsArriveUnchanged(@TempDir Path dir) throws Exception {
        Path probe = source(dir, "System.out.print(String.join(\"#\", a));");
        List<String> command = List.of(java(), probe.toString(), "value; echo pwned", "value | more", "$(whoami)");

        ProcessRunner.Result r = ProcessRunner.run(command, dir, 60, 100_000, List.of());

        assertTrue(r.success(), r.output());
        assertEquals("value; echo pwned#value | more#$(whoami)", last(r.output()));
    }

    @Test
    void environmentVariablesAreWithheldFromTheChild(@TempDir Path dir) throws Exception {
        Path probe = source(dir, "System.out.print(System.getenv(\"PATH\") != null);");
        List<String> command = List.of(java(), probe.toString());

        assertEquals("true", last(ProcessRunner.run(command, dir, 60, 100_000, List.of()).output()));
        ProcessRunner.Result withheld = ProcessRunner.run(command, dir, 60, 100_000, List.of("PATH"));
        assertTrue(withheld.success(), withheld.output());
        assertEquals("false", last(withheld.output()));
    }

    @Test
    void outputLimitStopsAChattyProcess(@TempDir Path dir) throws Exception {
        Path probe = source(dir, "for (int i = 0; i < 100000; i++) System.out.println(\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\");");

        ProcessRunner.Result r = ProcessRunner.run(List.of(java(), probe.toString()), dir, 60, 2_000, List.of());

        assertTrue(r.outputLimitExceeded());
    }
}

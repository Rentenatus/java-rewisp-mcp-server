/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ein Fake-Gradle-Wrapper fuer Resolver-Tests ohne installiertes Gradle: {@code gradlew}/{@code gradlew.bat} im
 * Projektverzeichnis liefert fuer {@code properties} und {@code dependencies} die vorgegebene Ausgabe, schreibt
 * alle Aufrufe nach {@code args.log} und kann einen Exit-Code liefern oder haengen.
 *
 * @author Janusch Rentenatus
 */
public final class FakeGradle {

    private FakeGradle() {
    }

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** Legt den Wrapper samt {@code fake-properties.txt}/{@code fake-dependencies.txt} im Projektverzeichnis an. */
    public static void install(Path projectDir, String properties, String dependencies, int exitCode, boolean hang)
            throws IOException {
        write(projectDir, windows() ? "gradlew.bat" : "gradlew", properties, dependencies, exitCode, hang);
    }

    /** Wie {@link #install}, aber als systemweites {@code gradle} (ohne Wrapper im Projekt); liefert den Skriptpfad. */
    public static Path installSystemGradle(Path dir, String properties, String dependencies) throws IOException {
        String name = windows() ? "gradle.bat" : "gradle";
        write(dir, name, properties, dependencies, 0, false);
        return dir.resolve(name);
    }

    private static void write(Path projectDir, String scriptName, String properties, String dependencies,
                              int exitCode, boolean hang) throws IOException {
        Files.writeString(projectDir.resolve("fake-properties.txt"), properties);
        Files.writeString(projectDir.resolve("fake-dependencies.txt"), dependencies);
        if (windows()) {
            String delay = hang ? "ping -n 30 127.0.0.1 >nul\r\n" : "";
            Files.writeString(projectDir.resolve(scriptName), "@echo off\r\n"
                    + "echo %* >> \"%~dp0args.log\"\r\n"
                    + delay
                    + (exitCode != 0 ? "exit /b " + exitCode + "\r\n" : "")
                    + "if \"%1\"==\"properties\" type \"%~dp0fake-properties.txt\"\r\n"
                    + "if \"%1\"==\"dependencies\" type \"%~dp0fake-dependencies.txt\"\r\n");
        } else {
            Path script = projectDir.resolve(scriptName);
            Files.writeString(script, "#!/bin/sh\n"
                    + "echo \"$@\" >> \"$(dirname \"$0\")/args.log\"\n"
                    + (hang ? "sleep 30\n" : "")
                    + (exitCode != 0 ? "exit " + exitCode + "\n" : "")
                    + "case \"$1\" in\n"
                    + "  properties) cat \"$(dirname \"$0\")/fake-properties.txt\" ;;\n"
                    + "  dependencies) cat \"$(dirname \"$0\")/fake-dependencies.txt\" ;;\n"
                    + "esac\n");
            script.toFile().setExecutable(true);
        }
    }

    public static String calls(Path projectDir) throws IOException {
        Path log = projectDir.resolve("args.log");
        return Files.exists(log) ? Files.readString(log) : "";
    }
}

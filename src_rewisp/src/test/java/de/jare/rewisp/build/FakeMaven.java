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
 * Ein Fake-{@code mvn} fuer Resolver-Tests ohne Maven: ein Skript, das ein Java-Einzeldatei-Programm startet.
 * {@code help:effective-pom} schreibt eine kleine effektive POM, {@code dependency:build-classpath} schreibt
 * {@code libs/<Verzeichnisname>.jar}; fuer Verzeichnisse, deren Name in {@code failingDirs} steht, endet der Lauf
 * mit Exit-Code 1.
 *
 * @author Janusch Rentenatus
 */
final class FakeMaven {

    private FakeMaven() {
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** @return Pfad des Skripts, das als {@code mavenExecutable} uebergeben wird */
    static String install(Path dir, String failingDirs) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("FakeMvn.java"), """
                import java.nio.file.*;
                public class FakeMvn {
                    public static void main(String[] args) throws Exception {
                        java.util.List<String> failing = java.util.List.of(%s);
                        String cwd = Path.of("").toAbsolutePath().getFileName().toString();
                        for (String a : args) {
                            if (a.startsWith("-Doutput=")) {
                                Files.writeString(Path.of(a.substring("-Doutput=".length())),
                                        "<project><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
                            } else if (a.startsWith("-Dmdep.outputFile=")) {
                                if (failing.contains(cwd)) { System.err.println("cannot resolve " + cwd); System.exit(1); }
                                Files.writeString(Path.of(a.substring("-Dmdep.outputFile=".length())), "libs/" + cwd + ".jar");
                            }
                        }
                    }
                }
                """.formatted(failingDirs));
        if (windows()) {
            Path script = dir.resolve("fakemvn.cmd");
            Files.writeString(script, "@echo off\r\njava \"%~dp0FakeMvn.java\" %*\r\n");
            return script.toString();
        }
        Path script = dir.resolve("fakemvn");
        Files.writeString(script, "#!/bin/sh\nexec java \"$(dirname \"$0\")/FakeMvn.java\" \"$@\"\n");
        script.toFile().setExecutable(true);
        return script.toString();
    }
}

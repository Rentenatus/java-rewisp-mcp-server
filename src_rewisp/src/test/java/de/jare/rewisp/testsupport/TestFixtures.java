/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Kopiert Test-Fixtures in ein temporaeres Verzeichnis, damit Build-Laeufe (mvn/gradle) keine
 * {@code target/}- oder {@code build/}-Verzeichnisse im Quellbaum erzeugen.
 *
 * @author Janusch Rentenatus
 */
public final class TestFixtures {

    /** Wurzel der Fixtures im Quellbaum. */
    /**
     * Verzeichnis der Test-Ressourcen. Die Beispielprojekte liegen im Backend-Modul; beide Module setzen die
     * Systemeigenschaft {@code rewisp.test.resources} (siehe Surefire-Konfiguration der POMs). Ohne Eigenschaft gilt der
     * Pfad relativ zum Modul-Verzeichnis, damit Tests auch aus der IDE laufen.
     */
    public static final Path RESOURCES = Path.of(System.getProperty("rewisp.test.resources", "src/test/resources"));

    public static final Path ROOT = RESOURCES.resolve("fixtures");

    private TestFixtures() {
    }

    /** Kopiert {@code fixtures/<name>} nach {@code target} (ohne {@code target/}- und {@code build/}-Unterverzeichnisse). */
    public static Path copy(String name, Path target) throws IOException {
        Path source = ROOT.resolve(name);
        if (!Files.isDirectory(source)) {
            throw new IOException("Fixture not found: " + source);
        }
        try (var stream = Files.walk(source)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path rel = source.relativize(p);
                if (rel.startsWith("target") || rel.startsWith("build")
                        || rel.toString().contains("/target/") || rel.toString().contains("\\target\\")) {
                    continue;
                }
                Path dest = target.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
    }
}

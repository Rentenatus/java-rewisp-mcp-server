package de.jare.rewisp.build;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Strukturiertes Modell eines einzelnen Moduls.
 *
 * @param moduleId              Modul-Kennung (meist Verzeichnisname)
 * @param moduleRoot            Modul-Root-Pfad
 * @param sourceRoots           Source-Roots (main)
 * @param testSourceRoots       Source-Roots (test)
 * @param generatedSourceRoots  Generierte Source-Roots
 * @param compileClasspath      Compile-Classpath als Liste von JAR/Pfad-Strings
 * @param languageLevel         Java-Language-Level (z.B. "21")
 */
public record ModuleContext(
        String moduleId,
        Path moduleRoot,
        Set<Path> sourceRoots,
        Set<Path> testSourceRoots,
        Set<Path> generatedSourceRoots,
        List<String> compileClasspath,
        String languageLevel
) {}

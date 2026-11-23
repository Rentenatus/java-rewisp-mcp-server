package de.jare.rewisp.build;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Structured model of a single module.
 *
 * @param moduleId              module identifier (usually the directory name)
 * @param moduleRoot            module root path
 * @param sourceRoots           source roots (main)
 * @param testSourceRoots       source roots (test)
 * @param generatedSourceRoots  generated source roots
 * @param compileClasspath      compile classpath as a list of JAR/path strings
 * @param languageLevel         Java language level (e.g. "21")
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

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.analysis;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Spoon writes a copy {@code <Name>.java<Number>.java} of every {@code VirtualFile} into the temp directory and
 * only deletes it at JVM exit. On every model rebuild that would fill the temp folder
 * (practice-test finding). This class removes exactly those newly created copies of our source files.
 *
 * @author Janusch Rentenatus
 */
final class SpoonTempFiles {

    private static final String GLOB = "*.java*.java";

    private SpoonTempFiles() {
    }

    /** Names of the Spoon temp copies currently lying in {@code dir}. */
    static Set<String> snapshot(Path dir) {
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, GLOB)) {
            for (Path p : stream) {
                names.add(p.getFileName().toString());
            }
        } catch (IOException | RuntimeException e) {
            // Temp-Verzeichnis nicht lesbar: nichts aufzuraeumen
        }
        return names;
    }

    /**
     * Deletes the copies of the files {@code sourceFileNames}
     * (simple file names, e.g. {@code Foo.java}) that were newly created since {@code before}.
     *
     * @return number of deleted files
     */
    static int deleteNewSince(Path dir, Set<String> before, Set<String> sourceFileNames) {
        int deleted = 0;
        for (String name : snapshot(dir)) {
            if (before.contains(name) || !isCopyOf(name, sourceFileNames)) {
                continue;
            }
            try {
                if (Files.deleteIfExists(dir.resolve(name))) {
                    deleted++;
                }
            } catch (IOException | RuntimeException e) {
                // bleibt bis zum JVM-Ende liegen (deleteOnExit)
            }
        }
        return deleted;
    }

    private static boolean isCopyOf(String tempName, Set<String> sourceFileNames) {
        for (String source : sourceFileNames) {
            if (tempName.startsWith(source) && tempName.endsWith(".java")
                    && tempName.substring(source.length(), tempName.length() - ".java".length()).matches("\\d+")) {
                return true;
            }
        }
        return false;
    }
}
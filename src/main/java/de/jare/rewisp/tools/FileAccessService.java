/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jare.rewisp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import de.jare.rewisp.model.Fingerprint;

/**
 * Centralized file access service for REWRITE layer.
 * Provides controlled filesystem operations that follow the 3-layer architecture.
 * 
 * <p><strong>Architecture Rule:</strong> This service is part of the REWRITE layer
 * and may read from SPEICHER (filesystem). It must NOT access SPOON layer.
 * 
 * <p>SPOON tools must NOT use this service directly. They should only access
 * data through REWRITE layer services like SnapshotService.
 */
public final class FileAccessService {

    private FileAccessService() {
        // Utility class - prevent instantiation
    }

    /**
     * Checks if a file exists on the filesystem.
     * 
     * @param file the path to check
     * @return true if the file exists, false otherwise
     */
    public static boolean fileExists(Path file) {
        return Files.exists(file);
    }

    /**
     * Checks if a directory exists on the filesystem.
     * 
     * @param directory the path to check
     * @return true if the directory exists, false otherwise
     */
    public static boolean directoryExists(Path directory) {
        return Files.isDirectory(directory);
    }

    /**
     * Reads the content of a file as a string.
     * 
     * @param file the path to the file
     * @return the file content as string
     * @throws IOException if the file cannot be read
     */
    public static String readFileContent(Path file) throws IOException {
        return Files.readString(file);
    }

    /**
     * Gets the last modified time of a file.
     * 
     * @param file the path to the file
     * @return the last modified time in milliseconds
     * @throws IOException if the file cannot be accessed
     */
    public static long getLastModifiedTime(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toMillis();
    }

    /**
     * Gets the size of a file in bytes.
     * 
     * @param file the path to the file
     * @return the file size in bytes
     * @throws IOException if the file cannot be accessed
     */
    public static long getFileSize(Path file) throws IOException {
        return Files.size(file);
    }

    /**
     * Creates a fingerprint for a file (last modified time + size).
     * 
     * @param file the path to the file
     * @return a Fingerprint object, or null if file doesn't exist
     * @throws IOException if the file cannot be accessed
     */
    public static Fingerprint createFingerprint(Path file) throws IOException {
        if (!fileExists(file)) {
            return null;
        }
        return new Fingerprint(
            getLastModifiedTime(file),
            getFileSize(file)
        );
    }

    /**
     * Walks the file tree from a root directory and returns all Java source files.
     * 
     * @param root the root directory to walk
     * @return set of all Java source file paths
     * @throws IOException if the walk operation fails
     */
    public static Set<Path> findJavaSourceFiles(Path root) throws IOException {
        Set<Path> javaFiles = new HashSet<>();
        if (!directoryExists(root)) {
            return javaFiles;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".java"))
                  .forEach(javaFiles::add);
        }
        return javaFiles;
    }

    /**
     * Checks if two files have the same content by comparing their fingerprints.
     * 
     * @param file1 the first file
     * @param file2 the second file
     * @return true if fingerprints match, false otherwise
     * @throws IOException if files cannot be accessed
     */
    public static boolean fingerprintsMatch(Path file1, Path file2) throws IOException {
        Fingerprint fp1 = createFingerprint(file1);
        Fingerprint fp2 = createFingerprint(file2);
        if (fp1 == null && fp2 == null) return true;
        if (fp1 == null || fp2 == null) return false;
        return fp1.equals(fp2);
    }

    /**
     * Checks if a file has been modified since a given fingerprint was created.
     * 
     * @param file the file to check
     * @param storedFingerprint the stored fingerprint to compare against
     * @return true if file has been modified, false otherwise
     * @throws IOException if file cannot be accessed
     */
    public static boolean isFileModified(Path file, Fingerprint storedFingerprint) throws IOException {
        if (!fileExists(file)) {
            return true; // File deleted = modified
        }
        Fingerprint current = createFingerprint(file);
        return storedFingerprint == null || !storedFingerprint.equals(current);
    }

    /**
     * Gets all regular files in a directory (non-recursive).
     * 
     * @param directory the directory to scan
     * @return list of regular file paths
     * @throws IOException if directory cannot be accessed
     */
    public static List<Path> listFiles(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!directoryExists(directory)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                  .forEach(files::add);
        }
        return files;
    }

    /**
     * Gets all directories in a directory (non-recursive).
     * 
     * @param directory the directory to scan
     * @return list of directory paths
     * @throws IOException if directory cannot be accessed
     */
    public static List<Path> listDirectories(Path directory) throws IOException {
        List<Path> dirs = new ArrayList<>();
        if (!directoryExists(directory)) {
            return dirs;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isDirectory)
                  .forEach(dirs::add);
        }
        return dirs;
    }
}

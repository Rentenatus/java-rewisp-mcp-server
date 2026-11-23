/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import java.util.ArrayDeque;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility methods for path operations.
 *
 * <p>Centralizes path normalization, relativization, and build-file detection
 * across the codebase.
 */
public final class PathUtils {


    private PathUtils() {}

    /**
     * Normalizes a path, handling null input gracefully.
     *
     * @param path the path to normalize, may be null
     * @return the normalized path, or null if input was null
     */
    public static Path normalize(Path path) {
        return path != null ? path.normalize() : null;
    }

    /**
     * Canonical form of a path: {@code toRealPath()} (resolves e.g. Windows 8.3 short names),
     * for non-existing files fallback to {@code toAbsolutePath().normalize()}.
     * Keys for fingerprints, dirty and edited sets should be
     * stored in this form, so that short and long names do not pass each other by.
     */
    public static Path canonical(Path path) {
        if (path == null) return null;
        try {
            return path.toRealPath();
        } catch (IOException | RuntimeException e) {
            // Datei existiert (noch) nicht: den existierenden Vorfahren kanonisieren, Rest anhaengen
            Path abs = path.toAbsolutePath().normalize();
            ArrayDeque<Path> rest = new ArrayDeque<>();
            Path cur = abs;
            while (cur != null) {
                try {
                    Path real = cur.toRealPath();
                    for (Path part : rest) real = real.resolve(part);
                    return real;
                } catch (IOException | RuntimeException ignored) {
                    if (cur.getFileName() != null) rest.push(cur.getFileName());
                    cur = cur.getParent();
                }
            }
            return abs;
        }
    }

    /**
     * Normalizes all paths in a set.
     *
     * @param paths the set of paths to normalize, may be null
     * @return an unmodifiable set of normalized paths, or empty set if input was null
     */
    public static Set<Path> normalizeSet(Set<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return Set.of();
        }
        return paths.stream()
                .map(PathUtils::normalize)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Safely relativizes a child path against a parent path.
     *
     * @param child the child path
     * @param parent the parent path
     * @return the relativized path, or the child path if relativization fails
     */
    public static Path relativize(Path child, Path parent) {
        if (child == null) return null;
        if (parent == null) return child;
        try {
            return parent.relativize(child);
        } catch (IllegalArgumentException e) {
            return child;
        }
    }

    /**
     * Relativizes a child path against a project root, resolving real paths first
     * (8.3 short names, symlinks, case differences) to avoid mismatched-prefix bugs.
     *
     * <p>Windows short paths (e.g. {@code ADMINI~1} vs {@code Administrator}) lead to
     * broken relativize results if root and child originate from different sources.
     * This method canonicalizes both paths via {@link Path#toRealPath()} first.</p>
     *
     * @param root the project root
     * @param child the child path (must exist on disk)
     * @return the relative path, or the child path if relativization fails
     */
    public static Path relativizeFromRoot(Path root, Path child) {
        if (child == null) return null;
        if (root == null) return child;
        try {
            return root.toRealPath().relativize(canonical(child));
        } catch (IOException | IllegalArgumentException e) {
            try {
                return root.relativize(child);
            } catch (IllegalArgumentException e2) {
                return child;
            }
        }
    }

    /**
     * Resolves a relative path against a project root, canonicalized to the real path.
     *
     * <p>Spoon-derived absolute paths use Windows long names while project roots may
     * originate from short-name (8.3) paths. EditManager tracks edited files by exact
     * path, so write operations must use a consistent canonical form. This method
     * resolves via the root and canonicalizes the result.</p>
     *
     * @param root the project root (may be short-named)
     * @param relative the relative path (e.g. from a RewriteResult)
     * @return the canonical absolute path, or root.resolve(relative) as fallback
     */
    public static Path resolveReal(Path root, Path relative) {
        if (relative == null) return null;
        if (root == null) return relative.isAbsolute() ? relative : null;
        Path resolved = relative.isAbsolute() ? relative : root.resolve(relative);
        try {
            return resolved.toRealPath();
        } catch (IOException | IllegalArgumentException e) {
            return resolved;
        }
    }

    /**
     * Gets the file name as a string, handling null input gracefully.
     *
     * @param path the path
     * @return the file name string, or null
     */
    public static String getFileName(Path path) {
        return path != null && path.getFileName() != null
                ? path.getFileName().toString()
                : null;
    }

}

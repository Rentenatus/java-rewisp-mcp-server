/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.util.Locale;
import java.util.stream.Collectors;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Uniform rule for which directories are ignored for snapshot, change tracking and
 * watcher. Only the path segments <em>relative to the project root</em> are checked
 * (never substrings of the full path).
 *
 * <p>Build output directories ({@code target}, {@code build}, {@code out})
 * only count above a {@code src} segment: a Java package named
 * {@code build} under {@code src/main/java} is regular source code.</p>
 *
 * @author Janusch Rentenatus
 */
public final class IgnoreRules {

    /** Default list when no configuration is present. */
    public static final List<String> DEFAULT_DIRECTORIES = List.of(
            "target", "build", "out", ".git", ".gradle", ".idea", ".vscode", "node_modules");

    private static final Set<String> BUILD_OUTPUT = Set.of("target", "build", "out");
    private static final Set<String> SOURCE_DIR_NAMES = Set.of("java", "kotlin", "groovy", "scala");

    private static final IgnoreRules DEFAULTS = new IgnoreRules(DEFAULT_DIRECTORIES);

    private final Set<String> ignored;

    public IgnoreRules(Collection<String> ignoredDirectories) {
        // Gross-/Kleinschreibung egal: unter Windows/macOS sind "Target" und "target" dasselbe Verzeichnis
        this.ignored = ignoredDirectories == null ? Set.of()
                : ignoredDirectories.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    /** Rule with the default list. */
    public static IgnoreRules defaults() {
        return DEFAULTS;
    }

    /**
     * @param root project root (or base directory of the watcher)
     * @param path file or directory; if it is not under {@code root}, nothing is ignored
     * @return {@code true} when the path (relative to {@code root}) should be ignored
     */
    public boolean isIgnored(Path root, Path path) {
        if (root == null || path == null) {
            return false;
        }
        Path normRoot = root.toAbsolutePath().normalize();
        Path normPath = path.isAbsolute() ? path.normalize() : normRoot.resolve(path).normalize();
        if (!normPath.startsWith(normRoot)) {
            return false;
        }
        return isIgnoredRelative(normRoot.relativize(normPath), rootIsSourceTree(normRoot));
    }

    /**
     * If the project root itself lies in a source tree ({@code .../src/...} or folders
     * {@code java}/{@code kotlin}/{@code groovy}/{@code scala}), packages named
     * {@code build}/{@code target}/{@code out} below it are regular source code.
     */
    private static boolean rootIsSourceTree(Path normRoot) {
        // Nur die letzten drei Segmente betrachten: ein "src" weit oben im Dateisystem
        // (z.B. D:\src\mein-projekt) macht das Projekt nicht zum Quellbaum.
        int count = normRoot.getNameCount();
        for (int i = Math.max(0, count - 3); i < count; i++) {
            String name = normRoot.getName(i).toString();
            if (name.equals("src")) {
                return true;
            }
        }
        Path last = normRoot.getFileName();
        return last != null && SOURCE_DIR_NAMES.contains(last.toString());
    }

    /** Like {@link #isIgnored(Path, Path)}, but for an already relative path. */
    public boolean isIgnoredRelative(Path relative) {
        return isIgnoredRelative(relative, false);
    }

    private boolean isIgnoredRelative(Path relative, boolean startUnderSrc) {
        boolean underSrc = startUnderSrc;
        for (Path segment : relative) {
            String name = segment.toString().toLowerCase(Locale.ROOT);
            if (name.equals("src")) {
                underSrc = true;
            }
            if (ignored.contains(name) && !(underSrc && BUILD_OUTPUT.contains(name))) {
                return true;
            }
        }
        return false;
    }
}

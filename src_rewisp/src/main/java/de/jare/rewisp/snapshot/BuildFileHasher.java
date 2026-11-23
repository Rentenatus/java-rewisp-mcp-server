/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.util.TreeSet;

import java.util.Objects;
import java.util.Set;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import de.jare.rewisp.build.PomUtils;
import de.jare.rewisp.util.ProjectLayout;

/**
 * Hashes of the build files of a project ({@code pom.xml}, Gradle configuration, parent POM).
 * The snapshot only contains {@code .java} files; build changes are therefore
 * detected separately. Belongs in the rewrite/build layer, not in the reading tools.
 *
 * @author Janusch Rentenatus
 */
public final class BuildFileHasher {

    private static final String PARENT_KEY = "parent-pom";

    private BuildFileHasher() {}

    /** Directories that never contain relevant build files (build output, VCS, dependencies). */
    private static final Set<String> IGNORED_DIRS = Set.of("target", "build", "out", ".git", ".gradle", "node_modules");

    /**
     * Hash per build file (key: path relative to the project root with {@code /}, resp.
     * {@code parent-pom}); missing files are absent from the map. Covers the root build files
     * AND all module build files ({@code module-a/pom.xml}, {@code module-b/build.gradle}, ...),
     * so multi-module changes are detected too (AP-5.25 / BT:I-03).
     */
    public static Map<String, String> hash(Path projectRoot) {
        Map<String, String> out = new TreeMap<>();
        if (projectRoot == null) return out;
        ContentHasher hasher = new ContentHasher();
        List<String> names = new ArrayList<>(List.of(ProjectLayout.POM_FILE));
        names.addAll(ProjectLayout.GRADLE_CONFIG_FILES);
        for (String name : names) {
            put(out, hasher, name, projectRoot.resolve(name));
        }
        // Alle Modul-Build-Dateien im Baum (target/build/.git/... werden uebersprungen)
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(projectRoot) && IGNORED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (ProjectLayout.isBuildConfigFile(fileName)) {
                        String key = projectRoot.relativize(file).toString().replace('\\', '/');
                        put(out, hasher, key, file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // unlesbarer Baum: Root-Dateien bleiben gehasht
        }
        Path parent = PomUtils.resolveParentPomPath(projectRoot);
        if (parent != null) {
            put(out, hasher, PARENT_KEY, parent);
        }
        return out;
    }

    /** Keys whose hash differs (changed, new or removed), sorted. */
    public static List<String> changed(Map<String, String> baseline, Map<String, String> current) {
        Set<String> keys = new TreeSet<>(baseline.keySet());
        keys.addAll(current.keySet());
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            if (!Objects.equals(baseline.get(key), current.get(key))) changed.add(key);
        }
        return changed;
    }

    private static void put(Map<String, String> out, ContentHasher hasher, String key, Path file) {
        if (!Files.isRegularFile(file)) return;
        try {
            out.put(key, hasher.sha256(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            out.put(key, "unreadable");
        }
    }
}

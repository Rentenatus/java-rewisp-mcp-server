/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.BuildDetector;
import de.jare.rewisp.build.ModuleContext;
import de.jare.rewisp.model.Fingerprint;
import de.jare.rewisp.JdkClasspathResolver;

import spoon.Launcher;
import spoon.MavenLauncher;

/**
 * Utility methods for project build operations.
 *
 * <p>Centralizes launcher creation, classpath configuration, source directory
 * resolution, and module counting for Java projects.
 */
public final class ProjectBuildHelpers {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectBuildHelpers.class);
    private static final Object JAVA_HOME_LOCK = new Object();

    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern MAVEN_MODULE_PATTERN = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");
    /** include 'a', ":b" and include("a", "b") (Groovy and Kotlin DSL) including continuation lines. */
    private static final Pattern GRADLE_INCLUDE_STATEMENT = Pattern.compile(
            "(?m)^\\s*include\\s*\\(?((?:\\s*[\"'][^\"']+[\"']\\s*,?)+)\\s*\\)?");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
    private ProjectBuildHelpers() {}

    // ==================== LAUNCHER CREATION ====================

    /**
     * Creates a Spoon Launcher for the project, using MavenLauncher for Maven projects.
     *
     * <p>For Maven projects, handles symlink resolution for both project path and java.home
     * to avoid "invalid location for system libraries" errors on Windows with symlinked JDKs.
     *
     * @param projectDir the project directory
     * @param buildInfo the build detection info
     * @return a configured Spoon Launcher
     */
    public static Launcher createLauncher(Path projectDir, BuildDetector.BuildInfo buildInfo) {
        Launcher launcher;
        if (buildInfo.type() == BuildDetector.BuildType.MAVEN) {
            try {
                // Resolve symlinks in the project path and in java.home before
                // passing to MavenLauncher. On Windows (e.g. scoop-managed JDKs
                // where JAVA_HOME is a 'current' symlink), MavenLauncher's
                // system-library detection rejects symlinked paths with
                // "invalid location for system libraries". Resolving to the
                // real path avoids this.
                Path realProjectDir = resolveRealPath(projectDir);
                String realJavaHome = resolveRealJavaHome();
                // java.home ist JVM-global: gleichzeitige Loads duerfen es nicht gegenseitig ueberschreiben
                synchronized (JAVA_HOME_LOCK) {
                String savedJavaHome = realJavaHome != null
                        ? setAndSaveJavaHome(realJavaHome) : null;
                try {
                    launcher = new MavenLauncher(
                            realProjectDir.toAbsolutePath().toString(),
                            MavenLauncher.SOURCE_TYPE.APP_SOURCE);
                    launcher.getEnvironment().setCommentEnabled(true);
                    /* MavenLauncher resolves JDK classpath via ECJ bootclasspath detection;
                       calling applyJdkClasspath here would break it (see P36). */
                    LOG.info("Using MavenLauncher for {} (NoClasspath={}, JDK via ECJ bootclasspath)",
                            realProjectDir, launcher.getEnvironment().getNoClasspath());
                    return launcher;
                } finally {
                    if (savedJavaHome != null) {
                        System.setProperty("java.home", savedJavaHome);
                    }
                }
                }
            } catch (Exception e) {
                LOG.warn("MavenLauncher failed ({}), falling back to noclasspath", e.getMessage());
            }
        }

        launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setAutoImports(true);
        launcher.getEnvironment().setCommentEnabled(true);
        applyJdkClasspath(launcher);

        Path srcDir = buildInfo.type() == BuildDetector.BuildType.GRADLE ? null : findSourceDir(projectDir, buildInfo);
        if (buildInfo.type() == BuildDetector.BuildType.GRADLE) {
            // nur die Main-Quellen der Module (kein src/test, keine Build-Ausgabe), wie MavenLauncher APP_SOURCE
            addGradleSourceDirs(launcher, projectDir);
        } else if (srcDir != null && Files.isDirectory(srcDir)) {
            launcher.addInputResource(srcDir.toAbsolutePath().toString());
        } else {
            launcher.addInputResource(projectDir.toAbsolutePath().toString());
        }

        LOG.info("Using noclasspath Launcher for {} (src: {})", buildInfo.type(),
                srcDir != null ? srcDir : gradleSourceDirs(projectDir));
        return launcher;
    }

    // ==================== CLASSPATH CONFIGURATION ====================

    /**
     * Supplements the Spoon launcher classpath with JDK module JARs
     * (java.base, java.desktop, etc.) so JDK types like javax.swing are resolvable.
     * Failures are logged and silently skipped; the launcher continues in
     * noclasspath mode if no JDK JARs are available.
     *
     * @param launcher the launcher to configure
     */
    public static void applyJdkClasspath(Launcher launcher) {
        try {
            List<String> jdkCp = new JdkClasspathResolver().resolve();
            if (jdkCp.isEmpty()) {
                LOG.warn("JDK classpath empty; JDK types may be unresolved (noclasspath fallback)");
                return;
            }
            List<String> combined = new ArrayList<>();
            String[] existing = launcher.getEnvironment().getSourceClasspath();
            if (existing != null) {
                for (String s : existing) {
                    combined.add(s);
                }
            }
            combined.addAll(jdkCp);
            launcher.getEnvironment().setSourceClasspath(combined.toArray(new String[0]));
            LOG.info("JDK classpath applied: {} JARs ({} total classpath entries)",
                    jdkCp.size(), combined.size());
        } catch (Exception e) {
            LOG.warn("JDK classpath resolution failed ({}); continuing without it", e.getMessage());
        }
    }

    /**
     * Sets the source encoding of the launcher to ISO-8859-1 when the project contains files that are not
     * valid UTF-8 (otherwise Spoon fails on reading with MalformedInputException). The model is
     * anyway rebuilt later from the rewrite state (text, not bytes).
     */
    public static void applySourceEncoding(Launcher launcher, Path root) {
        if (root == null || !Files.isDirectory(root)) return;
        try (var stream = Files.walk(root)) {
            boolean latin1 = stream
                    .filter(p -> p.toString().endsWith(ProjectLayout.JAVA_EXTENSION) && Files.isRegularFile(p))
                    .anyMatch(p -> {
                        try {
                            return SourceFiles.charsetOf(Files.readAllBytes(p)) != StandardCharsets.UTF_8;
                        } catch (IOException e) {
                            return false;
                        }
                    });
            if (latin1) {
                launcher.getEnvironment().setEncoding(StandardCharsets.ISO_8859_1);
                LOG.info("Project {} contains non-UTF-8 sources: Spoon reads them as ISO-8859-1", root);
            }
        } catch (IOException e) {
            LOG.debug("Encoding scan of {} failed: {}", root, e.getMessage());
        }
    }

    /** Appends entries to the source classpath of the launcher (without duplicates). */
    public static void addToClasspath(Launcher launcher, Collection<String> entries) {
        if (entries == null || entries.isEmpty()) return;
        Set<String> combined = new LinkedHashSet<>();
        String[] existing = launcher.getEnvironment().getSourceClasspath();
        if (existing != null) {
            combined.addAll(List.of(existing));
        }
        for (String entry : entries) {
            // Spoon wirft bei nicht existierenden Eintraegen (InvalidClassPathException) und das Laden scheitert
            if (entry != null && Files.exists(Path.of(entry))) combined.add(entry);
        }
        launcher.getEnvironment().setSourceClasspath(combined.toArray(new String[0]));
    }

    /**
     * Enriches the launcher classpath from a resolved BuildContext.
     * Called after createLauncher and before buildModel.
     * For reload operations, a previously resolved BuildContext can supplement
     * the MavenLauncher classpath.
     *
     * @param launcher the launcher to configure
     * @param buildCtx the resolved build context
     */
    public static void enrichClasspathFromBuild(Launcher launcher, de.jare.rewisp.build.BuildContext buildCtx) {
        if (buildCtx == null || buildCtx.modules().isEmpty()) return;
        // Vereinigung der Classpaths aller Module (Reihenfolge erhalten, ohne Duplikate): ein Multi-Module-Projekt
        // braucht die Abhaengigkeiten jedes Moduls
        LinkedHashSet<String> union = new LinkedHashSet<>();
        for (ModuleContext module : buildCtx.modules()) {
            if (module.compileClasspath() != null) union.addAll(module.compileClasspath());
        }
        if (union.isEmpty()) return;
        ModuleContext modCtx = new ModuleContext(buildCtx.modules().get(0).moduleId(), buildCtx.modules().get(0).moduleRoot(),
                Set.of(), Set.of(), Set.of(), new ArrayList<>(union),
                buildCtx.modules().get(0).languageLevel());

        String[] existingCp = launcher.getEnvironment().getSourceClasspath();
        List<String> combined = new ArrayList<>();
        if (existingCp != null) {
            for (String s : existingCp) {
                combined.add(s);
            }
        }
        combined.addAll(modCtx.compileClasspath());
        launcher.getEnvironment().setSourceClasspath(combined.toArray(new String[0]));
        LOG.info("Classpath enriched from BuildContext: +{} entries ({} total)",
                modCtx.compileClasspath().size(), combined.size());
    }

    // ==================== SOURCE DIRECTORY RESOLUTION ====================

    /**
     * Finds the source directory for the project based on build type.
     *
     * @param projectDir the project directory
     * @param buildInfo the build detection info
     * @return the source directory, or projectDir if no specific source dir found
     */
    public static Path findSourceDir(Path projectDir, BuildDetector.BuildInfo buildInfo) {
        if (buildInfo.type() == BuildDetector.BuildType.MAVEN) {
            Path src = projectDir.resolve(ProjectLayout.MAIN_JAVA_DIR);
            if (Files.isDirectory(src)) return src;
        }
        Path src = projectDir.resolve("src");
        if (Files.isDirectory(src)) return src;
        return projectDir;
    }

    /**
     * Adds Gradle source directories to the launcher.
     *
     * @param launcher the launcher to configure
     * @param projectDir the project directory
     */
    public static void addGradleSourceDirs(Launcher launcher, Path projectDir) {
        List<Path> dirs = gradleSourceDirs(projectDir);
        for (Path p : dirs) {
            launcher.addInputResource(p.toAbsolutePath().toString());
        }
        if (dirs.isEmpty()) {
            launcher.addInputResource(projectDir.toAbsolutePath().toString());
        }
    }

    /** Directories that are never entered when searching for Gradle modules. */
    private static final Set<String> GRADLE_SKIPPED_DIRS =
            Set.of("build", "buildSrc", "src", "target", "out", "node_modules");

    /**
     * The Java main source directories of a Gradle project: {@code src/main/java} of the root project and of every
     * submodule (directory with its own build file, up to depth 3). Test sources, {@code buildSrc} and
     * build outputs are not included.
     */
    public static List<Path> gradleSourceDirs(Path projectDir) {
        List<Path> result = new ArrayList<>();
        collectGradleSourceDirs(projectDir, projectDir, 0, result);
        return result;
    }

    private static void collectGradleSourceDirs(Path root, Path dir, int depth, List<Path> out) {
        boolean hasBuildFile = ProjectLayout.GRADLE_BUILD_FILES.stream().anyMatch(f -> Files.isRegularFile(dir.resolve(f)));
        Path main = dir.resolve(ProjectLayout.MAIN_JAVA_DIR);
        if ((dir.equals(root) || hasBuildFile) && Files.isDirectory(main)) {
            out.add(main);
        }
        if (depth >= 3) return;
        try (var children = Files.list(dir)) {
            for (Path child : (Iterable<Path>) children.sorted()::iterator) {
                String name = child.getFileName().toString();
                if (Files.isDirectory(child) && !name.startsWith(".") && !GRADLE_SKIPPED_DIRS.contains(name)) {
                    collectGradleSourceDirs(root, child, depth + 1, out);
                }
            }
        } catch (IOException e) {
            LOG.debug("Could not list {}: {}", dir, e.getMessage());
        }
    }

    // ==================== MODULE COUNTING ====================

    /**
     * Counts the number of modules in a multi-module build.
     * Maven: counts &lt;module&gt; entries in pom.xml.
     * Gradle: counts include directives in settings.gradle.
     * Returns 1 for single-module projects (the project itself).
     *
     * @param projectDir the project directory
     * @param buildInfo the build detection info
     * @return the number of modules, minimum 1
     */
    public static int countModules(Path projectDir, BuildDetector.BuildInfo buildInfo) {
        if (buildInfo.type() == BuildDetector.BuildType.MAVEN) {
            return countMavenModules(projectDir);
        } else if (buildInfo.type() == BuildDetector.BuildType.GRADLE) {
            return countGradleModules(projectDir);
        }
        return 1;
    }

    /**
     * Counts Maven modules by parsing pom.xml for &lt;module&gt; elements.
     *
     * @param projectDir the project directory
     * @return the number of modules, minimum 1
     */
    private static int countMavenModules(Path projectDir) {
        Path pom = projectDir.resolve(ProjectLayout.POM_FILE);
        if (Files.exists(pom)) {
            try {
                return Math.max(mavenModuleNames(Files.readString(pom)).size(), 1);
            } catch (Exception e) {
                return 1;
            }
        }
        return 1;
    }

    /** Module names from a pom.xml: comments are ignored, profiles counted, duplicates only once. */
    public static Set<String> mavenModuleNames(String pomContent) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = MAVEN_MODULE_PATTERN.matcher(XML_COMMENT.matcher(pomContent).replaceAll(""));
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /**
     * Counts Gradle modules by parsing settings.gradle for include directives.
     *
     * @param projectDir the project directory
     * @return the number of modules, minimum 1
     */
    private static int countGradleModules(Path projectDir) {
        for (String settingsFile : ProjectLayout.GRADLE_SETTINGS_FILES) {
            Path settings = projectDir.resolve(settingsFile);
            if (Files.exists(settings)) {
                try {
                    return Math.max(gradleModuleNames(Files.readString(settings)).size(), 1);
                } catch (Exception e) {
                    return 1;
                }
            }
        }
        return 1;
    }

    /** Project names from include statements (several names per statement, parentheses, ":" prefix, comments). */
    static Set<String> gradleModuleNames(String settingsContent) {
        String noComments = settingsContent.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        Set<String> names = new LinkedHashSet<>();
        Matcher statement = GRADLE_INCLUDE_STATEMENT.matcher(noComments);
        while (statement.find()) {
            Matcher q = QUOTED.matcher(statement.group(1));
            while (q.find()) names.add(q.group(1).replaceFirst("^:", ""));
        }
        return names;
    }
    // ==================== FINGERPRINT OPERATIONS ====================

    /**
     * Builds a fingerprint map for all .java files under the given source root.
     *
     * @param sourceRoot the source root directory
     * @return a map of normalized paths to fingerprints
     */
    public static Map<Path, Fingerprint> buildFingerprints(Path sourceRoot) {
        Map<Path, Fingerprint> fingerprints = new HashMap<>();
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) return fingerprints;
        try (var stream = Files.walk(sourceRoot)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(ProjectLayout.JAVA_EXTENSION))
                  .filter(p -> !de.jare.rewisp.snapshot.IgnoreRules.defaults().isIgnored(sourceRoot, p))
                  .forEach(p -> {
                      try {
                          long mod = Files.getLastModifiedTime(p).toMillis();
                          long size = Files.size(p);
                          fingerprints.put(PathUtils.canonical(p), new Fingerprint(mod, size));
                      } catch (Exception e) {
                          LOG.warn("Could not fingerprint {}: {}", p, e.getMessage());
                      }
                  });
        } catch (Exception e) {
            LOG.warn("Could not walk source root for fingerprints: {}", e.getMessage());
        }
        return fingerprints;
    }

    // ==================== NAME RESOLUTION ====================

    /**
     * Derives a project name from the project directory name.
     *
     * @param projectDir the project directory
     * @param source the original source path/URL
     * @return the derived project name
     */
    public static String deriveName(Path projectDir, String source) {
        return projectDir != null && projectDir.getFileName() != null
                ? projectDir.getFileName().toString()
                : "unknown";
    }

    // ==================== SYMLINK RESOLUTION ====================

    /**
     * Resolves a path to its real (non-symlink) form.
     * Returns the original path if resolution fails.
     *
     * @param path the path to resolve
     * @return the real path, or the original path if resolution fails
     */
    public static Path resolveRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (Exception e) {
            return path;
        }
    }

    /**
     * Returns the real (non-symlink) java.home path, or null if it cannot be resolved.
     * On Windows scoop JDKs, java.home is typically a 'current' symlink that
     * MavenLauncher rejects.
     *
     * @return the real java.home path, or null
     */
    public static String resolveRealJavaHome() {
        String javaHome = System.getProperty("java.home", "");
        if (javaHome.isEmpty()) return null;
        try {
            Path real = Path.of(javaHome).toRealPath();
            return real.toAbsolutePath().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Temporarily sets java.home to the real path and returns the previous value
     * so the caller can restore it in a finally block.
     *
     * @param realJavaHome the real java.home path to set
     * @return the previous java.home value, or null if not set
     */
    public static String setAndSaveJavaHome(String realJavaHome) {
        String old = System.getProperty("java.home");
        System.setProperty("java.home", realJavaHome);
        return old;
    }
}

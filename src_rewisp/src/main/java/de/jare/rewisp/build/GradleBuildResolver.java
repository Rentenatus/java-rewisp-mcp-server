/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import de.jare.rewisp.util.ProjectLayout;

import de.jare.rewisp.snapshot.ContentHasher;
import de.jare.rewisp.build.BuildResolutionErrorCode;
import de.jare.rewisp.build.BuildDiagnosticCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gradle Build Resolver implementation that resolves Gradle project structure,
 * source roots, and compile classpath by executing Gradle wrapper subprocesses.
 * 
 * <p>Uses {@code ./gradlew properties} and {@code ./gradlew dependencies} to extract
 * project metadata and dependencies. Supports both Groovy and Kotlin DSL.
 * 
 * @author Janusch Rentenatus
 */
public final class GradleBuildResolver implements BuildResolver {

    private static final String[] GRADLE_BUILD_FILES = {
            "build.gradle", "build.gradle.kts"
    };

    private static final String[] GRADLE_SETTINGS_FILES = {
            "settings.gradle", "settings.gradle.kts"
    };

    private static final String[] GRADLE_CONFIG_FILES = {
            "gradle.properties", "gradle/libs.versions.toml"
    };

    private final ContentHasher hasher = new ContentHasher();

    @Override
    public boolean supports(Path projectRoot) {
        for (String file : GRADLE_BUILD_FILES) {
            if (projectRoot.resolve(file).toFile().exists()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public BuildContext resolve(Path projectRoot, ResolveOptions options)
            throws BuildResolutionException {

        // 1. Find Gradle executable
        String gradleExecutable = findGradleExecutable(projectRoot);

        // 2. Get properties output for project structure
        String propertiesOutput = runGradle(projectRoot, gradleExecutable,
                withNetworkPolicy(List.of("properties", "--quiet", "--no-daemon"), options),
                options.timeoutSeconds(), options.maxOutputBytes());

        // 3. Classpath as real JAR paths via init-script task (BT:B-02: the dependency tree only
        // yields Maven coordinates, which are not valid classpath entries)
        Path initScript = writeClasspathInitScript();
        String classpathOutput = runGradle(projectRoot, gradleExecutable,
                withNetworkPolicy(List.of("-I", initScript.toAbsolutePath().toString(), CLASSPATH_TASK,
                        "--quiet", "--no-daemon"), options),
                options.timeoutSeconds(), options.maxOutputBytes());
        List<String> classpathJars = parseClasspathJars(classpathOutput);

        // 4. Detect source roots
        Set<Path> sourceRoots = detectGradleSourceRoots(projectRoot);

        // 5. Parse modules
        List<ModuleContext> modules = parseGradleModules(
                projectRoot, propertiesOutput, classpathJars);

        // 6. Compute fingerprint
        BuildFingerprint fingerprint = computeGradleFingerprint(
                projectRoot, classpathOutput, sourceRoots);

        // 7. Collect diagnostics
        List<BuildDiagnostic> diagnostics = collectDiagnostics(
                projectRoot, gradleExecutable, propertiesOutput, classpathOutput);

        return new BuildContext("GRADLE", modules, fingerprint, diagnostics);
    }

    /** Appends {@code --offline} as long as no network access is allowed (like {@code -o} in Maven). */
    static List<String> withNetworkPolicy(List<String> args, ResolveOptions options) {
        if (options.networkAccess()) return args;
        List<String> result = new ArrayList<>(args);
        result.add("--offline");
        return result;
    }

    /**
     * Finds the Gradle executable (wrapper or system-wide).
     */
    private String findGradleExecutable(Path projectRoot) {
        String systemGradle = System.getProperty("gradle.executable");
        Path wrapper = wrapperIn(projectRoot, isWindows());
        if (wrapper != null) {
            return wrapper.toAbsolutePath().toString();
        }
        if (systemGradle != null && !systemGradle.isBlank()) {
            return systemGradle;
        }
        return findExecutable(projectRoot, isWindows());
    }

    /** Wrapper script of the operating system in the project, otherwise {@code null}. */
    private static Path wrapperIn(Path projectRoot, boolean windows) {
        Path wrapper = projectRoot.resolve(windows ? "gradlew.bat" : "gradlew");
        return Files.exists(wrapper) && (windows || Files.isExecutable(wrapper)) ? wrapper : null;
    }

    /**
     * Executable for an OS: the wrapper in the project, otherwise {@code gradle} from the PATH
     * (on Windows {@code gradle.bat}, because CreateProcess only resolves .exe itself).
     */
    public static String findExecutable(Path projectRoot, boolean windows) {
        Path wrapper = wrapperIn(projectRoot, windows);
        if (wrapper != null) {
            return wrapper.toAbsolutePath().toString();
        }
        return windows ? "gradle.bat" : "gradle";
    }

    /**
     * Executes a Gradle subprocess with security constraints.
     */
    private String runGradle(Path projectRoot, String executable, List<String> args,
                            int timeoutSeconds, int maxOutputBytes)
            throws BuildResolutionException {
        validateArgs(args);

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);

        try {
            var result = de.jare.rewisp.util.ProcessRunner.run(command, projectRoot, timeoutSeconds,
                    maxOutputBytes, List.of("GRADLE_OPTS", "JAVA_OPTS"));
            if (result.timedOut()) {
                throw new BuildResolutionException(BuildResolutionErrorCode.TIMEOUT,
                        "Gradle process exceeded " + timeoutSeconds + " seconds");
            }
            if (result.outputLimitExceeded()) {
                throw new BuildResolutionException(BuildResolutionErrorCode.OUTPUT_LIMIT,
                        "Gradle output exceeded " + maxOutputBytes + " bytes");
            }
            if (result.exitCode() != 0) {
                throw new BuildResolutionException(BuildResolutionErrorCode.BUILD_FAILED,
                        "Gradle exit code " + result.exitCode() + ": " + result.tail(2000));
            }
            return result.output();
        } catch (IOException e) {
            throw new BuildResolutionException(BuildResolutionErrorCode.IO_ERROR, e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildResolutionException(BuildResolutionErrorCode.INTERRUPTED, "Gradle process interrupted");
        }
    }
    /** Init-script task that prints the compile classpath as absolute JAR paths (BT:B-02). */
    static final String CLASSPATH_TASK = "rewispClasspath";
    /** Marker lines around the JAR path output of {@link #CLASSPATH_TASK}. */
    static final String CP_MARKER = "REWISP_CP";

    private static final Set<String> ALLOWED_TASKS = Set.of("properties", "dependencies", CLASSPATH_TASK);
    private static final Set<String> ALLOWED_CONFIGURATIONS =
            Set.of("compileClasspath", "runtimeClasspath", "testCompileClasspath");
    private static final Set<String> ALLOWED_FLAGS = Set.of("--quiet", "-q", "--no-daemon", "--offline");

    /**
     * Checks the argument list against the allowlist: tasks and the values of {@code --configuration}
     * and {@code -I}/{@code --init-script} are checked separately, flags only from a fixed set are
     * allowed. Init scripts are only accepted from the temp directory (the resolver writes them itself).
     */
    static void validateArgs(List<String> args) throws BuildResolutionException {
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("--configuration".equals(arg) || "-c".equals(arg)) {
                String value = i + 1 < args.size() ? args.get(++i) : null;
                if (value == null || !ALLOWED_CONFIGURATIONS.contains(value)) {
                    throw new BuildResolutionException(BuildResolutionErrorCode.GOAL_NOT_ALLOWED,
                            "Gradle configuration not in allowlist: " + value);
                }
            } else if ("-I".equals(arg) || "--init-script".equals(arg)) {
                String value = i + 1 < args.size() ? args.get(++i) : null;
                if (value == null || !isTempPath(value)) {
                    throw new BuildResolutionException(BuildResolutionErrorCode.GOAL_NOT_ALLOWED,
                            "Gradle init script must be a file in the temp directory: " + value);
                }
            } else if (arg.startsWith("-")) {
                if (!ALLOWED_FLAGS.contains(arg)) {
                    throw new BuildResolutionException(BuildResolutionErrorCode.GOAL_NOT_ALLOWED,
                            "Gradle flag not in allowlist: " + arg);
                }
            } else if (!ALLOWED_TASKS.contains(arg)) {
                throw new BuildResolutionException(BuildResolutionErrorCode.GOAL_NOT_ALLOWED,
                        "Gradle task not in allowlist: " + arg);
            }
        }
    }

    /** true when the path lies under {@code java.io.tmpdir} (where the resolver writes its init script). */
    private static boolean isTempPath(String value) {
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            return Path.of(value).toAbsolutePath().normalize().startsWith(tmp);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Writes the init script for {@link #CLASSPATH_TASK} into the temp directory. The task prints the
     * absolute paths of {@code sourceSets.main.compileClasspath.files} of every Java project between
     * {@code REWISP_CP_BEGIN}/{@code REWISP_CP_END} markers.
     */
    static Path writeClasspathInitScript() throws BuildResolutionException {
        String script = """
                allprojects { p ->
                    p.tasks.register('%s') {
                        doLast {
                            if (p.plugins.hasPlugin('java') || p.plugins.hasPlugin('java-library')) {
                                println '%s_BEGIN'
                                p.sourceSets.main.compileClasspath.files.each { println it.absolutePath }
                                println '%s_END'
                            }
                        }
                    }
                }
                """.formatted(CLASSPATH_TASK, CP_MARKER, CP_MARKER);
        try {
            Path file = Files.createTempFile("rewisp-gradle-init", ".gradle");
            Files.writeString(file, script);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new BuildResolutionException(BuildResolutionErrorCode.IO_ERROR,
                    "cannot write init script: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the JAR paths between the {@link #CP_MARKER} markers (one absolute path per line);
     * entries that are not existing regular files are dropped. Without markers the result is empty.
     */
    static List<String> parseClasspathJars(String output) {
        List<String> entries = new ArrayList<>();
        if (output == null) {
            return entries;
        }
        boolean inMarker = false;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.equals(CP_MARKER + "_BEGIN")) {
                inMarker = true;
            } else if (trimmed.equals(CP_MARKER + "_END")) {
                inMarker = false;
            } else if (inMarker && !trimmed.isEmpty() && Files.isRegularFile(Path.of(trimmed))) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    /**
     * Detects Gradle source roots in the project.
     */
    private Set<Path> detectGradleSourceRoots(Path projectRoot) {
        Set<Path> roots = new LinkedHashSet<>();

        // Standard Java source roots
        addIfDirectory(roots, projectRoot.resolve(ProjectLayout.MAIN_JAVA_DIR));
        addIfDirectory(roots, projectRoot.resolve(ProjectLayout.MAIN_KOTLIN_DIR));
        addIfDirectory(roots, projectRoot.resolve(ProjectLayout.MAIN_GROOVY_DIR));
        addIfDirectory(roots, projectRoot.resolve("src/main/scala"));

        // Test source roots
        addIfDirectory(roots, projectRoot.resolve(ProjectLayout.TEST_JAVA_DIR));
        addIfDirectory(roots, projectRoot.resolve("src/test/kotlin"));

        // Generated sources
        addIfDirectory(roots, projectRoot.resolve("build/generated/sources"));
        addIfDirectory(roots, projectRoot.resolve("build/generated-source"));

        // buildSrc sources
        Path buildSrc = projectRoot.resolve("buildSrc");
        if (Files.isDirectory(buildSrc)) {
            addIfDirectory(roots, buildSrc.resolve(ProjectLayout.MAIN_JAVA_DIR));
            addIfDirectory(roots, buildSrc.resolve(ProjectLayout.MAIN_KOTLIN_DIR));
            addIfDirectory(roots, buildSrc.resolve(ProjectLayout.MAIN_GROOVY_DIR));
        }

        return roots;
    }

    private void addIfDirectory(Set<Path> roots, Path path) {
        if (Files.isDirectory(path)) {
            roots.add(path);
        }
    }

    /**
     * Parses Gradle modules from properties output.
     */
    private List<ModuleContext> parseGradleModules(
            Path projectRoot, String propertiesOutput, List<String> classpathEntries) {

        List<ModuleContext> modules = new ArrayList<>();

        // For MVP: Create one module for the root project
        // In future: parse subProjects from properties output
        String moduleId = extractProjectName(propertiesOutput, projectRoot);
        Set<Path> sourceRoots = detectGradleSourceRoots(projectRoot);
        String languageLevel = extractLanguageLevel(propertiesOutput);

        modules.add(new ModuleContext(
                moduleId,
                projectRoot,
                filterSourceRoots(projectRoot, sourceRoots, "main"),
                filterSourceRoots(projectRoot, sourceRoots, "test"),
                Set.of(),
                classpathEntries,
                languageLevel
        ));

        return modules;
    }

    /**
     * Filters source roots by type ({@code main}/{@code test}); the decision uses the path RELATIVE to
     * the project root, so a root directory that itself contains "test" does not matter.
     */
    static Set<Path> filterSourceRoots(Path projectRoot, Set<Path> sourceRoots, String type) {
        Set<Path> filtered = new LinkedHashSet<>();
        for (Path root : sourceRoots) {
            Path relative;
            try {
                relative = projectRoot.relativize(root);
            } catch (IllegalArgumentException e) {
                relative = root;
            }
            for (Path segment : relative) {
                if (segment.toString().equals(type)) {
                    filtered.add(root);
                    break;
                }
            }
        }
        return filtered;
    }
    /**
     * Extracts the project name from properties output.
     */
    private String extractProjectName(String propertiesOutput, Path projectRoot) {
        String name = propertyValue(propertiesOutput, "rootProject.name", "name");
        return name != null ? name : projectRoot.getFileName().toString();
    }

    /**
     * Extracts the Java language level from properties output.
     */
    private String extractLanguageLevel(String propertiesOutput) {
        String level = propertyValue(propertiesOutput, "sourceCompatibility", "targetCompatibility");
        return level != null ? level : "17"; // Default
    }

    /**
     * Reads the first matching value from the output of {@code gradle properties}
     * (format {@code name: value}; tolerant also {@code name=value}); {@code null} when not present.
     */
    static String propertyValue(String propertiesOutput, String... keys) {
        if (propertiesOutput == null) {
            return null;
        }
        for (String key : keys) {
            Matcher matcher = Pattern.compile("^" + Pattern.quote(key) + "\\s*[:=]\\s*(.+)$", Pattern.MULTILINE)
                    .matcher(propertiesOutput);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return null;
    }
    /**
     * Computes the Gradle fingerprint for caching and change detection.
     * The classpath hash covers the JAR-path output of {@link #CLASSPATH_TASK} (BT:B-02).
     */
    private BuildFingerprint computeGradleFingerprint(
            Path projectRoot, String classpathOutput, Set<Path> sourceRoots) {

        // Hash build files
        StringBuilder buildFilesContent = new StringBuilder();
        
        // Build file
        for (String file : GRADLE_BUILD_FILES) {
            Path buildFile = projectRoot.resolve(file);
            if (Files.exists(buildFile)) {
                buildFilesContent.append(readSafe(buildFile));
            }
        }

        // Settings file
        for (String file : GRADLE_SETTINGS_FILES) {
            Path settingsFile = projectRoot.resolve(file);
            if (Files.exists(settingsFile)) {
                buildFilesContent.append(readSafe(settingsFile));
            }
        }

        // Config files
        for (String file : GRADLE_CONFIG_FILES) {
            Path configFile = projectRoot.resolve(file);
            if (Files.exists(configFile)) {
                buildFilesContent.append(readSafe(configFile));
            }
        }

        String buildFilesHash = hasher.sha256(buildFilesContent.toString());
        String classpathHash = hasher.sha256(classpathOutput != null ? classpathOutput : "");
        String sourceRootsHash = hasher.sha256(
                sourceRoots.stream().sorted().map(Path::toString)
                        .reduce("", (a, b) -> a + ";" + b));
        String toolchainHash = System.getProperty("java.version");

        return new BuildFingerprint("GRADLE", buildFilesHash,
                classpathHash, sourceRootsHash, toolchainHash, "");
    }

    /**
     * Collects diagnostics from the build resolution process.
     */
    private List<BuildDiagnostic> collectDiagnostics(
            Path projectRoot, String gradleExecutable, 
            String propertiesOutput, String dependenciesOutput) {
        List<BuildDiagnostic> diagnostics = new ArrayList<>();

        // Check if gradlew exists
        Path gradlew = projectRoot.resolve("gradlew");
        Path gradlewBat = projectRoot.resolve("gradlew.bat");
        if (!Files.exists(gradlew) && !Files.exists(gradlewBat)) {
            diagnostics.add(new BuildDiagnostic(
                    BuildDiagnosticCode.GRADLE_WRAPPER_MISSING,
                    BuildDiagnostic.Severity.WARN,
                    "Gradle wrapper not found in project root. Using system gradle if available.",
                    projectRoot.getFileName().toString()));
        }

        // Check if build file exists
        boolean hasBuildFile = false;
        for (String file : GRADLE_BUILD_FILES) {
            if (Files.exists(projectRoot.resolve(file))) {
                hasBuildFile = true;
                break;
            }
        }
        if (!hasBuildFile) {
            diagnostics.add(new BuildDiagnostic(
                    BuildDiagnosticCode.GRADLE_BUILD_FILE_MISSING,
                    BuildDiagnostic.Severity.ERROR,
                    "No build.gradle or build.gradle.kts found in project root.",
                    projectRoot.getFileName().toString()));
        }

        // Check if dependencies output was empty
        if (dependenciesOutput != null && dependenciesOutput.trim().isEmpty()) {
            diagnostics.add(new BuildDiagnostic(
                    BuildDiagnosticCode.DEPENDENCIES_EMPTY,
                    BuildDiagnostic.Severity.INFO,
                    "No dependencies found for compileClasspath configuration.",
                    projectRoot.getFileName().toString()));
        }

        return diagnostics;
    }

    private String readSafe(Path path) {
        if (path == null) return "";
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "";
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}

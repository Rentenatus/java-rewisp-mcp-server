/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import de.jare.rewisp.snapshot.ContentHasher;
import de.jare.rewisp.build.BuildResolutionErrorCode;
import de.jare.rewisp.build.BuildDiagnosticCode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
                List.of("properties", "--quiet", "--no-daemon"),
                options.timeoutSeconds(), options.maxOutputBytes());

        // 3. Get dependencies output for compile classpath
        String dependenciesOutput = runGradle(projectRoot, gradleExecutable,
                List.of("dependencies", "--configuration", "compileClasspath", "--quiet", "--no-daemon"),
                options.timeoutSeconds(), options.maxOutputBytes());

        // 4. Detect source roots
        Set<Path> sourceRoots = detectGradleSourceRoots(projectRoot);

        // 5. Parse modules
        List<ModuleContext> modules = parseGradleModules(
                projectRoot, propertiesOutput, dependenciesOutput);

        // 6. Compute fingerprint
        BuildFingerprint fingerprint = computeGradleFingerprint(
                projectRoot, dependenciesOutput, sourceRoots);

        // 7. Collect diagnostics
        List<BuildDiagnostic> diagnostics = collectDiagnostics(
                projectRoot, gradleExecutable, propertiesOutput, dependenciesOutput);

        return new BuildContext("GRADLE", modules, fingerprint, diagnostics);
    }

    /**
     * Finds the Gradle executable (wrapper or system-wide).
     */
    private String findGradleExecutable(Path projectRoot) throws BuildResolutionException {
        // Try gradlew.bat (Windows)
        Path gradlewBat = projectRoot.resolve("gradlew.bat");
        if (Files.exists(gradlewBat)) {
            return gradlewBat.toAbsolutePath().toString();
        }

        // Try gradlew (Unix)
        Path gradlew = projectRoot.resolve("gradlew");
        if (Files.exists(gradlew) && Files.isExecutable(gradlew)) {
            return gradlew.toAbsolutePath().toString();
        }

        // Try system-wide gradle
        String systemGradle = System.getProperty("gradle.executable");
        if (systemGradle != null && !systemGradle.isBlank()) {
            return systemGradle;
        }

        // Fallback: hope it's in PATH
        if (isWindows()) {
            return "gradlew.bat";
        }
        return "./gradlew";
    }

    /**
     * Executes a Gradle subprocess with security constraints.
     */
    private String runGradle(Path projectRoot, String executable, List<String> args,
                            int timeoutSeconds, int maxOutputBytes)
            throws BuildResolutionException {
        // Validate task allowlist
        for (String arg : args) {
            if (arg.startsWith("-")) continue;
            if (!isAllowedTask(arg)) {
                throw new BuildResolutionException(BuildResolutionErrorCode.GOAL_NOT_ALLOWED,
                        "Gradle task not in allowlist: " + arg);
            }
        }

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        // Minimize environment for security
        // Remove potentially dangerous variables
        pb.environment().remove("GRADLE_OPTS");
        pb.environment().remove("JAVA_OPTS");

        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() + line.length() > maxOutputBytes) {
                        process.destroyForcibly();
                        throw new BuildResolutionException(BuildResolutionErrorCode.OUTPUT_LIMIT,
                                "Gradle output exceeded " + maxOutputBytes + " bytes");
                    }
                    output.append(line).append('\n');
                }
            }
            boolean finished = process.waitFor(timeoutSeconds,
                    java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new BuildResolutionException(BuildResolutionErrorCode.TIMEOUT,
                        "Gradle process exceeded " + timeoutSeconds + " seconds");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new BuildResolutionException(BuildResolutionErrorCode.BUILD_FAILED,
                        "Gradle exit code " + exitCode + ": " + truncateOutput(output.toString(), 500));
            }
            return output.toString();
        } catch (IOException e) {
            throw new BuildResolutionException(BuildResolutionErrorCode.IO_ERROR, e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildResolutionException(BuildResolutionErrorCode.INTERRUPTED, "Gradle process interrupted");
        }
    }

    /**
     * Checks if a Gradle task is in the allowlist.
     * Allowlist: properties, dependencies
     */
    private boolean isAllowedTask(String task) {
        return "properties".equals(task) || "dependencies".equals(task);
    }

    /**
     * Detects Gradle source roots in the project.
     */
    private Set<Path> detectGradleSourceRoots(Path projectRoot) {
        Set<Path> roots = new LinkedHashSet<>();

        // Standard Java source roots
        addIfDirectory(roots, projectRoot.resolve("src/main/java"));
        addIfDirectory(roots, projectRoot.resolve("src/main/kotlin"));
        addIfDirectory(roots, projectRoot.resolve("src/main/groovy"));
        addIfDirectory(roots, projectRoot.resolve("src/main/scala"));

        // Test source roots
        addIfDirectory(roots, projectRoot.resolve("src/test/java"));
        addIfDirectory(roots, projectRoot.resolve("src/test/kotlin"));

        // Generated sources
        addIfDirectory(roots, projectRoot.resolve("build/generated/sources"));
        addIfDirectory(roots, projectRoot.resolve("build/generated-source"));

        // buildSrc sources
        Path buildSrc = projectRoot.resolve("buildSrc");
        if (Files.isDirectory(buildSrc)) {
            addIfDirectory(roots, buildSrc.resolve("src/main/java"));
            addIfDirectory(roots, buildSrc.resolve("src/main/kotlin"));
            addIfDirectory(roots, buildSrc.resolve("src/main/groovy"));
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
            Path projectRoot, String propertiesOutput, String dependenciesOutput) {

        List<ModuleContext> modules = new ArrayList<>();
        
        // For MVP: Create one module for the root project
        // In future: parse subProjects from properties output
        String moduleId = extractProjectName(propertiesOutput, projectRoot);
        Set<Path> sourceRoots = detectGradleSourceRoots(projectRoot);
        List<String> classpathEntries = parseGradleClasspath(dependenciesOutput);
        String languageLevel = extractLanguageLevel(propertiesOutput);

        modules.add(new ModuleContext(
                moduleId,
                projectRoot,
                filterSourceRoots(sourceRoots, "main"),
                filterSourceRoots(sourceRoots, "test"),
                Set.of(),
                classpathEntries,
                languageLevel
        ));

        return modules;
    }

    /**
     * Filters source roots to only include those matching the specified type.
     */
    private Set<Path> filterSourceRoots(Set<Path> sourceRoots, String type) {
        Set<Path> filtered = new LinkedHashSet<>();
        for (Path root : sourceRoots) {
            String pathStr = root.toString();
            if (pathStr.contains("/" + type + "/") || pathStr.contains("\\" + type + "\\")) {
                filtered.add(root);
            }
        }
        return filtered;
    }

    /**
     * Extracts the project name from properties output.
     */
    private String extractProjectName(String propertiesOutput, Path projectRoot) {
        if (propertiesOutput != null) {
            // Look for rootProject.name=... or name=...
            Pattern pattern = Pattern.compile("^(rootProject\\.name|name)=(.+)$", Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(propertiesOutput);
            if (matcher.find()) {
                return matcher.group(2).trim();
            }
        }
        return projectRoot.getFileName().toString();
    }

    /**
     * Extracts the Java language level from properties output.
     */
    private String extractLanguageLevel(String propertiesOutput) {
        if (propertiesOutput != null) {
            // Look for sourceCompatibility=... or targetCompatibility=...
            Pattern pattern = Pattern.compile("^(sourceCompatibility|targetCompatibility)=(.+)$", 
                    Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(propertiesOutput);
            if (matcher.find()) {
                return matcher.group(2).trim();
            }
        }
        return "17"; // Default
    }

    /**
     * Parses classpath entries from Gradle dependencies output.
     * Gradle dependencies output is a tree structure, we extract artifact coordinates.
     */
    private List<String> parseGradleClasspath(String dependenciesOutput) {
        List<String> entries = new ArrayList<>();
        if (dependenciesOutput == null || dependenciesOutput.isBlank()) {
            return entries;
        }

        // Pattern to match dependency lines like:
        // +--- org.springframework:spring-core:6.1.0
        // |    +--- org.springframework:spring-jcl:6.1.0
        // Also handles: implementation 'org.apache.commons:commons-lang3:3.14.0'
        Pattern pattern = Pattern.compile(
                "(?:[|\\s]*[+\\-\\|]?\\s*)" +
                "(?:implementation|compileOnly|api|runtimeOnly|annotationProcessor)\\s+'?" +
                "([^'\\s:]+):([^'\\s:]+):([^'\\s:]+)'?",
                Pattern.CASE_INSENSITIVE);

        Matcher matcher = pattern.matcher(dependenciesOutput);
        while (matcher.find()) {
            String groupId = matcher.group(1);
            String artifactId = matcher.group(2);
            String version = matcher.group(3);
            
            // Create Maven-style coordinate
            String coordinate = groupId + ":" + artifactId + ":" + version;
            entries.add(coordinate);
        }

        // Also try to extract from tree format: +--- group:artifact:version
        Pattern treePattern = Pattern.compile(
                "[|\\s]*[+\\-\\|]?\\s*" +
                "(?:\\(n\\)\\s+)?" +  // (n) for note
                "([a-zA-Z0-9._-]+):([a-zA-Z0-9._-]+):([a-zA-Z0-9._-]+)",
                Pattern.CASE_INSENSITIVE);

        Matcher treeMatcher = treePattern.matcher(dependenciesOutput);
        while (treeMatcher.find()) {
            String groupId = treeMatcher.group(1);
            String artifactId = treeMatcher.group(2);
            String version = treeMatcher.group(3);
            
            String coordinate = groupId + ":" + artifactId + ":" + version;
            if (!entries.contains(coordinate)) {
                entries.add(coordinate);
            }
        }

        return entries;
    }

    /**
     * Computes the Gradle fingerprint for caching and change detection.
     */
    private BuildFingerprint computeGradleFingerprint(
            Path projectRoot, String dependenciesOutput, Set<Path> sourceRoots) {

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
        String classpathHash = hasher.sha256(dependenciesOutput != null ? dependenciesOutput : "");
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
                    "warning",
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
                    "error",
                    "No build.gradle or build.gradle.kts found in project root.",
                    projectRoot.getFileName().toString()));
        }

        // Check if dependencies output was empty
        if (dependenciesOutput != null && dependenciesOutput.trim().isEmpty()) {
            diagnostics.add(new BuildDiagnostic(
                    BuildDiagnosticCode.DEPENDENCIES_EMPTY,
                    "info",
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

    private String truncateOutput(String output, int maxLength) {
        if (output == null) return "";
        if (output.length() <= maxLength) return output;
        return output.substring(0, maxLength) + "... (truncated)";
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}

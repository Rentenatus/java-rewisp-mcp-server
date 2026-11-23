package de.jare.rewisp.build;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.jare.rewisp.util.ProjectLayout;

import de.jare.rewisp.snapshot.ContentHasher;
import de.jare.rewisp.build.BuildResolutionErrorCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maven implementation of the {@link BuildResolver}.
 *
 * <p>Resolves classpath, module structure and source roots via Maven subprocess calls
 * ({@code help:effective-pom}, {@code dependency:build-classpath}).
 * Only goals from the configured allowlist may be executed.
 *
 * <p>Subprocess execution with timeout, output limit and process termination
 * via {@code destroyForcibly}.
 */
public final class MavenBuildResolver implements BuildResolver {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MavenBuildResolver.class);

    private final ContentHasher hasher = new ContentHasher();
    private final String mavenExecutable;
    private final Set<String> allowedGoals;

    private static final Set<String> DEFAULT_ALLOWED_GOALS = Set.of(
            "help:effective-pom",
            "dependency:build-classpath"
    );

    public MavenBuildResolver() {
        this(defaultMavenExecutable(), DEFAULT_ALLOWED_GOALS);
    }

    private static String defaultMavenExecutable() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win") ? "mvn.cmd" : "mvn";
    }

    public MavenBuildResolver(String mavenExecutable, Set<String> allowedGoals) {
        this.mavenExecutable = mavenExecutable;
        this.allowedGoals = Set.copyOf(allowedGoals);
    }

    @Override
    public boolean supports(Path projectRoot) {
        return projectRoot.resolve(ProjectLayout.POM_FILE).toFile().exists();
    }

    @Override
    public BuildContext resolve(Path projectRoot, ResolveOptions options)
            throws BuildResolutionException {

        // Ausgabedateien in einem Temp-Verzeichnis: der Projekt-Root bleibt unberuehrt (Watcher, VCS)
        Path tmp;
        try {
            tmp = Files.createTempDirectory("rewisp-maven");
        } catch (IOException e) {
            throw new BuildResolutionException(BuildResolutionErrorCode.IO_ERROR, e.getMessage(), e);
        }
        String effectivePom;
        String classpathOutput;
        try {
            // 1. effective-pom fuer Modulstruktur und Language-Level
            Path effectivePomFile = tmp.resolve("effective-pom.xml");
            runMaven(projectRoot,
                    List.of("help:effective-pom", "-Doutput=" + effectivePomFile.toAbsolutePath()),
                    options);
            effectivePom = readSafe(effectivePomFile);

            // 2. build-classpath fuer Compile-Classpath (in Datei, um Log-Rauschen zu vermeiden)
            Path classpathFile = tmp.resolve("classpath.txt");
            runMaven(projectRoot,
                    List.of("dependency:build-classpath",
                           "-Dmdep.outputFile=" + classpathFile.toAbsolutePath(),
                           "-DincludeScope=compile"),
                    options);
            classpathOutput = readSafe(classpathFile);
        } finally {
            deleteQuietly(tmp.resolve("effective-pom.xml"));
            deleteQuietly(tmp.resolve("classpath.txt"));
            deleteQuietly(tmp);
        }
        // 3. Source-Roots bestimmen
        Set<Path> sourceRoots = detectSourceRoots(projectRoot);

        // 4. Root-Modul aus der effective-pom, danach die deklarierten Module (je mit eigenem Classpath)
        List<ModuleContext> modules = new ArrayList<>(parseModules(projectRoot, effectivePom, classpathOutput));
        List<BuildDiagnostic> diagnostics = new ArrayList<>();
        addDeclaredModules(projectRoot, projectRoot, languageLevel(effectivePom), options, modules, diagnostics, 0);

        // 5. Fingerprint erzeugen (Root-Classpath plus die Classpaths der Module)
        StringBuilder allClasspaths = new StringBuilder(classpathOutput != null ? classpathOutput : "");
        for (ModuleContext m : modules.subList(1, modules.size())) {
            allClasspaths.append('\n').append(m.moduleId()).append(':').append(String.join("|", m.compileClasspath()));
        }
        BuildFingerprint fingerprint = computeFingerprint(projectRoot, allClasspaths.toString(), sourceRoots);

        return new BuildContext("MAVEN", modules, fingerprint, diagnostics);
    }

    /** Hint on errors of an offline run: missing plugins/dependencies are the most common cause. */
    static final String OFFLINE_HINT = "\n(Resolution ran offline (mvn -o). If a plugin or dependency is missing from the local "
            + "repository, start the server with -Dbuild.networkAccess=true.)";

    /** Upper limit of the modules whose classpath is resolved (one Maven run per module). */
    static int maxModules() {
        return Integer.getInteger("build.maxModules", 30);
    }

    /**
     * Appends the modules declared in {@code <modules>} (recursively, up to depth 4) to {@code out}. The classpath
     * of each module is resolved individually; if that fails (e.g. because a sibling module is not installed
     * offline), the module stays in the context without a classpath and a diagnostic names the reason.
     */
    private void addDeclaredModules(Path reactorRoot, Path moduleDir, String languageLevel, ResolveOptions options,
                                    List<ModuleContext> out, List<BuildDiagnostic> diagnostics, int depth) {
        if (depth > 4) return;
        for (String name : de.jare.rewisp.util.ProjectBuildHelpers.mavenModuleNames(
                readSafe(moduleDir.resolve(ProjectLayout.POM_FILE)))) {
            Path dir = moduleDir.resolve(name).normalize();
            String id = dir.startsWith(reactorRoot) ? reactorRoot.relativize(dir).toString().replace('\\', '/') : name;
            if (!dir.startsWith(reactorRoot) || !Files.isRegularFile(dir.resolve(ProjectLayout.POM_FILE))) {
                diagnostics.add(new BuildDiagnostic(BuildDiagnosticCode.MODULES_INCOMPLETE, BuildDiagnostic.Severity.WARN,
                        "Declared module has no pom.xml inside the project: " + name, id));
                continue;
            }
            if (out.size() > maxModules()) {
                diagnostics.add(new BuildDiagnostic(BuildDiagnosticCode.MODULES_INCOMPLETE, BuildDiagnostic.Severity.WARN,
                        "Module limit reached (" + maxModules() + "); further modules are not resolved. "
                                + "Raise -Dbuild.maxModules to resolve more.", id));
                return;
            }
            List<String> classpath = List.of();
            try {
                classpath = parseClasspath(moduleClasspath(dir, options));
            } catch (BuildResolutionException e) {
                LOG.warn("Classpath of module {} could not be resolved: {}", id, e.getMessage());
                diagnostics.add(new BuildDiagnostic(BuildDiagnosticCode.MODULE_CLASSPATH_FAILED,
                        BuildDiagnostic.Severity.WARN, "Classpath could not be resolved: " + e.getMessage(), id));
            }
            Set<Path> main = new LinkedHashSet<>();
            addIfDirectory(main, dir.resolve(ProjectLayout.MAIN_JAVA_DIR));
            Set<Path> generated = new LinkedHashSet<>();
            addIfDirectory(generated, dir.resolve("target/generated-sources"));
            Set<Path> test = new LinkedHashSet<>();
            addIfDirectory(test, dir.resolve(ProjectLayout.TEST_JAVA_DIR));
            out.add(new ModuleContext(id, dir, main, test, generated, classpath, languageLevel));
            addDeclaredModules(reactorRoot, dir, languageLevel, options, out, diagnostics, depth + 1);
        }
    }

    private static void addIfDirectory(Set<Path> target, Path dir) {
        if (Files.isDirectory(dir)) target.add(dir);
    }

    /** Compile classpath of a single module ({@code dependency:build-classpath} in the module directory). */
    private String moduleClasspath(Path moduleDir, ResolveOptions options) throws BuildResolutionException {
        Path tmp;
        try {
            tmp = Files.createTempDirectory("rewisp-maven-module");
        } catch (IOException e) {
            throw new BuildResolutionException(BuildResolutionErrorCode.IO_ERROR, e.getMessage(), e);
        }
        Path file = tmp.resolve("classpath.txt");
        try {
            runMaven(moduleDir, List.of("dependency:build-classpath", "-Dmdep.outputFile=" + file.toAbsolutePath(),
                    "-DincludeScope=compile"), options);
            return readSafe(file);
        } finally {
            deleteQuietly(file);
            deleteQuietly(tmp);
        }
    }

    private static List<String> parseClasspath(String classpathOutput) {
        if (classpathOutput == null || classpathOutput.isBlank()) return List.of();
        return Arrays.asList(classpathOutput.trim().split(Pattern.quote(System.getProperty("path.separator"))));
    }

    private void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException e) { LOG.debug("Could not delete {}: {}", path, e.getMessage()); }
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : "..." + s.substring(s.length() - max);
    }

    private String runMaven(Path projectRoot, List<String> args,
                           ResolveOptions options)
            throws BuildResolutionException {
        // Goal-Allowlist pruefen
        List<String> goals = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("-")) {
                flags.add(arg);
            } else {
                if (!allowedGoals.contains(arg)) {
                    throw new BuildResolutionException(BuildResolutionErrorCode.GOAL_NOT_ALLOWED,
                            "Maven goal not in allowlist: " + arg);
                }
                goals.add(arg);
            }
        }

        List<String> command = new ArrayList<>();
        command.add(mavenExecutable);
        command.addAll(goals);
        command.addAll(flags);
        if (!options.networkAccess()) {
            command.add("-o");
        }
        command.add("-q");

        try {
            var result = de.jare.rewisp.util.ProcessRunner.run(command, projectRoot, options.timeoutSeconds(),
                    options.maxOutputBytes(), List.of("MAVEN_OPTS", "JAVA_TOOL_OPTIONS"));
            if (result.timedOut()) {
                throw new BuildResolutionException(BuildResolutionErrorCode.TIMEOUT,
                        "Maven process exceeded " + options.timeoutSeconds() + " seconds");
            }
            if (result.outputLimitExceeded()) {
                throw new BuildResolutionException(BuildResolutionErrorCode.OUTPUT_LIMIT,
                        "Maven output exceeded " + options.maxOutputBytes() + " bytes");
            }
            if (result.exitCode() != 0) {
                throw new BuildResolutionException(BuildResolutionErrorCode.BUILD_FAILED,
                        "Maven exit code " + result.exitCode() + ": " + result.tail(2000)
                                + (options.networkAccess() ? "" : OFFLINE_HINT));
            }
            return result.output();
        } catch (IOException e) {
            throw new BuildResolutionException(BuildResolutionErrorCode.IO_ERROR, e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildResolutionException(BuildResolutionErrorCode.INTERRUPTED, "Maven process interrupted");
        }
    }
    private static final Pattern LEVEL = Pattern.compile(
            "<(?:maven\\.compiler\\.release|maven\\.compiler\\.source|release|source)>\\s*([0-9][0-9.]*)\\s*</");

    /** Java language level from the effective POM (release before source); default 17. */
    static String languageLevel(String effectivePom) {
        if (effectivePom != null) {
            Matcher m = LEVEL.matcher(effectivePom);
            if (m.find()) {
                String level = m.group(1);
                return level.startsWith("1.") ? level.substring(2) : level;
            }
        }
        return "17";
    }

    private Set<Path> detectSourceRoots(Path projectRoot) {
        Set<Path> roots = new LinkedHashSet<>();
        Path mainJava = projectRoot.resolve(ProjectLayout.MAIN_JAVA_DIR);
        if (mainJava.toFile().isDirectory()) roots.add(mainJava);
        Path testJava = projectRoot.resolve(ProjectLayout.TEST_JAVA_DIR);
        if (testJava.toFile().isDirectory()) roots.add(testJava);
        Path generated = projectRoot.resolve("target/generated-sources");
        if (generated.toFile().isDirectory()) roots.add(generated);
        return roots;
    }

    private List<ModuleContext> parseModules(Path projectRoot,
                                              String effectivePom,
                                              String classpathOutput) {
        // Parse classpath file content (single line with path separator)
        List<String> classpathEntries = List.of();
        if (classpathOutput != null && !classpathOutput.isBlank()) {
            String cp = classpathOutput.trim();
            if (!cp.isEmpty()) {
                String sep = System.getProperty("path.separator");
                classpathEntries = Arrays.asList(cp.split(Pattern.quote(sep)));
            }
        }

        // Fuer Single-Module: ein ModuleContext
        // Fuer Multi-Module: Module aus effective-pom parsen (vereinfacht)
        List<ModuleContext> modules = new ArrayList<>();
        modules.add(new ModuleContext(
                projectRoot.getFileName().toString(),
                projectRoot,
                detectSourceRoots(projectRoot),
                Set.of(),
                Set.of(),
                classpathEntries,
                languageLevel(effectivePom)
        ));
        return modules;
    }

    BuildFingerprint computeFingerprint(Path projectRoot,
                                               String classpathOutput,
                                               Set<Path> sourceRoots) {
        String pomContent = readSafe(projectRoot.resolve(ProjectLayout.POM_FILE));
        String buildFilesHash = hasher.sha256(pomContent);
        String classpathHash = hasher.sha256(classpathOutput != null ? classpathOutput : "");
        String sourceRootsHash = hasher.sha256(
                sourceRoots.stream().sorted().map(Path::toString)
                        .reduce("", (a, b) -> a + ";" + b));
        String toolchainHash = System.getProperty("java.version");
        return new BuildFingerprint("MAVEN", buildFilesHash,
                classpathHash, sourceRootsHash, toolchainHash, "");
    }

    private String readSafe(Path path) {
        try { return Files.readString(path); }
        catch (IOException e) { return ""; }
    }
}

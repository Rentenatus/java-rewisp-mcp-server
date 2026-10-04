package de.jare.rewisp.build;

import de.jare.rewisp.snapshot.ContentHasher;
import de.jare.rewisp.build.BuildResolutionErrorCode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Maven-Implementierung des {@link BuildResolver}.
 *
 * <p>Loest Classpath, Modulstruktur und Source-Roots ueber Maven-Subprocess-Aufrufe
 * auf ({@code help:effective-pom}, {@code dependency:build-classpath}).
 * Nur Goals aus der konfigurierten Allowlist duerfen ausgefuehrt werden.
 *
 * <p>Subprocess-Ausfuehrung mit Timeout, Output-Limit und Process-Beendigung
 * via {@code destroyForcibly}.
 */
public final class MavenBuildResolver implements BuildResolver {

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
        return projectRoot.resolve("pom.xml").toFile().exists();
    }

    @Override
    public BuildContext resolve(Path projectRoot, ResolveOptions options)
            throws BuildResolutionException {

        // 1. effective-pom fuer Modulstruktur
        Path effectivePomFile = projectRoot.resolve(".effective-pom.xml");
        runMaven(projectRoot,
                List.of("help:effective-pom", "-Doutput=" + effectivePomFile.toAbsolutePath()),
                options);
        String effectivePom = readSafe(effectivePomFile);
        deleteQuietly(effectivePomFile);

        // 2. build-classpath fuer Compile-Classpath (write to temp file to avoid log noise)
        Path classpathFile = projectRoot.resolve(".maven-classpath.txt");
        runMaven(projectRoot,
                List.of("dependency:build-classpath",
                       "-Dmdep.outputFile=" + classpathFile.toAbsolutePath(),
                       "-DincludeScope=compile"),
                options);
        String classpathOutput = readSafe(classpathFile);
        deleteQuietly(classpathFile);

        // 3. Source-Roots bestimmen
        Set<Path> sourceRoots = detectSourceRoots(projectRoot);

        // 4. Module aus effective-pom parsen
        List<ModuleContext> modules = parseModules(projectRoot, effectivePom, classpathOutput);

        // 5. Fingerprint erzeugen
        BuildFingerprint fingerprint = computeFingerprint(projectRoot, classpathOutput, sourceRoots);

        return new BuildContext("MAVEN", modules, fingerprint, List.of());
    }

    private void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
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

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            final int maxBytes = options.maxOutputBytes();
            final Process proc = process;

            // Read output asynchronously so waitFor can enforce the timeout
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            if (output.length() + line.length() + 1 > maxBytes) {
                                proc.destroyForcibly();
                                break;
                            }
                            output.append(line).append('\n');
                        }
                    }
                } catch (IOException e) {
                    // Process killed or stream closed
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = process.waitFor(options.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readerThread.interrupt();
                throw new BuildResolutionException(BuildResolutionErrorCode.TIMEOUT,
                        "Maven process exceeded " + options.timeoutSeconds() + " seconds");
            }
            readerThread.join(2000);

            int exitCode = process.exitValue();
            // Check output limit exceeded (reader thread broke out of loop)
            if (output.length() >= maxBytes) {
                throw new BuildResolutionException(BuildResolutionErrorCode.OUTPUT_LIMIT,
                        "Maven output exceeded " + maxBytes + " bytes");
            }
            if (exitCode != 0) {
                throw new BuildResolutionException(BuildResolutionErrorCode.BUILD_FAILED,
                        "Maven exit code " + exitCode);
            }
            return output.toString();
        } catch (IOException e) {
            throw new BuildResolutionException(BuildResolutionErrorCode.IO_ERROR, e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildResolutionException(BuildResolutionErrorCode.INTERRUPTED, "Maven process interrupted");
        }
    }

    private Set<Path> detectSourceRoots(Path projectRoot) {
        Set<Path> roots = new LinkedHashSet<>();
        Path mainJava = projectRoot.resolve("src/main/java");
        if (mainJava.toFile().isDirectory()) roots.add(mainJava);
        Path testJava = projectRoot.resolve("src/test/java");
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
                classpathEntries = Arrays.asList(cp.split(java.util.regex.Pattern.quote(sep)));
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
                "17" // Default; aus effective-pom praeziser
        ));
        return modules;
    }

    BuildFingerprint computeFingerprint(Path projectRoot,
                                               String classpathOutput,
                                               Set<Path> sourceRoots) {
        String pomContent = readSafe(projectRoot.resolve("pom.xml"));
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

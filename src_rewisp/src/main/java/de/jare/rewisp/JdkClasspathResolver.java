package de.jare.rewisp;

import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Resolves JDK module class files from the running JVM's JRT filesystem
 * and packages them as JAR files suitable for Spoon's source classpath.
 *
 * <p>JDK 9+ replaced {@code rt.jar} with the JRT image ({@code $java.home/lib/modules}).
 * Spoon's {@code setSourceClasspath(String[])} expects JAR paths, so this class
 * extracts the relevant modules into cached temporary JARs on first use.
 * Subsequent calls within the same JVM session reuse the cache.
 *
 * <p>The module set covers the common standard-library packages:
 * {@code java.base}, {@code java.desktop}, {@code java.logging}, {@code java.sql},
 * {@code java.xml}, {@code java.management}, {@code java.net.http}, {@code java.naming},
 * {@code java.transaction.xa}, {@code java.scripting}, {@code java.compiler}.
 *
 * @author Janusch Rentenatus
 */
public class JdkClasspathResolver {

    private static final Logger LOG = LoggerFactory.getLogger(JdkClasspathResolver.class);

    /**
     * Modules that cover the vast majority of standard-library types
     * encountered in application code. Adding more is cheap — each
     * module is only extracted if it exists in the running JDK.
     */
    private static final String[] DEFAULT_MODULES = {
        "java.base",
        "java.desktop",
        "java.logging",
        "java.sql",
        "java.xml",
        "java.management",
        "java.net.http",
        "java.naming",
        "java.transaction.xa",
        "java.scripting",
        "java.compiler",
        "java.datatransfer",
        "java.prefs",
        "java.security.jgss",
        "java.security.sasl",
        "java.instrument",
        "java.sql.rowset",
    };

    /**
     * Cache: JDK installation → list of JAR paths.
     * Keyed by the {@code java.home} path so different JDK installations
     * don't collide.
     */
    private static final ConcurrentHashMap<String, List<String>> CACHE = new ConcurrentHashMap<>();

    /**
     * Returns the list of JAR file paths containing the extracted class files
     * from the default JDK modules. Results are cached per JVM installation.
     *
     * @return list of JAR paths, or empty list if resolution failed
     */
    public List<String> resolve() {
        String javaHome = System.getProperty("java.home", "");
        if (javaHome.isEmpty()) {
            LOG.warn("java.home not set; JDK classpath resolution skipped");
            return List.of();
        }
        return CACHE.computeIfAbsent(javaHome, this::extractModules);
    }

    private List<String> extractModules(String javaHome) {
        FileSystem jrtFs;
        try {
            jrtFs = FileSystems.getFileSystem(URI.create("jrt:/"));
        } catch (FileSystemNotFoundException | ProviderNotFoundException e) {
            LOG.warn("JRT filesystem not available ({}). JDK classpath resolution skipped", e.getMessage());
            return List.of();
        }

        // Persistenter Cache (~/.rewisp/jdk-classpath/<Version>-<Hash des java.home>): ein Lauf extrahiert die
        // Module nur einmal, jeder Serverstart danach nutzt sie wieder (statt bei jedem Start neue Temp-JARs)
        String jdkVersion = System.getProperty("java.version", "unknown").replaceAll("[^a-zA-Z0-9._-]", "_");
        String homeHash = Integer.toHexString(javaHome.hashCode());
        Path finalDir = Path.of(System.getProperty("user.home"), ".rewisp", "jdk-classpath", jdkVersion + "-" + homeHash);
        Path marker = finalDir.resolve(".complete");
        if (Files.exists(marker)) {
            List<String> cached = new ArrayList<>();
            for (String moduleName : DEFAULT_MODULES) {
                Path jar = finalDir.resolve(moduleName + ".jar");
                if (Files.isRegularFile(jar)) cached.add(jar.toAbsolutePath().toString());
            }
            if (!cached.isEmpty()) {
                LOG.info("JDK classpath reused from cache: {} module JARs in {}", cached.size(), finalDir);
                return Collections.unmodifiableList(cached);
            }
        }

        Path tempDir;
        try {
            Files.createDirectories(finalDir.getParent());
            tempDir = Files.createTempDirectory(finalDir.getParent(), "extract-" + jdkVersion + "-");
        } catch (IOException e) {
            LOG.warn("Failed to create directory for JDK classpath: {}", e.getMessage());
            return List.of();
        }

        List<String> jarPaths = new ArrayList<>();
        for (String moduleName : DEFAULT_MODULES) {
            Path moduleRoot = jrtFs.getPath("/modules", moduleName);
            if (!Files.exists(moduleRoot)) {
                continue;
            }
            Path jarPath = tempDir.resolve(moduleName + ".jar");
            try {
                createJarFromDirectory(jarPath, moduleRoot);
                jarPaths.add(jarPath.toAbsolutePath().toString());
                LOG.debug("Extracted JDK module '{}' to {}", moduleName, jarPath);
            } catch (IOException e) {
                LOG.warn("Failed to extract JDK module '{}': {}", moduleName, e.getMessage());
            }
        }

        if (jarPaths.isEmpty()) {
            LOG.warn("No JDK modules extracted; JDK classpath resolution produced no JARs");
            return Collections.unmodifiableList(jarPaths);
        }
        // Fertige Extraktion atomar an den festen Ort verschieben; ein paralleler Prozess kann zuvorgekommen sein
        try {
            Files.writeString(tempDir.resolve(".complete"), "ok");
            Files.move(tempDir, finalDir, StandardCopyOption.ATOMIC_MOVE);
            List<String> moved = new ArrayList<>();
            for (String jar : jarPaths) moved.add(finalDir.resolve(Path.of(jar).getFileName()).toAbsolutePath().toString());
            LOG.info("JDK classpath resolved: {} module JARs in {}", moved.size(), finalDir);
            return Collections.unmodifiableList(moved);
        } catch (IOException e) {
            if (Files.exists(marker)) { // anderer Prozess war schneller: dessen Ergebnis nutzen
                deleteQuietly(tempDir);
                return extractModulesFromCache(finalDir);
            }
            LOG.info("JDK classpath resolved: {} module JARs in {} (not cached: {})", jarPaths.size(), tempDir, e.getMessage());
            return Collections.unmodifiableList(jarPaths);
        }
    }

    private static List<String> extractModulesFromCache(Path dir) {
        List<String> out = new ArrayList<>();
        for (String moduleName : DEFAULT_MODULES) {
            Path jar = dir.resolve(moduleName + ".jar");
            if (Files.isRegularFile(jar)) out.add(jar.toAbsolutePath().toString());
        }
        return Collections.unmodifiableList(out);
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    /**
     * Creates a JAR file containing all files (recursively) from the given
     * JRT module directory.
     */
    private void createJarFromDirectory(Path jarPath, Path moduleRoot) throws IOException {
        Files.createDirectories(jarPath.getParent());
        try (var jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            try (Stream<Path> paths = Files.walk(moduleRoot)) {
                paths.filter(Files::isRegularFile)
                     .forEach(p -> {
                         String entryName = moduleRoot.relativize(p).toString()
                                 .replace('\\', '/');
                         var entry = new JarEntry(entryName);
                         try {
                             jos.putNextEntry(entry);
                             Files.copy(p, jos);
                             jos.closeEntry();
                         } catch (IOException e) {
                             LOG.warn("Failed to add {} to JAR: {}", entryName, e.getMessage());
                         }
                     });
            }
        }
    }
}

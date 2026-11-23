/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.ClosedWatchServiceException;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches the build configuration of project directories with WatchService and debouncing.
 * Only events for build files (pom.xml, build.gradle*, ...) are forwarded to the ChangeTracker after a
 * configurable debounce time; source directories (src) are not watched at all.
 */
public final class WorkspaceWatcher {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWatcher.class);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "workspace-watcher-scheduler");
                t.setDaemon(true);
                return t;
            });
    
    private final Map<Path, WatchService> watchServices = new ConcurrentHashMap<>();
    private final Map<Path, Long> lastEventTime = new ConcurrentHashMap<>();
    private final FileChangeListener listener;
    private final long debounceMillis;
    private final Map<String, Path> projectRoots = new ConcurrentHashMap<>();
    private final IgnoreRules ignoreRules;

    /**
     * Creates a new WorkspaceWatcher.
     * 
     * @param changeTracker receiver of the events (usually the ChangeTracker)
     * @param debounceMillis debounce time in milliseconds
     * @param ignoredDirectories list of directories to ignore
     */
    public WorkspaceWatcher(FileChangeListener changeTracker, long debounceMillis,
                          List<String> ignoredDirectories) {
        this.listener = changeTracker;
        this.debounceMillis = debounceMillis;
        this.ignoreRules = new IgnoreRules(ignoredDirectories);
    }

    /**
     * Starts watching a project directory.
     * 
     * @param projectName the project name
     * @param projectRoot the project root directory
     * @throws IOException when watching cannot be started
     */
    public void watch(String projectName, Path projectRoot) throws IOException {
        // Ein erneutes watch() fuer dasselbe Projekt ersetzt den alten Watcher (kein Service-Leak)
        stopWatching(projectName);
        WatchService ws = FileSystems.getDefault().newWatchService();
        try {
            // Root-Verzeichnis und Unterverzeichnisse registrieren
            projectRoot.register(ws, StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
            registerSubdirectories(projectRoot, projectRoot, ws);
        } catch (IOException | RuntimeException e) {
            try {
                ws.close();
            } catch (IOException closeEx) {
                LOG.debug("Closing watch service failed: {}", closeEx.getMessage());
            }
            throw e;
        }
        projectRoots.put(projectName, projectRoot);
        watchServices.put(projectRoot, ws);

        // Watcher-Thread starten
        Thread watcherThread = new Thread(() -> watchLoop(projectName, projectRoot, ws));
        watcherThread.setDaemon(true);
        watcherThread.setName("watcher-" + projectName);
        watcherThread.start();
    }

    /**
     * Registers {@code start} and all subdirectories recursively for watching.
     * Ignored directories (relative to {@code root}) are skipped.
     */
    private void registerSubdirectories(Path start, Path root, WatchService ws) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir,
                    BasicFileAttributes attrs) throws IOException {
                if (shouldIgnore(root, dir) || isSourceTree(root, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Source directories ({@code src}, below the root directory) are not watched: build files
     * never lie in them, and a tree with thousands of source files should cost neither registrations nor events (plan 2.8, D4).
     */
    private static boolean isSourceTree(Path root, Path dir) {
        return !dir.equals(root) && "src".equals(dir.getFileName().toString());
    }

    /**
     * The watcher loop for a project.
     *
     * <p>Debounce means <em>aggregating</em> here, not discarding: after the first event
     * {@code debounceMillis} is waited and then everything accumulated (deduplicated by
     * path and kind) is forwarded. Errors while processing an event do not end the loop;
     * it only ends when the WatchService is closed or the thread
     * is interrupted.</p>
     */
    private void watchLoop(String projectName, Path root, WatchService ws) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = ws.take();
                if (debounceMillis > 0) {
                    Thread.sleep(debounceMillis);
                }
                Set<String> seen = new HashSet<>();
                processKey(projectName, root, ws, key, seen);
                // Weitere bereits anstehende Keys im selben Zug abarbeiten
                WatchKey next;
                while ((next = ws.poll()) != null) {
                    processKey(projectName, root, ws, next, seen);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // regulär: stopWatching/shutdown
        } catch (RuntimeException e) {
            LOG.error("Workspace watcher for '{}' terminated unexpectedly", projectName, e);
        } finally {
            try {
                ws.close();
            } catch (IOException e) {
                LOG.debug("Closing watch service failed: {}", e.getMessage());
            }
            watchServices.remove(root, ws); // nur den eigenen Service (ein neuer watch() darf nicht entfernt werden)
        }
    }

    private void processKey(String projectName, Path root, WatchService ws, WatchKey key, Set<String> seen) {
        // Event-Kontext ist relativ zum registrierten (Unter-)Verzeichnis, nicht zum Root
        Path base = key.watchable() instanceof Path watched ? watched : root;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (!(event.context() instanceof Path contextPath)) {
                continue; // OVERFLOW: kein Pfad
            }
            Path resolved = base.resolve(contextPath).normalize();
            String kind = event.kind().name();
            if (!seen.add(kind + '|' + resolved)) {
                continue;
            }
            try {
                if (shouldIgnore(root, resolved)) {
                    continue;
                }
                if ("ENTRY_CREATE".equals(kind) && Files.isDirectory(resolved)) {
                    registerNewDirectory(projectName, root, ws, resolved, seen);
                }
                if (isBuildConfig(resolved)) {
                    listener.onFileChanged(projectName, resolved, kind);
                }
            } catch (RuntimeException | IOException e) {
                LOG.warn("Workspace watcher for '{}': event {} {} failed: {}",
                        projectName, kind, resolved, e.getMessage());
            }
        }
        key.reset(); // ungültige Keys (gelöschtes Verzeichnis) sind unkritisch
    }

    /**
     * A new directory is watched subsequently. Files created between creation and
     * registration no longer produce an event; they are reported here afterwards.
     */
    private void registerNewDirectory(String projectName, Path root, WatchService ws, Path dir,
                                      Set<String> seen) throws IOException {
        registerSubdirectories(dir, root, ws);
        try (var stream = Files.walk(dir)) {
            stream.filter(p -> !p.equals(dir) && !shouldIgnore(root, p) && isBuildConfig(p)).forEach(p -> {
                if (seen.add("ENTRY_CREATE|" + p)) {
                    listener.onFileChanged(projectName, p, "ENTRY_CREATE");
                }
            });
        }
    }

    private static boolean isBuildConfig(Path path) {
        Path name = path.getFileName();
        return name != null && de.jare.rewisp.util.ProjectLayout.isBuildConfigFile(name.toString());
    }
    /**
     * Checks whether a path should be ignored.
     * 
     * @param path the path to check
     * @return true when it should be ignored
     */
    private boolean shouldIgnore(Path root, Path path) {
        if (path == null) return true;
        return ignoreRules.isIgnored(root, path);
    }

    /**
     * Stops watching a specific project.
     * 
     * @param projectName the project name
     */
    public void stopWatching(String projectName) {
        Path root = projectRoots.remove(projectName);
        if (root != null) {
            WatchService ws = watchServices.remove(root);
            if (ws != null) {
                try {
                    ws.close();
                } catch (IOException e) {
                    // Best effort
                    LOG.debug("Closing watch service failed: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * Stops all watchers and shuts down the scheduler.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        watchServices.values().forEach(ws -> {
            try { 
                ws.close(); 
            } catch (IOException e) {
                // Ignorieren
                LOG.debug("Closing watch service failed: {}", e.getMessage());
            }
        });
        watchServices.clear();
        projectRoots.clear();
    }

    /**
     * Returns all currently watched project roots.
     * 
     * @return map of project names and their root paths
     */
    public Map<String, Path> getWatchedProjects() {
        return Map.copyOf(projectRoots);
    }
}

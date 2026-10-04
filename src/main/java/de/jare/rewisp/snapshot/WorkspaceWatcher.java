/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Überwacht Projektverzeichnisse mit WatchService und Debouncing.
 * Events werden nach einer konfigurierbaren Debounce-Zeit
 * an den ChangeTracker weitergegeben.
 */
public final class WorkspaceWatcher {

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "workspace-watcher-scheduler");
                t.setDaemon(true);
                return t;
            });
    
    private final Map<Path, WatchService> watchServices = new ConcurrentHashMap<>();
    private final Map<Path, Long> lastEventTime = new ConcurrentHashMap<>();
    private final ChangeTracker changeTracker;
    private final long debounceMillis;
    private final Map<String, Path> projectRoots = new ConcurrentHashMap<>();
    private final List<String> ignoredDirectories;

    /**
     * Erstellt einen neuen WorkspaceWatcher.
     * 
     * @param changeTracker Der ChangeTracker für Event-Weitergabe
     * @param debounceMillis Debounce-Zeit in Millisekunden
     * @param ignoredDirectories Liste von Verzeichnissen, die ignoriert werden sollen
     */
    public WorkspaceWatcher(ChangeTracker changeTracker, long debounceMillis,
                          List<String> ignoredDirectories) {
        this.changeTracker = changeTracker;
        this.debounceMillis = debounceMillis;
        this.ignoredDirectories = ignoredDirectories != null ? 
                List.copyOf(ignoredDirectories) : List.of();
    }

    /**
     * Beginnt die Überwachung eines Projektverzeichnisses.
     * 
     * @param projectName Der Projektname
     * @param projectRoot Das Projekt-Root-Verzeichnis
     * @throws IOException wenn die Überwachung nicht gestartet werden kann
     */
    public void watch(String projectName, Path projectRoot) throws IOException {
        projectRoots.put(projectName, projectRoot);
        WatchService ws = FileSystems.getDefault().newWatchService();
        
        // Root-Verzeichnis registrieren
        projectRoot.register(ws, StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE);
        
        watchServices.put(projectRoot, ws);

        // Rekursive Registrierung für Unterverzeichnisse
        registerSubdirectories(projectRoot, ws);

        // Watcher-Thread starten
        Thread watcherThread = new Thread(() -> watchLoop(projectName, projectRoot, ws));
        watcherThread.setDaemon(true);
        watcherThread.setName("watcher-" + projectName);
        watcherThread.start();
    }

    /**
     * Registriert alle Unterverzeichnisse rekursiv für die Überwachung.
     */
    private void registerSubdirectories(Path root, WatchService ws) throws IOException {
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, 
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (!shouldIgnore(dir)) {
                    dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Die Watcher-Schleife für ein Projekt.
     */
    private void watchLoop(String projectName, Path root, WatchService ws) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = ws.take();
                long now = System.currentTimeMillis();
                long lastTime = lastEventTime.getOrDefault(root, 0L);

                // Debounce: nur wenn genug Zeit vergangen ist
                if (now - lastTime >= debounceMillis) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path contextPath = (Path) event.context();
                        Path resolved = root.resolve(contextPath).normalize();
                        
                        if (!shouldIgnore(resolved)) {
                            changeTracker.onFileChanged(projectName, resolved,
                                    event.kind().name());
                        }
                    }
                    lastEventTime.put(root, now);
                }

                if (!key.reset()) {
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Andere Exceptions abfangen und weiterlaufen
                break;
            }
        }
        
        // Cleanup
        try {
            ws.close();
        } catch (IOException e) {
            // Ignorieren
        }
        watchServices.remove(root);
    }

    /**
     * Prüft, ob ein Pfad ignoriert werden soll.
     * 
     * @param path Der zu prüfende Pfad
     * @return true wenn ignoriert werden soll
     */
    private boolean shouldIgnore(Path path) {
        if (path == null) return true;
        
        String pathStr = path.toString();
        for (String dir : ignoredDirectories) {
            if (pathStr.contains("/" + dir + "/") || 
                pathStr.contains("\\" + dir + "\\") ||
                pathStr.endsWith("/" + dir) ||
                pathStr.endsWith("\\" + dir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Beendet die Überwachung eines bestimmten Projekts.
     * 
     * @param projectName Der Projektname
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
                }
            }
        }
    }

    /**
     * Beendet alle Watcher und shutdown den Scheduler.
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
            }
        });
        watchServices.clear();
        projectRoots.clear();
    }

    /**
     * Gibt alle derzeit überwachten Projekt-Roots zurück.
     * 
     * @return Map mit Projektnamen und ihren Root-Pfaden
     */
    public Map<String, Path> getWatchedProjects() {
        return Map.copyOf(projectRoots);
    }
}

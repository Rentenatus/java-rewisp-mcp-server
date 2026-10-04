/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Normalisiert File-System-Events und aktualisiert den DirtyState.
 * 
 * <p>Empfängt Events vom WorkspaceWatcher und übersetzt sie in
 * DirtyEntry-Einträge mit korrekter DirtyReason und ChangeOrigin.
 */
public final class ChangeTracker {

    private final Map<String, ProjectDirtyState> dirtyStates;
    private final Map<String, Path> projectRoots;
    private final AtomicLong sequence = new AtomicLong(0);

    /**
     * Erstellt einen neuen ChangeTracker.
     */
    public ChangeTracker() {
        this.dirtyStates = new ConcurrentHashMap<>();
        this.projectRoots = new ConcurrentHashMap<>();
    }

    /**
     * Registriert ein Projekt für das Tracking.
     * 
     * @param projectName Der Projektname
     * @param projectRoot Der Projekt-Root-Pfad
     * @param dirtyState Der DirtyState des Projekts
     */
    public void registerProject(String projectName, Path projectRoot,
                                 ProjectDirtyState dirtyState) {
        projectRoots.put(projectName, projectRoot);
        dirtyStates.put(projectName, dirtyState);
    }

    /**
     * Wird vom WorkspaceWatcher aufgerufen, wenn sich eine Datei ändert.
     * 
     * @param projectName Der Projektname
     * @param absolutePath Der absolute Pfad der geänderten Datei
     * @param eventKind Die Art des Events (ENTRY_MODIFY, ENTRY_CREATE, ENTRY_DELETE)
     */
    public void onFileChanged(String projectName, Path absolutePath, String eventKind) {
        Path projectRoot = projectRoots.get(projectName);
        ProjectDirtyState state = dirtyStates.get(projectName);
        if (projectRoot == null || state == null) return;

        // Ignoriere Events für Dateien außerhalb des Projekt-Roots
        if (!absolutePath.startsWith(projectRoot)) return;

        try {
            Path relative = projectRoot.relativize(absolutePath).normalize();
            long seq = sequence.incrementAndGet();

            DirtyReason reason;
            ChangeOrigin origin = ChangeOrigin.FILESYSTEM;

            if ("ENTRY_DELETE".equals(eventKind)) {
                reason = DirtyReason.SOURCE_DELETED;
            } else if ("ENTRY_CREATE".equals(eventKind)) {
                reason = DirtyReason.SOURCE_CHANGED;
            } else {
                // ENTRY_MODIFY oder andere
                reason = DirtyReason.SOURCE_CHANGED;
            }

            // Build-Konfigurations-Dateien besonders behandeln
            String fileName = absolutePath.getFileName().toString();
            if (fileName.equals("pom.xml") || 
                fileName.equals("build.gradle") ||
                fileName.equals("build.gradle.kts") ||
                fileName.equals("settings.gradle") ||
                fileName.equals("settings.gradle.kts") ||
                fileName.equals("gradle.properties")) {
                state.markBuildConfiguration();
                return;
            }

            // Ignoriere bestimmte Verzeichnisse
            if (shouldIgnore(relative)) {
                return;
            }

            // Nur .java-Dateien und Verzeichnisse berücksichtigen
            if (!fileName.endsWith(".java") && !Files.isDirectory(absolutePath)) {
                return;
            }

            DirtyEntry entry = new DirtyEntry(relative, seq, origin,
                    EnumSet.of(reason));
            state.markSource(entry);
        } catch (Exception e) {
            // Relativisierung fehlgeschlagen - ignorieren
        }
    }

    /**
     * Prüft, ob eine relative Pfad ignoriert werden soll.
     * 
     * @param relativePath Der relative Pfad
     * @return true wenn ignoriert werden soll
     */
    private boolean shouldIgnore(Path relativePath) {
        String pathStr = relativePath.toString();
        String[] parts = pathStr.split("/");
        
        // Ignoriere bestimmte Verzeichnisse
        for (String part : parts) {
            if (part.equals("target") || part.equals("build") || 
                part.equals(".git") || part.equals(".gradle") || 
                part.equals(".idea") || part.equals("out") ||
                part.equals("node_modules") || part.equals(".vscode")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deregistriert ein Projekt.
     * 
     * @param projectName Der Projektname
     */
    public void unregisterProject(String projectName) {
        dirtyStates.remove(projectName);
        projectRoots.remove(projectName);
    }

    /**
     * Gibt den aktuellen Sequence-Zähler zurück (für Tests).
     * 
     * @return Der aktuelle Sequence-Wert
     */
    long getSequence() {
        return sequence.get();
    }
}

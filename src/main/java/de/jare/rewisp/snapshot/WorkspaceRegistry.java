/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prüft Projekt-Roots gegen eine Allowlist und normalisiert Pfade.
 * Verhindert Path-Traversal und Symlink-Ausbrüche.
 * 
 * <p>Alle Projektverzeichnisse müssen in der Allowlist registriert sein,
 * oder die Allowlist ist leer (dann sind alle Pfade erlaubt).
 */
public final class WorkspaceRegistry {

    private final Set<Path> allowedRoots;
    private final ConcurrentHashMap<String, Path> registered = new ConcurrentHashMap<>();

    /**
     * Erstellt ein neues WorkspaceRegistry mit einer Liste von erlaubten Root-Pfaden.
     * 
     * @param allowedRootStrings Liste von erlaubten Root-Pfaden als Strings
     */
    public WorkspaceRegistry(List<String> allowedRootStrings) {
        this.allowedRoots = ConcurrentHashMap.newKeySet();
        for (String root : allowedRootStrings) {
            try {
                Path normalized = Path.of(root).toAbsolutePath().normalize();
                allowedRoots.add(normalized);
            } catch (Exception e) {
                // Ungültiger Pfad wird ignoriert
            }
        }
    }

    /**
     * Prüft, ob ein Pfad innerhalb der erlaubten Roots liegt.
     * 
     * @param projectRoot Der zu prüfende Pfad
     * @return true wenn der Pfad erlaubt ist
     */
    public boolean isAllowed(Path projectRoot) {
        if (allowedRoots.isEmpty()) {
            return true; // Wenn keine Allowlist konfiguriert, alles erlaubt
        }

        try {
            Path normalized = projectRoot.toAbsolutePath().normalize();
            
            // Auch den realen Pfad prüfen (für Symlinks und Windows 8.3-Shortnames)
            // Aber nur, wenn der Pfad existiert - sonst ignorieren
            Path realPath = normalized;
            try {
                if (java.nio.file.Files.exists(normalized)) {
                    realPath = normalized.toRealPath();
                }
            } catch (IOException e) {
                // Falls toRealPath fehlschlägt, bleibe bei normalized
            }
            
            for (Path allowed : allowedRoots) {
                // Prüfe sowohl normalisierten als auch realen Pfad
                if (normalized.startsWith(allowed) || realPath.startsWith(allowed)) {
                    return true;
                }
                // Auch die realen Pfade der erlaubten Roots prüfen
                // (für Symlinks in der Allowlist selbst)
                try {
                    Path realAllowed = allowed.toRealPath();
                    if (normalized.startsWith(realAllowed) || realPath.startsWith(realAllowed)) {
                        return true;
                    }
                } catch (IOException e) {
                    // Falls toRealPath fehlschlägt, ignoriere
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * Registriert ein Projekt mit Pfad-Prüfung und Symlink-Auflösung.
     * 
     * @param projectName Der Projektname
     * @param projectRoot Der Projekt-Root-Pfad
     * @return Der normalisierte, reale Pfad
     * @throws SecurityException wenn der Pfad nicht erlaubt ist oder ein Symlink-Ausbruch erkannt wird
     */
    public Path register(String projectName, Path projectRoot) throws SecurityException {
        Path normalized = projectRoot.toAbsolutePath().normalize();

        // Symlink-Auflösung
        try {
            Path realPath = normalized.toRealPath();
            
            // Prüfe ob realPath noch innerhalb eines erlaubten Roots liegt
            if (!isAllowed(realPath)) {
                throw new SecurityException("Path traversal detected: "
                        + projectRoot + " resolves to " + realPath);
            }
            registered.put(projectName, realPath);
            return realPath;
        } catch (java.io.IOException e) {
            // Wenn toRealPath fehlschlägt (z.B. auf Systemen ohne Symlink-Unterstützung),
            // verwenden wir den normalisierten Pfad
            if (!isAllowed(normalized)) {
                throw new SecurityException("Could not resolve real path and path not in allowlist: " 
                        + projectRoot, e);
            }
            registered.put(projectName, normalized);
            return normalized;
        }
    }

    /**
     * Gibt den registrierten Pfad für einen Projektnamen zurück.
     * 
     * @param projectName Der Projektname
     * @return Der registrierte Pfad, oder null wenn nicht registriert
     */
    public Path get(String projectName) {
        return registered.get(projectName);
    }

    /**
     * deregistriert ein Projekt.
     * 
     * @param projectName Der Projektname
     */
    public void unregister(String projectName) {
        registered.remove(projectName);
    }

    /**
     * Prüft explizit auf Path-Traversal in einem Pfad-String.
     * 
     * @param pathString Der zu prüfende Pfad-String
     * @return true wenn der Pfad Path-Traversal enthält
     */
    public static boolean containsPathTraversal(String pathString) {
        if (pathString == null) return false;
        // Prüfe auf .. in Pfaden
        if (pathString.contains("..")) return true;
        // Auf Windows: Prüfung auf Laufwerksbuchstaben mit relativen Pfaden
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            if (pathString.contains("..\\")) return true;
            // Prüfe auf absolute Pfade auf anderen Laufwerken
            if (pathString.length() >= 3 && Character.isLetter(pathString.charAt(0)) &&
                    pathString.charAt(1) == ':' && pathString.charAt(2) == '\\') {
                return true;
            }
        }
        return false;
    }

    /**
     * Gibt alle erlaubten Root-Pfade zurück.
     * 
     * @return Set der erlaubten Roots
     */
    public Set<Path> getAllowedRoots() {
        return java.util.Collections.unmodifiableSet(allowedRoots);
    }

    /**
     * Fügt einen neuen erlaubten Root-Pfad hinzu.
     * 
     * @param rootString Der neue Root-Pfad als String
     */
    public void addAllowedRoot(String rootString) {
        try {
            Path normalized = Path.of(rootString).toAbsolutePath().normalize();
            allowedRoots.add(normalized);
        } catch (Exception e) {
            // Ungültiger Pfad wird ignoriert
        }
    }
}

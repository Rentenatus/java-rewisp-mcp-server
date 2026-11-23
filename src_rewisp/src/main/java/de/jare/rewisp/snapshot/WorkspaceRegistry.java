/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.nio.file.Files;
import java.util.Collections;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks project roots against an allowlist and normalizes paths.
 * Prevents path traversal and symlink escapes.
 * 
 * <p>All project directories must be registered in the allowlist,
 * or the allowlist is empty (then all paths are allowed).
 */
public final class WorkspaceRegistry {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorkspaceRegistry.class);

    private final Set<Path> allowedRoots;
    private final ConcurrentHashMap<String, Path> registered = new ConcurrentHashMap<>();

    /**
     * Creates a new WorkspaceRegistry with a list of allowed root paths.
     * 
     * @param allowedRootStrings list of allowed root paths as strings
     */
    public WorkspaceRegistry(List<String> allowedRootStrings) {
        this.allowedRoots = ConcurrentHashMap.newKeySet();
        for (String root : allowedRootStrings) {
            try {
                Path normalized = Path.of(root).toAbsolutePath().normalize();
                allowedRoots.add(normalized);
            } catch (Exception e) {
                // Ungültiger Pfad wird ignoriert
                LOG.warn("Ignoring invalid allowed root '{}': {}", root, e.getMessage());
            }
        }
    }

    /**
     * Checks whether a path lies within the allowed roots.
     * 
     * @param projectRoot the path to check
     * @return true when the path is allowed
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
                if (Files.exists(normalized)) {
                    realPath = normalized.toRealPath();
                }
            } catch (IOException e) {
                // Falls toRealPath fehlschlägt, bleibe bei normalized
                LOG.debug("toRealPath failed for {}: {}", normalized, e.getMessage());
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
                    LOG.debug("toRealPath failed for allowed root {}: {}", allowed, e.getMessage());
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * Registers a project with path check and symlink resolution.
     * 
     * @param projectName the project name
     * @param projectRoot the project root path
     * @return the normalized, real path
     * @throws SecurityException when the path is not allowed or a symlink escape is detected
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
        } catch (IOException e) {
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
     * Returns the registered path for a project name.
     * 
     * @param projectName the project name
     * @return the registered path, or null when not registered
     */
    public Path get(String projectName) {
        return registered.get(projectName);
    }

    /**
     * deregisters a project.
     * 
     * @param projectName the project name
     */
    public void unregister(String projectName) {
        registered.remove(projectName);
    }

    /**
     * Explicitly checks a path string for path traversal.
     * 
     * @param pathString the path string to check
     * @return true when the path contains path traversal
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
     * Returns all allowed root paths.
     * 
     * @return set of the allowed roots
     */
    public Set<Path> getAllowedRoots() {
        return Collections.unmodifiableSet(allowedRoots);
    }

    /**
     * Adds a new allowed root path.
     * 
     * @param rootString the new root path as string
     */
    public void addAllowedRoot(String rootString) {
        try {
            Path normalized = Path.of(rootString).toAbsolutePath().normalize();
            allowedRoots.add(normalized);
        } catch (Exception e) {
            // Ungültiger Pfad wird ignoriert
            LOG.warn("Ignoring invalid allowed root '{}': {}", rootString, e.getMessage());
        }
    }
}

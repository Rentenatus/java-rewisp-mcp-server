/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.model.ProjectEntryFactory;

/**
 * Registry of the loaded projects: storage, lookup by name or alias, dirty and expiry marking.
 * Deliberately contains no load, build or Spoon logic (see {@code ProjectManager} as facade).
 *
 * @author Janusch Rentenatus
 */
public final class ProjectRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectRegistry.class);

    private final ConcurrentHashMap<String, ProjectEntry> entries = new ConcurrentHashMap<>();

    /** Stores the entry resp. replaces it (key: project name). */
    public void put(ProjectEntry entry) {
        entries.put(entry.name(), entry);
    }

    /** Looks up by name, then by alias; {@code null} when not loaded. */
    public ProjectEntry find(String nameOrAlias) {
        // Name vor Alias: ein exakter Projektname ist eindeutig, ein Alias kann kollidieren
        ProjectEntry byName = entries.get(nameOrAlias);
        if (byName != null) return byName;
        for (ProjectEntry entry : entries.values()) {
            if (entry.alias() != null && entry.alias().equals(nameOrAlias)) {
                return entry;
            }
        }
        return null;
    }

    /** Entry with the exact project name (without alias resolution) or {@code null}. */
    public ProjectEntry findByName(String name) {
        return entries.get(name);
    }

    /** Removes the entry with the exact project name; {@code null} when not present. */
    public ProjectEntry removeByName(String name) {
        return entries.remove(name);
    }

    /** All loaded projects (live view). */
    public Collection<ProjectEntry> list() {
        return entries.values();
    }

    /** Stable copy of the entries, e.g. for iterating during changes. */
    public List<ProjectEntry> snapshotOfEntries() {
        return new ArrayList<>(entries.values());
    }

    public void clear() {
        entries.clear();
    }

    /** Marks the project as stale: all source files become dirty. */
    public void markDirty(String nameOrAlias) {
        ProjectEntry entry = find(nameOrAlias);
        if (entry == null || entry.modelDirty()) return;
        put(ProjectEntryFactory.withAllFilesDirty(entry));
    }

    /**
     * Marks projects whose expiry date has passed as expired (without deleting them).
     *
     * @return names of the newly expired projects
     */
    public List<String> markExpired() {
        Instant now = Instant.now();
        List<String> newlyExpired = new ArrayList<>();
        for (ProjectEntry entry : entries.values()) {
            if (!entry.expired() && entry.expiryDate() != null && now.isAfter(entry.expiryDate())) {
                put(ProjectEntryFactory.withExpired(entry, true));
                LOG.info("Project '{}' expired at {}", entry.name(), entry.expiryDate());
                newlyExpired.add(entry.name());
            }
        }
        return newlyExpired;
    }
}

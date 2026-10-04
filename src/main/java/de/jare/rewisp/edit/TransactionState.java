/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the state of an active transaction in the EditManager.
 *
 * <p>Encapsulates all transaction-related state:
 * <ul>
 *   <li>Active flag</li>
 *   <li>Pending changes (file -> content mapping)</li>
 *   <li>Pending deletions (files to delete at commit)</li>
 *   <li>Edit log</li>
 *   <li>Last committed files and deletions</li>
 * </ul>
 *
 * <p>This class centralizes transaction state management that was previously
 * scattered across multiple fields in EditManager.</p>
 */
public final class TransactionState {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionState.class);

    private boolean active = false;
    private final Map<Path, String> pendingChanges = new HashMap<>();
    private final Set<Path> pendingDeletions = new HashSet<>();
    private final List<String> editLog = new ArrayList<>();
    private Set<Path> lastCommittedFiles = Set.of();
    private Set<Path> lastCommittedDeletions = Set.of();

    /**
     * Creates a new, inactive transaction state.
     */
    public TransactionState() {}

    // ==================== STATE QUERIES ====================

    /**
     * Checks if a transaction is currently active.
     *
     * @return true if a transaction is active
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Returns the number of pending changes in the current transaction.
     *
     * @return the count of pending changes
     */
    public int getPendingChangeCount() {
        return pendingChanges.size();
    }

    /**
     * Returns the number of pending deletions in the current transaction.
     *
     * @return the count of pending deletions
     */
    public int getPendingDeletionCount() {
        return pendingDeletions.size();
    }

    /**
     * Returns the set of files written by the last successful commit.
     *
     * @return an unmodifiable set of committed file paths
     */
    public Set<Path> getLastCommittedFiles() {
        return lastCommittedFiles;
    }

    /**
     * Returns the set of files deleted by the last successful commit.
     *
     * @return an unmodifiable set of deleted file paths
     */
    public Set<Path> getLastCommittedDeletions() {
        return lastCommittedDeletions;
    }

    /**
     * Returns a copy of the edit log.
     *
     * @return a new list containing all edit log entries
     */
    public List<String> getEditLog() {
        return new ArrayList<>(editLog);
    }

    // ==================== TRANSACTION LIFECYCLE ====================

    /**
     * Begins a new transaction, clearing any pending changes or deletions from
     * a previous transaction that wasn't committed or rolled back.
     */
    public void begin() {
        active = true;
        pendingChanges.clear();
        pendingDeletions.clear();
        LOG.info("Transaction started");
    }

    /**
     * Commits all pending changes and ends the transaction.
     *
     * @param committedFiles the set of files that were successfully committed
     */
    public void commit(Set<Path> committedFiles) {
        active = false;
        lastCommittedFiles = Set.copyOf(committedFiles);
        pendingChanges.clear();
        pendingDeletions.clear();
        LOG.info("Transaction committed: {} file(s)", committedFiles.size());
    }

    /**
     * Commits all pending changes and deletions and ends the transaction.
     *
     * @param committedFiles the set of files that were successfully written
     * @param committedDeletions the set of files that were successfully deleted
     */
    public void commit(Set<Path> committedFiles, Set<Path> committedDeletions) {
        active = false;
        lastCommittedFiles = Set.copyOf(committedFiles);
        lastCommittedDeletions = Set.copyOf(committedDeletions);
        pendingChanges.clear();
        pendingDeletions.clear();
        LOG.info("Transaction committed: {} file(s) written, {} file(s) deleted",
                committedFiles.size(), committedDeletions.size());
    }

    /**
     * Rolls back the transaction, discarding all pending changes and deletions.
     * No file on disk was modified by buffered operations, so nothing to restore.
     */
    public void rollback() {
        LOG.info("Transaction rolled back: {} pending change(s), {} pending deletion(s) discarded",
                pendingChanges.size(), pendingDeletions.size());
        pendingChanges.clear();
        pendingDeletions.clear();
        active = false;
    }

    // ==================== CHANGE MANAGEMENT ====================

    /**
     * Adds a pending change to the transaction.
     *
     * @param file the file path
     * @param content the new content for the file
     */
    public void addPendingChange(Path file, String content) {
        if (file != null) {
            pendingChanges.put(file.normalize(), content);
        }
    }

    /**
     * Returns the pending change for a specific file.
     *
     * @param file the file path
     * @return the pending content, or null if no change for this file
     */
    public String getPendingChange(Path file) {
        return file != null ? pendingChanges.get(file.normalize()) : null;
    }

    /**
     * Returns all pending changes.
     *
     * @return an unmodifiable map of file paths to new content
     */
    public Map<Path, String> getPendingChanges() {
        return Map.copyOf(pendingChanges);
    }

    /**
     * Checks if there is a pending change for a specific file.
     *
     * @param file the file path
     * @return true if there is a pending change for this file
     */
    public boolean hasPendingChange(Path file) {
        return file != null && pendingChanges.containsKey(file.normalize());
    }

    /**
     * Removes a pending change for a specific file.
     *
     * @param file the file path
     */
    public void removePendingChange(Path file) {
        if (file != null) {
            pendingChanges.remove(file.normalize());
        }
    }

    /**
     * Clears all pending changes.
     */
    public void clearPendingChanges() {
        pendingChanges.clear();
    }

    // ==================== DELETION MANAGEMENT ====================

    /**
     * Adds a pending deletion to the transaction. The file is deleted at
     * commit time; a rollback discards the deletion without touching the file.
     *
     * @param file the file path
     */
    public void addPendingDeletion(Path file) {
        if (file != null) {
            pendingDeletions.add(file.normalize());
        }
    }

    /**
     * Returns all pending deletions.
     *
     * @return an unmodifiable set of file paths to delete at commit
     */
    public Set<Path> getPendingDeletions() {
        return Set.copyOf(pendingDeletions);
    }

    /**
     * Checks if there is a pending deletion for a specific file.
     *
     * @param file the file path
     * @return true if there is a pending deletion for this file
     */
    public boolean hasPendingDeletion(Path file) {
        return file != null && pendingDeletions.contains(file.normalize());
    }

    /**
     * Removes a pending deletion for a specific file (e.g. because the same
     * transaction wrote a new version of the file after the delete request).
     *
     * @param file the file path
     */
    public void removePendingDeletion(Path file) {
        if (file != null) {
            pendingDeletions.remove(file.normalize());
        }
    }

    // ==================== EDIT LOGGING ====================

    /**
     * Adds an entry to the edit log.
     *
     * @param description the description of the edit
     */
    public void logEdit(String description) {
        if (description != null) {
            editLog.add(description);
        }
    }

    /**
     * Clears the edit log.
     */
    public void clearEditLog() {
        editLog.clear();
    }

    // ==================== HARD RESET ====================

    /**
     * Performs a hard reset of all transaction state.
     * Discards any pending transaction, clears the edit log, and resets the
     * last committed files and deletions.
     */
    public void hardReset() {
        active = false;
        pendingChanges.clear();
        pendingDeletions.clear();
        editLog.clear();
        lastCommittedFiles = Set.of();
        lastCommittedDeletions = Set.of();
        LOG.info("Transaction state hard reset: all transactions, backups, and edit log cleared");
    }
}

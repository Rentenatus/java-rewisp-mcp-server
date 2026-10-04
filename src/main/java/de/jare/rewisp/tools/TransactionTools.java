/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jare.rewisp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;

import java.util.List;
import java.util.Map;

/**
 * Transaction tools: begin_transaction, commit_transaction, rollback_transaction,
 * get_edit_summary.
 *
 * @author Janusch Rentenatus
 */
/**
 * REWRITE-based tool: Transaction operation tools.
 * Extends BaseEditTool to ensure filesystem access goes through REWRITE layer (3-layer architecture compliance).
 */
public class TransactionTools extends BaseEditTool {

    private final EditManager editManager;

    public TransactionTools(ProjectManager manager, EditManager editManager) {
        super(manager);
        this.editManager = editManager;
    }

    @Override protected String toolName() {
        // This class handles multiple tools; registration uses separate instances
        return "transaction";
    }
    @Override protected String toolDescription() { return "[SPOON] Transaction operations"; }
    @Override protected Map<String, Object> toolProperties() { return Map.of(); }
    @Override protected List<String> toolRequired() { return List.of(); }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) {
        return domainError("DOMAIN_ERROR", "Use specific transaction tool instances");
    }

    // --- Individual tool classes ---

    public static class Begin extends BaseEditTool {
        private final EditManager editManager;
        public Begin(ProjectManager mgr, EditManager em) { super(mgr); this.editManager = em; }
        @Override protected String toolName() { return "begin_transaction"; }
        @Override protected String toolDescription() {
            return "[SPOON] Start a transaction. Subsequent edit calls accumulate in memory until commit.";
        }
        @Override protected Map<String, Object> toolProperties() { return Map.of(); }
        @Override protected List<String> toolRequired() { return List.of(); }
        @Override protected CallToolResult handle(McpSyncServerExchange ex, CallToolRequest req) {
            editManager.beginTransaction();
            return ok("Transaction started. Edits will accumulate in memory until commit_transaction.");
        }
    }

    public static class Commit extends BaseEditTool {
        private final EditManager editManager;
        public Commit(ProjectManager mgr, EditManager em) { super(mgr); this.editManager = em; }
        @Override protected String toolName() { return "commit_transaction"; }
        @Override protected String toolDescription() {
            return "[SPOON] Write all accumulated changes to disk atomically. Either all succeed or none.";
        }
        @Override protected Map<String, Object> toolProperties() { return Map.of(); }
        @Override protected List<String> toolRequired() { return List.of(); }
        @Override protected CallToolResult handle(McpSyncServerExchange ex, CallToolRequest req) throws Exception {
            if (!editManager.isInTransaction()) {
                return domainError("DOMAIN_ERROR", "No active transaction. Call begin_transaction first.");
            }
            int pending = editManager.getPendingChangeCount();
            boolean success = editManager.commitTransaction();
            if (success) {
                // Update editedFiles (fingerprint skipping) and dirtyFiles (model
                // staleness) with the committed files and committed deletions —
                // aber nur auf dem Projekt, dem die Dateien tatsächlich gehören
                // (Pfad-Präfix gegen den Projekt-Root). Ohne den Filter würde der
                // Commit bei mehreren geladenen Projekten jedem Projekt die
                // Dateien aller anderen unterschieben — dirty-Warnungen lügen
                // dann und check_project_dirty verlangt unnötige Reloads.
                java.util.Set<java.nio.file.Path> committed = editManager.getLastCommittedFiles();
                java.util.Set<java.nio.file.Path> deleted = editManager.getLastCommittedDeletions();
                for (var entry : manager.list()) {
                    java.nio.file.Path root = entry.originalProjectDir() != null
                            ? entry.originalProjectDir() : entry.projectDir();
                    // Windows-Falle: originalProjectDir kann als 8.3-Kurzname
                    // (ADMINI~1) vorliegen, committete Dateien mit Langnamen.
                    // normalize() löst das nicht auf — beide Seiten per
                    // toRealPath() vergleichen (Fallback: normalize()).
                    java.nio.file.Path rootReal = toRealPath(root);
                    java.util.function.Predicate<java.nio.file.Path> mine =
                            p -> rootReal != null && toRealPath(p).startsWith(rootReal);
                    java.util.Set<java.nio.file.Path> myCommitted = committed.stream()
                            .filter(mine).collect(java.util.stream.Collectors.toSet());
                    java.util.Set<java.nio.file.Path> myDeleted = deleted.stream()
                            .filter(mine).collect(java.util.stream.Collectors.toSet());
                    java.util.Set<java.nio.file.Path> updatedEdited = new java.util.HashSet<>(entry.editedFiles());
                    updatedEdited.addAll(myCommitted);
                    java.util.Set<java.nio.file.Path> updatedDirty = new java.util.HashSet<>(entry.dirtyFiles());
                    updatedDirty.addAll(myCommitted);
                    updatedDirty.addAll(myDeleted);
                    var updatedEntry = new de.jare.rewisp.model.ProjectEntry(
                            entry.name(), entry.alias(), entry.expiryDate(),
                            entry.projectDir(), entry.launcher(), entry.model(),
                            entry.buildType(), entry.delomboked(), entry.lombokVersion(),
                            entry.originalProjectDir(), entry.originalSource(),
                            entry.sourceFingerprints(), entry.expired(), entry.editable(),
                            entry.modulesDetected(), entry.modulesLoaded(),
                            java.util.Collections.unmodifiableSet(updatedDirty),
                            java.util.Collections.unmodifiableSet(updatedEdited));
                    manager.updateEntry(updatedEntry);
                }
                return ok("Transaction committed. " + pending + " file(s) written atomically"
                        + (deleted.isEmpty() ? "." : ", " + deleted.size() + " file(s) deleted."));
            } else {
                return domainError("DOMAIN_ERROR", "Commit failed. All temp files cleaned up. Transaction is still open — "
                        + "fix the issue and retry, or call rollback_transaction.");
            }
        }
    }

    public static class Rollback extends BaseEditTool {
        private final EditManager editManager;
        public Rollback(ProjectManager mgr, EditManager em) { super(mgr); this.editManager = em; }
        @Override protected String toolName() { return "rollback_transaction"; }
        @Override protected String toolDescription() {
            return "[SPOON] Discard all accumulated changes. Original fingerprints remain valid.";
        }
        @Override protected Map<String, Object> toolProperties() { return Map.of(); }
        @Override protected List<String> toolRequired() { return List.of(); }
        @Override protected CallToolResult handle(McpSyncServerExchange ex, CallToolRequest req) {
            if (!editManager.isInTransaction()) {
                return domainError("DOMAIN_ERROR", "No active transaction to roll back.");
            }
            editManager.rollbackTransaction();
            return ok("Transaction rolled back. All pending changes discarded.");
        }
    }

    public static class GetEditSummary extends BaseEditTool {
        private final EditManager editManager;
        public GetEditSummary(ProjectManager mgr, EditManager em) { super(mgr); this.editManager = em; }
        @Override protected String toolName() { return "get_edit_summary"; }
        @Override protected String toolDescription() {
            return "[SPOON] Returns a compact summary of everything changed since the project was loaded.";
        }
        @Override protected Map<String, Object> toolProperties() { return Map.of(); }
        @Override protected List<String> toolRequired() { return List.of(); }
        @Override protected CallToolResult handle(McpSyncServerExchange ex, CallToolRequest req) {
            List<String> log = editManager.getEditLog();
            if (log.isEmpty()) {
                return ok("No edits recorded since project load.");
            }
            StringBuilder sb = new StringBuilder("Edit summary (since load):\n");
            for (String entry : log) {
                sb.append("- ").append(entry).append("\n");
            }
            return ok(sb);
        }
    }

    /**
     * Löst einen Pfad auf den realen (kanonischen) Pfad auf — inklusive
     * Windows-8.3-Kurznamen (ADMINI~1) und Symlinks. Schlägt das fehl
     * (z.B. Datei existiert nicht mehr, wie bei gelöschten Dateien),
     * wird der absolut normalisierte Pfad verwendet.
     */
    private static java.nio.file.Path toRealPath(java.nio.file.Path p) {
        if (p == null) return null;
        try {
            return p.toAbsolutePath().toRealPath();
        } catch (java.io.IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}

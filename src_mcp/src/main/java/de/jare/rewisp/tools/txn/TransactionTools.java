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

package de.jare.rewisp.tools.txn;

import java.util.List;
import java.util.Map;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.api.DefaultRewispDriver;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.tools.base.BaseEditTool;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Transaction tools: begin_transaction, commit_transaction, rollback_transaction,
 * get_edit_summary, get_transaction_diff. The logic lives in the backend
 * ({@code RewispDriver}); the tools only render the result.
 *
 * @author Janusch Rentenatus
 */
public class TransactionTools extends BaseEditTool {

    public TransactionTools(ProjectManager manager, EditManager editManager) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager);
    }

    @Override protected String toolName() {
        // This class handles multiple tools; registration uses separate instances
        return "transaction";
    }
    @Override protected String toolDescription() { return "[SPOON] Transaction operations"; }
    @Override protected Map<String, Object> toolProperties() { return Map.of(); }
    @Override protected List<String> toolRequired() { return List.of(); }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        return domainError(ErrorType.DOMAIN_ERROR, "Use specific transaction tool instances");
    }

    // --- Individual tool classes ---

    public static class Begin extends BaseEditTool {
        public Begin(ProjectManager mgr, EditManager em) {
            super(mgr);
            this.driver = new DefaultRewispDriver(mgr, em);
        }
        @Override protected String toolName() { return "begin_transaction"; }
        @Override protected String toolDescription() {
            return "[SPOON] Start a transaction. Subsequent edit calls accumulate in memory until commit.";
        }
        @Override protected Map<String, Object> toolProperties() { return Map.of(); }
        @Override protected List<String> toolRequired() { return List.of(); }
        @Override public CallToolResult doHandle(McpSyncServerExchange ex, CallToolRequest req) {
            driver.beginTransaction();
            return ok("Transaction started. Edits will accumulate in memory until commit_transaction.");
        }
    }

    public static class Commit extends BaseEditTool {
        public Commit(ProjectManager mgr, EditManager em) {
            super(mgr);
            this.driver = new DefaultRewispDriver(mgr, em);
        }
        @Override protected String toolName() { return "commit_transaction"; }
        @Override protected String toolDescription() {
            return "[SPOON] Write all accumulated changes to disk atomically. Either all succeed or none.";
        }
        @Override protected Map<String, Object> toolProperties() { return Map.of(); }
        @Override protected List<String> toolRequired() { return List.of(); }
        @Override public CallToolResult doHandle(McpSyncServerExchange ex, CallToolRequest req) {
            var result = driver.commitTransaction();
            return ok("Transaction committed. " + result.filesWritten() + " file(s) written atomically"
                    + (result.filesDeleted() == 0 ? "." : ", " + result.filesDeleted() + " file(s) deleted."));
        }
    }

    public static class Rollback extends BaseEditTool {
        public Rollback(ProjectManager mgr, EditManager em) {
            super(mgr);
            this.driver = new DefaultRewispDriver(mgr, em);
        }
        @Override protected String toolName() { return "rollback_transaction"; }
        @Override protected String toolDescription() {
            return "[SPOON] Discard all accumulated changes. Original fingerprints remain valid.";
        }
        @Override protected Map<String, Object> toolProperties() { return Map.of(); }
        @Override protected List<String> toolRequired() { return List.of(); }
        @Override public CallToolResult doHandle(McpSyncServerExchange ex, CallToolRequest req) {
            driver.rollbackTransaction();
            return ok("Transaction rolled back. All pending changes discarded.");
        }
    }

    public static class GetEditSummary extends BaseEditTool {
        public GetEditSummary(ProjectManager mgr, EditManager em) {
            super(mgr);
            this.driver = new DefaultRewispDriver(mgr, em);
        }
        @Override protected String toolName() { return "get_edit_summary"; }
        @Override protected String toolDescription() {
            return "[SPOON] Returns a compact summary of everything changed since the project was loaded.";
        }
        @Override protected Map<String, Object> toolProperties() { return Map.of(); }
        @Override protected List<String> toolRequired() { return List.of(); }
        @Override public CallToolResult doHandle(McpSyncServerExchange ex, CallToolRequest req) {
            List<String> log = driver.editLog();
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

    public static class GetDiff extends BaseEditTool {
        public GetDiff(ProjectManager mgr, EditManager em) {
            super(mgr);
            this.driver = new DefaultRewispDriver(mgr, em);
        }
        @Override protected String toolName() { return "get_transaction_diff"; }
        @Override protected String toolDescription() {
            return "[SPOON] Shows what the open transaction would change: a diff between the frozen state "
                    + "(file content when the transaction first touched it) and the current transaction state. "
                    + "Nothing is written. Use statOnly=true for a per-file summary without line diffs.";
        }
        @Override protected Map<String, Object> toolProperties() {
            return Map.of("statOnly", Map.of("type", "boolean",
                    "description", "If true, list only the files with +/- line counts (no diff lines)"));
        }
        @Override protected List<String> toolRequired() { return List.of(); }
        @Override public CallToolResult doHandle(McpSyncServerExchange ex, CallToolRequest req) {
            boolean statOnly = req.arguments() != null && boolArg(req, "statOnly");
            var diff = driver.transactionDiff(statOnly);
            if (diff.files() == 0 && diff.changes().isEmpty()) {
                return ok("Transaction is open; no net changes against the frozen state"
                        + (diff.restored() > 0 ? " (" + diff.restored() + " touched file(s) are back at their original content)." : "."));
            }
            StringBuilder body = new StringBuilder();
            for (var c : diff.changes()) {
                switch (c.kind()) {
                    case "delete" -> body.append("  delete ").append(c.path()).append(" (-").append(c.removed()).append(")\n");
                    case "create" -> body.append("  create ").append(c.path()).append(" (+").append(c.added()).append(")\n");
                    case "modify" -> body.append("  modify ").append(c.path()).append(" (+").append(c.added())
                            .append(" -").append(c.removed()).append(")\n");
                    default -> body.append("  ").append(c.kind()).append(' ').append(c.path()).append('\n');
                }
                if (c.diff() != null) body.append(c.diff());
            }
            StringBuilder sb = new StringBuilder("Transaction diff (frozen state -> current transaction state): ")
                    .append(diff.files()).append(" file(s), +").append(diff.added()).append(" -").append(diff.removed()).append(" lines");
            if (diff.restored() > 0) sb.append("; ").append(diff.restored()).append(" touched file(s) unchanged vs. frozen");
            sb.append("\n").append(body);
            return ok(sb);
        }
    }
}
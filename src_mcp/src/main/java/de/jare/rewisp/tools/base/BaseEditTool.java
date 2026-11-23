/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
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

package de.jare.rewisp.tools.base;

import de.jare.rewisp.api.EditPipeline;
import de.jare.rewisp.api.RewriteEdit;
import de.jare.rewisp.edit.DryRunPreview;





import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.jare.rewisp.ProjectManager;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.DirtyCheckResult;




/**
 * Base class for all REWRITE-based tools (edit operations).
 *
 * <p><strong>Architecture Rule:</strong> REWRITE owns the project state: files, the pending
 * transaction buffer and the snapshot. Edit tools may access the filesystem; Spoon is only used
 * for pre-analysis, and its model is always derived from the Rewrite state
 * ({@code ProjectManager.ensureSpoonModel}).</p>
 *
 * <p>Responsibilities: code editing (members, annotations, classes), file operations
 * (create, delete, move), transaction handling, snapshot refresh.</p>
 *
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
public abstract class BaseEditTool extends BaseTool {


    /**
     * Constructs the tool with the given project manager.
     */
    /** The rewrite template in the backend. */
    protected final EditPipeline pipeline;

    protected BaseEditTool(ProjectManager manager) {
        super(manager);
        this.pipeline = new EditPipeline(manager);
    }

    @Override
    protected boolean requiresProjectLock() {
        return true;
    }
    /**
     * Spoon reads only from Rewrite: the model is rebuilt from the Rewrite state when needed
     * before the entry is handed to the tool.
     */
    @Override
    protected ProjectEntry findEntry(String name) {
        return manager.ensureSpoonModel(super.findEntry(name).name());
    }

    // ==================== Rewrite-Vorlage (Phase 2/3 der Edit-Tools) ====================
    // Die Vorlage selbst liegt im Backend (EditPipeline); die Tools rendern nur das Ergebnis.

    /** Hint appended when the compile check rejects; tools with their own escape hatch override it. */
    protected String compileRejectionHint() {
        return "(Disable this check with -Dedit.validate=false.)";
    }

    /**
     * Pre-write check (D2): if the rewrite result would make the project uncompilable, the edit
     * is rejected and nothing is written.
     *
     * @return an error result or {@code null} if the edit may be written
     */
    protected CallToolResult rejectIfNotCompiling(ProjectEntry entry, de.jare.rewisp.rewrite.RewriteResult preview) {
        return rejectIfNotCompiling(entry, preview, Map.of());
    }

    /** Like above, with files the edit subsequently moves (old -> new path). */
    protected CallToolResult rejectIfNotCompiling(ProjectEntry entry, de.jare.rewisp.rewrite.RewriteResult preview,
                                                  Map<Path, Path> fileMoves) {
        try {
            pipeline.rejectIfNotCompiling(entry, preview, fileMoves, compileRejectionHint());
            return null;
        } catch (de.jare.rewisp.api.RewispException e) {
            return domainError(e.code(), e.getMessage(), e.context());
        }
    }

    /**
     * Result of the rewrite template: {@code early != null} is the finished response (error or dry run), otherwise
     * it holds the entry and the rewrite result.
     */
    public record RewriteOutcome(CallToolResult early, ProjectEntry entry,
                                    de.jare.rewisp.rewrite.RewriteResult preview) {
        public boolean finished() {
            return early != null;
        }
    }

    /**
     * Phase 2 of the edit tools: preview via the {@link EditPipeline}; errors and dry run become the finished
     * response. Nothing is written.
     */
    protected RewriteOutcome previewRewrite(ProjectEntry entry, String projectName,
                                            de.jare.rewisp.rewrite.RewriteEngine engine, RewriteEdit edit,
                                            boolean dryRun) {
        EditPipeline.Outcome outcome;
        try {
            outcome = pipeline.preview(entry, projectName, engine, edit, dryRun, compileRejectionHint());
        } catch (de.jare.rewisp.api.RewispException e) {
            return early(domainError(e.code(), e.getMessage(), e.context()));
        }
        if (outcome.dryRun()) {
            return early(dryRunResult(outcome.preview(), outcome.dryRunSnapshot()));
        }
        return new RewriteOutcome(null, outcome.entry(), outcome.preview());
    }

    /** Response of a dry run: nothing written, the diff of the preview. */
    protected CallToolResult dryRunResult(de.jare.rewisp.rewrite.RewriteResult preview,
                                          de.jare.rewisp.snapshot.ProjectSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("DRY RUN - ").append(toolName()).append(": no files written. Would change ")
                .append(preview.changedFiles().size()).append(" file(s):\n");
        DryRunPreview.append(sb, preview, snapshot);
        return ok(sb);
    }

    /** Phase 3 of the edit tools: writes the result files via the EditManager. */
    protected ProjectEntry commitRewrite(ProjectEntry entry, de.jare.rewisp.edit.EditManager editManager,
                                         de.jare.rewisp.rewrite.RewriteResult preview, RewriteEdit edit)
            throws IOException {
        return pipeline.commit(entry, editManager, preview, edit);
    }

    /** {@link #previewRewrite} and on success {@link #commitRewrite}; the caller only builds the success message. */
    protected RewriteOutcome applyRewrite(ProjectEntry entry, String projectName,
                                          de.jare.rewisp.rewrite.RewriteEngine engine,
                                          de.jare.rewisp.edit.EditManager editManager, RewriteEdit edit,
                                          boolean dryRun) throws IOException {
        RewriteOutcome preview = previewRewrite(entry, projectName, engine, edit, dryRun);
        if (preview.finished()) return preview;
        ProjectEntry written = commitRewrite(entry, editManager, preview.preview(), edit);
        return new RewriteOutcome(null, written, preview.preview());
    }

    private static RewriteOutcome early(CallToolResult result) {
        return new RewriteOutcome(result, null, null);
    }

    /** Checks that the project is editable; throws otherwise. */
    protected ProjectEntry requireEditable(ProjectEntry entry) {
        return EditPipeline.requireEditable(entry);
    }

    /** Writes several files of an edit (fingerprints checked beforehand); see {@link EditPipeline#writeAll}. */
    protected ProjectEntry writeAll(ProjectEntry entry, de.jare.rewisp.edit.EditManager editMgr,
                                    Map<Path, String> writes) throws IOException {
        return pipeline.writeAll(entry, editMgr, writes);
    }

    /** Re-reads files fresh from disk into the snapshots after a rollback; see {@link EditPipeline}. */
    protected void restoreSnapshotFromDisk(Set<Path> absoluteFiles) throws IOException {
        pipeline.restoreSnapshotFromDisk(absoluteFiles);
    }
    // ==================== Dirty Checking (Filesystem-based) ====================




    // ==================== Result Builders ====================

    private static final tools.jackson.databind.ObjectMapper DOMAIN_ERROR_MAPPER = new tools.jackson.databind.ObjectMapper();











    // ==================== Schema Helpers ====================








    /**
     * Formats recipe diagnostics (e.g. source files that could not be parsed and were skipped, so
     * references inside them were NOT updated). Empty when there are none.
     */
    protected static String formatDiagnostics(de.jare.rewisp.rewrite.RewriteResult result) {
        if (result == null || result.diagnostics().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var d : result.diagnostics()) {
            sb.append("> ").append(d.severity()).append(' ').append(d.code()).append(": ")
              .append(d.message());
            if (d.path() != null && !d.path().isEmpty()) sb.append(" (").append(d.path()).append(')');
            sb.append('\n');
        }
        return sb.append('\n').toString();
    }

    /**
     * Formats a multi-module warning when detected modules exceed loaded modules.
     */
    protected static String formatMultiModuleWarning(ProjectEntry entry) {
        return formatMultiModuleWarning(entry.modulesDetected(), entry.modulesLoaded());
    }

    /** Like {@link #formatMultiModuleWarning(ProjectEntry)}, with the numbers from a backend result. */
    protected static String formatMultiModuleWarning(int modulesDetected, int modulesLoaded) {
        if (modulesDetected <= modulesLoaded) return "";
        int unloaded = modulesDetected - modulesLoaded;
        return "> WARNING: " + modulesDetected + " modules detected, "
                + modulesLoaded + " loaded. Edits may miss references in "
                + unloaded + " unloaded module" + (unloaded > 1 ? "s" : "") + ". "
                + "Load all modules before editing for full coverage.\n\n";
    }

    /**
     * Formats a scoped dirty warning (no clean confirmation — token efficiency).
     */
    protected static String formatDirtyWarning(DirtyCheckResult dirty) {
        if (!dirty.isDirty()) return "";
        StringBuilder sb = new StringBuilder();
        int total = dirty.changedFiles().size() + dirty.deletedFiles().size() + dirty.newFiles().size();
        sb.append("> WARNING: ").append(total).append(" source file(s) changed since load:\n");
        List<String> all = new ArrayList<>();
        all.addAll(dirty.changedFiles());
        all.addAll(dirty.deletedFiles());
        all.addAll(dirty.newFiles());
        int cap = Math.min(all.size(), 10);
        for (int i = 0; i < cap; i++) {
            sb.append("> ").append(all.get(i)).append("\n");
        }
        if (all.size() > 10) sb.append("> ...and ").append(all.size() - 10).append(" more\n");
        sb.append("> Call `reload_java_project` to refresh the model.\n\n");
        return sb.toString();
    }

    // ==================== Code Generation Utilities ====================



    /**
     * Validates an annotation target specification shared by the add, edit,
     * and remove annotation tools.
     *
     * @param targetType {@code "class"}, {@code "method"}, or {@code "field"}
     * @param targetName method or field name (null/blank for class)
     * @return an error message if invalid, or {@code null} if valid
     */
    public static String validateAnnotationTarget(String targetType, String targetName) {
        return de.jare.rewisp.api.AnnotationTargets.validate(targetType, targetName);
    }
}

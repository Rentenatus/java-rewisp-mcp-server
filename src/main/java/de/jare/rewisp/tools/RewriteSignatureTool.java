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
import de.jare.rewisp.edit.AutoImportResolver;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.ProjectState;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.RewriteException;
import de.jare.rewisp.rewrite.RewriteRequest;
import de.jare.rewisp.rewrite.RewriteResult;
import de.jare.rewisp.rewrite.RewriteSyncManager;
import de.jare.rewisp.rewrite.RewrittenFile;
import de.jare.rewisp.util.PathUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * The {@code RewriteSignatureTool} — changes a method's signature (return type
 * and/or parameters).
 *
 * <p>Architektur (ab 1.1.1-REWRITE): Spoon wird NUR für die Vorab-Analyse verwendet
 * (Methoden-Existenz, Overload-Ausschluss, Auto-Import-Resolution, Caller-Zählung).
 * Die eigentliche Änderung erfolgt AUSSCHLIESSLICH über OpenRewrite
 * (RewriteSignatureRecipe): AST-basierte Deklarations-Änderung und TODO-Marker
 * an echten Call-Sites. Es gibt keinen Spoon-Fallback für Edit-Operationen mehr —
 * die zeilenbasierte Signatur-Ersetzung entfällt.</p>
 *
 * @author Janusch Rentenatus
 */
public class RewriteSignatureTool extends BaseEditTool {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "rewrite-signature";

    private final EditManager editManager;
    private final AutoImportResolver importResolver;
    private final RewriteEngine rewriteEngine;
    private final RewriteSyncManager rewriteSyncManager;

    /**
     * Konstruktor mit vollständiger OpenRewrite-Unterstützung.
     * OpenRewrite ist Pflicht — es gibt keinen Spoon-Fallback mehr.
     */
    public RewriteSignatureTool(ProjectManager manager, EditManager editManager,
                              RewriteEngine rewriteEngine,
                              RewriteSyncManager rewriteSyncManager) {
        super(manager);
        this.editManager = editManager;
        this.importResolver = new AutoImportResolver();
        this.rewriteEngine = rewriteEngine;
        this.rewriteSyncManager = rewriteSyncManager;
    }

    @Override protected String toolName() { return "rewrite_signature"; }
    @Override protected String toolDescription() {
        return "[REWRITE] Change a method's signature (return type and/or parameters). Two modes: "
                + "signature_only (change just the declaration, no caller updates — useful for "
                + "interface methods or staged refactoring) or signature_and_callers (default: "
                + "inserts TODO markers at call sites so arguments can be reviewed). "
                + "Content via OpenRewrite; Spoon only for pre-edit analysis.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "methodName", Map.of("type", "string", "description", "Method name"),
            "newReturnType", Map.of("type", "string", "description", "New return type (omit to keep current)"),
            "newParameters", Map.of("type", "string", "description", "New parameter list (e.g. 'int count, String label')"),
            "mode", Map.of("type", "string", "description", "'signature_only' or 'signature_and_callers' (default)"),
            "imports", Map.of("type", "array", "description",
                "Optional: list of fully-qualified import names (e.g. ['java.util.List']) "
                + "to disambiguate types with the same simple name in different packages."),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }
    @Override protected List<String> toolRequired() { return req("name", "className", "methodName"); }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String methodName = arg(request, "methodName");
        String newReturnType = arg(request, "newReturnType");
        String newParameters = arg(request, "newParameters");
        String mode = arg(request, "mode");
        java.util.List<String> manualImports = stringListArg(request, "imports");
        if (mode == null) mode = "signature_and_callers";

        if (!"signature_only".equals(mode) && !"signature_and_callers".equals(mode)) {
            return domainError("INVALID_MODE",
                    "mode must be 'signature_only' or 'signature_and_callers'",
                    ctx("mode", mode, "validModes", "signature_only, signature_and_callers"));
        }

        ProjectEntry entry = findEntry(name);
        requireEditable(entry);

        if (rewriteEngine == null) {
            return domainError("OPENREWRITE_REQUIRED",
                    "rewrite_signature requires the OpenRewrite engine. "
                            + "Per architecture decision there is no Spoon fallback for edits.",
                    ctx("tool", toolName(), "className", className, "methodName", methodName));
        }

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> targetType = entry.model().getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals(className))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Type not found: " + className));

        Path targetFile = targetType.getPosition().getFile() != null
                ? targetType.getPosition().getFile().toPath() : null;
        if (targetFile == null) {
            return domainError("NO_SOURCE_FILE",
                    "Cannot determine source file for class " + targetType.getSimpleName(),
                    ctx("className", className, "methodName", methodName));
        }

        // Methode muss eindeutig sein (Overloads nicht unterstützt)
        List<CtMethod<?>> candidates = new ArrayList<>();
        for (CtMethod<?> m : targetType.getMethods()) {
            if (m.getSimpleName().equals(methodName)) candidates.add(m);
        }
        if (candidates.isEmpty()) {
            return domainError("METHOD_NOT_FOUND",
                    "Method '" + methodName + "' not found in " + targetType.getSimpleName(),
                    ctx("className", className, "methodName", methodName));
        }
        if (candidates.size() > 1) {
            return domainError("OVERLOADED_METHOD",
                    "Multiple methods named '" + methodName + "'. Not yet supported for overloaded methods.",
                    ctx("className", className, "methodName", methodName,
                        "suggestion", "Rename the overloads first or edit the source manually."));
        }
        CtMethod<?> target = candidates.get(0);

        // Defaults: nicht angegebene Teile der Signatur bleiben unverändert
        String retType = newReturnType != null ? newReturnType : target.getType().getSimpleName();
        String params = newParameters != null ? newParameters : getParamString(target);

        // Signatur unverändert? Dann kein Edit nötig. Vergleich über die
        // effektiven Werte (gegebenenfalls Defaults), nicht über die Präsenz.
        boolean retChanged = newReturnType != null && !newReturnType.isBlank()
                && !newReturnType.trim().equals(target.getType().getSimpleName());
        boolean paramChanged = newParameters != null
                && !newParameters.trim().equals(getParamString(target).trim());
        boolean signatureChanged = retChanged || paramChanged;
        if (!signatureChanged) {
            return ok("Signature unchanged: " + methodName + "(" + getParamString(target) + ") — no edits made.");
        }

        // Caller-Zählung (Spoon-Analyse) + Stale-Guard
        int callersUpdated = 0;
        Map<Path, CtType<?>> freshTypes = Map.of();
        if ("signature_and_callers".equals(mode)) {
            CallToolResult guard = guardStaleThreshold(entry);
            if (guard != null) return guard;
            freshTypes = freshTypesForDirtyFiles(entry);
            callersUpdated = countCallers(entry, targetType, methodName, freshTypes);
        }

        // ========== PHASE 2: OPENREWRITE EDIT (mandatory) ==========
        ProjectState state = manager.getProjectState(name);
        if (state == null || state.snapshot() == null) {
            return domainError("NO_SNAPSHOT", "No snapshot available for project: " + name,
                    ctx("className", className, "methodName", methodName));
        }

        // Bei dreckigem Projekt (vorherige Edits): Snapshot aktualisieren, damit
        // OpenRewrite die aktuellen Dateiinhalte sieht. Schmaler Scope liest nur
        // die dreckigen Dateien neu (Stale-Map), breiter Scope baut komplett neu auf.
        String refreshError = refreshSnapshotForEdit(entry, "signature_and_callers".equals(mode));
        if (refreshError != null) {
            return domainError("SNAPSHOT_REFRESH_FAILED",
                    "Could not refresh project snapshot before edit: " + refreshError,
                        ctx("className", className, "methodName", methodName));
        }

        // Caller-Updates brauchen das gesamte Projekt im Scope
        Set<Path> scopePaths = "signature_and_callers".equals(mode)
                ? Set.of()
                : Set.of(PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile));

        Map<String, String> params2 = new HashMap<>();
        params2.put("className", className);
        params2.put("methodName", methodName);
        params2.put("newReturnType", newReturnType != null ? newReturnType : "");
        params2.put("newParameters", newParameters != null ? newParameters : "");
        params2.put("mode", mode);
        params2.put("imports", manualImports != null ? String.join(",", manualImports) : "");

        RewriteRequest rewriteRequest = new RewriteRequest(
                name, RECIPE_ID, params2, scopePaths, state.snapshot().id());

        // BuildContext laden (null-Fallback: RAW-Projekte ohne Build laufen weiter)
        de.jare.rewisp.build.BuildContext buildContext = null;
        try {
            buildContext = manager.ensureBuildContext(name);
        } catch (de.jare.rewisp.build.BuildResolutionException e) {
            // Build-Kontext nicht verfuegbar - fuer viele Recipes OK
            // (OpenRewriteEngineImpl behandelt null)
        }

        RewriteResult previewResult;
        try {
            previewResult = rewriteEngine.preview(state.snapshot(), buildContext, rewriteRequest);

        } catch (RewriteException e) {
            return domainError("REWRITE_PREVIEW_FAILED",
                    "OpenRewrite preview failed: " + e.getMessage(),
                    ctx("className", className, "methodName", methodName, "errorCode", e.code()));
        }

        // ========== DRY RUN: nur Preview, keine Dateien schreiben ==========
        if (boolArg(request, "dryRun")) {
            StringBuilder sb = new StringBuilder();
            sb.append("DRY RUN - ").append(toolName())
              .append(": no files written. Would change ")
              .append(previewResult.changedFiles().size()).append(" file(s):\n");
            for (Path rel : previewResult.changedFiles().keySet()) {
                sb.append("  modify ").append(rel).append('\n');
            }
            for (Path rel : previewResult.deletedFiles()) {
                sb.append("  delete ").append(rel).append('\n');
            }
            for (Path rel : previewResult.createdFiles()) {
                sb.append("  create ").append(rel).append('\n');
            }
            return ok(sb);
        }

        if (previewResult.changedFiles().isEmpty()) {

            return domainError("NO_CHANGES",
                    "OpenRewrite produced no changes rewriting signature of " + methodName + ".",
                    ctx("className", className, "methodName", methodName,
                        "suggestion", "The method may not be found by the recipe."));
        }

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        for (Map.Entry<Path, RewrittenFile> changed : previewResult.changedFiles().entrySet()) {
            Path absFile = PathUtils.resolveReal(entry.originalProjectDir(), changed.getKey());
            entry = editManager.writeFile(entry, absFile, changed.getValue().content(), null);
        }
        manager.updateEntry(entry);
        editManager.logEdit(toolName() + ": " + className + "." + methodName + " -> "
                + retType + " " + methodName + "(" + params + ") [OpenRewrite]");

        if (rewriteSyncManager != null) {
            rewriteSyncManager.onRewriteComplete(previewResult);
        }

        String newDecl = retType + " " + methodName + "(" + params + ")";
        StringBuilder sb = new StringBuilder();
        sb.append("Signature changed: ").append(methodName).append("(").append(getParamString(target))
                .append(") -> ").append(newDecl).append(" [OpenRewrite]\n");
        sb.append("Mode: ").append(mode).append("\n");
        if ("signature_and_callers".equals(mode)) {
            sb.append("Callers updated: ").append(callersUpdated).append("\n");
            sb.append(formatFreshParsedWarning(freshTypes));
        }
        sb.append(formatMultiModuleWarning(entry));
        return ok(sb);
    }

    // ========== SPOON PRE-ANALYSIS Helpers ==========

    private String getParamString(CtMethod<?> method) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < method.getParameters().size(); i++) {
            if (i > 0) sb.append(", ");
            var p = method.getParameters().get(i);
            sb.append(p.getType() != null ? p.getType().getSimpleName() : "?")
                    .append(" ").append(p.getSimpleName());
        }
        return sb.toString();
    }

    /** Zählt echte Call-Sites der Methode (Owner-geprüft) über die Spoon-Modelle. */
    private int countCallers(ProjectEntry entry, CtType<?> targetType, String methodName,
                             Map<Path, CtType<?>> freshTypes) {
        int count = 0;
        for (CtType<?> type : entry.model().getAllTypes()) {
            Path tf = type.getPosition().getFile() != null
                    ? type.getPosition().getFile().toPath().normalize() : null;
            CtType<?> scanType = (tf != null) ? freshTypes.getOrDefault(tf, type) : type;
            for (CtMethod<?> method : scanType.getMethods()) {
                if (method.getBody() == null) continue;
                var invocations = method.getBody().getElements(new TypeFilter<>(CtInvocation.class));
                for (var inv : invocations) {
                    var exec = inv.getExecutable();
                    if (exec.getDeclaringType() != null
                            && exec.getDeclaringType().getQualifiedName().equals(targetType.getQualifiedName())
                            && exec.getSimpleName().equals(methodName)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}

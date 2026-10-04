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
import java.util.stream.Collectors;

import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

/**
 * The {@code ReplaceMethodBodyTool} — replaces the entire body of an existing method.
 *
 * <p>Architektur (ab 1.1.1-REWRITE): Spoon wird NUR für die Vorab-Analyse verwendet
 * (Methoden-Existenz, Signatur-Disambiguierung, Abstract-Check, Auto-Import-Resolution).
 * Die eigentliche Änderung erfolgt AUSSCHLIESSLICH über OpenRewrite
 * (ReplaceMethodBodyRecipe mit replaceBody-Koordinate). Es gibt keinen Spoon-Fallback
 * für Edit-Operationen mehr — auch die zeilenbasierte Brace-Suche und CR-Offset-
 * Mathematik der alten Implementierung entfällt.</p>
 *
 * @author Janusch Rentenatus
 */
public class ReplaceMethodBodyTool extends BaseEditTool {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "replace-method-body";

    private final EditManager editManager;
    private final AutoImportResolver importResolver;
    private final RewriteEngine rewriteEngine;
    private final de.jare.rewisp.rewrite.RewriteSyncManager rewriteSyncManager;

    /**
     * Konstruktor mit vollständiger OpenRewrite-Unterstützung.
     * OpenRewrite ist Pflicht — es gibt keinen Spoon-Fallback mehr.
     */
    public ReplaceMethodBodyTool(ProjectManager manager, EditManager editManager,
                               RewriteEngine rewriteEngine,
                               de.jare.rewisp.rewrite.RewriteSyncManager rewriteSyncManager) {
        super(manager);
        this.editManager = editManager;
        this.importResolver = new AutoImportResolver();
        this.rewriteEngine = rewriteEngine;
        this.rewriteSyncManager = rewriteSyncManager;
    }

    @Override protected String toolName() { return "replace_method_body"; }
    @Override protected String toolDescription() {
        return "[REWRITE] Replace the entire body of an existing method. Signature must match exactly. "
                + "Automatic import resolution: types not resolvable in the current scope are searched "
                + "in the project and imports are inserted. Unresolved types block the edit. "
                + "Content via OpenRewrite; Spoon only for pre-edit analysis.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "methodName", Map.of("type", "string", "description", "Method name"),
            "signature", Map.of("type", "string", "description", "Comma-separated parameter types (e.g. 'int, String')"),
            "newBody", Map.of("type", "string", "description", "New method body (without enclosing braces)"),
            "imports", Map.of("type", "array", "description",
                "Optional: list of fully-qualified import names (e.g. ['java.util.List']) "
                + "to disambiguate types with the same simple name in different packages. "
                + "Each FQN must be a project type or a loadable JDK class; invalid FQNs are reported as unresolved."),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }
    @Override protected List<String> toolRequired() { return req("name", "className", "methodName", "newBody"); }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String methodName = arg(request, "methodName");
        String signature = arg(request, "signature");
        String newBody = arg(request, "newBody");
        java.util.List<String> manualImports = stringListArg(request, "imports");

        ProjectEntry entry = findEntry(name);
        requireEditable(entry);

        if (rewriteEngine == null) {
            return domainError("OPENREWRITE_REQUIRED",
                    "replace_method_body requires the OpenRewrite engine. "
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

        // Methoden-Existenz + Signatur-Disambiguierung
        List<CtMethod<?>> candidates = targetType.getMethods().stream()
                .filter(m -> m.getSimpleName().equals(methodName))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return domainError("METHOD_NOT_FOUND",
                    "No method named '" + methodName + "' found in class " + targetType.getSimpleName(),
                    ctx("className", className, "methodName", methodName));
        }
        CtMethod<?> target;
        if (signature != null && !signature.isBlank()) {
            List<String> sigParams = splitTopLevelCommas(signature);
            target = candidates.stream().filter(m -> paramsMatch(m, sigParams)).findFirst().orElse(null);
            if (target == null) {
                return domainError("SIGNATURE_NOT_FOUND",
                        "No method matching signature '" + methodName + "(" + signature + ")' found in class "
                                + targetType.getSimpleName() + ". Available: " + listAvailableSignatures(candidates),
                        ctx("className", className, "methodName", methodName, "signature", signature));
            }
        } else if (candidates.size() == 1) {
            target = candidates.get(0);
        } else {
            return domainError("OVERLOADED_METHOD",
                    "Multiple methods named '" + methodName + "' found. Specify signature. Available: "
                            + listAvailableSignatures(candidates),
                    ctx("className", className, "methodName", methodName,
                        "suggestion", "Provide the 'signature' parameter to disambiguate."));
        }
        if (target.getBody() == null) {
            return domainError("NO_BODY",
                    "Method '" + methodName + "' has no body (abstract or interface method). "
                            + "Cannot replace body of a method without one.",
                    ctx("className", className, "methodName", methodName));
        }

        // Auto-Import-Resolution für den neuen Rumpf
        var importResult = importResolver.resolve(entry, targetType, newBody, manualImports);
        if (!importResult.unresolvedTypes().isEmpty()) {
            return domainError("UNRESOLVED_TYPES",
                    "Cannot resolve type(s) in method body: " + importResult.unresolvedTypes()
                            + ". No matching import found in project or declared dependencies. "
                            + "Provide the fully qualified name or add the dependency.",
                    ctx("className", className, "methodName", methodName,
                        "unresolvedTypes", importResult.unresolvedTypes().toString()));
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
        String refreshError = refreshSnapshotForEdit(entry, false);
        if (refreshError != null) {
            return domainError("SNAPSHOT_REFRESH_FAILED",
                    "Could not refresh project snapshot before edit: " + refreshError,
                        ctx("className", className, "methodName", methodName));
        }

        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile);

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("methodName", methodName);
        params.put("signature", signature != null ? signature : "");
        params.put("newBody", newBody != null ? newBody : "");
        params.put("imports", String.join(",", importResult.importsToAdd()));

        RewriteRequest rewriteRequest = new RewriteRequest(
                name, RECIPE_ID, params, Set.of(relativeFile), state.snapshot().id());

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
                    "OpenRewrite produced no changes replacing body of " + methodName + ".",
                    ctx("className", className, "methodName", methodName,
                        "suggestion", "The method may not be found by the recipe. "
                            + "Verify className/methodName/signature."));
        }

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        for (Map.Entry<Path, RewrittenFile> changed : previewResult.changedFiles().entrySet()) {
            Path absFile = PathUtils.resolveReal(entry.originalProjectDir(), changed.getKey());
            entry = editManager.writeFile(entry, absFile, changed.getValue().content(), null);
        }
        manager.updateEntry(entry);
        editManager.logEdit(toolName() + ": " + className + "." + methodName + " [OpenRewrite]");

        if (rewriteSyncManager != null) {
            rewriteSyncManager.onRewriteComplete(previewResult);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Method body replaced: ").append(className).append(".").append(methodName)
                .append(" [OpenRewrite]\n");
        if (!importResult.importsToAdd().isEmpty()) {
            sb.append("Imports added: ").append(importResult.importsToAdd()).append("\n");
        }
        sb.append(formatMultiModuleWarning(entry));
        return ok(sb);
    }

    // ========== SPOON PRE-ANALYSIS Helpers ==========

    /** Vergleicht die Parameter-Typen einer Methode mit einer Signatur-Liste. */
    private static boolean paramsMatch(CtMethod<?> method, List<String> sigParams) {
        if (method.getParameters().size() != sigParams.size()) return false;
        for (int i = 0; i < sigParams.size(); i++) {
            String expected = eraseType(sigParams.get(i));
            String actual = method.getParameters().get(i).getType() != null
                    ? method.getParameters().get(i).getType().getSimpleName() : "";
            if (!actual.equals(expected)) return false;
        }
        return true;
    }

    private static String eraseType(String type) {
        String t = type.trim();
        if (t.endsWith("...")) {
            t = t.substring(0, t.length() - 3) + "[]";
        }
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt).trim();
        int lastDot = t.lastIndexOf('.');
        if (lastDot >= 0) t = t.substring(lastDot + 1);
        return t;
    }

    private static String listAvailableSignatures(List<CtMethod<?>> methods) {
        return methods.stream()
                .map(m -> m.getParameters().stream()
                        .map(p -> p.getType() != null ? p.getType().getSimpleName() : "?")
                        .collect(Collectors.joining(", ")))
                .map(sig -> sig.isEmpty() ? "()" : "(" + sig + ")")
                .collect(Collectors.joining(", "));
    }
}

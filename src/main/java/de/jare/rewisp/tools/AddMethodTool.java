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
import de.jare.rewisp.edit.TypeErasureChecker;
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

import spoon.reflect.declaration.CtType;

/**
 * The {@code AddMethodTool} — adds a new method to an existing class.
 *
 * <p>Architektur (ab 1.1.1-REWRITE): Spoon wird NUR für die Vorab-Analyse verwendet
 * (Erasure-Kollisions-Prüfung, Auto-Import-Resolution, Unresolved-Type-Erkennung).
 * Die eigentliche Änderung erfolgt AUSSCHLIESSLICH über OpenRewrite
 * (AddMethodRecipe). Es gibt keinen Spoon-Fallback für Edit-Operationen mehr.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddMethodTool extends BaseEditTool {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "add-method";

    private final EditManager editManager;
    private final RewriteEngine rewriteEngine;
    private final de.jare.rewisp.rewrite.RewriteSyncManager rewriteSyncManager;

    /**
     * Konstruktor mit vollständiger OpenRewrite-Unterstützung.
     * OpenRewrite ist Pflicht — es gibt keinen Spoon-Fallback mehr.
     */
    public AddMethodTool(ProjectManager manager, EditManager editManager,
                       RewriteEngine rewriteEngine,
                       de.jare.rewisp.rewrite.RewriteSyncManager rewriteSyncManager) {
        super(manager);
        this.editManager = editManager;
        this.rewriteEngine = rewriteEngine;
        this.rewriteSyncManager = rewriteSyncManager;
    }

    @Override
    protected String toolName() {
        return "add_method";
    }

    @Override
    protected String toolDescription() {
        return "[REWRITE] Add a new method to an existing class or interface. Checks for erasure collisions. "
                + "Automatic import resolution applies to types in the signature and body. "
                + "On an interface: with no body, emits an abstract method (semicolon); "
                + "with a body, emits a 'default' method (Java 8+). On a class: with no body, "
                + "emits an empty-bodied method; with a body, emits the full method. "
                + "Edit via OpenRewrite; Spoon only for pre-edit analysis.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "methodName", Map.of("type", "string", "description", "Method name"),
            "returnType", Map.of("type", "string", "description", "Return type (e.g. 'void', 'String')"),
            "parameters", Map.of("type", "string", "description", "Comma-separated 'type name' pairs (e.g. 'int count, String label')"),
            "modifiers", Map.of("type", "string", "description", "Optional: 'public', 'private', 'protected', 'static'"),
            "body", Map.of("type", "string", "description", "Optional: method body (without braces)"),
            "imports", Map.of("type", "array", "description",
                "Optional: list of fully-qualified import names (e.g. ['java.util.List']) "
                + "to disambiguate types with the same simple name in different packages. "
                + "Each FQN must be a project type or a loadable JDK class; invalid FQNs are reported as unresolved."),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }

    @Override
    protected List<String> toolRequired() {
        return req("name", "className", "methodName", "returnType");
    }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String methodName = arg(request, "methodName");
        String returnType = arg(request, "returnType");
        String parameters = arg(request, "parameters");
        String modifiers = arg(request, "modifiers");
        String body = arg(request, "body");
        java.util.List<String> manualImports = stringListArg(request, "imports");

        ProjectEntry entry = findEntry(name);
        requireEditable(entry);

        if (rewriteEngine == null) {
            return domainError("OPENREWRITE_REQUIRED",
                    "add_method requires the OpenRewrite engine. "
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
                    "Cannot determine source file for class '" + className + "'.",
                    ctx("className", className, "methodName", methodName));
        }

        // Erasure-Prüfung (Bracket-aware Split, damit Generics nicht auseinandergerissen werden)
        List<String> paramTypes = new ArrayList<>();
        if (parameters != null && !parameters.isBlank()) {
            for (String p : splitTopLevelCommas(parameters)) {
                paramTypes.add(erasedParamSimpleName(p));
            }
        }
        var clash = new TypeErasureChecker().checkErasure(targetType, methodName, paramTypes);
        if (clash.clash()) {
            return domainError("ERASURE_COLLISION", clash.message(),
                    ctx("className", className, "methodName", methodName,
                        "parameters", parameters, "returnType", returnType,
                        "suggestion", "Change the method signature to avoid erasure collision with an existing method."));
        }

        // Auto-Import-Resolution für Rückgabetyp, Parameter und Body
        StringBuilder importCheckText = new StringBuilder();
        importCheckText.append(returnType);
        if (parameters != null && !parameters.isBlank()) {
            importCheckText.append(' ').append(parameters);
        }
        if (body != null && !body.isBlank()) {
            importCheckText.append(' ').append(body);
        }
        var importResult = new AutoImportResolver().resolve(entry, targetType, importCheckText.toString(), manualImports);
        if (!importResult.unresolvedTypes().isEmpty()) {
            return domainError("UNRESOLVED_TYPES",
                    "Cannot resolve type(s) in method signature or body: " + importResult.unresolvedTypes()
                            + ". Provide the fully qualified name or add the dependency.",
                    ctx("className", className, "methodName", methodName,
                        "returnType", returnType, "parameters", parameters,
                        "unresolvedTypes", importResult.unresolvedTypes().toString(),
                        "suggestion", "Use fully qualified names (e.g. java.awt.Color) or load the dependency."));
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
        params.put("returnType", returnType);
        params.put("parameters", parameters != null ? parameters : "");
        params.put("modifiers", modifiers != null ? modifiers : "");
        params.put("body", body != null ? body : "");
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
                    ctx("className", className, "methodName", methodName,
                        "errorCode", e.code()));
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
                    "OpenRewrite produced no changes for method " + methodName + " on " + className + ".",
                    ctx("className", className, "methodName", methodName,
                        "suggestion", "The target class may not be found by the recipe. "
                            + "Verify className matches the source file."));
        }

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        for (Map.Entry<Path, RewrittenFile> changed : previewResult.changedFiles().entrySet()) {
            Path absFile = PathUtils.resolveReal(entry.originalProjectDir(), changed.getKey());
            entry = editManager.writeFile(entry, absFile, changed.getValue().content(), null);
        }
        manager.updateEntry(entry);
        editManager.logEdit(toolName() + ": " + className + "." + methodName
                + "(" + (parameters == null ? "" : parameters) + ") [OpenRewrite]");

        if (rewriteSyncManager != null) {
            rewriteSyncManager.onRewriteComplete(previewResult);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Method added: ").append(className).append(".").append(methodName)
                .append(" [OpenRewrite]\n");
        sb.append(formatMultiModuleWarning(entry));
        return ok(sb);
    }

    // ========== Hilfsmethoden ==========

    /**
     * Extrahiert den erasure-reduzierten einfachen Typnamen aus einem
     * Parameter-Fragment wie "int count" oder "Map&lt;K, V&gt; map".
     */
    private static String erasedParamSimpleName(String param) {
        String t = param.trim();
        if (t.isEmpty()) return t;
        while (t.startsWith("final ") || t.startsWith("final\t")) t = t.substring(6).trim();
        int sp = t.lastIndexOf(' ');
        String typePart = sp >= 0 ? t.substring(0, sp) : t;
        // Varargs normalisieren: "int..." -> "int[]"
        if (typePart.endsWith("...")) {
            typePart = typePart.substring(0, typePart.length() - 3) + "[]";
        }
        int lt = typePart.indexOf('<');
        if (lt >= 0) typePart = typePart.substring(0, lt).trim();
        int lastDot = typePart.lastIndexOf('.');
        if (lastDot >= 0) typePart = typePart.substring(lastDot + 1);
        return typePart;
    }
}

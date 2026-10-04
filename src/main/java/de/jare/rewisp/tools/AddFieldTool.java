/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the software and associated documentation files in the same way.
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import spoon.reflect.declaration.CtType;

/**
 * The {@code AddFieldTool} — adds a new field to an existing class.
 *
 * <p>Architektur (ab 1.1.1-REWRITE): Spoon wird NUR für die Vorab-Analyse verwendet
 * (Feld-Duplikat-Prüfung, Auto-Import-Resolution, Unresolved-Type-Erkennung).
 * Die eigentliche Änderung erfolgt AUSSCHLIESSLICH über OpenRewrite
 * (AddFieldRecipe). Es gibt keinen Spoon-Fallback für Edit-Operationen mehr.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddFieldTool extends BaseEditTool {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "add-field";

    private final EditManager editManager;
    private final RewriteEngine rewriteEngine;
    private final RewriteSyncManager rewriteSyncManager;

    /**
     * Konstruktor mit vollständiger OpenRewrite-Unterstützung.
     * OpenRewrite ist Pflicht — es gibt keinen Spoon-Fallback mehr.
     */
    public AddFieldTool(ProjectManager manager, EditManager editManager,
                       RewriteEngine rewriteEngine,
                       RewriteSyncManager rewriteSyncManager) {
        super(manager);
        this.editManager = editManager;
        this.rewriteEngine = rewriteEngine;
        this.rewriteSyncManager = rewriteSyncManager;
    }

    @Override
    protected String toolName() {
        return "add_field";
    }

    @Override
    protected String toolDescription() {
        return "[REWRITE] Add a new field to an existing class. Automatic import resolution applies "
                + "to the field type and initializer expression. "
                + "Edit via OpenRewrite; Spoon only for pre-edit analysis.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "fieldName", Map.of("type", "string", "description", "Field name"),
            "type", Map.of("type", "string", "description", "Field type (e.g. 'String', 'int')"),
            "modifiers", Map.of("type", "string", "description", "Optional: 'private', 'public', 'static', 'final'"),
            "initializer", Map.of("type", "string", "description", "Optional: initializer expression (e.g. '\"hello\"' or '42')"),
            "imports", Map.of("type", "array", "description",
                "Optional: list of fully-qualified import names (e.g. ['java.util.List']) "
                + "to disambiguate types with the same simple name in different packages. "
                + "Each FQN must be a project type or a loadable JDK class; invalid FQNs are reported as unresolved."),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }

    @Override
    protected java.util.List<String> toolRequired() {
        return req("name", "className", "fieldName", "type");
    }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String fieldName = arg(request, "fieldName");
        String type = arg(request, "type");
        String modifiers = arg(request, "modifiers");
        String initializer = arg(request, "initializer");
        java.util.List<String> manualImports = stringListArg(request, "imports");

        ProjectEntry entry = findEntry(name);
        requireEditable(entry);

        if (rewriteEngine == null) {
            return domainError("OPENREWRITE_REQUIRED",
                    "add_field requires the OpenRewrite engine. "
                            + "Per architecture decision there is no Spoon fallback for edits.",
                    ctx("tool", toolName(), "className", className, "fieldName", fieldName));
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
                    ctx("className", className, "fieldName", fieldName));
        }

        // Feld-Duplikat-Prüfung
        for (var field : targetType.getFields()) {
            if (field.getSimpleName().equals(fieldName)) {
                return domainError("FIELD_EXISTS",
                        "Field '" + fieldName + "' already exists in class " + targetType.getSimpleName(),
                        ctx("className", className, "fieldName", fieldName,
                            "suggestion", "Use a different field name or remove the existing field first."));
            }
        }

        // Auto-Import-Resolution für Typ + Initializer (Spoon-Analyse)
        String checkText = type;
        if (initializer != null && !initializer.isBlank()) {
            checkText += " " + initializer;
        }
        var importResult = new AutoImportResolver().resolve(entry, targetType, checkText, manualImports);
        if (!importResult.unresolvedTypes().isEmpty()) {
            return domainError("UNRESOLVED_TYPES",
                    "Cannot resolve type(s) in field declaration: " + importResult.unresolvedTypes()
                            + ". Provide the fully qualified name or add the dependency.",
                    ctx("className", className, "fieldName", fieldName, "type", type,
                        "unresolvedTypes", importResult.unresolvedTypes().toString(),
                        "suggestion", "Use fully qualified names (e.g. java.awt.Color) or load the dependency."));
        }

        // ========== PHASE 2: OPENREWRITE EDIT (mandatory) ==========
        ProjectState state = manager.getProjectState(name);
        if (state == null || state.snapshot() == null) {
            return domainError("NO_SNAPSHOT", "No snapshot available for project: " + name,
                    ctx("className", className, "fieldName", fieldName));
        }

        // Bei dreckigem Projekt (vorherige Edits): Snapshot aktualisieren, damit
        // OpenRewrite die aktuellen Dateiinhalte sieht. Schmaler Scope liest nur
        // die dreckigen Dateien neu (Stale-Map), breiter Scope baut komplett neu auf.
        String refreshError = refreshSnapshotForEdit(entry, false);
        if (refreshError != null) {
            return domainError("SNAPSHOT_REFRESH_FAILED",
                    "Could not refresh project snapshot before edit: " + refreshError,
                        ctx("className", className, "fieldName", fieldName));
        }

        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile);

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("fieldName", fieldName);
        params.put("type", type);
        params.put("modifiers", modifiers != null ? modifiers : "");
        params.put("initializer", initializer != null ? initializer : "");
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
                    ctx("className", className, "fieldName", fieldName,
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
                    "OpenRewrite produced no changes for field " + fieldName + " on " + className + ".",
                    ctx("className", className, "fieldName", fieldName,
                        "suggestion", "The target class may not be found by the recipe. "
                            + "Verify className matches the source file."));
        }

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        // Kanonische (Real-)Pfade: konsistent mit Spoon-basierten Tools, damit
        // EditManager die Datei als "edited this session" erkennt.
        for (Map.Entry<Path, RewrittenFile> changed : previewResult.changedFiles().entrySet()) {
            Path absFile = PathUtils.resolveReal(entry.originalProjectDir(), changed.getKey());
            entry = editManager.writeFile(entry, absFile, changed.getValue().content(), null);
        }
        manager.updateEntry(entry);
        editManager.logEdit(toolName() + ": " + className + "." + fieldName + " (" + type + ") [OpenRewrite]");

        if (rewriteSyncManager != null) {
            rewriteSyncManager.onRewriteComplete(previewResult);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Field added: ").append(className).append(".").append(fieldName)
                .append(" [OpenRewrite]\n");
        sb.append(formatMultiModuleWarning(entry));
        return ok(sb);
    }
}

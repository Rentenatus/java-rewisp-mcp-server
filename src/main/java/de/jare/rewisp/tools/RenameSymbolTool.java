/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */

package de.jare.rewisp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;
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

import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * The {@code RenameSymbolTool} — renames a method, class, or field across
 * every caller, backed by OpenRewrite.
 *
 * <p>Architektur (ab 1.1.1-REWRITE): Spoon wird NUR für die Vorab-Analyse verwendet
 * (Symbol-Validierung, Overload-Check, Occurrence-Zählung, String-Literal-Scan).
 * Die eigentliche Änderung erfolgt AUSSCHLIESSLICH über OpenRewrite
 * (RenameSymbolRecipe) — Deklaration UND Call-Sites typsicher über den AST,
 * nicht mehr per Regex über Quelltext. Es gibt keinen Spoon-Fallback für
 * Edit-Operationen mehr.</p>
 *
 * @author Janusch Rentenatus
 */
public class RenameSymbolTool extends BaseEditTool {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "rename-symbol";

    private final EditManager editManager;
    private final RewriteEngine rewriteEngine;
    private final RewriteSyncManager rewriteSyncManager;

    /**
     * Konstruktor mit vollständiger OpenRewrite-Unterstützung.
     * OpenRewrite ist Pflicht — es gibt keinen Spoon-Fallback mehr.
     */
    public RenameSymbolTool(ProjectManager manager, EditManager editManager,
                           RewriteEngine rewriteEngine,
                           RewriteSyncManager rewriteSyncManager) {
        super(manager);
        this.editManager = editManager;
        this.rewriteEngine = rewriteEngine;
        this.rewriteSyncManager = rewriteSyncManager;
    }

    @Override
    protected String toolName() {
        return "rename_symbol";
    }

    @Override
    protected String toolDescription() {
        return "[REWRITE] Rename a class, method, or field. By default updates every caller (global). "
                + "Set updateCallers=false to rename only the declaration without touching call sites. "
                + "Edit via OpenRewrite; Spoon only for pre-edit analysis.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name of the target"),
            "oldName", Map.of("type", "string", "description", "Current name of the symbol"),
            "newName", Map.of("type", "string", "description", "New name for the symbol"),
            "scope", Map.of("type", "string", "description", "Optional: 'method', 'field', or 'class' (default: method)"),
            "updateCallers", Map.of("type", "boolean", "description", "If true (default), update all call sites. If false, rename only the declaration."),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }

    @Override
    protected List<String> toolRequired() {
        return req("name", "className", "oldName", "newName");
    }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String oldName = arg(request, "oldName");
        String newName = arg(request, "newName");
        String scope = arg(request, "scope");
        if (scope == null) scope = "method";

        boolean updateCallers = boolArg(request, "updateCallers");
        Object raw = request.arguments().get("updateCallers");
        if (raw == null) updateCallers = true;

        ProjectEntry entry = findEntry(name);
        requireEditable(entry);

        if (rewriteEngine == null) {
            return domainError("OPENREWRITE_REQUIRED",
                    "rename_symbol requires the OpenRewrite engine. "
                            + "Per architecture decision there is no Spoon fallback for edits.",
                    ctx("tool", toolName(), "className", className, "oldName", oldName));
        }

        if (!"method".equals(scope) && !"field".equals(scope) && !"class".equals(scope)) {
            return domainError("INVALID_SCOPE",
                    "scope must be 'method', 'field', or 'class'",
                    ctx("scope", scope, "validScopes", "method, field, class"));
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
                    ctx("className", className, "oldName", oldName));
        }

        String[] analysisError = validateSymbol(targetType, oldName, scope, updateCallers);
        if (analysisError != null) {
            return domainError(analysisError[0], analysisError[1],
                    ctx("className", className, "oldName", oldName, "scope", scope));
        }

        // Stale-Guard bei Caller-Updates (viele Dateien betroffen)
        if (updateCallers) {
            CallToolResult guard = guardStaleThreshold(entry);
            if (guard != null) return guard;
        }
        Map<Path, CtType<?>> freshTypes = updateCallers
                ? freshTypesForDirtyFiles(entry) : Map.of();

        // Occurrence-Zählung für die Antwort (Deklarationen + Call-Sites)
        int callersUpdated = countOccurrences(entry, targetType, oldName, scope, updateCallers);

        // ========== PHASE 2: OPENREWRITE EDIT (mandatory) ==========
        ProjectState state = manager.getProjectState(name);
        if (state == null || state.snapshot() == null) {
            return domainError("NO_SNAPSHOT", "No snapshot available for project: " + name,
                    ctx("className", className, "oldName", oldName));
        }

        // Bei dreckigem Projekt (vorherige Edits): Snapshot aktualisieren, damit
        // OpenRewrite die aktuellen Dateiinhalte sieht. Schmaler Scope liest nur
        // die dreckigen Dateien neu (Stale-Map), breiter Scope baut komplett neu auf.
        String refreshError = refreshSnapshotForEdit(entry, updateCallers);
        if (refreshError != null) {
            return domainError("SNAPSHOT_REFRESH_FAILED",
                    "Could not refresh project snapshot before edit: " + refreshError,
                        ctx("className", className, "oldName", oldName));
        }

        // Caller-Updates brauchen das GESAMTE Projekt im Scope (Typ-Attribution
        // über Dateigrenzen); declaration-only genügt die Zieldatei.
        Set<Path> scopePaths = updateCallers
                ? Set.of()
                : Set.of(PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile));

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("oldName", oldName);
        params.put("newName", newName);
        params.put("scope", scope);
        params.put("updateCallers", String.valueOf(updateCallers));

        RewriteRequest rewriteRequest = new RewriteRequest(
                name, RECIPE_ID, params, scopePaths, state.snapshot().id());

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
                    ctx("className", className, "oldName", oldName, "errorCode", e.code()));
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
                    "OpenRewrite produced no changes renaming " + oldName + " to " + newName + ".",
                    ctx("className", className, "oldName", oldName,
                        "suggestion", "The symbol may not be found by the recipe. "
                            + "Verify className/oldName/scope."));
        }

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        for (Map.Entry<Path, RewrittenFile> changed : previewResult.changedFiles().entrySet()) {
            Path absFile = PathUtils.resolveReal(entry.originalProjectDir(), changed.getKey());
            entry = editManager.writeFile(entry, absFile, changed.getValue().content(), null);
        }
        manager.updateEntry(entry);
        editManager.logEdit(toolName() + ": " + scope + " " + oldName + " -> " + newName
                + " in " + className + " [OpenRewrite]");

        // Bei Klassen-Renames: Quelldatei an den neuen Klassennamen anpassen
        if ("class".equals(scope)) {
            Path oldFile = targetFile;
            Path newFile = oldFile.resolveSibling(newName + ".java");
            if (!oldFile.equals(newFile)) {
                editManager.moveFile(oldFile, newFile);
                editManager.logEdit(toolName() + ": renamed file "
                        + oldFile.getFileName() + " -> " + newFile.getFileName());
            }
        }

        if (rewriteSyncManager != null) {
            rewriteSyncManager.onRewriteComplete(previewResult);
        }

        List<UnresolvedRef> unresolved = scanUnresolvedReferences(entry, oldName);

        StringBuilder sb = new StringBuilder();
        sb.append("Renamed: ").append(oldName).append(" -> ").append(newName).append(" [OpenRewrite]\n");
        sb.append("Callers updated: ").append(callersUpdated).append("\n");
        sb.append("Files changed: ").append(previewResult.changedFiles().size()).append("\n");
        if (!updateCallers) {
            sb.append("Mode: declaration only (updateCallers=false). Call sites were NOT modified.\n");
        }
        if (!unresolved.isEmpty()) {
            sb.append("Unresolved references (string literals):\n");
            for (UnresolvedRef ref : unresolved) {
                sb.append("  ").append(ref.file()).append(":").append(ref.line())
                        .append(" - ").append(ref.context()).append("\n");
            }
        }
        if (updateCallers) sb.append(formatFreshParsedWarning(freshTypes));
        sb.append(formatMultiModuleWarning(entry));
        return ok(sb);
    }

    // ========== SPOON PRE-ANALYSIS (read-only) ==========

    /**
     * Validiert die Symbol-Existenz und den Declaration-Only-Overload-Fall.
     *
     * @return null wenn gültig, sonst {errorCode, message}
     */
    private String[] validateSymbol(CtType<?> targetType, String oldName,
                                    String scope, boolean updateCallers) {
        switch (scope) {
            case "method" -> {
                long methodCount = targetType.getMethods().stream()
                        .filter(m -> m.getSimpleName().equals(oldName))
                        .count();
                if (methodCount == 0) {
                    return new String[]{"SYMBOL_NOT_FOUND",
                            "Method '" + oldName + "' not found in " + targetType.getQualifiedName()};
                }
                if (!updateCallers && methodCount > 1) {
                    return new String[]{"OVERLOADED_METHOD",
                            "Cannot rename method '" + oldName + "' in declaration-only mode "
                                    + "(updateCallers=false) when overloaded methods exist (" + methodCount
                                    + " overloads). Use updateCallers=true to rename all overloads globally."};
                }
            }
            case "field" -> {
                boolean found = targetType.getFields().stream()
                        .anyMatch(f -> f.getSimpleName().equals(oldName));
                if (!found) {
                    return new String[]{"SYMBOL_NOT_FOUND",
                            "Field '" + oldName + "' not found in " + targetType.getQualifiedName()};
                }
            }
            case "class" -> {
                if (!targetType.getSimpleName().equals(oldName)) {
                    return new String[]{"SYMBOL_NOT_FOUND",
                            "Class '" + targetType.getQualifiedName() + "' is not named '" + oldName + "'."};
                }
            }
            default -> {
                return new String[]{"INVALID_SCOPE",
                        "scope must be 'method', 'field', or 'class'"};
            }
        }
        return null;
    }

    /** Zählt Occurrences (Deklarationen und ggf. Call-Sites) für die Antwort. */
    private int countOccurrences(ProjectEntry entry, CtType<?> targetType, String oldName,
                                 String scope, boolean updateCallers) {
        int count = 0;
        if ("method".equals(scope)) {
            count += targetType.getMethods().stream()
                    .filter(m -> m.getSimpleName().equals(oldName)).count();
            if (updateCallers) {
                for (CtType<?> type : entry.model().getAllTypes()) {
                    for (var method : type.getMethods()) {
                        if (method.getBody() == null) continue;
                        List<CtInvocation<?>> invocations = method.getBody()
                                .getElements(new TypeFilter<>(CtInvocation.class));
                        for (var inv : invocations) {
                            var exec = inv.getExecutable();
                            if (exec.getDeclaringType() != null
                                    && exec.getDeclaringType().getQualifiedName().equals(targetType.getQualifiedName())
                                    && exec.getSimpleName().equals(oldName)) {
                                count++;
                            }
                        }
                    }
                }
            }
        } else if ("field".equals(scope)) {
            count += targetType.getFields().stream()
                    .filter(f -> f.getSimpleName().equals(oldName)).count();
            if (updateCallers) {
                for (CtType<?> type : entry.model().getAllTypes()) {
                    for (var method : type.getMethods()) {
                        if (method.getBody() == null) continue;
                        var accesses = method.getBody()
                                .getElements(new TypeFilter<>(CtFieldAccess.class));
                        for (var fa : accesses) {
                            var fieldRef = fa.getVariable();
                            if (fieldRef.getDeclaringType() != null
                                    && fieldRef.getDeclaringType().getQualifiedName().equals(targetType.getQualifiedName())
                                    && fieldRef.getSimpleName().equals(oldName)) {
                                count++;
                            }
                        }
                    }
                }
            }
        } else {
            count = 1;
        }
        return count;
    }

    private record UnresolvedRef(String file, int line, String context) {}

    /** Scannt String-Literale auf Erwähnungen des alten Namens (Unresolved-Referenzen). */
    private List<UnresolvedRef> scanUnresolvedReferences(ProjectEntry entry, String oldName) {
        List<UnresolvedRef> result = new ArrayList<>();
        for (CtType<?> type : entry.model().getAllTypes()) {
            var literals = type.getElements(new TypeFilter<>(spoon.reflect.code.CtLiteral.class));
            for (var lit : literals) {
                if (lit.getValue() instanceof String s && s.contains(oldName)) {
                    String file = type.getPosition() != null && type.getPosition().getFile() != null
                            ? type.getPosition().getFile().getName() : type.getQualifiedName();
                    result.add(new UnresolvedRef(file, lit.getPosition().getLine(), "\"" + s + "\""));
                }
            }
        }
        return result;
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.analysis.ReferenceFinder;
import de.jare.rewisp.analysis.TypeLookup;
import de.jare.rewisp.analysis.TypeNotFoundException;
import de.jare.rewisp.api.RewispDriver.RenameRequest;
import de.jare.rewisp.api.RewispDriver.RenameResult;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.edit.StringLiteralScanner;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.util.PathUtils;
import de.jare.rewisp.util.ProjectLayout;

import spoon.reflect.declaration.CtType;

/**
 * {@code rename_symbol}: rename a class, method or field, including all callers. Spoon is used only for
 * pre-edit analysis (symbol validation, overload check, counting, string literal scan); the change itself
 * happens exclusively via OpenRewrite.
 *
 * @author Janusch Rentenatus
 */
final class RenameOperations {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "rename-symbol";

    private RenameOperations() {
    }

    static RenameResult rename(ProjectManager manager, EditManager editManager, RewriteEngine engine,
            EditPipeline pipeline, ProjectEntry entry, RenameRequest request) throws IOException {
        String className = request.className();
        String oldName = request.oldName();
        String newName = request.newName();
        String scope = request.scope() == null ? "method" : request.scope();
        boolean updateCallers = request.updateCallers() == null || request.updateCallers();

        EditPipeline.requireEditable(entry);

        if (engine == null) {
            throw new RewispException(ErrorType.OPENREWRITE_REQUIRED,
                    "rename_symbol requires the OpenRewrite engine. "
                            + "Per architecture decision there is no Spoon fallback for edits.",
                    Map.of("tool", "rename_symbol", "className", String.valueOf(className),
                            "oldName", String.valueOf(oldName)));
        }

        if (!"method".equals(scope) && !"field".equals(scope) && !"class".equals(scope)) {
            throw new RewispException(ErrorType.INVALID_SCOPE,
                    "scope must be 'method', 'field', or 'class'",
                    Map.of("scope", scope, "validScopes", "method, field, class"));
        }

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> targetType;
        try {
            targetType = TypeLookup.require(entry.model(), className, "Type not found: ");
        } catch (TypeNotFoundException e) {
            throw new RewispException(ErrorType.TYPE_NOT_FOUND, e.getMessage(), Map.of("className", e.typeName()));
        }

        Path targetFile = targetType.getPosition().getFile() != null
                ? targetType.getPosition().getFile().toPath() : null;
        if (targetFile == null) {
            throw new RewispException(ErrorType.NO_SOURCE_FILE,
                    "Cannot determine source file for class '" + className + "'.",
                    Map.of("className", className, "oldName", oldName));
        }

        validateSymbol(targetType, className, oldName, scope, updateCallers);

        // Zaehlung fuer die Antwort: umbenannte Deklarationen und Aufrufstellen getrennt
        int declarationsRenamed = countDeclarations(targetType, oldName, scope);
        int callersUpdated = updateCallers ? countCallSites(entry, targetType, oldName, scope) : 0;

        // ========== PHASE 2: OPENREWRITE EDIT ==========
        // Caller-Updates brauchen das GESAMTE Projekt im Scope (Typ-Attribution ueber Dateigrenzen);
        // declaration-only genuegt die Zieldatei.
        Set<Path> scopePaths = updateCallers
                ? Set.of()
                : Set.of(PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile));

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("oldName", oldName);
        params.put("newName", newName);
        params.put("scope", scope);
        params.put("updateCallers", String.valueOf(updateCallers));

        Map<String, Object> errorContext = Map.of("className", className, "oldName", oldName);
        RewriteEdit edit = new RewriteEdit(RECIPE_ID, params, scopePaths, updateCallers, true,
                errorContext,
                "OpenRewrite produced no changes renaming " + oldName + " to " + newName + ".",
                "The symbol may not be found by the recipe. Verify className/oldName/scope.",
                "rename_symbol: " + scope + " " + oldName + " -> " + newName
                        + " in " + className + " [OpenRewrite]");
        if ("class".equals(scope)) {
            // die Quelldatei wird nach dem Schreiben umbenannt: die Kompilierpruefung muss das mitrechnen
            Path oldRel = PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile);
            edit = edit.withFileMoves(Map.of(oldRel, oldRel.resolveSibling(newName + ProjectLayout.JAVA_EXTENSION)));
        }
        var outcome = pipeline.preview(entry, entry.name(), engine, edit, request.dryRun(),
                "(Disable this check with -Dedit.validate=false.)");
        if (outcome.dryRun()) {
            return new RenameResult(entry.name(), declarationsRenamed, callersUpdated, updateCallers,
                    outcome.preview(), outcome.dryRunSnapshot(), true, null, entry.modulesDetected(),
                    entry.modulesLoaded());
        }
        var previewResult = outcome.preview();

        // Klassen-Rename verschiebt die Quelldatei: Kollision vor dem ersten Schreiben pruefen
        if ("class".equals(scope)) {
            Path collision = targetFile.resolveSibling(newName + ProjectLayout.JAVA_EXTENSION);
            if (!targetFile.equals(collision)
                    && (Files.exists(collision)
                        || editManager.getPendingChanges().containsKey(PathUtils.canonical(collision)))) {
                throw new RewispException(ErrorType.CLASS_EXISTS,
                        "Cannot rename class: target file already exists: " + collision,
                        Map.of("className", className, "oldName", oldName, "newName", newName));
            }
        }

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        entry = pipeline.commit(entry, editManager, previewResult, edit);

        // Bei Klassen-Renames: Quelldatei an den neuen Klassennamen anpassen
        if ("class".equals(scope)) {
            Path oldFile = targetFile;
            Path newFile = oldFile.resolveSibling(newName + ProjectLayout.JAVA_EXTENSION);
            if (!oldFile.equals(newFile)) {
                editManager.moveFile(oldFile, newFile);
                editManager.logEdit("rename_symbol: renamed file "
                        + oldFile.getFileName() + " -> " + newFile.getFileName());
            }
        }

        StringLiteralScanner.Result unresolved = StringLiteralScanner.scan(entry.model(), oldName);
        return new RenameResult(entry.name(), declarationsRenamed, callersUpdated, updateCallers, previewResult,
                null, false, unresolved, entry.modulesDetected(), entry.modulesLoaded());
    }

    /** Validates the symbol existence and the declaration-only overload case. */
    private static void validateSymbol(CtType<?> targetType, String className, String oldName, String scope,
            boolean updateCallers) {
        Map<String, Object> ctx = Map.of("className", className, "oldName", oldName, "scope", scope);
        switch (scope) {
            case "method" -> {
                long methodCount = targetType.getMethods().stream()
                        .filter(m -> m.getSimpleName().equals(oldName))
                        .count();
                if (methodCount == 0) {
                    throw new RewispException(ErrorType.SYMBOL_NOT_FOUND,
                            "Method '" + oldName + "' not found in " + targetType.getQualifiedName(), ctx);
                }
                if (!updateCallers && methodCount > 1) {
                    throw new RewispException(ErrorType.OVERLOADED_METHOD,
                            "Cannot rename method '" + oldName + "' in declaration-only mode "
                                    + "(updateCallers=false) when overloaded methods exist (" + methodCount
                                    + " overloads). Use updateCallers=true to rename all overloads globally.", ctx);
                }
            }
            case "field" -> {
                boolean found = targetType.getFields().stream().anyMatch(f -> f.getSimpleName().equals(oldName));
                if (!found) {
                    throw new RewispException(ErrorType.SYMBOL_NOT_FOUND,
                            "Field '" + oldName + "' not found in " + targetType.getQualifiedName(), ctx);
                }
            }
            case "class" -> {
                if (!targetType.getSimpleName().equals(oldName)) {
                    throw new RewispException(ErrorType.SYMBOL_NOT_FOUND,
                            "Class '" + targetType.getQualifiedName() + "' is not named '" + oldName + "'.", ctx);
                }
            }
            default -> throw new RewispException(ErrorType.INVALID_SCOPE,
                    "scope must be 'method', 'field', or 'class'", ctx);
        }
    }

    /** Declarations for the response; the call sites come from the shared {@link ReferenceFinder} search. */
    private static int countDeclarations(CtType<?> targetType, String oldName, String scope) {
        if ("class".equals(scope)) return 1;
        return "method".equals(scope)
                ? (int) targetType.getMethods().stream().filter(m -> m.getSimpleName().equals(oldName)).count()
                : (int) targetType.getFields().stream().filter(f -> f.getSimpleName().equals(oldName)).count();
    }

    private static int countCallSites(ProjectEntry entry, CtType<?> targetType, String oldName, String scope) {
        if ("class".equals(scope)) {
            // Typbezuege (Felder, Parameter, Rueckgabetypen, new, extends ...) ausserhalb der Deklaration selbst
            return ReferenceFinder.find(entry.model(), targetType, null, null, null, entry.originalProjectDir()).size();
        }
        return ReferenceFinder.find(entry.model(), targetType, oldName, scope, null,
                entry.originalProjectDir()).size();
    }
}

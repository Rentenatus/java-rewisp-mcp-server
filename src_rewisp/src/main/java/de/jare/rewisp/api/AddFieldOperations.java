/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import de.jare.rewisp.api.RewispDriver.AddFieldRequest;
import de.jare.rewisp.api.RewispDriver.EditResult;
import de.jare.rewisp.edit.AutoImportResolver;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.util.JavaNames;
import de.jare.rewisp.util.PathUtils;

import spoon.reflect.declaration.CtType;

import static de.jare.rewisp.api.EditSupport.ctx;

/**
 * {@code add_field}: adds a field to a class. Spoon is used only for pre-edit analysis (duplicate check,
 * auto-import, unresolved-type detection); the change happens exclusively via OpenRewrite.
 *
 * @author Janusch Rentenatus
 */
final class AddFieldOperations {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "add-field";

    private AddFieldOperations() {
    }

    static EditResult add(EditManager editManager, RewriteEngine engine, EditPipeline pipeline, ProjectEntry entry,
            AddFieldRequest request) throws IOException {
        String className = request.className();
        String fieldName = request.fieldName();
        String type = request.type();
        String modifiers = request.modifiers();
        String initializer = request.initializer();

        EditPipeline.requireEditable(entry);

        EditSupport.requireEngine(engine, "add_field",
                ctx("tool", "add_field", "className", className, "fieldName", fieldName));

        // Eingaben syntaktisch pruefen, bevor irgendetwas geschrieben oder analysiert wird
        if (!JavaNames.isValidIdentifier(fieldName)) {
            throw new RewispException(ErrorType.INVALID_PARAMS, "Not a valid Java field name: '" + fieldName + "'",
                    ctx("className", className, "fieldName", String.valueOf(fieldName)));
        }
        if (!JavaNames.isValidTypeExpression(type)) {
            throw new RewispException(ErrorType.INVALID_PARAMS, "Not a valid Java type: '" + type + "' "
                    + "(expected e.g. int, String, java.util.List<String>, int[])",
                    ctx("className", className, "fieldName", fieldName, "type", String.valueOf(type)));
        }
        if (!JavaNames.isExpressionSnippet(initializer)) {
            throw new RewispException(ErrorType.INVALID_PARAMS, "Invalid initializer: '" + initializer
                    + "' (must be a single expression: balanced brackets, no ';' at top level)",
                    ctx("className", className, "fieldName", fieldName));
        }
        if (!JavaNames.isValidFieldModifiers(modifiers)) {
            throw new RewispException(ErrorType.INVALID_PARAMS, "Invalid modifiers: '" + modifiers + "' "
                    + "(allowed: public, protected, private, static, final, volatile, transient; no repeats)",
                    ctx("className", className, "fieldName", fieldName, "modifiers", String.valueOf(modifiers)));
        }

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> targetType = EditSupport.requireType(entry, className);

        Path targetFile = EditSupport.sourceFile(targetType);
        if (targetFile == null) {
            throw new RewispException(ErrorType.NO_SOURCE_FILE,
                    "Cannot determine source file for class '" + className + "'.",
                    ctx("className", className, "fieldName", fieldName));
        }

        // Feld-Duplikat-Prüfung
        for (var field : targetType.getFields()) {
            if (field.getSimpleName().equals(fieldName)) {
                throw new RewispException(ErrorType.FIELD_EXISTS,
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
        var importResult = new AutoImportResolver().resolve(entry, targetType, checkText, request.imports());
        if (!importResult.unresolvedTypes().isEmpty()) {
            throw new RewispException(ErrorType.UNRESOLVED_TYPES,
                    "Cannot resolve type(s) in field declaration: " + importResult.unresolvedTypes()
                            + ". " + AutoImportResolver.importHint(importResult.unresolvedTypes()),
                    ctx("className", className, "fieldName", fieldName, "type", type,
                            "unresolvedTypes", importResult.unresolvedTypes().toString(),
                            "suggestion", "Use fully qualified names (e.g. java.awt.Color) or load the dependency."));
        }

        // ========== PHASE 2/3: OPENREWRITE EDIT + APPLY ==========
        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile);

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("fieldName", fieldName);
        params.put("type", type);
        params.put("modifiers", modifiers != null ? modifiers : "");
        params.put("initializer", initializer != null ? initializer : "");
        params.put("imports", String.join(",", importResult.importsToAdd()));

        RewriteEdit edit = new RewriteEdit(RECIPE_ID, params, Set.of(relativeFile), false, false,
                ctx("className", className, "fieldName", fieldName),
                "OpenRewrite produced no changes for field " + fieldName + " on " + className + ".",
                "The target class may not be found by the recipe. Verify className matches the source file.",
                "add_field: " + className + "." + fieldName + " (" + type + ") [OpenRewrite]");
        return EditSupport.apply(pipeline, editManager, engine, entry, edit, request.dryRun(),
                EditSupport.DEFAULT_COMPILE_HINT, importResult.importsToAdd());
    }
}

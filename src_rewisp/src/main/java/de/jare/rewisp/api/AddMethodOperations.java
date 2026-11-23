/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.jare.rewisp.api.RewispDriver.AddMethodRequest;
import de.jare.rewisp.api.RewispDriver.EditResult;
import de.jare.rewisp.edit.AutoImportResolver;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.edit.SourceCodeUtils;
import de.jare.rewisp.edit.TypeErasureChecker;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.util.JavaNames;
import de.jare.rewisp.util.PathUtils;

import spoon.reflect.declaration.CtType;

import static de.jare.rewisp.api.EditSupport.ctx;

/**
 * {@code add_method}: adds a method to a class or interface. Spoon is used only for
 * pre-edit analysis (erasure collision check, auto-import, unresolved-type detection); the change
 * happens exclusively via OpenRewrite.
 *
 * @author Janusch Rentenatus
 */
final class AddMethodOperations {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "add-method";

    private AddMethodOperations() {
    }

    /** Syntactic validation of the free text before anything is analyzed or written (injection protection). */
    private static void validateInputs(String className, String methodName, String returnType, String parameters,
            String modifiers, String body) {
        String bad = null;
        if (!JavaNames.isValidIdentifier(methodName)) {
            bad = "Not a valid Java method name: '" + methodName + "'";
        } else if (!"void".equals(returnType == null ? null : returnType.trim()) && !JavaNames.isValidTypeExpression(returnType)) {
            bad = "Not a valid Java return type: '" + returnType + "' (expected e.g. void, int, String, List<String>)";
        } else if (!JavaNames.isValidParameterList(parameters)) {
            bad = "Not a valid parameter list: '" + parameters + "' (expected 'Type name, Type name', e.g. 'int count, String label')";
        } else if (!JavaNames.isValidMethodModifiers(modifiers)) {
            bad = "Invalid modifiers: '" + modifiers + "' (no repeats, one visibility, abstract excludes private/static/final/synchronized/native/strictfp)";
        } else if (!JavaNames.isBalancedSnippet(body)) {
            bad = "The body has unbalanced braces/parentheses or an unterminated string/comment; it must be a self-contained block body (without enclosing braces)";
        } else if (body != null && !body.isBlank() && modifiers != null
                && Arrays.asList(modifiers.trim().split("[\\s,]+")).contains("abstract")) {
            bad = "An abstract method cannot have a body";
        }
        if (bad == null) return;
        throw new RewispException(ErrorType.INVALID_PARAMS, bad,
                ctx("className", String.valueOf(className), "methodName", String.valueOf(methodName)));
    }

    static EditResult add(EditManager editManager, RewriteEngine engine, EditPipeline pipeline, ProjectEntry entry,
            AddMethodRequest request) throws IOException {
        String className = request.className();
        String methodName = request.methodName();
        String returnType = request.returnType();
        String parameters = request.parameters();
        String modifiers = request.modifiers();
        String body = request.body();

        EditPipeline.requireEditable(entry);

        validateInputs(className, methodName, returnType, parameters, modifiers, body);

        EditSupport.requireEngine(engine, "add_method",
                ctx("tool", "add_method", "className", className, "methodName", methodName));

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> targetType = EditSupport.requireType(entry, className);

        Path targetFile = EditSupport.sourceFile(targetType);
        if (targetFile == null) {
            throw new RewispException(ErrorType.NO_SOURCE_FILE,
                    "Cannot determine source file for class '" + className + "'.",
                    ctx("className", className, "methodName", methodName));
        }

        // Erasure-Prüfung (Bracket-aware Split, damit Generics nicht auseinandergerissen werden)
        List<String> paramTypes = new ArrayList<>();
        if (parameters != null && !parameters.isBlank()) {
            for (String p : SourceCodeUtils.splitTopLevelCommas(parameters)) {
                paramTypes.add(erasedParamSimpleName(p));
            }
        }
        var clash = new TypeErasureChecker().checkErasure(targetType, methodName, paramTypes);
        if (clash.clash()) {
            throw new RewispException(ErrorType.ERASURE_COLLISION, clash.message(),
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
        var importResult = new AutoImportResolver().resolve(entry, targetType, importCheckText.toString(),
                request.imports());
        if (!importResult.unresolvedTypes().isEmpty()) {
            throw new RewispException(ErrorType.UNRESOLVED_TYPES,
                    "Cannot resolve type(s) in method signature or body: " + importResult.unresolvedTypes()
                            + ". " + AutoImportResolver.importHint(importResult.unresolvedTypes()),
                    ctx("className", className, "methodName", methodName,
                            "returnType", returnType, "parameters", parameters,
                            "unresolvedTypes", importResult.unresolvedTypes().toString(),
                            "suggestion", "Use fully qualified names (e.g. java.awt.Color) or load the dependency."));
        }

        // ========== PHASE 2/3: OPENREWRITE EDIT + APPLY ==========
        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile);

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("methodName", methodName);
        params.put("returnType", returnType);
        params.put("parameters", parameters != null ? parameters : "");
        params.put("modifiers", modifiers != null ? modifiers : "");
        params.put("body", body != null ? body : "");
        params.put("imports", String.join(",", importResult.importsToAdd()));

        RewriteEdit edit = new RewriteEdit(RECIPE_ID, params, Set.of(relativeFile), false, false,
                ctx("className", className, "methodName", methodName),
                "OpenRewrite produced no changes for method " + methodName + " on " + className + ".",
                "The target class may not be found by the recipe. Verify className matches the source file.",
                "add_method: " + className + "." + methodName
                        + "(" + (parameters == null ? "" : parameters) + ") [OpenRewrite]");
        return EditSupport.apply(pipeline, editManager, engine, entry, edit, request.dryRun(),
                EditSupport.DEFAULT_COMPILE_HINT, importResult.importsToAdd());
    }

    // ========== Hilfsmethoden ==========

    /**
     * Extracts the erasure-reduced simple type name from a
     * parameter fragment like "int count" or "Map&lt;K, V&gt; map".
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

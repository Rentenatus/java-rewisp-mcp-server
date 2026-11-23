/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import de.jare.rewisp.api.RewispDriver.EditResult;
import de.jare.rewisp.api.RewispDriver.ReplaceBodyRequest;
import de.jare.rewisp.edit.AutoImportResolver;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.edit.SourceCodeUtils;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.util.JavaNames;
import de.jare.rewisp.util.PathUtils;

import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import static de.jare.rewisp.api.EditSupport.ctx;

/**
 * {@code replace_method_body}: replaces the entire body of a method. Spoon is used only for pre-edit analysis
 * (method existence, signature disambiguation, abstract check, auto-import); the change happens
 * exclusively via OpenRewrite.
 *
 * @author Janusch Rentenatus
 */
final class ReplaceMethodBodyOperations {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "replace-method-body";

    private ReplaceMethodBodyOperations() {
    }

    static EditResult replace(EditManager editManager, RewriteEngine engine, EditPipeline pipeline,
            ProjectEntry entry, ReplaceBodyRequest request) throws IOException {
        String className = request.className();
        String methodName = request.methodName();
        String signature = request.signature();
        String newBody = request.newBody();

        EditPipeline.requireEditable(entry);

        String bad = null;
        if (!JavaNames.isBalancedSnippet(newBody)) {
            bad = "The new body has unbalanced braces/parentheses or an unterminated string/comment; it must be a self-contained block body (without enclosing braces)";
        } else if (signature != null && !signature.isBlank() && !signature.trim().equals("()")) {
            for (String t : signature.split(",(?![^<]*>)")) {
                String typeText = t.trim().endsWith("...") ? t.trim().substring(0, t.trim().length() - 3) : t;
                if (!JavaNames.isValidTypeExpression(typeText)) {
                    bad = "Not a valid signature type list: '" + signature + "' (expected e.g. 'int, String')";
                    break;
                }
            }
        }
        if (bad != null) {
            throw new RewispException(ErrorType.INVALID_PARAMS, bad,
                    ctx("className", className, "methodName", String.valueOf(methodName)));
        }

        EditSupport.requireEngine(engine, "replace_method_body",
                ctx("tool", "replace_method_body", "className", className, "methodName", methodName));

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> targetType = EditSupport.requireType(entry, className);

        Path targetFile = EditSupport.sourceFile(targetType);
        if (targetFile == null) {
            throw new RewispException(ErrorType.NO_SOURCE_FILE,
                    "Cannot determine source file for class " + targetType.getSimpleName(),
                    ctx("className", className, "methodName", methodName));
        }

        // Methoden-Existenz + Signatur-Disambiguierung
        List<CtMethod<?>> candidates = targetType.getMethods().stream()
                .filter(m -> m.getSimpleName().equals(methodName))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            throw new RewispException(ErrorType.METHOD_NOT_FOUND,
                    "No method named '" + methodName + "' found in class " + targetType.getSimpleName(),
                    ctx("className", className, "methodName", methodName));
        }
        CtMethod<?> target;
        if (signature != null && !signature.isBlank()) {
            List<String> sigParams = SourceCodeUtils.splitTopLevelCommas(signature);
            target = candidates.stream().filter(m -> paramsMatch(m, sigParams)).findFirst().orElse(null);
            if (target == null) {
                throw new RewispException(ErrorType.SIGNATURE_NOT_FOUND,
                        "No method matching signature '" + methodName + "(" + signature + ")' found in class "
                                + targetType.getSimpleName() + ". Available: " + listAvailableSignatures(candidates),
                        ctx("className", className, "methodName", methodName, "signature", signature));
            }
        } else if (candidates.size() == 1) {
            target = candidates.get(0);
        } else {
            throw new RewispException(ErrorType.OVERLOADED_METHOD,
                    "Multiple methods named '" + methodName + "' found. Specify signature. Available: "
                            + listAvailableSignatures(candidates),
                    ctx("className", className, "methodName", methodName,
                            "suggestion", "Provide the 'signature' parameter to disambiguate."));
        }
        if (target.getBody() == null) {
            throw new RewispException(ErrorType.NO_BODY,
                    "Method '" + methodName + "' has no body (abstract or interface method). "
                            + "Cannot replace body of a method without one.",
                    ctx("className", className, "methodName", methodName));
        }

        // Auto-Import-Resolution für den neuen Rumpf
        var importResult = new AutoImportResolver().resolve(entry, targetType, newBody, request.imports());
        if (!importResult.unresolvedTypes().isEmpty()) {
            throw new RewispException(ErrorType.UNRESOLVED_TYPES,
                    "Cannot resolve type(s) in method body: " + importResult.unresolvedTypes()
                            + ". No matching import found in project or declared dependencies. "
                            + AutoImportResolver.importHint(importResult.unresolvedTypes()),
                    ctx("className", className, "methodName", methodName,
                            "unresolvedTypes", importResult.unresolvedTypes().toString()));
        }

        // ========== PHASE 2/3: OPENREWRITE EDIT + APPLY ==========
        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile);

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("methodName", methodName);
        params.put("signature", signature != null ? signature : "");
        params.put("newBody", newBody != null ? newBody : "");
        params.put("imports", String.join(",", importResult.importsToAdd()));

        RewriteEdit edit = new RewriteEdit(RECIPE_ID, params, Set.of(relativeFile), false, false,
                ctx("className", className, "methodName", methodName),
                "OpenRewrite produced no changes replacing body of " + methodName + ".",
                "The method may not be found by the recipe. Verify className/methodName/signature.",
                "replace_method_body: " + className + "." + methodName + " [OpenRewrite]");
        return EditSupport.apply(pipeline, editManager, engine, entry, edit, request.dryRun(),
                EditSupport.DEFAULT_COMPILE_HINT, importResult.importsToAdd());
    }

    // ========== SPOON PRE-ANALYSIS Helpers ==========

    /** Compares the parameter types of a method with a signature list. */
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

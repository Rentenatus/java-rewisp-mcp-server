/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.jare.rewisp.analysis.ReferenceFinder;
import de.jare.rewisp.api.RewispDriver.EditResult;
import de.jare.rewisp.api.RewispDriver.SignatureRequest;
import de.jare.rewisp.api.RewispDriver.SignatureResult;
import de.jare.rewisp.edit.AutoImportResolver;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.recipes.RecipeSupport;
import de.jare.rewisp.util.JavaNames;
import de.jare.rewisp.util.PathUtils;

import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import static de.jare.rewisp.api.EditSupport.ctx;

/**
 * {@code rewrite_signature}: changes return type and/or parameters of a method. Spoon is used only for
 * pre-edit analysis (method existence, overload exclusion, auto-import, caller counting); the change happens
 * exclusively via OpenRewrite.
 *
 * @author Janusch Rentenatus
 */
final class RewriteSignatureOperations {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "rewrite-signature";

    /** Hint of the compile check: this tool has its own escape hatch with {@code allowBreakingChanges}. */
    private static final String COMPILE_HINT =
            "Pass allowBreakingChanges=true to apply it anyway (callers stay broken until you fix them), "
                    + "or disable the check with -Dedit.validate=false.";

    private RewriteSignatureOperations() {
    }

    private static String modeOf(SignatureRequest request) {
        return request.mode() == null ? "signature_and_callers" : request.mode();
    }

    /** The mode is checked before project access. */
    static void requireValidMode(SignatureRequest request) {
        String mode = modeOf(request);
        if (!"signature_only".equals(mode) && !"signature_and_callers".equals(mode)) {
            throw new RewispException(ErrorType.INVALID_MODE,
                    "mode must be 'signature_only' or 'signature_and_callers'",
                    ctx("mode", mode, "validModes", "signature_only, signature_and_callers"));
        }
    }

    static SignatureResult rewrite(EditManager editManager, RewriteEngine engine, EditPipeline pipeline,
            ProjectEntry entry, SignatureRequest request) throws IOException {
        String className = request.className();
        String methodName = request.methodName();
        String newReturnType = request.newReturnType();
        String newParameters = request.newParameters();
        String mode = modeOf(request);

        EditPipeline.requireEditable(entry);

        String bad = null;
        if (newReturnType != null && !newReturnType.isBlank() && !"void".equals(newReturnType.trim())
                && !JavaNames.isValidTypeExpression(newReturnType)) {
            bad = "Not a valid Java return type: '" + newReturnType + "'";
        } else if (newParameters != null && !JavaNames.isValidParameterList(newParameters)) {
            bad = "Not a valid parameter list: '" + newParameters + "' (expected 'Type name, Type name', e.g. 'int count, String label')";
        }
        if (bad != null) {
            throw new RewispException(ErrorType.INVALID_PARAMS, bad,
                    ctx("className", className, "methodName", String.valueOf(methodName)));
        }

        EditSupport.requireEngine(engine, "rewrite_signature",
                ctx("tool", "rewrite_signature", "className", className, "methodName", methodName));

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> targetType = EditSupport.requireType(entry, className);

        Path targetFile = EditSupport.sourceFile(targetType);
        if (targetFile == null) {
            throw new RewispException(ErrorType.NO_SOURCE_FILE,
                    "Cannot determine source file for class " + targetType.getSimpleName(),
                    ctx("className", className, "methodName", methodName));
        }

        // Methode muss eindeutig sein (Overloads nicht unterstützt)
        List<CtMethod<?>> candidates = new ArrayList<>();
        for (CtMethod<?> m : targetType.getMethods()) {
            if (m.getSimpleName().equals(methodName)) candidates.add(m);
        }
        if (candidates.isEmpty()) {
            throw new RewispException(ErrorType.METHOD_NOT_FOUND,
                    "Method '" + methodName + "' not found in " + targetType.getSimpleName(),
                    ctx("className", className, "methodName", methodName));
        }
        if (candidates.size() > 1) {
            throw new RewispException(ErrorType.OVERLOADED_METHOD,
                    "Multiple methods named '" + methodName + "'. Not yet supported for overloaded methods.",
                    ctx("className", className, "methodName", methodName,
                            "suggestion", "Rename the overloads first or edit the source manually."));
        }
        CtMethod<?> target = candidates.get(0);

        // Defaults: nicht angegebene Teile der Signatur bleiben unverändert
        String retType = newReturnType != null ? newReturnType : target.getType().getSimpleName();
        String params = newParameters != null ? newParameters : getParamString(target);
        String oldParams = getParamString(target);

        // Signatur unverändert? Dann kein Edit nötig. Vergleich über die
        // effektiven Werte (gegebenenfalls Defaults), nicht über die Präsenz.
        boolean retChanged = newReturnType != null && !newReturnType.isBlank()
                && !sameReturnType(newReturnType, target.getType());
        boolean paramChanged = newParameters != null && !sameParameters(newParameters, target);
        boolean signatureChanged = retChanged || paramChanged;
        if (!signatureChanged) {
            return new SignatureResult(entry.name(), true, oldParams, null, mode, 0, null);
        }

        // Caller-Zählung (Spoon-Analyse)
        int callersUpdated = 0;
        if ("signature_and_callers".equals(mode)) {
            callersUpdated = countCallers(entry, targetType, methodName);
        }

        // Auto-Import-Resolution fuer neue Rueckgabe-/Parametertypen (wie add_method)
        StringBuilder importCheckText = new StringBuilder();
        if (newReturnType != null) importCheckText.append(newReturnType);
        if (newParameters != null && !newParameters.isBlank()) importCheckText.append(' ').append(newParameters);
        var importResult = new AutoImportResolver().resolve(entry, targetType, importCheckText.toString(),
                request.imports());
        if (!importResult.unresolvedTypes().isEmpty()) {
            throw new RewispException(ErrorType.UNRESOLVED_TYPES,
                    "Cannot resolve type(s) in the new signature: " + importResult.unresolvedTypes()
                            + ". " + AutoImportResolver.importHint(importResult.unresolvedTypes()),
                    ctx("className", className, "methodName", methodName,
                            "unresolvedTypes", importResult.unresolvedTypes().toString(),
                            "suggestion", "Use fully qualified names (e.g. java.awt.Color) or load the dependency."));
        }

        // ========== PHASE 2/3: OPENREWRITE EDIT + APPLY ==========
        // Caller-Updates brauchen das gesamte Projekt im Scope
        Set<Path> scopePaths = "signature_and_callers".equals(mode)
                ? Set.of()
                : Set.of(PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile));

        Map<String, String> params2 = new HashMap<>();
        params2.put("className", className);
        params2.put("methodName", methodName);
        params2.put("newReturnType", newReturnType != null ? newReturnType : "");
        // Fehlender Schluessel = Parameter unveraendert; "" hiesse "alle Parameter loeschen"
        if (newParameters != null) {
            params2.put("newParameters", newParameters);
        }
        params2.put("mode", mode);
        params2.put("imports", String.join(",", importResult.importsToAdd()));

        RewriteEdit edit = new RewriteEdit(RECIPE_ID, params2, scopePaths,
                "signature_and_callers".equals(mode), true,
                ctx("className", className, "methodName", methodName),
                "OpenRewrite produced no changes rewriting signature of " + methodName + ".",
                "The method may not be found by the recipe.",
                "rewrite_signature: " + className + "." + methodName + " -> "
                        + retType + " " + methodName + "(" + params + ") [OpenRewrite]");
        if (request.allowBreakingChanges()) edit = edit.withoutCompileCheck();
        EditResult result = EditSupport.apply(pipeline, editManager, engine, entry, edit, request.dryRun(),
                COMPILE_HINT, importResult.importsToAdd());
        return new SignatureResult(result.project(), false, oldParams, retType + " " + methodName + "(" + params + ")",
                mode, callersUpdated, result);
    }

    // ========== SPOON PRE-ANALYSIS Helpers ==========

    /** Normalizes a type expression: without package prefixes and whitespace. */
    private static String normalizeType(String type) {
        return type.replaceAll("(?:[a-z_][\\w]*\\.)+(?=[A-Z])", "").replaceAll("\\s+", "");
    }

    /** Same return type (generics and packages are normalized, not only the simple name). */
    private static boolean sameReturnType(String requested, spoon.reflect.reference.CtTypeReference<?> actual) {
        if (actual == null) return false;
        if (normalizeType(requested).equals(normalizeType(actual.toString()))) return true;
        // ohne Generics angegeben: Vergleich auf den Simple-Namen
        return requested.indexOf('<') < 0
                && RecipeSupport.eraseType(requested).equals(actual.getSimpleName());
    }

    /** Same parameter list (type via erasure, name exact; whitespace and packages irrelevant). */
    private static boolean sameParameters(String requested, CtMethod<?> method) {
        List<String> wanted = RecipeSupport.splitTopLevel(requested);
        var actual = method.getParameters();
        if (wanted.size() != actual.size()) return false;
        for (int i = 0; i < wanted.size(); i++) {
            String w = wanted.get(i).trim();
            int sep = w.lastIndexOf(' ');
            if (sep < 0) return false;
            String wantedType = w.substring(0, sep).trim();
            String wantedName = w.substring(sep + 1).trim();
            var p = actual.get(i);
            if (!wantedName.equals(p.getSimpleName())) return false;
            String actualType = p.getType() != null ? p.getType().getSimpleName() : "";
            if (!RecipeSupport.eraseType(wantedType).equals(actualType)) return false;
        }
        return true;
    }

    private static String getParamString(CtMethod<?> method) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < method.getParameters().size(); i++) {
            if (i > 0) sb.append(", ");
            var p = method.getParameters().get(i);
            sb.append(p.getType() != null ? p.getType().getSimpleName() : "?")
                    .append(" ").append(p.getSimpleName());
        }
        return sb.toString();
    }

    /** Counts real call sites of the method (owner-checked) with the shared reference search. */
    private static int countCallers(ProjectEntry entry, CtType<?> targetType, String methodName) {
        return ReferenceFinder.find(entry.model(), targetType, methodName, "method",
                null, entry.originalProjectDir()).size();
    }
}

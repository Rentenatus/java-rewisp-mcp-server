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
import java.util.stream.Collectors;

import de.jare.rewisp.analysis.ReferenceFinder;
import de.jare.rewisp.api.RewispDriver.EditResult;
import de.jare.rewisp.api.RewispDriver.RemoveMemberRequest;
import de.jare.rewisp.api.RewispDriver.RemoveMemberResult;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.edit.SourceCodeUtils;
import de.jare.rewisp.edit.StringLiteralScanner;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.util.PathUtils;

import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import static de.jare.rewisp.api.EditSupport.ctx;

/**
 * {@code remove_member}: removes a method or field. Spoon is used only for pre-edit analysis
 * (reference search for safe mode, signature disambiguation, string literal scan); the change happens
 * exclusively via OpenRewrite.
 *
 * @author Janusch Rentenatus
 */
final class RemoveMemberOperations {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "remove-member";

    private RemoveMemberOperations() {
    }

    static RemoveMemberResult remove(EditManager editManager, RewriteEngine engine, EditPipeline pipeline,
            ProjectEntry entry, RemoveMemberRequest request) throws IOException {
        String className = request.className();
        String memberName = request.memberName();
        String scope = request.scope() == null ? "method" : request.scope();
        String signature = request.signature();
        boolean hard = "hard".equals(request.mode());

        EditPipeline.requireEditable(entry);

        EditSupport.requireEngine(engine, "remove_member",
                ctx("tool", "remove_member", "className", className, "memberName", memberName));

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> targetType = EditSupport.requireType(entry, className);

        Path targetFile = EditSupport.sourceFile(targetType);
        if (targetFile == null) {
            throw new RewispException(ErrorType.NO_SOURCE_FILE,
                    "Cannot determine source file for class '" + className + "'.",
                    ctx("className", className, "memberName", memberName));
        }

        if (!"method".equals(scope) && !"field".equals(scope)) {
            throw new RewispException(ErrorType.INVALID_SCOPE,
                    "scope must be 'method' or 'field'",
                    ctx("scope", scope == null ? "" : scope, "validScopes", "method, field"));
        }

        // Referenz-Suche (Safe Mode verweigert bei Referenzen)
        List<String> references = findReferences(entry, targetType, memberName, scope, signature);
        if (!hard && !references.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Cannot remove '").append(memberName).append("' — ").append(references.size())
                    .append(" reference(s) found:\n");
            for (String ref : references) sb.append("  ").append(ref).append("\n");
            sb.append("Use mode='hard' to remove anyway (dangling references will break compilation).");
            throw new RewispException(ErrorType.REFERENCES_EXIST, sb.toString(),
                    ctx("className", className, "memberName", memberName,
                            "referenceCount", references.size(),
                            "suggestion", "Remove the references first or use mode='hard'."));
        }

        // Member-Existenz + Signatur-Disambiguierung
        String[] analysisError = validateMember(targetType, memberName, scope, signature);
        if (analysisError != null) {
            throw new RewispException(analysisError[0], analysisError[1],
                    ctx("className", className, "memberName", memberName, "scope", scope));
        }

        // ========== PHASE 2/3: OPENREWRITE EDIT + APPLY ==========
        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile);

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("memberName", memberName);
        params.put("scope", scope);
        params.put("signature", signature != null ? signature : "");
        params.put("hard", String.valueOf(hard));

        RewriteEdit edit = new RewriteEdit(RECIPE_ID, params, Set.of(relativeFile), false, false,
                ctx("className", className, "memberName", memberName),
                "OpenRewrite produced no changes for removing " + memberName
                        + " from " + className + ".",
                "The member may not be found by the recipe. Verify className/memberName/signature.",
                "remove_member: " + scope + " " + memberName + " in " + className
                        + (hard ? " (hard mode)" : "") + " [OpenRewrite]");
        // Hard mode ist die ausdrueckliche Zustimmung, hängende Referenzen zu hinterlassen: keine Kompilierpruefung
        if (hard) edit = edit.withoutCompileCheck();
        var outcome = pipeline.preview(entry, entry.name(), engine, edit, request.dryRun(),
                EditSupport.DEFAULT_COMPILE_HINT);
        if (outcome.dryRun()) {
            return new RemoveMemberResult(new EditResult(entry.name(), outcome.preview(), outcome.dryRunSnapshot(),
                    true, entry.modulesDetected(), entry.modulesLoaded(), List.of()), hard, references, List.of());
        }
        entry = pipeline.commit(entry, editManager, outcome.preview(), edit);

        // Unresolved string-literal references
        List<String> stringRefs = scanStringLiterals(entry, memberName);
        return new RemoveMemberResult(new EditResult(entry.name(), outcome.preview(), null, false,
                entry.modulesDetected(), entry.modulesLoaded(), List.of()), hard, references, stringRefs);
    }

    /**
     * Validates member existence and signature disambiguation.
     *
     * @return null when valid, otherwise {errorCode, message}
     */
    private static String[] validateMember(CtType<?> targetType, String memberName, String scope,
            String signature) {
        if ("method".equals(scope)) {
            List<CtMethod<?>> matching = targetType.getMethods().stream()
                    .filter(m -> m.getSimpleName().equals(memberName))
                    .collect(Collectors.toList());
            if (matching.isEmpty()) {
                return new String[]{ErrorType.MEMBER_NOT_FOUND.name(),
                        "Method '" + memberName + "' not found in " + targetType.getQualifiedName()};
            }
            if (signature != null && !signature.isBlank()) {
                List<String> sigParams = SourceCodeUtils.splitTopLevelCommas(signature);
                boolean anyMatch = matching.stream().anyMatch(m -> paramsMatch(m, sigParams));
                if (!anyMatch) {
                    return new String[]{ErrorType.SIGNATURE_NOT_FOUND.name(),
                            "No method matching signature '" + memberName + "(" + signature
                                    + ")' found in " + targetType.getQualifiedName()};
                }
            } else if (matching.size() > 1) {
                return new String[]{ErrorType.OVERLOADED_METHOD.name(),
                        "Multiple methods named '" + memberName + "' in " + targetType.getQualifiedName()
                                + ". Provide the 'signature' parameter to disambiguate."};
            }
        } else if ("field".equals(scope)) {
            boolean found = targetType.getFields().stream()
                    .anyMatch(f -> f.getSimpleName().equals(memberName));
            if (!found) {
                return new String[]{ErrorType.MEMBER_NOT_FOUND.name(),
                        "Field '" + memberName + "' not found in " + targetType.getQualifiedName()};
            }
        }
        return null;
    }

    /**
     * Reference search over the whole type (shared {@link ReferenceFinder} logic like find_references).
     * If a signature is given, only references to exactly that overload count; references within
     * the member to be removed itself (recursion, self-reference) do not block the removal.
     */
    private static List<String> findReferences(ProjectEntry entry, CtType<?> targetType, String memberName,
            String scope, String signature) {
        List<String> parameterTypes = null;
        if ("method".equals(scope) && signature != null && !signature.isBlank()) {
            parameterTypes = SourceCodeUtils.splitTopLevelCommas(signature).stream()
                    .map(RemoveMemberOperations::eraseType).toList();
        }
        String selfOwner = targetType.getSimpleName() + "." + memberName;
        List<String> refs = new ArrayList<>();
        for (var r : ReferenceFinder.find(entry.model(), targetType, memberName, scope,
                parameterTypes, entry.originalProjectDir())) {
            boolean inside = "method".equals(scope) ? r.owner().startsWith(selfOwner + "(") : r.owner().equals(selfOwner);
            if (inside) continue;
            refs.add(r.location() + " in " + r.owner() + " " + r.description());
        }
        if ("method".equals(scope)) refs.addAll(overridingMethods(entry, targetType, memberName, parameterTypes));
        return refs;
    }

    /**
     * Methods in subtypes that override or implement the method to be removed: without the
     * declaration their {@code @Override} fails (or they lose their base), even if never called.
     */
    private static List<String> overridingMethods(ProjectEntry entry, CtType<?> targetType, String memberName,
            List<String> parameterTypes) {
        List<CtMethod<?>> targets = targetType.getMethods().stream()
                .filter(m -> m.getSimpleName().equals(memberName))
                .filter(m -> parameterTypes == null || paramsMatch(m, parameterTypes))
                .collect(Collectors.toList());
        List<String> result = new ArrayList<>();
        if (targets.isEmpty()) return result;
        for (CtType<?> sub : entry.model().getAllTypes()) {
            if (sub.equals(targetType) || !sub.isSubtypeOf(targetType.getReference())) continue;
            for (CtMethod<?> m : sub.getMethods()) {
                if (!m.getSimpleName().equals(memberName)) continue;
                if (targets.stream().noneMatch(m::isOverriding)) continue;
                String where = m.getPosition() != null && m.getPosition().isValidPosition()
                        ? m.getPosition().getFile().getName() + ":" + m.getPosition().getLine() : sub.getQualifiedName();
                result.add(where + " in " + sub.getSimpleName() + "." + memberName + " (overrides/implements " + targetType.getSimpleName() + "." + memberName + ")");
            }
        }
        return result;
    }

    /** Scans string literals for mentions of the member (unresolved-reference warning). */
    private static List<String> scanStringLiterals(ProjectEntry entry, String memberName) {
        StringLiteralScanner.Result scan = StringLiteralScanner.scan(entry.model(), memberName);
        List<String> result = new ArrayList<>();
        for (StringLiteralScanner.Hit h : scan.hits()) {
            result.add(h.file() + ":" + (h.line() > 0 ? String.valueOf(h.line()) : "?") + " — " + h.text());
        }
        if (scan.omitted() > 0) result.add("... and " + scan.omitted() + " more");
        return result;
    }

    /** Compares the parameter types of a method with a signature list (P54). */
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

    /** Strips Generics/Paket; normalisiert Varargs zu "[]". */
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
}

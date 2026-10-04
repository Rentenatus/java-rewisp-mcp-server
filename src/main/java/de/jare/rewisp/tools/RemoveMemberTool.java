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
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * The {@code RemoveMemberTool} — removes a method or field from a class.
 *
 * <p>Architektur (ab 1.1.1-REWRITE): Spoon wird NUR für die Vorab-Analyse verwendet
 * (Referenz-Suche für den Safe Mode, Signatur-Disambiguierung, String-Literal-Scan).
 * Die eigentliche Änderung erfolgt AUSSCHLIESSLICH über OpenRewrite
 * (RemoveMemberRecipe). Es gibt keinen Spoon-Fallback für Edit-Operationen mehr.</p>
 *
 * @author Janusch Rentenatus
 */
public class RemoveMemberTool extends BaseEditTool {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "remove-member";

    private final EditManager editManager;
    private final RewriteEngine rewriteEngine;
    private final RewriteSyncManager rewriteSyncManager;

    /**
     * Konstruktor mit vollständiger OpenRewrite-Unterstützung.
     * OpenRewrite ist Pflicht — es gibt keinen Spoon-Fallback mehr.
     */
    public RemoveMemberTool(ProjectManager manager, EditManager editManager,
                           RewriteEngine rewriteEngine,
                           RewriteSyncManager rewriteSyncManager) {
        super(manager);
        this.editManager = editManager;
        this.rewriteEngine = rewriteEngine;
        this.rewriteSyncManager = rewriteSyncManager;
    }

    @Override protected String toolName() { return "remove_member"; }
    @Override protected String toolDescription() {
        return "[REWRITE] Remove a method or field. Safe mode (default): refuses if references exist. "
                + "Hard mode: removes regardless and lists dangling references. "
                + "For overloaded methods, provide the optional 'signature' parameter "
                + "(comma-separated parameter types, e.g. 'int, String') to disambiguate. "
                + "Edit via OpenRewrite; Spoon only for pre-edit analysis.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "memberName", Map.of("type", "string", "description", "Method or field name to remove"),
            "scope", Map.of("type", "string", "description", "'method' or 'field' (default: method)"),
            "mode", Map.of("type", "string", "description", "'safe' (default) or 'hard'"),
            "signature", Map.of("type", "string", "description", "Comma-separated parameter types (e.g. 'int, String') to disambiguate overloaded methods"),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }
    @Override protected List<String> toolRequired() { return req("name", "className", "memberName"); }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String memberName = arg(request, "memberName");
        String scope = arg(request, "scope");
        String mode = arg(request, "mode");
        String signature = arg(request, "signature");
        if (scope == null) scope = "method";
        boolean hard = "hard".equals(mode);

        ProjectEntry entry = findEntry(name);
        requireEditable(entry);

        if (rewriteEngine == null) {
            return domainError("OPENREWRITE_REQUIRED",
                    "remove_member requires the OpenRewrite engine. "
                            + "Per architecture decision there is no Spoon fallback for edits.",
                    ctx("tool", toolName(), "className", className, "memberName", memberName));
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
                    ctx("className", className, "memberName", memberName));
        }

        if (!"method".equals(scope) && !"field".equals(scope)) {
            return domainError("INVALID_SCOPE",
                    "scope must be 'method' or 'field'",
                    ctx("scope", scope == null ? "" : scope, "validScopes", "method, field"));
        }

        // Referenz-Suche (Safe Mode verweigert bei Referenzen)
        List<String> references = findReferences(entry, targetType, memberName, scope);
        if (!hard && !references.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Cannot remove '").append(memberName).append("' — ").append(references.size())
                    .append(" reference(s) found:\n");
            for (String ref : references) sb.append("  ").append(ref).append("\n");
            sb.append("Use mode='hard' to remove anyway (dangling references will break compilation).");
            return domainError("REFERENCES_EXIST", sb.toString(),
                    ctx("className", className, "memberName", memberName,
                        "referenceCount", references.size(),
                        "suggestion", "Remove the references first or use mode='hard'."));
        }

        // Member-Existenz + Signatur-Disambiguierung
        String[] analysisError = validateMember(targetType, memberName, scope, signature);
        if (analysisError != null) {
            return domainError(analysisError[0], analysisError[1],
                    ctx("className", className, "memberName", memberName, "scope", scope));
        }

        // ========== PHASE 2: OPENREWRITE EDIT (mandatory) ==========
        ProjectState state = manager.getProjectState(name);
        if (state == null || state.snapshot() == null) {
            return domainError("NO_SNAPSHOT", "No snapshot available for project: " + name,
                    ctx("className", className, "memberName", memberName));
        }

        // Bei dreckigem Projekt (vorherige Edits): Snapshot aktualisieren, damit
        // OpenRewrite die aktuellen Dateiinhalte sieht. Schmaler Scope liest nur
        // die dreckigen Dateien neu (Stale-Map), breiter Scope baut komplett neu auf.
        String refreshError = refreshSnapshotForEdit(entry, false);
        if (refreshError != null) {
            return domainError("SNAPSHOT_REFRESH_FAILED",
                    "Could not refresh project snapshot before edit: " + refreshError,
                        ctx("className", className, "memberName", memberName));
        }

        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile);

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("memberName", memberName);
        params.put("scope", scope);
        params.put("signature", signature != null ? signature : "");
        params.put("hard", String.valueOf(hard));

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
                    ctx("className", className, "memberName", memberName,
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
                    "OpenRewrite produced no changes for removing " + memberName
                            + " from " + className + ".",
                    ctx("className", className, "memberName", memberName,
                        "suggestion", "The member may not be found by the recipe. "
                            + "Verify className/memberName/signature."));
        }

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        for (Map.Entry<Path, RewrittenFile> changed : previewResult.changedFiles().entrySet()) {
            Path absFile = PathUtils.resolveReal(entry.originalProjectDir(), changed.getKey());
            entry = editManager.writeFile(entry, absFile, changed.getValue().content(), null);
        }
        manager.updateEntry(entry);
        editManager.logEdit(toolName() + ": " + scope + " " + memberName + " in " + className
                + (hard ? " (hard mode)" : "") + " [OpenRewrite]");

        if (rewriteSyncManager != null) {
            rewriteSyncManager.onRewriteComplete(previewResult);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Removed: ").append(className).append(".").append(memberName)
                .append(" (").append(hard ? "hard" : "safe").append(" mode) [OpenRewrite]\n");
        if (hard && !references.isEmpty()) {
            sb.append("WARNING: Dangling references that will not compile:\n");
            for (String ref : references) sb.append("  ").append(ref).append("\n");
        }
        // Unresolved string-literal references
        List<String> stringRefs = scanStringLiterals(entry, memberName);
        if (!stringRefs.isEmpty()) {
            sb.append("Unresolved references (string literals):\n");
            for (String ref : stringRefs) sb.append("  ").append(ref).append("\n");
        }
        sb.append(formatMultiModuleWarning(entry));
        return ok(sb);
    }

    // ========== SPOON PRE-ANALYSIS (read-only) ==========

    /**
     * Validiert Member-Existenz und Signatur-Disambiguierung.
     *
     * @return null wenn gültig, sonst {errorCode, message}
     */
    private String[] validateMember(CtType<?> targetType, String memberName,
                                    String scope, String signature) {
        if ("method".equals(scope)) {
            List<CtMethod<?>> matching = targetType.getMethods().stream()
                    .filter(m -> m.getSimpleName().equals(memberName))
                    .collect(java.util.stream.Collectors.toList());
            if (matching.isEmpty()) {
                return new String[]{"MEMBER_NOT_FOUND",
                        "Method '" + memberName + "' not found in " + targetType.getQualifiedName()};
            }
            if (signature != null && !signature.isBlank()) {
                List<String> sigParams = splitTopLevelCommas(signature);
                boolean anyMatch = matching.stream().anyMatch(m -> paramsMatch(m, sigParams));
                if (!anyMatch) {
                    return new String[]{"SIGNATURE_NOT_FOUND",
                            "No method matching signature '" + memberName + "(" + signature
                                    + ")' found in " + targetType.getQualifiedName()};
                }
            } else if (matching.size() > 1) {
                return new String[]{"OVERLOADED_METHOD",
                        "Multiple methods named '" + memberName + "' in " + targetType.getQualifiedName()
                                + ". Provide the 'signature' parameter to disambiguate."};
            }
        } else if ("field".equals(scope)) {
            boolean found = targetType.getFields().stream()
                    .anyMatch(f -> f.getSimpleName().equals(memberName));
            if (!found) {
                return new String[]{"MEMBER_NOT_FOUND",
                        "Field '" + memberName + "' not found in " + targetType.getQualifiedName()};
            }
        }
        return null;
    }

    /** Referenz-Suche über die Spoon-Modelle aller geladenen Typen. */
    private List<String> findReferences(ProjectEntry entry, CtType<?> targetType, String memberName, String scope) {
        List<String> refs = new ArrayList<>();
        for (CtType<?> type : entry.model().getAllTypes()) {
            for (var method : type.getMethods()) {
                if (method.getBody() == null) continue;
                if ("method".equals(scope)) {
                    var invocations = method.getBody().getElements(new TypeFilter<>(CtInvocation.class));
                    for (var inv : invocations) {
                        var exec = inv.getExecutable();
                        if (exec.getDeclaringType() != null
                                && exec.getDeclaringType().getQualifiedName().equals(targetType.getQualifiedName())
                                && exec.getSimpleName().equals(memberName)) {
                            refs.add(type.getQualifiedName() + "." + method.getSimpleName() + " calls " + memberName);
                        }
                    }
                } else if ("field".equals(scope)) {
                    var accesses = method.getBody().getElements(new TypeFilter<>(CtFieldAccess.class));
                    for (var fa : accesses) {
                        var field = fa.getVariable();
                        if (field.getDeclaringType() != null
                                && field.getDeclaringType().getQualifiedName().equals(targetType.getQualifiedName())
                                && field.getSimpleName().equals(memberName)) {
                            refs.add(type.getQualifiedName() + "." + method.getSimpleName() + " accesses " + memberName);
                        }
                    }
                }
            }
        }
        return refs;
    }

    /** Scannt String-Literale auf Erwähnungen des Members (Unresolved-Reference-Warnung). */
    private List<String> scanStringLiterals(ProjectEntry entry, String memberName) {
        List<String> result = new ArrayList<>();
        for (CtType<?> type : entry.model().getAllTypes()) {
            var literals = type.getElements(new TypeFilter<>(spoon.reflect.code.CtLiteral.class));
            for (var lit : literals) {
                if (lit.getValue() instanceof String s && s.contains(memberName)) {
                    result.add(type.getQualifiedName() + ":" + lit.getPosition().getLine()
                            + " — \"" + s + "\"");
                }
            }
        }
        return result;
    }

    /** Vergleicht die Parameter-Typen einer Methode mit einer Signatur-Liste (P54). */
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

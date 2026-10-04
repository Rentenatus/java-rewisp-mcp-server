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
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.RewriteException;
import de.jare.rewisp.rewrite.RewriteRequest;
import de.jare.rewisp.rewrite.RewriteResult;
import de.jare.rewisp.rewrite.RewriteSyncManager;
import de.jare.rewisp.rewrite.RewrittenFile;
import de.jare.rewisp.snapshot.ProjectState;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

/**
 * The {@code AddAnnotationTool} — adds an annotation to a class, method, or field.
 *
 * <p>Architektur (ab 1.1.1-REWRITE): Spoon wird NUR für die Vorab-Analyse verwendet
 * (Ziel-Validierung, Duplikat-Prüfung, Overload-Check). Die eigentliche Änderung
 * erfolgt AUSSCHLIESSLICH über OpenRewrite (AddAnnotationRecipe). Es gibt keinen
 * Spoon-Fallback für Edit-Operationen mehr.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddAnnotationTool extends BaseEditTool {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "add-annotation";

    private final EditManager editManager;
    private final RewriteSyncManager rewriteSyncManager;
    private final RewriteEngine rewriteEngine;
    /**
     * Konstruktor mit vollständiger OpenRewrite-Unterstützung.
     * OpenRewrite ist Pflicht — es gibt keinen Spoon-Fallback mehr.
     */
    public AddAnnotationTool(ProjectManager manager, EditManager editManager,
                           RewriteEngine rewriteEngine,
                           RewriteSyncManager rewriteSyncManager) {
        super(manager);
        this.editManager = editManager;
        this.rewriteSyncManager = rewriteSyncManager;
        this.rewriteEngine = rewriteEngine;
    }

    @Override protected String toolName() { return "add_annotation"; }
    @Override protected String toolDescription() {
        return "[REWRITE] Add an annotation to a class, method, or field. "
                + "Checks for duplicates. Tracks stale files in RewriteSyncManager. "
                + "Edit via OpenRewrite; Spoon only for pre-edit analysis.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "targetType", Map.of("type", "string", "description", "'class', 'method', or 'field'"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "targetName", Map.of("type", "string", "description", "Method or field name (omit for class)"),
            "annotation", Map.of("type", "string", "description", "Annotation name (simple or qualified)"),
            "attributes", Map.of("type", "string", "description", "Optional: key=value pairs (e.g. 'value=\"/new\"')"),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }
    @Override protected java.util.List<String> toolRequired() {
        return req("name", "targetType", "className", "annotation");
    }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        String targetType = arg(request, "targetType");
        String className = arg(request, "className");
        String targetName = arg(request, "targetName");
        String annotation = arg(request, "annotation");
        String attributes = arg(request, "attributes");

        ProjectEntry entry = findEntry(name);
        requireEditable(entry);

        if (rewriteEngine == null) {
            return domainError("OPENREWRITE_REQUIRED",
                    "add_annotation requires the OpenRewrite engine. "
                            + "Per architecture decision there is no Spoon fallback for edits.",
                    ctx("tool", toolName(), "className", className, "annotation", annotation));
        }

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> type = entry.model().getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals(className))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Type not found: " + className));

        Path targetFile = type.getPosition().getFile() != null
                ? type.getPosition().getFile().toPath() : null;
        if (targetFile == null) {
            return domainError("NO_SOURCE_FILE",
                    "Cannot determine source file for class '" + className + "'.",
                    ctx("className", className, "annotation", annotation));
        }

        String[] analysisError = validateTarget(type, targetType, className, targetName, annotation);
        if (analysisError != null) {
            Map<String, Object> errCtx = ctx("className", className, "targetType", targetType,
                    "annotation", annotation);
            if (targetName != null) errCtx.put("targetName", targetName);
            if (analysisError.length > 2 && analysisError[2] != null) {
                errCtx.put("suggestion", analysisError[2]);
            }
            return domainError(analysisError[0], analysisError[1], errCtx);
        }

        // ========== PHASE 2: OPENREWRITE EDIT (mandatory) ==========
        ProjectState state = manager.getProjectState(name);
        if (state == null || state.snapshot() == null) {
            return domainError("NO_SNAPSHOT", "No snapshot available for project: " + name,
                    ctx("className", className, "annotation", annotation));
        }

        // Bei dreckigem Projekt (vorherige Edits): Snapshot aktualisieren, damit
        // OpenRewrite die aktuellen Dateiinhalte sieht. Schmaler Scope liest nur
        // die dreckigen Dateien neu (Stale-Map), breiter Scope baut komplett neu auf.
        String refreshError = refreshSnapshotForEdit(entry, false);
        if (refreshError != null) {
            return domainError("SNAPSHOT_REFRESH_FAILED",
                    "Could not refresh project snapshot before edit: " + refreshError,
                        ctx("className", className, "annotation", annotation));
        }

        // Real-Path-Resolver nötig: Spoon-Positionen nutzen ggf. Windows-Kurznamen (8.3),
        // originalProjectDir den Langnamen — plain relativize würde einen kaputten Scope liefern.
        Path relativeFile = de.jare.rewisp.util.PathUtils.relativizeFromRoot(
                entry.originalProjectDir(), targetFile);

        Map<String, String> params = new HashMap<>();
        params.put("targetType", targetType);
        params.put("className", className);
        params.put("targetName", targetName != null ? targetName : "");
        params.put("annotation", annotation);
        params.put("attributes", attributes != null ? attributes : "");

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
                    ctx("className", className, "annotation", annotation,
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
                    "OpenRewrite produced no changes for @" + annotation + " on "
                            + targetType + (targetName != null ? " " + targetName : "") + ".",
                    ctx("className", className, "annotation", annotation,
                        "suggestion", "The target may not be found by the recipe. "
                            + "Verify className/targetName match the source file."));
        }

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        // Kanonische (Real-)Pfade: konsistent mit Spoon-basierten Tools, damit
        // EditManager die Datei als "edited this session" erkennt.
        for (Map.Entry<Path, RewrittenFile> changed : previewResult.changedFiles().entrySet()) {
            Path absFile = de.jare.rewisp.util.PathUtils.resolveReal(
                    entry.originalProjectDir(), changed.getKey());
            entry = editManager.writeFile(entry, absFile, changed.getValue().content(), null);
        }
        manager.updateEntry(entry);
        editManager.logEdit(toolName() + ": @" + annotation + " on " + targetType
                + (targetName != null ? " " + targetName : "") + " in " + className
                + " [OpenRewrite]");

        if (rewriteSyncManager != null) {
            rewriteSyncManager.onRewriteComplete(previewResult);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Annotation added: @").append(annotation).append(" on ").append(targetType);
        if (targetName != null) sb.append(" ").append(targetName);
        sb.append(" [OpenRewrite]\n");
        sb.append(formatMultiModuleWarning(entry));
        return ok(sb);
    }

    // ========== SPOON PRE-ANALYSIS (read-only) ==========

    /**
     * Validiert das Ziel über die Spoon-Analyse.
     *
     * @return null wenn gültig, sonst {errorCode, message, suggestion}
     */
    private String[] validateTarget(CtType<?> type, String targetType, String className,
                                    String targetName, String annotation) {
        switch (targetType == null ? "" : targetType) {
            case "class" -> {
                if (hasAnnotation(type.getAnnotations(), annotation)) {
                    return new String[]{"DUPLICATE_ANNOTATION",
                            "Class '" + className + "' already has annotation @" + annotation + ".",
                            "Use edit_annotation to modify the existing annotation or remove_annotation first."};
                }
            }
            case "method" -> {
                if (targetName == null) {
                    return new String[]{"MISSING_TARGET_NAME",
                            "targetName required for method annotations.",
                            "Provide the 'targetName' parameter with the method name."};
                }
                long methodCount = type.getMethods().stream()
                        .filter(m -> m.getSimpleName().equals(targetName))
                        .count();
                if (methodCount == 0) {
                    throw new IllegalArgumentException(
                            "Method '" + targetName + "' not found in " + className);
                }
                if (methodCount > 1) {
                    return new String[]{"OVERLOADED_METHOD",
                            "Multiple methods named '" + targetName + "' in " + className
                                    + ". Annotation tools do not yet support overloaded methods.",
                            "Rename the overloaded methods to disambiguate, or edit the source manually."};
                }
                CtMethod<?> method = type.getMethods().stream()
                        .filter(m -> m.getSimpleName().equals(targetName))
                        .findFirst().orElseThrow();
                if (hasAnnotation(method.getAnnotations(), annotation)) {
                    return new String[]{"DUPLICATE_ANNOTATION",
                            "Method '" + targetName + "' already has annotation @" + annotation + ".",
                            "Use edit_annotation to modify or remove_annotation first."};
                }
                String overrideErr = validateOverride(method, annotation, targetName);
                if (overrideErr != null) {
                    return new String[]{"OVERRIDE_INVALID", overrideErr,
                            "Ensure the method overrides a supertype method, or remove @Override."};
                }
            }
            case "field" -> {
                if (targetName == null) {
                    return new String[]{"MISSING_TARGET_NAME",
                            "targetName required for field annotations.",
                            "Provide the 'targetName' parameter with the field name."};
                }
                CtField<?> field = type.getFields().stream()
                        .filter(f -> f.getSimpleName().equals(targetName))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Field '" + targetName + "' not found in " + className));
                if (hasAnnotation(field.getAnnotations(), annotation)) {
                    return new String[]{"DUPLICATE_ANNOTATION",
                            "Field '" + targetName + "' already has annotation @" + annotation + ".",
                            "Use edit_annotation to modify or remove_annotation first."};
                }
            }
            default -> {
                return new String[]{"INVALID_TARGET_TYPE",
                        "targetType must be 'class', 'method', or 'field'",
                        "validTypes: class, method, field"};
            }
        }
        return null;
    }

    private static boolean hasAnnotation(Iterable<CtAnnotation<?>> annotations, String search) {
        for (CtAnnotation<?> a : annotations) {
            if (annotationMatches(a.getAnnotationType().getQualifiedName(), search)) {
                return true;
            }
        }
        return false;
    }

    private static boolean annotationMatches(String qualifiedName, String search) {
        if (qualifiedName.equals(search)) return true;
        int dot = qualifiedName.lastIndexOf('.');
        return dot >= 0 && qualifiedName.substring(dot + 1).equals(search);
    }

    /**
     * Validiert, dass {@code @Override} nur auf eine Methode gesetzt wird,
     * die tatsächlich eine Supertyp-Methode überschreibt.
     */
    private static String validateOverride(CtMethod<?> method, String annotation, String targetName) {
        String simple = annotation;
        int dot = annotation.lastIndexOf('.');
        if (dot >= 0) simple = annotation.substring(dot + 1);
        if (!"Override".equals(simple)) return null;
        if (method.getTopDefinitions().isEmpty()) {
            return "Method '" + targetName + "' does not override any supertype method. "
                    + "@Override would cause a compile error.";
        }
        return null;
    }
}

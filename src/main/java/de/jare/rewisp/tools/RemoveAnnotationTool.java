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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

/**
 * The {@code RemoveAnnotationTool} — removes an annotation from a class, method, or field.
 *
 * <p>Architektur (ab 1.1.1-REWRITE): Spoon wird NUR für die Vorab-Analyse verwendet
 * (Ziel-Validierung, Overload-Check, Annotation-Existenz). Die eigentliche Änderung
 * erfolgt AUSSCHLIESSLICH über OpenRewrite (RemoveAnnotationRecipe). Es gibt keinen
 * Spoon-Fallback für Edit-Operationen mehr — die zeilenbasierte Annotations-Suche
 * entfällt.</p>
 *
 * @author Janusch Rentenatus
 */
public class RemoveAnnotationTool extends BaseEditTool {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "remove-annotation";

    private final EditManager editManager;
    private final RewriteSyncManager rewriteSyncManager;
    private final RewriteEngine rewriteEngine;
    /**
     * Konstruktor mit vollständiger OpenRewrite-Unterstützung.
     * OpenRewrite ist Pflicht — es gibt keinen Spoon-Fallback mehr.
     */
    public RemoveAnnotationTool(ProjectManager manager, EditManager editManager,
                           RewriteEngine rewriteEngine,
                           RewriteSyncManager rewriteSyncManager) {
        super(manager);
        this.editManager = editManager;
        this.rewriteSyncManager = rewriteSyncManager;
        this.rewriteEngine = rewriteEngine;
    }

    @Override protected String toolName() { return "remove_annotation"; }
    @Override protected String toolDescription() {
        return "[REWRITE] Remove an annotation from a class, method, or field. "
                + "Content via OpenRewrite; Spoon only for pre-edit analysis.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "targetType", Map.of("type", "string", "description", "'class', 'method', or 'field'"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "targetName", Map.of("type", "string", "description", "Method or field name (omit for class)"),
            "annotation", Map.of("type", "string", "description", "Annotation name (simple or qualified)"),
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

        ProjectEntry entry = findEntry(name);
        requireEditable(entry);

        if (rewriteEngine == null) {
            return domainError("OPENREWRITE_REQUIRED",
                    "remove_annotation requires the OpenRewrite engine. "
                            + "Per architecture decision there is no Spoon fallback for edits.",
                    ctx("tool", toolName(), "className", className, "annotation", annotation));
        }

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> type = entry.model().getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals(className))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Type not found: " + className));

        Path file = type.getPosition().getFile() != null
                ? type.getPosition().getFile().toPath() : null;
        if (file == null) {
            return domainError("NO_SOURCE_FILE", "Cannot determine source file.",
                    ctx("className", className, "annotation", annotation));
        }

        String targetErr = validateAnnotationTarget(targetType, targetName);
        if (targetErr != null) {
            return domainError("INVALID_TARGET", targetErr,
                    ctx("targetType", targetType, "targetName", targetName));
        }

        // Annotation-Existenz prüfen
        String[] analysisError = validateAnnotationPresent(type, targetType, targetName, annotation);
        if (analysisError != null) {
            return domainError(analysisError[0], analysisError[1],
                    ctx("className", className, "targetType", targetType,
                        "targetName", targetName != null ? targetName : "",
                        "annotation", annotation));
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

        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), file);

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("targetType", targetType);
        params.put("targetName", targetName != null ? targetName : "");
        params.put("annotation", annotation);

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
                    ctx("className", className, "annotation", annotation, "errorCode", e.code()));
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
                    "OpenRewrite produced no changes removing @" + annotation + ".",
                    ctx("className", className, "annotation", annotation));
        }

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        for (Map.Entry<Path, RewrittenFile> changed : previewResult.changedFiles().entrySet()) {
            Path absFile = PathUtils.resolveReal(entry.originalProjectDir(), changed.getKey());
            entry = editManager.writeFile(entry, absFile, changed.getValue().content(), null);
        }
        manager.updateEntry(entry);
        editManager.logEdit(toolName() + ": removed @" + annotation + " from " + targetType
                + (targetName != null ? " " + targetName : "") + " in " + className + " [OpenRewrite]");

        if (rewriteSyncManager != null) {
            rewriteSyncManager.onRewriteComplete(previewResult);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Annotation removed: @").append(annotation).append(" from ").append(targetType);
        if (targetName != null) sb.append(" ").append(targetName);
        sb.append(" [OpenRewrite]\n");
        sb.append(formatMultiModuleWarning(entry));
        return ok(sb);
    }

    // ========== SPOON PRE-ANALYSIS (read-only) ==========

    /**
     * Prüft, dass das Ziel existiert und die Annotation darauf vorhanden ist.
     *
     * @return null wenn gültig, sonst {errorCode, message}
     */
    private String[] validateAnnotationPresent(CtType<?> type, String targetType,
                                               String targetName, String annotation) {
        switch (targetType == null ? "" : targetType) {
            case "class" -> {
                if (!hasAnnotation(type.getAnnotations(), annotation)) {
                    return new String[]{"ANNOTATION_NOT_FOUND",
                            "Annotation '@" + annotation + "' not found on class "
                                    + type.getQualifiedName() + "."};
                }
            }
            case "method" -> {
                java.util.List<CtMethod<?>> matching = type.getMethods().stream()
                        .filter(m -> m.getSimpleName().equals(targetName))
                        .toList();
                if (matching.isEmpty()) {
                    return new String[]{"TARGET_NOT_FOUND",
                            "Method '" + targetName + "' not found in " + type.getQualifiedName()};
                }
                if (matching.size() > 1) {
                    return new String[]{"OVERLOADED_METHOD",
                            "Multiple methods named '" + targetName + "' in " + type.getQualifiedName()
                                    + ". Annotation tools do not yet support overloaded methods."};
                }
                if (!hasAnnotation(matching.get(0).getAnnotations(), annotation)) {
                    return new String[]{"ANNOTATION_NOT_FOUND",
                            "Annotation '@" + annotation + "' not found on method '" + targetName + "'."};
                }
            }
            case "field" -> {
                CtField<?> field = type.getFields().stream()
                        .filter(f -> f.getSimpleName().equals(targetName))
                        .findFirst().orElse(null);
                if (field == null) {
                    return new String[]{"TARGET_NOT_FOUND",
                            "Field '" + targetName + "' not found in " + type.getQualifiedName()};
                }
                if (!hasAnnotation(field.getAnnotations(), annotation)) {
                    return new String[]{"ANNOTATION_NOT_FOUND",
                            "Annotation '@" + annotation + "' not found on field '" + targetName + "'."};
                }
            }
            default -> {
                return new String[]{"INVALID_TARGET_TYPE",
                        "targetType must be 'class', 'method', or 'field'"};
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
}

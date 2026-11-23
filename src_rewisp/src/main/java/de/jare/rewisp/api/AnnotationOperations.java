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

import java.util.List;
import de.jare.rewisp.api.RewispDriver.EditAnnotationRequest;
import de.jare.rewisp.api.RewispDriver.RemoveAnnotationRequest;
import de.jare.rewisp.api.RewispDriver.AddAnnotationRequest;
import de.jare.rewisp.api.RewispDriver.EditResult;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.util.JavaNames;
import de.jare.rewisp.util.PathUtils;

import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import static de.jare.rewisp.api.EditSupport.ctx;

/**
 * {@code add_annotation}, {@code edit_annotation}, {@code remove_annotation}: annotations on classes, methods
 * and fields. Spoon is used only for pre-edit analysis (target validation, duplicate check, overload check,
 * annotation existence); the change happens exclusively via OpenRewrite.
 *
 * @author Janusch Rentenatus
 */
final class AnnotationOperations {

    private AnnotationOperations() {
    }

    // ==================== add_annotation ====================

    private static final String ADD_RECIPE_ID = "add-annotation";

    static EditResult add(EditManager editManager, RewriteEngine engine, EditPipeline pipeline, ProjectEntry entry,
            AddAnnotationRequest request) throws IOException {
        String targetType = request.targetType();
        String className = request.className();
        String targetName = request.targetName();
        String annotation = request.annotation();
        String attributes = request.attributes();

        EditPipeline.requireEditable(entry);

        String bad = null;
        if (!JavaNames.isValidAnnotationName(annotation)) {
            bad = "Not a valid annotation name: '" + annotation + "'";
        } else if (!JavaNames.isExpressionSnippet(attributes)) {
            bad = "Invalid annotation attributes: '" + attributes + "' (expected e.g. value=\"x\", since=\"1.0\"; unbalanced brackets or ';' are not allowed)";
        }
        if (bad != null) {
            throw new RewispException(ErrorType.INVALID_PARAMS, bad,
                    ctx("className", className, "annotation", String.valueOf(annotation)));
        }

        EditSupport.requireEngine(engine, "add_annotation",
                ctx("tool", "add_annotation", "className", className, "annotation", annotation));

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> type = EditSupport.requireType(entry, className);

        Path targetFile = EditSupport.sourceFile(type);
        if (targetFile == null) {
            throw new RewispException(ErrorType.NO_SOURCE_FILE,
                    "Cannot determine source file for class '" + className + "'.",
                    ctx("className", className, "annotation", annotation));
        }

        String[] analysisError = validateAddTarget(type, targetType, className, targetName, annotation);
        if (analysisError != null) {
            Map<String, Object> errCtx = ctx("className", className, "targetType", targetType,
                    "annotation", annotation);
            if (targetName != null) errCtx.put("targetName", targetName);
            if (analysisError.length > 2 && analysisError[2] != null) {
                errCtx.put("suggestion", analysisError[2]);
            }
            throw new RewispException(analysisError[0], analysisError[1], errCtx);
        }

        // ========== PHASE 2/3: OPENREWRITE EDIT + APPLY ==========
        // Real-Path-Resolver nötig: Spoon-Positionen nutzen ggf. Windows-Kurznamen (8.3),
        // originalProjectDir den Langnamen — plain relativize würde einen kaputten Scope liefern.
        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), targetFile);

        Map<String, String> params = new HashMap<>();
        params.put("targetType", targetType);
        params.put("className", className);
        params.put("targetName", targetName != null ? targetName : "");
        params.put("annotation", annotation);
        params.put("attributes", attributes != null ? attributes : "");

        RewriteEdit edit = new RewriteEdit(ADD_RECIPE_ID, params, Set.of(relativeFile), false, false,
                ctx("className", className, "annotation", annotation),
                "OpenRewrite produced no changes for @" + annotation + " on "
                        + targetType + (targetName != null ? " " + targetName : "") + ".",
                "The target may not be found by the recipe. Verify className/targetName match the source file.",
                "add_annotation: @" + annotation + " on " + targetType
                        + (targetName != null ? " " + targetName : "") + " in " + className
                        + " [OpenRewrite]");
        return EditSupport.apply(pipeline, editManager, engine, entry, edit, request.dryRun(),
                EditSupport.DEFAULT_COMPILE_HINT, java.util.List.of());
    }

    /**
     * Validates the target via the Spoon analysis.
     *
     * @return null when valid, otherwise {errorCode, message, suggestion}
     */
    private static String[] validateAddTarget(CtType<?> type, String targetType, String className,
            String targetName, String annotation) {
        switch (targetType == null ? "" : targetType) {
            case "class" -> {
                if (hasAnnotation(type.getAnnotations(), annotation)) {
                    return new String[]{ErrorType.DUPLICATE_ANNOTATION.name(),
                            "Class '" + className + "' already has annotation @" + annotation + ".",
                            "Use edit_annotation to modify the existing annotation or remove_annotation first."};
                }
            }
            case "method" -> {
                if (targetName == null) {
                    return new String[]{ErrorType.MISSING_TARGET_NAME.name(),
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
                    return new String[]{ErrorType.OVERLOADED_METHOD.name(),
                            "Multiple methods named '" + targetName + "' in " + className
                                    + ". Annotation tools do not yet support overloaded methods.",
                            "Rename the overloaded methods to disambiguate, or edit the source manually."};
                }
                CtMethod<?> method = type.getMethods().stream()
                        .filter(m -> m.getSimpleName().equals(targetName))
                        .findFirst().orElseThrow();
                if (hasAnnotation(method.getAnnotations(), annotation)) {
                    return new String[]{ErrorType.DUPLICATE_ANNOTATION.name(),
                            "Method '" + targetName + "' already has annotation @" + annotation + ".",
                            "Use edit_annotation to modify or remove_annotation first."};
                }
                String overrideErr = validateOverride(method, annotation, targetName);
                if (overrideErr != null) {
                    return new String[]{ErrorType.OVERRIDE_INVALID.name(), overrideErr,
                            "Ensure the method overrides a supertype method, or remove @Override."};
                }
            }
            case "field" -> {
                if (targetName == null) {
                    return new String[]{ErrorType.MISSING_TARGET_NAME.name(),
                            "targetName required for field annotations.",
                            "Provide the 'targetName' parameter with the field name."};
                }
                CtField<?> field = type.getFields().stream()
                        .filter(f -> f.getSimpleName().equals(targetName))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Field '" + targetName + "' not found in " + className));
                if (hasAnnotation(field.getAnnotations(), annotation)) {
                    return new String[]{ErrorType.DUPLICATE_ANNOTATION.name(),
                            "Field '" + targetName + "' already has annotation @" + annotation + ".",
                            "Use edit_annotation to modify or remove_annotation first."};
                }
            }
            default -> {
                return new String[]{ErrorType.INVALID_TARGET_TYPE.name(),
                        "targetType must be 'class', 'method', or 'field'",
                        "validTypes: class, method, field"};
            }
        }
        return null;
    }

    /**
     * Validates that {@code @Override} is only set on a method that
     * actually overrides a supertype method.
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

    // ==================== edit_annotation ====================

    private static final String EDIT_RECIPE_ID = "edit-annotation";

    static EditResult edit(EditManager editManager, RewriteEngine engine, EditPipeline pipeline, ProjectEntry entry,
            EditAnnotationRequest request) throws IOException {
        String targetType = request.targetType();
        String className = request.className();
        String targetName = request.targetName();
        String annotation = request.annotation();
        String newAttributes = request.newAttributes();

        EditPipeline.requireEditable(entry);

        String bad = null;
        if (!JavaNames.isValidAnnotationName(annotation)) {
            bad = "Not a valid annotation name: '" + annotation + "'";
        } else if (!JavaNames.isExpressionSnippet(newAttributes)) {
            bad = "Invalid annotation attributes: '" + newAttributes + "' (expected e.g. value=\"x\", since=\"1.0\"; unbalanced brackets or ';' are not allowed)";
        }
        if (bad != null) {
            throw new RewispException(ErrorType.INVALID_PARAMS, bad,
                    ctx("className", className, "annotation", String.valueOf(annotation)));
        }

        EditSupport.requireEngine(engine, "edit_annotation",
                ctx("tool", "edit_annotation", "className", className, "annotation", annotation));

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> type = EditSupport.requireType(entry, className);

        Path file = EditSupport.sourceFile(type);
        if (file == null) {
            throw new RewispException(ErrorType.NO_SOURCE_FILE, "Cannot determine source file.",
                    ctx("className", className, "annotation", annotation));
        }

        targetType = targetType != null ? targetType : "class";
        String targetErr = AnnotationTargets.validate(targetType, targetName);
        if (targetErr != null) {
            throw new RewispException(ErrorType.INVALID_TARGET, targetErr,
                    ctx("targetType", targetType, "targetName", targetName));
        }

        // Ziel-Element lokalisieren + Annotation-Existenz prüfen
        String[] analysisError = validateAnnotationPresent(type, targetType, targetName, annotation);
        if (analysisError != null) {
            throw new RewispException(analysisError[0], analysisError[1],
                    ctx("className", className, "targetType", targetType,
                            "targetName", targetName != null ? targetName : "",
                            "annotation", annotation,
                            "suggestion", "Use add_annotation to add it first."));
        }

        // ========== PHASE 2/3: OPENREWRITE EDIT + APPLY ==========
        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), file);

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("targetType", targetType);
        params.put("targetName", targetName != null ? targetName : "");
        params.put("annotation", annotation);
        params.put("newAttributes", newAttributes != null ? newAttributes : "");

        RewriteEdit edit = new RewriteEdit(EDIT_RECIPE_ID, params, Set.of(relativeFile), false, false,
                ctx("className", className, "annotation", annotation),
                "OpenRewrite produced no changes editing @" + annotation + ".", null,
                "edit_annotation: @" + annotation + " attributes=[" + newAttributes
                        + "] on " + targetType + (targetName != null ? " " + targetName : "")
                        + " in " + className + " [OpenRewrite]");
        return EditSupport.apply(pipeline, editManager, engine, entry, edit, request.dryRun(),
                EditSupport.DEFAULT_COMPILE_HINT, java.util.List.of());
    }

    /**
     * Checks that the target exists and the annotation is present on it (edit and remove).
     *
     * @return null when valid, otherwise {errorCode, message}
     */
    private static String[] validateAnnotationPresent(CtType<?> type, String targetType, String targetName,
            String annotation) {
        switch (targetType == null ? "" : targetType) {
            case "class" -> {
                if (!hasAnnotation(type.getAnnotations(), annotation)) {
                    return new String[]{ErrorType.ANNOTATION_NOT_FOUND.name(),
                            "Annotation '@" + annotation + "' not found on class "
                                    + type.getQualifiedName() + "."};
                }
            }
            case "method" -> {
                List<CtMethod<?>> matching = type.getMethods().stream()
                        .filter(m -> m.getSimpleName().equals(targetName))
                        .toList();
                if (matching.isEmpty()) {
                    return new String[]{ErrorType.TARGET_NOT_FOUND.name(),
                            "Method '" + targetName + "' not found in " + type.getQualifiedName()};
                }
                if (matching.size() > 1) {
                    return new String[]{ErrorType.OVERLOADED_METHOD.name(),
                            "Multiple methods named '" + targetName + "' in " + type.getQualifiedName()
                                    + ". Annotation tools do not yet support overloaded methods."};
                }
                if (!hasAnnotation(matching.get(0).getAnnotations(), annotation)) {
                    return new String[]{ErrorType.ANNOTATION_NOT_FOUND.name(),
                            "Annotation '@" + annotation + "' not found on method '" + targetName + "'."};
                }
            }
            case "field" -> {
                CtField<?> field = type.getFields().stream()
                        .filter(f -> f.getSimpleName().equals(targetName))
                        .findFirst().orElse(null);
                if (field == null) {
                    return new String[]{ErrorType.TARGET_NOT_FOUND.name(),
                            "Field '" + targetName + "' not found in " + type.getQualifiedName()};
                }
                if (!hasAnnotation(field.getAnnotations(), annotation)) {
                    return new String[]{ErrorType.ANNOTATION_NOT_FOUND.name(),
                            "Annotation '@" + annotation + "' not found on field '" + targetName + "'."};
                }
            }
            default -> {
                return new String[]{ErrorType.INVALID_TARGET_TYPE.name(),
                        "targetType must be 'class', 'method', or 'field'"};
            }
        }
        return null;
    }

    // ==================== remove_annotation ====================

    private static final String REMOVE_RECIPE_ID = "remove-annotation";

    static EditResult remove(EditManager editManager, RewriteEngine engine, EditPipeline pipeline,
            ProjectEntry entry, RemoveAnnotationRequest request) throws IOException {
        String targetType = request.targetType();
        String className = request.className();
        String targetName = request.targetName();
        String annotation = request.annotation();

        EditPipeline.requireEditable(entry);

        if (!JavaNames.isValidAnnotationName(annotation)) {
            throw new RewispException(ErrorType.INVALID_PARAMS,
                    "Not a valid annotation name: '" + annotation + "'",
                    ctx("className", className, "annotation", String.valueOf(annotation)));
        }

        EditSupport.requireEngine(engine, "remove_annotation",
                ctx("tool", "remove_annotation", "className", className, "annotation", annotation));

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> type = EditSupport.requireType(entry, className);

        Path file = EditSupport.sourceFile(type);
        if (file == null) {
            throw new RewispException(ErrorType.NO_SOURCE_FILE, "Cannot determine source file.",
                    ctx("className", className, "annotation", annotation));
        }

        String targetErr = AnnotationTargets.validate(targetType, targetName);
        if (targetErr != null) {
            throw new RewispException(ErrorType.INVALID_TARGET, targetErr,
                    ctx("targetType", targetType, "targetName", targetName));
        }

        // Annotation-Existenz prüfen
        String[] analysisError = validateAnnotationPresent(type, targetType, targetName, annotation);
        if (analysisError != null) {
            throw new RewispException(analysisError[0], analysisError[1],
                    ctx("className", className, "targetType", targetType,
                            "targetName", targetName != null ? targetName : "",
                            "annotation", annotation));
        }

        // ========== PHASE 2/3: OPENREWRITE EDIT + APPLY ==========
        Path relativeFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), file);

        Map<String, String> params = new HashMap<>();
        params.put("className", className);
        params.put("targetType", targetType);
        params.put("targetName", targetName != null ? targetName : "");
        params.put("annotation", annotation);

        RewriteEdit edit = new RewriteEdit(REMOVE_RECIPE_ID, params, Set.of(relativeFile), false, false,
                ctx("className", className, "annotation", annotation),
                "OpenRewrite produced no changes removing @" + annotation + ".", null,
                "remove_annotation: removed @" + annotation + " from " + targetType
                        + (targetName != null ? " " + targetName : "") + " in " + className + " [OpenRewrite]");
        return EditSupport.apply(pipeline, editManager, engine, entry, edit, request.dryRun(),
                EditSupport.DEFAULT_COMPILE_HINT, java.util.List.of());
    }

    // ==================== gemeinsame Hilfen ====================

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

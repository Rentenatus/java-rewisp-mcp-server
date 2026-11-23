/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;

import java.text.ParseException;
import java.text.RuleBasedCollator;
import java.util.Comparator;
import java.util.List;

/**
 * OpenRewrite recipe for adding annotations to classes, methods or fields.
 * Replaces the Spoon-based edit implementation (Spoon remains responsible for the pre-analysis).
 *
 * <p>The visitor silently skips when the target already has the annotation
 * (duplicate check) or the target is not found. The structured error analysis
 * (DUPLICATE_ANNOTATION, OVERLOADED_METHOD etc.) is done in the tool via the Spoon analysis.</p>
 *
 * <p>Supports no-arg constructor + setters so that {@code OpenRewriteEngineImpl}
 * can instantiate the recipe reflectively from application.yaml.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddAnnotationRecipe extends Recipe {

    /** Sorts annotations alphabetically, @Override to the end (OpenRewrite convention). */
    private static final Comparator<J.Annotation> ANNOTATION_ORDER = createAnnotationOrder();

    private static Comparator<J.Annotation> createAnnotationOrder() {
        try {
            return Comparator.comparing(J.Annotation::getSimpleName,
                    new RuleBasedCollator("< Override"));
        } catch (ParseException e) {
            return (a, b) -> 1; // Fallback: neue Annotation ans Ende
        }
    }

    private String targetType = "class";
    private String className;
    private String targetName;
    private String annotation;
    private String attributes;

    /** No-arg constructor for reflective instantiation by the RewriteEngine. */
    public AddAnnotationRecipe() {
    }

    /**
     * Creates a new AddAnnotationRecipe.
     *
     * @param targetType the type of the target: "class", "method", or "field"
     * @param className the fully qualified class name
     * @param targetName the name of the method or field (may be null for classes)
     * @param annotation the name of the annotation (simple or fully qualified)
     * @param attributes optional: attribute pairs (e.g. "value=\"/new\"")
     */
    public AddAnnotationRecipe(String targetType, String className, String targetName,
                              String annotation, String attributes) {
        this.targetType = targetType;
        this.className = className;
        this.targetName = targetName;
        this.annotation = annotation;
        this.attributes = attributes;
    }

    // ========== Setter für die Reflexions-Instanziierung ==========

    public void setTargetType(String targetType) { this.targetType = targetType; }
    public void setClassName(String className) { this.className = className; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public void setAnnotation(String annotation) { this.annotation = annotation; }
    public void setAttributes(String attributes) { this.attributes = attributes; }

    // ========== Getter ==========

    public String getTargetType() { return targetType; }
    public String getClassName() { return className; }
    public String getTargetName() { return targetName; }
    public String getAnnotation() { return annotation; }
    public String getAttributes() { return attributes; }

    @Override
    public String getDisplayName() {
        return "Add annotation @" + simpleName(annotation);
    }

    @Override
    public String getDescription() {
        String target = "class".equals(targetType)
                ? String.valueOf(className)
                : className + "." + targetName;
        return "Adds @" + simpleName(annotation) + " to " + targetType + " " + target + ".";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
                if (!"class".equals(targetType) || !matchesClass(c) || hasAnnotation(c.getAllAnnotations())) {
                    return c;
                }
                return buildAnnotationTemplate().apply(getCursor(),
                        c.getCoordinates().addAnnotation(ANNOTATION_ORDER));
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if (!"method".equals(targetType) || targetName == null
                        || !targetName.equals(m.getSimpleName())
                        || hasAnnotation(m.getAllAnnotations())) {
                    return m;
                }
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return m;
                }
                return buildAnnotationTemplate().apply(getCursor(),
                        m.getCoordinates().addAnnotation(ANNOTATION_ORDER));
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVar, ExecutionContext ctx) {
                J.VariableDeclarations v = super.visitVariableDeclarations(multiVar, ctx);
                if (!"field".equals(targetType) || targetName == null
                        || !declaresVariable(v) || isLocalVariable()
                        || hasAnnotation(v.getAllAnnotations())) {
                    return v;
                }
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return v;
                }
                return buildAnnotationTemplate().apply(getCursor(),
                        v.getCoordinates().addAnnotation(ANNOTATION_ORDER));
            }

            // ========== Helper ==========

            /** Checks whether the declared class matches the className parameter. */
            private boolean matchesClass(J.ClassDeclaration c) {
                return RecipeSupport.matchesClass(className, c);
            }

            /** Checks whether one of the annotations already matches the target annotation type. */
            private boolean hasAnnotation(List<J.Annotation> annotations) {
                String targetSimple = simpleName(annotation);
                for (J.Annotation a : annotations) {
                    if (targetSimple.equals(a.getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            /** Checks whether the variable declaration contains the target variable. */
            private boolean declaresVariable(J.VariableDeclarations v) {
                for (J.VariableDeclarations.NamedVariable nv : v.getVariables()) {
                    if (targetName.equals(nv.getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            /** Checks whether the variable is local (inside a method) rather than a field. */
            private boolean isLocalVariable() {
                return getCursor().firstEnclosing(J.MethodDeclaration.class) != null;
            }

            /** Builds the JavaTemplate for the annotation; FQN annotations resolve the import as well. */
            private JavaTemplate buildAnnotationTemplate() {
                if (needsImport()) {
                    maybeAddImport(annotation, false);
                }
                String simple = simpleName(annotation);
                String source = "@" + simple;
                if (attributes != null && !attributes.isBlank()) {
                    source += "(" + attributes + ")";
                }
                JavaTemplate.Builder builder = JavaTemplate.builder(source);
                if (needsImport()) {
                    builder = builder.imports(annotation);
                }
                return builder.build();
            }

            /** Import only needed when the annotation is fully qualified and not java.lang. */
            private boolean needsImport() {
                return annotation.indexOf('.') >= 0 && !annotation.startsWith("java.lang.");
            }
        };
    }

    private static String simpleName(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}

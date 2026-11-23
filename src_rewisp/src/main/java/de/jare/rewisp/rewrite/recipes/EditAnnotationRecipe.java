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

import java.util.ArrayList;
import java.util.List;

/**
 * OpenRewrite recipe for editing the attributes of an existing annotation
 * on a class, method or field.
 * Replaces the Spoon-based edit implementation (Spoon remains responsible for the pre-analysis).
 *
 * <p>The annotation is replaced on the target element via the replaceAnnotations coordinate:
 * all other annotations of the element (printed as source text) are carried over. If the
 * target annotation does not exist on the element, the element is returned unchanged (the tool
 * then reports the structured error).</p>
 *
 * <p>Supports no-arg constructor + setters for reflective instantiation.</p>
 *
 * @author Janusch Rentenatus
 */
public class EditAnnotationRecipe extends Recipe {

    private String className;
    private String targetType;
    private String targetName;
    private String annotation;
    private String newAttributes;

    /** No-arg constructor for reflective instantiation by the RewriteEngine. */
    public EditAnnotationRecipe() {
    }

    /**
     * Creates a new EditAnnotationRecipe.
     *
     * @param targetType "class", "method" or "field"
     * @param className the fully qualified class name
     * @param targetName the name of the method/field (null for classes)
     * @param annotation the annotation name
     * @param newAttributes the new attribute pairs (e.g. "value=\"/new\"")
     */
    public EditAnnotationRecipe(String targetType, String className, String targetName,
                               String annotation, String newAttributes) {
        this.className = className;
        this.targetType = targetType;
        this.targetName = targetName;
        this.annotation = annotation;
        this.newAttributes = newAttributes;
    }

    // ========== Setter für die Reflexions-Instanziierung ==========

    public void setClassName(String className) { this.className = className; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public void setAnnotation(String annotation) { this.annotation = annotation; }
    public void setNewAttributes(String newAttributes) { this.newAttributes = newAttributes; }

    // ========== Getter ==========

    public String getClassName() { return className; }
    public String getTargetType() { return targetType; }
    public String getTargetName() { return targetName; }
    public String getAnnotation() { return annotation; }
    public String getNewAttributes() { return newAttributes; }

    @Override
    public String getDisplayName() {
        return "Edit annotation @" + annotation + " in " + className;
    }

    @Override
    public String getDescription() {
        String target = "class".equals(targetType)
                ? String.valueOf(className)
                : className + "." + targetName;
        return "Edits attributes of @" + annotation + " on " + targetType + " " + target + ".";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
                if (!"class".equals(targetType) || !matchesClass(c)
                        || !hasAnnotation(c.getLeadingAnnotations())) {
                    return c;
                }
                String newSrc = buildAnnotationSource(c.getLeadingAnnotations());
                return JavaTemplate.builder(newSrc).build()
                        .apply(getCursor(), c.getCoordinates().replaceAnnotations());
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if (!"method".equals(targetType) || targetName == null
                        || !targetName.equals(m.getSimpleName())
                        || !hasAnnotation(m.getLeadingAnnotations())) {
                    return m;
                }
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return m;
                }
                String newSrc = buildAnnotationSource(m.getLeadingAnnotations());
                return JavaTemplate.builder(newSrc).build()
                        .apply(getCursor(), m.getCoordinates().replaceAnnotations());
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVar, ExecutionContext ctx) {
                J.VariableDeclarations v = super.visitVariableDeclarations(multiVar, ctx);
                if (!"field".equals(targetType) || targetName == null
                        || !declaresVariable(v) || isLocalVariable()
                        || !hasAnnotation(v.getLeadingAnnotations())) {
                    return v;
                }
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return v;
                }
                String newSrc = buildAnnotationSource(v.getLeadingAnnotations());
                return JavaTemplate.builder(newSrc).build()
                        .apply(getCursor(), v.getCoordinates().replaceAnnotations());
            }

            // ========== Helper ==========

            private boolean matchesClass(J.ClassDeclaration c) {
                return RecipeSupport.matchesClass(className, c);
            }

            private boolean hasAnnotation(List<J.Annotation> annotations) {
                String simple = simpleName(annotation);
                for (J.Annotation a : annotations) {
                    if (simple.equals(a.getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            private boolean declaresVariable(J.VariableDeclarations v) {
                for (J.VariableDeclarations.NamedVariable nv : v.getVariables()) {
                    if (targetName.equals(nv.getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            private boolean isLocalVariable() {
                return getCursor().firstEnclosing(J.MethodDeclaration.class) != null;
            }

            /** Builds the new annotation set: target annotation replaced, neighbors kept. */
            private String buildAnnotationSource(List<J.Annotation> annotations) {
                List<String> parts = new ArrayList<>();
                for (J.Annotation a : annotations) {
                    if (simpleName(annotation).equals(a.getSimpleName())) {
                        parts.add(newAnnotationSource());
                    } else {
                        parts.add(a.toString());
                    }
                }
                return String.join(" ", parts);
            }

            private String newAnnotationSource() {
                String simple = simpleName(annotation);
                if (newAttributes != null && !newAttributes.isBlank()) {
                    return "@" + simple + "(" + newAttributes + ")";
                }
                return "@" + simple;
            }

            private static String simpleName(String name) {
                if (name == null) {
                    return "";
                }
                int dot = name.lastIndexOf('.');
                return dot >= 0 ? name.substring(dot + 1) : name;
            }
        };
    }
}

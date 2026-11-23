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
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenRewrite recipe for removing an annotation from a class,
 * method or field.
 * Replaces the Spoon-based edit implementation (Spoon remains responsible for the pre-analysis).
 *
 * <p>The visitor filters the target annotation out of the leading annotations of the
 * target element. If the annotation does not exist, the element is
 * returned unchanged (the tool then reports the structured error).</p>
 *
 * <p>Supports no-arg constructor + setters for reflective instantiation.</p>
 *
 * @author Janusch Rentenatus
 */
public class RemoveAnnotationRecipe extends Recipe {

    private String className;
    private String targetType;
    private String targetName;
    private String annotation;

    /** No-arg constructor for reflective instantiation by the RewriteEngine. */
    public RemoveAnnotationRecipe() {
    }

    /**
     * Creates a new RemoveAnnotationRecipe.
     *
     * @param targetType "class", "method" or "field"
     * @param className the fully qualified class name
     * @param targetName the name of the method/field (null for classes)
     * @param annotation the annotation name
     */
    public RemoveAnnotationRecipe(String targetType, String className, String targetName,
                                 String annotation) {
        this.className = className;
        this.targetType = targetType;
        this.targetName = targetName;
        this.annotation = annotation;
    }

    // ========== Setter für die Reflexions-Instanziierung ==========

    public void setClassName(String className) { this.className = className; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public void setAnnotation(String annotation) { this.annotation = annotation; }

    // ========== Getter ==========

    public String getClassName() { return className; }
    public String getTargetType() { return targetType; }
    public String getTargetName() { return targetName; }
    public String getAnnotation() { return annotation; }

    @Override
    public String getDisplayName() {
        return "Remove annotation @" + annotation + " from " + className;
    }

    @Override
    public String getDescription() {
        String target = "class".equals(targetType)
                ? String.valueOf(className)
                : className + "." + targetName;
        return "Removes @" + annotation + " from " + targetType + " " + target + ".";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
                if (!"class".equals(targetType) || !matchesClass(c)) {
                    return c;
                }
                List<J.Annotation> filtered = filterAnnotations(c.getLeadingAnnotations());
                if (filtered.size() == c.getLeadingAnnotations().size()) return c;
                J.ClassDeclaration r = c.withLeadingAnnotations(retainLead(c.getLeadingAnnotations(), filtered));
                if (filtered.isEmpty()) {
                    r = !r.getModifiers().isEmpty()
                            ? r.withModifiers(ListUtils.mapFirst(r.getModifiers(), x -> x.withPrefix(Space.EMPTY)))
                            : r.getPadding().withKind(r.getPadding().getKind().withPrefix(Space.EMPTY));
                }
                return r;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if (!"method".equals(targetType) || targetName == null
                        || !targetName.equals(m.getSimpleName())) {
                    return m;
                }
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return m;
                }
                List<J.Annotation> filtered = filterAnnotations(m.getLeadingAnnotations());
                if (filtered.size() == m.getLeadingAnnotations().size()) return m;
                J.MethodDeclaration r = m.withLeadingAnnotations(retainLead(m.getLeadingAnnotations(), filtered));
                if (filtered.isEmpty()) {
                    if (!r.getModifiers().isEmpty()) {
                        r = r.withModifiers(ListUtils.mapFirst(r.getModifiers(), x -> x.withPrefix(Space.EMPTY)));
                    } else if (r.getTypeParameters() != null) {
                        r = r.getPadding().withTypeParameters(r.getPadding().getTypeParameters().withPrefix(Space.EMPTY));
                    } else if (r.getReturnTypeExpression() != null) {
                        r = r.withReturnTypeExpression(r.getReturnTypeExpression().withPrefix(Space.EMPTY));
                    } else {
                        r = r.withName(r.getName().withPrefix(Space.EMPTY));
                    }
                }
                return r;
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVar, ExecutionContext ctx) {
                J.VariableDeclarations v = super.visitVariableDeclarations(multiVar, ctx);
                if (!"field".equals(targetType) || targetName == null
                        || !declaresVariable(v) || isLocalVariable()) {
                    return v;
                }
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return v;
                }
                List<J.Annotation> filtered = filterAnnotations(v.getLeadingAnnotations());
                if (filtered.size() == v.getLeadingAnnotations().size()) return v;
                J.VariableDeclarations r = v.withLeadingAnnotations(retainLead(v.getLeadingAnnotations(), filtered));
                if (filtered.isEmpty()) {
                    if (!r.getModifiers().isEmpty()) {
                        r = r.withModifiers(ListUtils.mapFirst(r.getModifiers(), x -> x.withPrefix(Space.EMPTY)));
                    } else if (r.getTypeExpression() != null) {
                        r = r.withTypeExpression(r.getTypeExpression().withPrefix(Space.EMPTY));
                    }
                }
                return r;
            }

            // ========== Helper ==========

            /** Removes the target annotation from the list (simple name match). */
            private List<J.Annotation> filterAnnotations(List<J.Annotation> annotations) {
                String simple = simpleName(annotation);
                List<J.Annotation> filtered = new ArrayList<>();
                for (J.Annotation a : annotations) {
                    if (!simple.equals(a.getSimpleName())) {
                        filtered.add(a);
                    }
                }
                return filtered;
            }

            /**
             * Removes the first annotation, the remaining first annotation takes over its prefix; otherwise
             * an empty line with indentation would remain at the place of the removed annotation.
             */
            private List<J.Annotation> retainLead(List<J.Annotation> original, List<J.Annotation> filtered) {
                if (filtered.isEmpty() || filtered.get(0) == original.get(0)) return filtered;
                List<J.Annotation> out = new ArrayList<>(filtered);
                out.set(0, out.get(0).withPrefix(original.get(0).getPrefix()));
                return out;
            }

            private boolean matchesClass(J.ClassDeclaration c) {
                return RecipeSupport.matchesClass(className, c);
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

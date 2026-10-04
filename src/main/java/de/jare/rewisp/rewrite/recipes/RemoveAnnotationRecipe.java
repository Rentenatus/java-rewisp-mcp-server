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
import org.openrewrite.java.tree.J;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenRewrite Recipe zum Entfernen einer Annotation von einer Klasse,
 * Methode oder einem Feld.
 * Ersetzt die Spoon-basierte Edit-Implementierung (Spoon bleibt für die Vorab-Analyse zuständig).
 *
 * <p>Der Visitor filtert die Ziel-Annotation aus den Leading-Annotations des
 * Zielelements. Existiert die Annotation nicht, wird das Element unverändert
 * zurückgegeben (das Tool meldet dann den strukturierten Fehler).</p>
 *
 * <p>Unterstützt No-Arg-Konstruktor + Setter für die Reflexions-Instanziierung.</p>
 *
 * @author Janusch Rentenatus
 */
public class RemoveAnnotationRecipe extends Recipe {

    private String className;
    private String targetType;
    private String targetName;
    private String annotation;

    /** No-Arg-Konstruktor für die Reflexions-Instanziierung durch die RewriteEngine. */
    public RemoveAnnotationRecipe() {
    }

    /**
     * Erstellt ein neues RemoveAnnotationRecipe.
     *
     * @param targetType "class", "method" oder "field"
     * @param className Der vollqualifizierte Klassenname
     * @param targetName Der Name der Methode/des Feldes (null für Klassen)
     * @param annotation Der Annotations-Name
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
                return filtered.size() != c.getLeadingAnnotations().size()
                        ? c.withLeadingAnnotations(filtered)
                        : c;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if (!"method".equals(targetType) || targetName == null
                        || !targetName.equals(m.getSimpleName())) {
                    return m;
                }
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || !matchesClass(enclosing)) {
                    return m;
                }
                List<J.Annotation> filtered = filterAnnotations(m.getLeadingAnnotations());
                return filtered.size() != m.getLeadingAnnotations().size()
                        ? m.withLeadingAnnotations(filtered)
                        : m;
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVar, ExecutionContext ctx) {
                J.VariableDeclarations v = super.visitVariableDeclarations(multiVar, ctx);
                if (!"field".equals(targetType) || targetName == null
                        || !declaresVariable(v) || isLocalVariable()) {
                    return v;
                }
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || !matchesClass(enclosing)) {
                    return v;
                }
                List<J.Annotation> filtered = filterAnnotations(v.getLeadingAnnotations());
                return filtered.size() != v.getLeadingAnnotations().size()
                        ? v.withLeadingAnnotations(filtered)
                        : v;
            }

            // ========== Helper ==========

            /** Entfernt die Ziel-Annotation aus der Liste (Simple-Name-Match). */
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

            private boolean matchesClass(J.ClassDeclaration c) {
                if (className == null) {
                    return false;
                }
                if (c.getType() != null && className.equals(c.getType().getFullyQualifiedName())) {
                    return true;
                }
                String simpleTarget = className.substring(className.lastIndexOf('.') + 1);
                return simpleTarget.equals(c.getSimpleName());
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

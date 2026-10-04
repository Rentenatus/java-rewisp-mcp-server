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
 * OpenRewrite Recipe zum Hinzufügen von Annotationen zu Klassen, Methoden oder Feldern.
 * Ersetzt die Spoon-basierte Edit-Implementierung (Spoon bleibt für die Vorab-Analyse zuständig).
 *
 * <p>Der Visitor überspringt stillschweigend, wenn das Ziel die Annotation bereits besitzt
 * (Duplikat-Prüfung) oder das Ziel nicht gefunden wird. Die strukturierte Fehleranalyse
 * (DUPLICATE_ANNOTATION, OVERLOADED_METHOD etc.) erfolgt im Tool über die Spoon-Analyse.</p>
 *
 * <p>Unterstützt No-Arg-Konstruktor + Setter, damit {@code OpenRewriteEngineImpl}
 * das Recipe per Reflexion aus application.yaml instanziieren kann.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddAnnotationRecipe extends Recipe {

    /** Sortiert Annotations alphabetisch, @Override ans Ende (OpenRewrite-Konvention). */
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

    /** No-Arg-Konstruktor für die Reflexions-Instanziierung durch die RewriteEngine. */
    public AddAnnotationRecipe() {
    }

    /**
     * Erstellt ein neues AddAnnotationRecipe.
     *
     * @param targetType Der Typ des Ziels: "class", "method", oder "field"
     * @param className Der vollqualifizierte Klassenname
     * @param targetName Der Name der Methode oder des Feldes (kann null sein für Klassen)
     * @param annotation Der Name der Annotation (einfach oder vollqualifiziert)
     * @param attributes Optional: Attribut-Paare (z.B. "value=\"/new\"")
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
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
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
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || !matchesClass(enclosing)) {
                    return v;
                }
                return buildAnnotationTemplate().apply(getCursor(),
                        v.getCoordinates().addAnnotation(ANNOTATION_ORDER));
            }

            // ========== Helper ==========

            /** Prüft, ob die deklarierte Klasse dem className-Parameter entspricht. */
            private boolean matchesClass(J.ClassDeclaration c) {
                if (className == null) {
                    return false;
                }
                if (c.getType() != null && className.equals(c.getType().getFullyQualifiedName())) {
                    return true;
                }
                // Fallback über den einfachen Namen (letzte Segment des FQN oder direkt)
                String simpleTarget = className.substring(className.lastIndexOf('.') + 1);
                return simpleTarget.equals(c.getSimpleName());
            }

            /** Prüft, ob eine der Annotationen bereits dem Ziel-Annotationstyp entspricht. */
            private boolean hasAnnotation(List<J.Annotation> annotations) {
                String targetSimple = simpleName(annotation);
                for (J.Annotation a : annotations) {
                    if (targetSimple.equals(a.getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            /** Prüft, ob die Variablen-Deklaration die Ziel-Variable enthält. */
            private boolean declaresVariable(J.VariableDeclarations v) {
                for (J.VariableDeclarations.NamedVariable nv : v.getVariables()) {
                    if (targetName.equals(nv.getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            /** Prüft, ob die Variable lokal ist (innerhalb einer Methode) statt ein Feld. */
            private boolean isLocalVariable() {
                return getCursor().firstEnclosing(J.MethodDeclaration.class) != null;
            }

            /** Baut das JavaTemplate für die Annotation; FQN-Annotations lösen den Import mit auf. */
            private JavaTemplate buildAnnotationTemplate() {
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

            /** Import nur nötig, wenn die Annotation vollqualifiziert und nicht java.lang ist. */
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

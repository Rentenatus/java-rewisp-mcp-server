/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.Comment;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TextComment;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenRewrite Recipe zur Änderung einer Methoden-Signatur (Rückgabetyp und/oder Parameter).
 * Ersetzt die Spoon-basierte Edit-Implementierung (Spoon bleibt für die Vorab-Analyse zuständig).
 *
 * <p>Semantik (wie die bisherige Implementierung): Die Deklaration wird AST-basiert
 * umgeschrieben; im Modus signature_and_callers erhalten echte Call-Sites
 * (Owner-geprüft über die Typ-Attribution) einen TODO-Marker zum Prüfen der
 * Argumente. Argument-Listen werden NICHT automatisch angepasst.</p>
 *
 * <p>Unterstützt No-Arg-Konstruktor + Setter für die Reflexions-Instanziierung.</p>
 *
 * @author Janusch Rentenatus
 */
public class RewriteSignatureRecipe extends Recipe {

    private String className;
    private String methodName;
    private String newReturnType;
    private String newParameters;
    /** "signature_only" oder "signature_and_callers" (Default). */
    private String mode;
    /** Kommaseparierte FQN-Importliste (von der Spoon-Vorab-Analyse aufgelöst). */
    private String imports;

    /** No-Arg-Konstruktor für die Reflexions-Instanziierung durch die RewriteEngine. */
    public RewriteSignatureRecipe() {
    }

    /**
     * Erstellt ein neues RewriteSignatureRecipe.
     *
     * @param className Der vollqualifizierte Klassenname
     * @param methodName Der Name der Methode
     * @param newReturnType Optional: neuer Rückgabetyp
     * @param newParameters Optional: neue Parameter-Liste
     * @param mode "signature_only" oder "signature_and_callers"
     */
    public RewriteSignatureRecipe(String className, String methodName,
                                 String newReturnType, String newParameters, String mode) {
        this.className = className;
        this.methodName = methodName;
        this.newReturnType = newReturnType;
        this.newParameters = newParameters;
        this.mode = mode;
    }

    // ========== Setter für die Reflexions-Instanziierung ==========

    public void setClassName(String className) { this.className = className; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public void setNewReturnType(String newReturnType) { this.newReturnType = newReturnType; }
    public void setNewParameters(String newParameters) { this.newParameters = newParameters; }
    public void setMode(String mode) { this.mode = mode; }
    public void setImports(String imports) { this.imports = imports; }

    // ========== Getter ==========

    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public String getNewReturnType() { return newReturnType; }
    public String getNewParameters() { return newParameters; }
    public String getMode() { return mode; }
    public String getImports() { return imports; }
    public boolean isUpdateCallers() { return !"signature_only".equalsIgnoreCase(mode); }

    @Override
    public String getDisplayName() {
        return "Rewrite signature of " + methodName + " in " + className;
    }

    @Override
    public String getDescription() {
        return "Changes the signature (return type and/or parameters) of method "
                + methodName + " in class " + className + ".";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if (methodName == null || !methodName.equals(m.getSimpleName())) {
                    return m;
                }
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || !matchesClass(enclosing)) {
                    return m;
                }

                boolean changed = false;

                // 1. Parameter-Liste ersetzen (Template am replaceParameters-Koordinat).
                //    Ein LEERER newParameters-Parameter bedeutet "keine Parameter".
                if (newParameters != null) {
                    if (newParameters.isBlank()) {
                        m = m.withParameters(List.of());
                    } else {
                        JavaTemplate.Builder builder =
                                JavaTemplate.builder(newParameters.trim());
                        for (String fqn : splitImports()) {
                            builder = builder.imports(fqn);
                        }
                        m = builder.build().apply(getCursor(), m.getCoordinates().replaceParameters());
                    }
                    changed = true;
                }

                // 2. Rückgabetyp ersetzen — NACH den Parametern, weil das Parameter-
                //    Template den Baum neu aufbaut. Der Identifier-Druck ist verbatim
                //    (deckt auch generische/Array-Typ-Zeichenfolgen ab).
                if (newReturnType != null && !newReturnType.isBlank()) {
                    Space rtPrefix = m.getReturnTypeExpression() != null
                            ? m.getReturnTypeExpression().getPrefix()
                            : Space.SINGLE_SPACE;
                    m = m.withReturnTypeExpression(new J.Identifier(Tree.randomId(),
                            rtPrefix, Markers.EMPTY, List.of(),
                            newReturnType.trim(), null, null));
                    changed = true;
                }
                return changed ? m.withMethodType(null) : m;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, ExecutionContext ctx) {
                J.MethodInvocation i = super.visitMethodInvocation(invocation, ctx);
                if (!isUpdateCallers() || methodName == null
                        || !methodName.equals(i.getSimpleName())) {
                    return i;
                }
                if (!invocationTargetsOwner(i.getMethodType())) {
                    return i;
                }
                // TODO-Marker vor der Invocation einfügen (Argumente sind manuell zu prüfen)
                Comment todo = new TextComment(false,
                        " TODO: signature of " + methodName + " changed - review arguments",
                        "\n", Markers.EMPTY);
                Space prefix = i.getPrefix();
                if (prefix.getComments().stream()
                        .anyMatch(c -> c instanceof TextComment tc
                                && tc.getText().contains("signature of " + methodName))) {
                    return i; // bereits markiert
                }
                List<Comment> comments = new ArrayList<>(prefix.getComments());
                comments.add(todo);
                return i.withPrefix(prefix.withComments(comments));
            }

            // ========== Helper ==========

            /** Prüft die deklarierte Klasse gegen den className-Parameter. */
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

            /** Invocation-Owner-Prüfung über die Typ-Attribution. */
            private boolean invocationTargetsOwner(JavaType.Method methodType) {
                if (methodType == null || methodType.getDeclaringType() == null) {
                    return false;
                }
                String fqn = methodType.getDeclaringType().getFullyQualifiedName();
                if (className != null && fqn.equals(className)) {
                    return true;
                }
                if (className != null) {
                    String simpleTarget = className.substring(className.lastIndexOf('.') + 1);
                    String simpleType = fqn.substring(fqn.lastIndexOf('.') + 1);
                    return simpleTarget.equals(simpleType);
                }
                return false;
            }

            private List<String> splitImports() {
                List<String> result = new ArrayList<>();
                if (imports != null && !imports.isBlank()) {
                    for (String part : imports.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) {
                            result.add(trimmed);
                        }
                    }
                }
                return result;
            }
        };
    }
}

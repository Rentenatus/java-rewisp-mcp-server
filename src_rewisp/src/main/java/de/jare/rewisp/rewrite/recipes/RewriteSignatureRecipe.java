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
 * OpenRewrite recipe for changing a method signature (return type and/or parameters).
 * Replaces the Spoon-based edit implementation (Spoon remains responsible for the pre-analysis).
 *
 * <p>Semantics (like the previous implementation): the declaration is rewritten
 * AST-based; in signature_and_callers mode real call sites
 * (owner-checked via type attribution) get a TODO marker for checking the
 * arguments. Argument lists are NOT adjusted automatically.</p>
 *
 * <p>Supports no-arg constructor + setters for reflective instantiation.</p>
 *
 * @author Janusch Rentenatus
 */
public class RewriteSignatureRecipe extends Recipe {

    private String className;
    private String methodName;
    private String newReturnType;
    private String newParameters;
    /** "signature_only" or "signature_and_callers" (default). */
    private String mode;
    /** Comma-separated FQN import list (resolved by the Spoon pre-analysis). */
    private String imports;

    /** No-arg constructor for reflective instantiation by the RewriteEngine. */
    public RewriteSignatureRecipe() {
    }

    /**
     * Creates a new RewriteSignatureRecipe.
     *
     * @param className the fully qualified class name
     * @param methodName the name of the method
     * @param newReturnType optional: new return type
     * @param newParameters optional: new parameter list
     * @param mode "signature_only" or "signature_and_callers"
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
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return m;
                }

                boolean changed = false;

                // Imports an der CU registrieren (Template-.imports() allein fuegt keine Imports ein)
                for (String fqn : splitImports()) {
                    maybeAddImport(fqn, false);
                }

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
                Space prefix = i.getPrefix();
                // Die Einrueckung des Aufrufs steht im Prefix bzw. im Suffix des letzten Kommentars; der neue
                // Kommentar uebernimmt sie, damit die Aufrufzeile ihre Einrueckung behaelt.
                String indent = prefix.getComments().isEmpty() ? prefix.getWhitespace()
                        : prefix.getComments().get(prefix.getComments().size() - 1).getSuffix();
                boolean ownLine = indent.contains("\n");
                Comment todo = new TextComment(false,
                        " TODO: signature of " + methodName + " changed - review arguments",
                        ownLine ? indent : "\n", Markers.EMPTY);
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

            /** Checks the declared class against the className parameter. */
            private boolean matchesClass(J.ClassDeclaration c) {
                return RecipeSupport.matchesClassStrict(className, c);
            }

            /** Invocation owner check via type attribution. */
            private boolean invocationTargetsOwner(JavaType.Method methodType) {
                if (methodType == null || methodType.getDeclaringType() == null) {
                    return false;
                }
                // Typ ist attributiert: strikter FQN-Vergleich (kein Simple-Name-Fallback)
                return className != null && className.equals(methodType.getDeclaringType().getFullyQualifiedName());
            }

            private List<String> splitImports() {
                return RecipeSupport.splitImports(imports);
            }
        };
    }
}

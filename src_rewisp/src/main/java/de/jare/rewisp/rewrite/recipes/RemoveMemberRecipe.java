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
import org.openrewrite.java.tree.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenRewrite recipe for removing methods or fields from a class.
 * Replaces the Spoon-based edit implementation (Spoon remains responsible for the pre-analysis).
 *
 * <p>The visitor filters the target declaration out of the class body. For multi-field
 * declarations ("int x, y;") only the named variable is removed; if none
 * remains, the whole statement is dropped. Reference checks (safe mode) and signature
 * disambiguation are done by the tool upfront via the Spoon analysis.</p>
 *
 * <p>Supports no-arg constructor + setters so that {@code OpenRewriteEngineImpl}
 * can instantiate the recipe reflectively from application.yaml.</p>
 *
 * @author Janusch Rentenatus
 */
public class RemoveMemberRecipe extends Recipe {

    private String className;
    private String memberName;
    private String scope;
    /** Comma-separated parameter types for disambiguation (only scope=method). */
    private String signature;
    /** "true"/"false" — informational only; the safe-mode check happens in the tool. */
    private String hard;

    /** No-arg constructor for reflective instantiation by the RewriteEngine. */
    public RemoveMemberRecipe() {
    }

    /**
     * Creates a new RemoveMemberRecipe.
     *
     * @param className the fully qualified class name (e.g. "com.example.MyClass")
     * @param memberName the name of the method or field
     * @param scope the scope: "method" or "field"
     * @param signature optional: comma-separated parameter types for disambiguation with "method"
     * @param hard whether to work in "hard" mode
     */
    public RemoveMemberRecipe(String className, String memberName, String scope, String signature, boolean hard) {
        this.className = className;
        this.memberName = memberName;
        this.scope = scope;
        this.signature = signature;
        this.hard = String.valueOf(hard);
    }

    // ========== Setter für die Reflexions-Instanziierung ==========

    public void setClassName(String className) { this.className = className; }
    public void setMemberName(String memberName) { this.memberName = memberName; }
    public void setScope(String scope) { this.scope = scope; }
    public void setSignature(String signature) { this.signature = signature; }
    public void setHard(String hard) { this.hard = hard; }

    // ========== Getter ==========

    public String getClassName() { return className; }
    public String getMemberName() { return memberName; }
    public String getScope() { return scope; }
    public String getSignature() { return signature; }
    public boolean isHard() { return "true".equalsIgnoreCase(hard); }

    @Override
    public String getDisplayName() {
        return "Remove " + (scope == null ? "member" : scope) + " " + memberName
                + " from " + className;
    }

    @Override
    public String getDescription() {
        return "Removes " + (scope == null ? "member" : scope) + " " + memberName
                + " from class " + className + ".";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = super.visitBlock(block, ctx);

                // Nur der direkte Class-Body der Zielklasse.
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return b;
                }
                if (getCursor().getParentTreeCursor().getValue() != enclosing) {
                    return b;
                }

                if ("method".equals(scope)) {
                    return removeMethod(b);
                } else if ("field".equals(scope)) {
                    return removeField(b);
                }
                return b;
            }

            // ========== Helper ==========

            /** Removes the matching method declaration (incl. signature match). */
            private J.Block removeMethod(J.Block body) {
                List<String> sigParams = splitSignature();
                boolean signatureGiven = !sigParams.isEmpty();
                List<Statement> kept = new ArrayList<>();
                boolean removed = false;
                for (Statement stmt : body.getStatements()) {
                    if (!removed && stmt instanceof J.MethodDeclaration m
                            && memberName != null && memberName.equals(m.getSimpleName())
                            && (!signatureGiven || signatureMatches(m, sigParams))) {
                        removed = true;
                        continue; // Statement entfernen
                    }
                    kept.add(stmt);
                }
                return removed ? body.withStatements(kept) : body;
            }

            /** Removes the field declaration; for multi-declarations only the variable. */
            private J.Block removeField(J.Block body) {
                List<Statement> kept = new ArrayList<>();
                boolean removed = false;
                for (Statement stmt : body.getStatements()) {
                    if (!removed && stmt instanceof J.VariableDeclarations vars) {
                        List<J.VariableDeclarations.NamedVariable> keptVars = new ArrayList<>();
                        for (J.VariableDeclarations.NamedVariable nv : vars.getVariables()) {
                            if (!removed && memberName != null && memberName.equals(nv.getSimpleName())) {
                                removed = true;
                                continue;
                            }
                            keptVars.add(nv);
                        }
                        if (keptVars.size() < vars.getVariables().size()) {
                            if (keptVars.isEmpty()) {
                                continue; // ganze Anweisung entfernen
                            }
                            kept.add(vars.withVariables(keptVars));
                            continue;
                        }
                    }
                    kept.add(stmt);
                }
                return removed ? body.withStatements(kept) : body;
            }

            /** Checks the declared class against the className parameter. */
            private boolean matchesClass(J.ClassDeclaration c) {
                return RecipeSupport.matchesClass(className, c);
            }

            /** Compares the method parameters with the signature (erasure-reduced). */
            private boolean signatureMatches(J.MethodDeclaration m, List<String> sigParams) {
                List<Statement> params = m.getParameters();
                int paramCount = params == null ? 0 : params.size();
                if (paramCount != sigParams.size()) {
                    return false;
                }
                for (int i = 0; i < sigParams.size(); i++) {
                    String expected = eraseType(sigParams.get(i));
                    String actual = "";
                    if (params.get(i) instanceof J.VariableDeclarations vd
                            && vd.getTypeExpression() != null) {
                        actual = eraseType(vd.getTypeExpression().toString());
                    }
                    if (!actual.equals(expected)) {
                        return false;
                    }
                }
                return true;
            }

            /** Strips generics/package; normalisiert Varargs. */
            private static String eraseType(String type) {
                return RecipeSupport.eraseType(type);
            }

            private List<String> splitSignature() {
                return RecipeSupport.splitTopLevel(signature);
            }
        };
    }
}

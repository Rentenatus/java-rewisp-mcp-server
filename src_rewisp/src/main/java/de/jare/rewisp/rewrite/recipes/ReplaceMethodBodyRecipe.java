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
import org.openrewrite.java.tree.Statement;

import java.util.List;

/**
 * OpenRewrite recipe for replacing the entire method body.
 * Replaces the Spoon-based edit implementation (Spoon remains responsible for the pre-analysis).
 *
 * <p>Match logic: class name + method name (+ optional signature with erasure-reduced
 * parameters). The new body is inserted via JavaTemplate at the replaceBody coordinate —
 * that replaces line-based brace search and CR offset math.</p>
 *
 * <p>Supports no-arg constructor + setters for reflective instantiation.</p>
 *
 * @author Janusch Rentenatus
 */
public class ReplaceMethodBodyRecipe extends Recipe {

    private String className;
    private String methodName;
    /** Comma-separated parameter types (optional, for disambiguation). */
    private String signature;
    private String newBody;
    /** Comma-separated FQN import list (resolved by the Spoon pre-analysis). */
    private String imports;

    /** No-arg constructor for reflective instantiation by the RewriteEngine. */
    public ReplaceMethodBodyRecipe() {
    }

    /**
     * Creates a new ReplaceMethodBodyRecipe.
     *
     * @param className the fully qualified class name
     * @param methodName the name of the method
     * @param signature optional: comma-separated parameter types
     * @param newBody the new method body (without curly braces)
     */
    public ReplaceMethodBodyRecipe(String className, String methodName, String signature, String newBody) {
        this.className = className;
        this.methodName = methodName;
        this.signature = signature;
        this.newBody = newBody;
    }

    // ========== Setter für die Reflexions-Instanziierung ==========

    public void setClassName(String className) { this.className = className; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public void setSignature(String signature) { this.signature = signature; }
    public void setNewBody(String newBody) { this.newBody = newBody; }
    public void setImports(String imports) { this.imports = imports; }

    // ========== Getter ==========

    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public String getSignature() { return signature; }
    public String getNewBody() { return newBody; }
    public String getImports() { return imports; }

    @Override
    public String getDisplayName() {
        return "Replace body of " + methodName + " in " + className;
    }

    @Override
    public String getDescription() {
        return "Replaces the entire body of method " + methodName + " in class " + className + ".";
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
                List<String> sigParams = splitSignature();
                if (!sigParams.isEmpty() && !signatureMatches(m, sigParams)) {
                    return m;
                }
                if (m.getBody() == null) {
                    return m; // abstrakt/interface — Tool validiert das vorab
                }

                for (String fqn : splitImports()) {
                    maybeAddImport(fqn);
                }

                JavaTemplate.Builder builder = JavaTemplate.builder(normalizedBody());
                for (String fqn : splitImports()) {
                    builder = builder.imports(fqn);
                }
                return builder.build().apply(getCursor(), m.getCoordinates().replaceBody());
            }

            // ========== Helper ==========

            /** Normalize body: remove leading indentation of the first token. */
            private String normalizedBody() {
                if (newBody == null || newBody.isBlank()) {
                    return ";";
                }
                return newBody.stripIndent().stripLeading();
            }

            /** Checks the declared class against the className parameter. */
            private boolean matchesClass(J.ClassDeclaration c) {
                return RecipeSupport.matchesClass(className, c);
            }

            /** Compares the method parameters with the signature (erasure-reduced). */
            private boolean signatureMatches(J.MethodDeclaration m, List<String> sigParams) {
                // Bevorzugt über die Typ-Attribution: varargs erscheinen dort als "int[]"
                if (m.getMethodType() != null && m.getMethodType().getParameterTypes() != null) {
                    var paramTypes = m.getMethodType().getParameterTypes();
                    if (paramTypes.size() == sigParams.size()) {
                        boolean allMatch = true;
                        for (int i = 0; i < sigParams.size(); i++) {
                            String expected = eraseType(sigParams.get(i));
                            String actual = javaTypeName(paramTypes.get(i));
                            if (!actual.equals(expected)) {
                                allMatch = false;
                                break;
                            }
                        }
                        if (allMatch) {
                            return true;
                        }
                    }
                }
                // Fallback: Quelltext-Vergleich der Type-Expressions
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

            /** Simple name of a JavaType (FQN → ClassName, primitive → keyword). */
            private static String javaTypeName(org.openrewrite.java.tree.JavaType type) {
                if (type == null) return "";
                if (type instanceof org.openrewrite.java.tree.JavaType.FullyQualified fqt) {
                    return eraseType(fqt.getClassName());
                }
                if (type instanceof org.openrewrite.java.tree.JavaType.Primitive prim) {
                    return prim.getClassName();
                }
                return eraseType(type.toString());
            }

            /** Strips Generics/Paket; normalisiert Varargs. */
            private static String eraseType(String type) {
                return RecipeSupport.eraseType(type);
            }

            private List<String> splitSignature() {
                return RecipeSupport.splitTopLevel(signature);
            }

            private List<String> splitImports() {
                return RecipeSupport.splitImports(imports);
            }
        };
    }
}

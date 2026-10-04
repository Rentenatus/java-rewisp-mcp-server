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

import java.util.ArrayList;
import java.util.List;

/**
 * OpenRewrite Recipe zum Ersetzen des gesamten Methodenrumpfes.
 * Ersetzt die Spoon-basierte Edit-Implementierung (Spoon bleibt für die Vorab-Analyse zuständig).
 *
 * <p>Match-Logik: Klassename + Methodenname (+ optional Signatur mit erasure-reduziertem
 * Parametern). Der neue Rumpf wird per JavaTemplate an der replaceBody-Koordinate
 * eingesetzt — das ersetzt zeilenbasierte Brace-Suche und CR-Offset-Mathematik.</p>
 *
 * <p>Unterstützt No-Arg-Konstruktor + Setter für die Reflexions-Instanziierung.</p>
 *
 * @author Janusch Rentenatus
 */
public class ReplaceMethodBodyRecipe extends Recipe {

    private String className;
    private String methodName;
    /** Kommaseparierte Parameter-Typen (optional, zur Disambiguierung). */
    private String signature;
    private String newBody;
    /** Kommaseparierte FQN-Importliste (von der Spoon-Vorab-Analyse aufgelöst). */
    private String imports;

    /** No-Arg-Konstruktor für die Reflexions-Instanziierung durch die RewriteEngine. */
    public ReplaceMethodBodyRecipe() {
    }

    /**
     * Erstellt ein neues ReplaceMethodBodyRecipe.
     *
     * @param className Der vollqualifizierte Klassenname
     * @param methodName Der Name der Methode
     * @param signature Optional: Komma-separierte Parameter-Typen
     * @param newBody Der neue Methodenrumpf (ohne geschweifte Klammern)
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
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
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

            /** Rumpf normalisieren: führende Einrückung des ersten Tokens entfernen. */
            private String normalizedBody() {
                if (newBody == null || newBody.isBlank()) {
                    return ";";
                }
                return newBody.stripIndent().stripLeading();
            }

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

            /** Vergleicht die Methoden-Parameter mit der Signatur (erasure-reduziert). */
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

            /** Simple-Name eines JavaType (FQN → ClassName, Primitive → Keyword). */
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
                if (type == null) return "";
                String t = type.trim();
                if (t.endsWith("...")) {
                    t = t.substring(0, t.length() - 3) + "[]";
                }
                int lt = t.indexOf('<');
                if (lt >= 0) t = t.substring(0, lt).trim();
                int lastDot = t.lastIndexOf('.');
                if (lastDot >= 0) t = t.substring(lastDot + 1);
                return t;
            }

            private List<String> splitSignature() {
                List<String> result = new ArrayList<>();
                if (signature != null && !signature.isBlank()) {
                    for (String part : signature.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) {
                            result.add(trimmed);
                        }
                    }
                }
                return result;
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

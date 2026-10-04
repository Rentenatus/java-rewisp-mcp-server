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
 * OpenRewrite Recipe zum Entfernen von Methoden oder Feldern aus einer Klasse.
 * Ersetzt die Spoon-basierte Edit-Implementierung (Spoon bleibt für die Vorab-Analyse zuständig).
 *
 * <p>Der Visitor filtert die Ziel-Deklaration aus dem Class-Body. Bei Multi-Feld-
 * Deklarationen ("int x, y;") wird nur die benannte Variable entfernt; bleibt keine
 * übrig, entfällt die ganze Anweisung. Referenz-Checks (Safe Mode) und Signatur-
 * Disambiguierung macht das Tool vorab über die Spoon-Analyse.</p>
 *
 * <p>Unterstützt No-Arg-Konstruktor + Setter, damit {@code OpenRewriteEngineImpl}
 * das Recipe per Reflexion aus application.yaml instanziieren kann.</p>
 *
 * @author Janusch Rentenatus
 */
public class RemoveMemberRecipe extends Recipe {

    private String className;
    private String memberName;
    private String scope;
    /** Kommaseparierte Parameter-Typen zur Disambiguierung (nur scope=method). */
    private String signature;
    /** "true"/"false" — nur informativ; der Safe-Mode-Check erfolgt im Tool. */
    private String hard;

    /** No-Arg-Konstruktor für die Reflexions-Instanziierung durch die RewriteEngine. */
    public RemoveMemberRecipe() {
    }

    /**
     * Erstellt ein neues RemoveMemberRecipe.
     *
     * @param className Der vollqualifizierte Klassenname (z.B. "com.example.MyClass")
     * @param memberName Der Name der Methode oder des Feldes
     * @param scope Der Scope: "method" oder "field"
     * @param signature Optional: Komma-separierte Parameter-Typen zur Disambiguierung bei "method"
     * @param hard Ob im "hard" Modus gearbeitet wird
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
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
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

            /** Entfernt die passende Methoden-Deklaration (inkl. Signatur-Match). */
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

            /** Entfernt die Feld-Deklaration; bei Multi-Deklaration nur die Variable. */
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
        };
    }
}

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
import org.openrewrite.java.tree.JavaType;

/**
 * OpenRewrite Recipe zum Umbenennen von Methoden, Feldern oder Klassen
 * inklusive aller Call-Sites. Ersetzt die Spoon-basierte Edit-Implementierung
 * (Spoon bleibt für die Vorab-Analyse zuständig).
 *
 * <p>Match-Logik:
 * Methoden — Deklaration in der Zielklasse (alle Overloads) plus Invocations,
 * deren deklarierender Typ die Zielklasse ist. Felder — Deklaration plus
 * Field-Accesses mit passendem Owner. Klassen — Deklaration plus Identifier-
 * Referenzen mit dem Klassentyp. Für Caller-Updates muss die Engine das
 * GESAMTE Projekt parsen (Scope leer), damit die Typ-Attribution über
 * Quellgrenzen hinweg funktioniert.</p>
 *
 * <p>Unterstützt No-Arg-Konstruktor + Setter für die Reflexions-Instanziierung.</p>
 *
 * @author Janusch Rentenatus
 */
public class RenameSymbolRecipe extends Recipe {

    private String className;
    private String oldName;
    private String newName;
    private String scope;
    /** "true"/"false" — ob Call-Sites mit umbenannt werden. */
    private String updateCallers;

    // ========== Legacy-API (parameterisierter Konstruktor + Getter) ==========

    private String symbolPath;
    private SymbolType symbolType = SymbolType.AUTO;

    /** No-Arg-Konstruktor für die Reflexions-Instanziierung durch die RewriteEngine. */
    public RenameSymbolRecipe() {
    }

    /**
     * Legacy-Konstruktor für die direkte Instanziierung (Tests, alte Aufrufer).
     *
     * @param symbolPath "com.example.MyClass.method()" bzw. ".field" oder Klassen-FQN
     * @param newName neuer Name
     * @param symbolType CLASS, METHOD, FIELD oder AUTO
     * @param updateCallSites ob Call-Sites mit umbenannt werden
     */
    public RenameSymbolRecipe(String symbolPath, String newName, SymbolType symbolType, boolean updateCallSites) {
        this.symbolPath = symbolPath;
        this.symbolType = symbolType == null ? SymbolType.AUTO : symbolType;
        this.newName = newName;
        this.updateCallers = String.valueOf(updateCallSites);
        switch (this.symbolType) {
            case CLASS -> this.scope = "class";
            case FIELD -> this.scope = "field";
            default -> this.scope = "method";
        }
        if (symbolPath != null) {
            String path = symbolPath.endsWith("()") ? symbolPath.substring(0, symbolPath.length() - 2) : symbolPath;
            int dot = path.lastIndexOf('.');
            if (this.symbolType == SymbolType.CLASS) {
                // symbolPath ist der FQN der Zielklasse selbst
                this.className = symbolPath;
                this.oldName = path.substring(path.lastIndexOf('.') + 1);
            } else if (dot > 0) {
                this.className = path.substring(0, dot);
                this.oldName = path.substring(dot + 1);
            } else {
                this.oldName = path;
            }
        }
    }

    public enum SymbolType {
        CLASS, METHOD, FIELD, AUTO
    }

    // ========== Setter für die Reflexions-Instanziierung ==========

    public void setClassName(String className) { this.className = className; }
    public void setOldName(String oldName) { this.oldName = oldName; }
    public void setNewName(String newName) { this.newName = newName; }
    public void setScope(String scope) { this.scope = scope; }
    public void setUpdateCallers(String updateCallers) { this.updateCallers = updateCallers; }

    // ========== Getter ==========

    public String getClassName() { return className; }
    public String getOldName() { return oldName; }
    public String getNewName() { return newName; }
    public String getScope() { return scope; }
    public boolean isUpdateCallers() { return !"false".equalsIgnoreCase(updateCallers); }

    /** Legacy-API. */
    public String getSymbolPath() { return symbolPath; }
    public SymbolType getSymbolType() { return symbolType; }
    public boolean isUpdateCallSites() { return isUpdateCallers(); }

    @Override
    public String getDisplayName() {
        return "Rename " + (scope == null ? "symbol" : scope) + " " + oldName + " to " + newName;
    }

    @Override
    public String getDescription() {
        String target = "class".equals(scope) ? oldName
                : (className == null ? "" : className) + "." + oldName;
        return "Renames " + (scope == null ? "symbol" : scope) + " " + target + " to " + newName + ".";
    }

    /**
     * Heuristik zur Symbol-Typ-Erkennung aus einem Pfad (legacy).
     */
    public static SymbolType detectSymbolType(String symbolPath) {
        if (symbolPath == null || symbolPath.isEmpty()) {
            return SymbolType.FIELD;
        }
        if (symbolPath.contains("(") && symbolPath.contains(")")) {
            return SymbolType.METHOD;
        }
        int lastDot = symbolPath.lastIndexOf('.');
        String lastPart = lastDot >= 0 ? symbolPath.substring(lastDot + 1) : symbolPath;
        if (!lastPart.isEmpty() && Character.isUpperCase(lastPart.charAt(0))) {
            return SymbolType.CLASS;
        }
        return SymbolType.FIELD;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if (!"method".equals(scope) || oldName == null || !oldName.equals(m.getSimpleName())) {
                    return m;
                }
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || !matchesClass(enclosing)) {
                    return m;
                }
                return m.withName(m.getName().withSimpleName(newName));
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation invocation, ExecutionContext ctx) {
                J.MethodInvocation i = super.visitMethodInvocation(invocation, ctx);
                if (!"method".equals(scope) || !isUpdateCallers()
                        || oldName == null || !oldName.equals(i.getSimpleName())) {
                    return i;
                }
                if (!invocationTargetsOwner(i.getMethodType())) {
                    return i;
                }
                return i.withName(i.getName().withSimpleName(newName));
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVar, ExecutionContext ctx) {
                J.VariableDeclarations v = super.visitVariableDeclarations(multiVar, ctx);
                if (!"field".equals(scope) || oldName == null || isLocalVariable()) {
                    return v;
                }
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || !matchesClass(enclosing)) {
                    return v;
                }
                boolean changed = false;
                java.util.List<J.VariableDeclarations.NamedVariable> renamed = new java.util.ArrayList<>();
                for (J.VariableDeclarations.NamedVariable nv : v.getVariables()) {
                    if (oldName.equals(nv.getSimpleName())) {
                        renamed.add(nv.withName(nv.getName().withSimpleName(newName)));
                        changed = true;
                    } else {
                        renamed.add(nv);
                    }
                }
                return changed ? v.withVariables(renamed) : v;
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                J.FieldAccess f = super.visitFieldAccess(fieldAccess, ctx);
                if (!"field".equals(scope) || !isUpdateCallers()
                        || oldName == null || !oldName.equals(f.getName().getSimpleName())) {
                    return f;
                }
                if (!fieldAccessTargetsOwner(f)) {
                    return f;
                }
                return f.withName(f.getName().withSimpleName(newName));
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);
                if (!"class".equals(scope) || oldName == null || !oldName.equals(c.getSimpleName())) {
                    return c;
                }
                // className ist bei Klassen-Renames der FQN des Ziels selbst
                if (className != null && c.getType() != null
                        && !className.equals(c.getType().getFullyQualifiedName())) {
                    return c;
                }
                return c.withName(c.getName().withSimpleName(newName));
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                J.Identifier id = super.visitIdentifier(identifier, ctx);
                if (!"class".equals(scope) || !isUpdateCallers()
                        || oldName == null || !oldName.equals(id.getSimpleName())) {
                    return id;
                }
                if (!identifierTypedAsTargetClass(id)) {
                    return id;
                }
                return id.withSimpleName(newName);
            }

            // ========== Helper ==========

            private boolean isLocalVariable() {
                return getCursor().firstEnclosing(J.MethodDeclaration.class) != null;
            }

            /** Deklarations-Klasse matcht className (FQN oder Simple-Name-Fallback). */
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
                if (methodType == null) {
                    return false;
                }
                JavaType.FullyQualified declaring = methodType.getDeclaringType();
                return declaring != null && typeMatches(declaring);
            }

            /** Field-Access-Owner-Prüfung über die Typ-Attribution des Targets. */
            private boolean fieldAccessTargetsOwner(J.FieldAccess f) {
                JavaType targetType = f.getTarget().getType();
                if (targetType instanceof JavaType.FullyQualified fqt) {
                    return typeMatches(fqt);
                }
                return false;
            }

            /** Identifier-Typ-Prüfung für Klassen-Referenzen. */
            private boolean identifierTypedAsTargetClass(J.Identifier id) {
                JavaType type = id.getType();
                if (type instanceof JavaType.FullyQualified fqt) {
                    return typeMatches(fqt);
                }
                if (type instanceof JavaType.Method mt && mt.getDeclaringType() != null) {
                    return typeMatches(mt.getDeclaringType());
                }
                return false;
            }

            /** Typ matcht die Zielklasse (FQN oder Simple-Name-Vergleich). */
            private boolean typeMatches(JavaType.FullyQualified type) {
                String fqn = type.getFullyQualifiedName();
                if (className != null && (fqn.equals(className)
                        || className.equals(type.getClassName()))) {
                    return true;
                }
                // Fallback: Simple-Name-Vergleich (letzte Segmente)
                if (className != null) {
                    String simpleTarget = className.substring(className.lastIndexOf('.') + 1);
                    String simpleType = fqn.substring(fqn.lastIndexOf('.') + 1);
                    return simpleTarget.equals(simpleType) && !"class".equals(scope);
                }
                return false;
            }
        };
    }
}

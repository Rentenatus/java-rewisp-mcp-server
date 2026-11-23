/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import java.util.ArrayList;
import java.util.List;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/**
 * OpenRewrite recipe for renaming methods, fields or classes
 * including all call sites. Replaces the Spoon-based edit implementation
 * (Spoon remains responsible for the pre-analysis).
 *
 * <p>Match logic:
 * methods — declaration in the target class (all overloads) plus invocations
 * whose declaring type is the target class. Fields — declaration plus
 * field accesses with a matching owner. Classes — declaration plus identifier
 * references with the class type. For caller updates the engine must parse the
 * ENTIRE project (scope empty) so that type attribution works
 * across source boundaries.</p>
 *
 * <p>Supports no-arg constructor + setters for reflective instantiation.</p>
 *
 * @author Janusch Rentenatus
 */
public class RenameSymbolRecipe extends Recipe {

    private String className;
    private String oldName;
    private String newName;
    private String scope;
    /** "true"/"false" — whether call sites are renamed as well. */
    private String updateCallers;

    // No-Arg-Konstruktor (implizit) fuer die Reflexions-Instanziierung durch die RewriteEngine

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

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if ("class".equals(scope) && oldName != null && m.isConstructor() && oldName.equals(m.getSimpleName())) {
                    // Konstruktoren heissen wie ihre Klasse: sie ziehen beim Klassen-Rename mit
                    J.ClassDeclaration owner = RecipeSupport.enclosingClass(getCursor());
                    return owner != null && matchesClass(owner)
                            ? m.withName(m.getName().withSimpleName(newName)) : m;
                }
                if (!"method".equals(scope) || oldName == null || !oldName.equals(m.getSimpleName())) {
                    return m;
                }
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !(matchesClass(enclosing) || overridesTarget(enclosing))) {
                    return m;
                }
                return m.withName(m.getName().withSimpleName(newName));
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference memberRef, ExecutionContext ctx) {
                J.MemberReference r = super.visitMemberReference(memberRef, ctx);
                if (!"method".equals(scope) || !isUpdateCallers() || oldName == null
                        || !oldName.equals(r.getReference().getSimpleName())) {
                    return r;
                }
                if (!invocationTargetsOwner(r.getMethodType())) {
                    return r;
                }
                return r.withReference(r.getReference().withSimpleName(newName));
            }

            @Override
            public J.Import visitImport(J.Import anImport, ExecutionContext ctx) {
                J.Import imp = super.visitImport(anImport, ctx);
                if (!imp.isStatic() || !isUpdateCallers() || oldName == null
                        || !("method".equals(scope) || "field".equals(scope))) {
                    return imp;
                }
                J.FieldAccess qualid = imp.getQualid();
                if (!oldName.equals(qualid.getSimpleName())
                        || !(qualid.getTarget().getType() instanceof JavaType.FullyQualified owner)
                        || !typeMatches(owner)) {
                    return imp;
                }
                return imp.withQualid(qualid.withName(qualid.getName().withSimpleName(newName)));
            }

            @Override
            public J.EnumValue visitEnumValue(J.EnumValue enumValue, ExecutionContext ctx) {
                J.EnumValue e = super.visitEnumValue(enumValue, ctx);
                if (!"field".equals(scope) || oldName == null || !oldName.equals(e.getName().getSimpleName())) {
                    return e;
                }
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return e;
                }
                return e.withName(e.getName().withSimpleName(newName));
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
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return v;
                }
                boolean changed = false;
                List<J.VariableDeclarations.NamedVariable> renamed = new ArrayList<>();
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
                if (!isUpdateCallers() || oldName == null || !oldName.equals(id.getSimpleName())) {
                    return id;
                }
                if ("field".equals(scope)) {
                    // unqualifizierte Zugriffe (und Deklarationen) mit Owner = Zielklasse
                    return fieldIdentifierOwnedByTarget(id) ? id.withSimpleName(newName) : id;
                }
                if (!"class".equals(scope)) {
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

            /** Declaration class matches className (FQN; simple-name fallback only without type attribution). */
            private boolean matchesClass(J.ClassDeclaration c) {
                return RecipeSupport.matchesClassStrict(className, c);
            }

            private String simpleName(String fqn) {
                return fqn.substring(fqn.lastIndexOf('.') + 1);
            }

            /** Invocation owner check via type attribution (incl. subtypes that override the method). */
            private boolean invocationTargetsOwner(JavaType.Method methodType) {
                if (methodType == null) {
                    return false;
                }
                JavaType.FullyQualified declaring = methodType.getDeclaringType();
                return declaring != null && (typeMatches(declaring) || isSubtypeOfTarget(declaring));
            }

            /** The class is a real subtype of the target class (override/implementation). */
            private boolean overridesTarget(J.ClassDeclaration c) {
                return c.getType() != null && isSubtypeOfTarget(c.getType());
            }

            private boolean isSubtypeOfTarget(JavaType type) {
                return className != null && !typeMatchesName(type)
                        && org.openrewrite.java.tree.TypeUtils.isAssignableTo(className, type);
            }

            private boolean typeMatchesName(JavaType type) {
                return type instanceof JavaType.FullyQualified fq && typeMatches(fq);
            }

            /** Field access owner check via the type attribution of the target. */
            private boolean fieldAccessTargetsOwner(J.FieldAccess f) {
                JavaType targetType = f.getTarget().getType();
                if (targetType instanceof JavaType.FullyQualified fqt) {
                    return typeMatches(fqt);
                }
                return false;
            }

            /** Identifier type check for class references. */
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

            /** Type matches the target class (strict FQN comparison; types come from the attribution). */
            private boolean typeMatches(JavaType.FullyQualified type) {
                return className != null && (className.equals(type.getFullyQualifiedName())
                        || className.equals(type.getClassName()));
            }

            /** Field identifier (also unqualified) belongs to a field of the target class. */
            private boolean fieldIdentifierOwnedByTarget(J.Identifier id) {
                JavaType.Variable fieldType = id.getFieldType();
                return fieldType != null && fieldType.getOwner() instanceof JavaType.FullyQualified owner
                        && typeMatches(owner);
            }
        };
    }
}

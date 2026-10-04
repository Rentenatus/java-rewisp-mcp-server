/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

/**
 * Recipe: Benennt eine Methode samt aller Invocations um.
 * Beide Parameter (oldMethodName, newMethodName) sind Pflicht — ohne sie
 * benennt das Recipe nichts um (keine hartcodierten Defaults mehr).
 */
public class RenameMethodRecipe extends Recipe {

    private String oldMethodName;
    private String newMethodName;

    public void setOldMethodName(String name) {
        this.oldMethodName = name;
    }

    public void setNewMethodName(String name) {
        this.newMethodName = name;
    }

    @Override
    public String getDisplayName() {
        return "Rename method " + oldMethodName + " to " + newMethodName;
    }

    @Override
    public String getDescription() {
        return "Renames all method invocations of " + oldMethodName
                + " to " + newMethodName + ". Also renames method declarations.";
    }

    @Override
    public JavaIsoVisitor<ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(
                    J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);
                if (m.getName() != null && m.getName().toString().equals(oldMethodName)) {
                    return m.withName(m.getName().withSimpleName(newMethodName));
                }
                return m;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(
                    J.MethodInvocation invocation, ExecutionContext ctx) {
                J.MethodInvocation i = super.visitMethodInvocation(invocation, ctx);
                if (i.getSimpleName() != null && i.getSimpleName().toString().equals(oldMethodName)) {
                    return i.withName(i.getName().withSimpleName(newMethodName));
                }
                return i;
            }
        };
    }
}

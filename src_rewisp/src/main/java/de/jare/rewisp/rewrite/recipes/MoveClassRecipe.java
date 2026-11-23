/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import java.util.ArrayList;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.Markers;

import java.util.List;

/**
 * OpenRewrite recipe for moving a class to another package.
 * Replaces the Spoon-based edit implementation (Spoon remains responsible for the pre-analysis).
 *
 * <p>Division of labor:
 * <ul>
 *   <li>Sub-recipe {@link org.openrewrite.java.ChangeType} moves the definition:
 *       the package declaration is rewritten and the source path of the file changes
 *       to the target directory (the engine detects this via the before/after path).</li>
 *   <li>Own visitor: import statements referring to the old FQN are rewritten to the new FQN
 *       in all files; references of the moved class to old package neighbors get explicit
 *       imports.</li>
 * </ul>
 * The physical file move (write file, delete old file) remains the job
 * of the tool resp. EditManager.</p>
 *
 * <p>Supports no-arg constructor + setters so that {@code OpenRewriteEngineImpl}
 * can instantiate the recipe reflectively from application.yaml.</p>
 *
 * @author Janusch Rentenatus
 */
public class MoveClassRecipe extends Recipe {

    private String fullyQualifiedClassName;
    private String targetPackage;
    private String keepTypeName;
    /** Comma-separated FQN list: old package neighbors that must be imported after the move. */
    private String samePackageImports;

    /** No-arg constructor for reflective instantiation by the RewriteEngine. */
    public MoveClassRecipe() {
    }

    /**
     * Creates a new MoveClassRecipe.
     *
     * @param fullyQualifiedClassName the fully qualified class name (e.g. "com.example.MyClass")
     * @param targetPackage the target package (e.g. "com.newpackage")
     * @param keepTypeName whether the class name should be kept
     */
    public MoveClassRecipe(String fullyQualifiedClassName, String targetPackage, boolean keepTypeName) {
        this.fullyQualifiedClassName = fullyQualifiedClassName;
        this.targetPackage = targetPackage;
        this.keepTypeName = String.valueOf(keepTypeName);
    }

    // ========== Setter für die Reflexions-Instanziierung ==========

    public void setFullyQualifiedClassName(String fullyQualifiedClassName) {
        this.fullyQualifiedClassName = fullyQualifiedClassName;
    }

    public void setTargetPackage(String targetPackage) {
        this.targetPackage = targetPackage;
    }

    public void setKeepTypeName(String keepTypeName) {
        this.keepTypeName = keepTypeName;
    }

    public void setSamePackageImports(String samePackageImports) {
        this.samePackageImports = samePackageImports;
    }

    // ========== Getter ==========

    public String getFullyQualifiedClassName() { return fullyQualifiedClassName; }
    public String getTargetPackage() { return targetPackage; }
    public boolean isKeepTypeName() { return !"false".equalsIgnoreCase(keepTypeName); }

    /** New FQN after the move. */
    public String getNewFullyQualifiedName() {
        if (fullyQualifiedClassName == null || targetPackage == null) {
            return null;
        }
        String simple = fullyQualifiedClassName.substring(fullyQualifiedClassName.lastIndexOf('.') + 1);
        return targetPackage.isBlank() ? simple : targetPackage + "." + simple;
    }

    @Override
    public String getDisplayName() {
        return "Move class " + fullyQualifiedClassName + " to " + targetPackage;
    }

    @Override
    public String getDescription() {
        return "Moves class " + fullyQualifiedClassName + " to package "
                + targetPackage + ", updating package declaration, imports, and references.";
    }

    /**
     * Sub-recipe: ChangeType moves the definition (package declaration +
     * source path). Reference updates in other files are done by the own visitor,
     * because type attribution via import references is unreliable without a classpath.
     */
    @Override
    public List<Recipe> getRecipeList() {
        String newFqn = getNewFullyQualifiedName();
        if (fullyQualifiedClassName == null || newFqn == null) {
            return List.of();
        }
        return List.of(new org.openrewrite.java.ChangeType(fullyQualifiedClassName, newFqn, false));
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);
                // Gleiche-Package-Referenzen der verschobenen Klasse brauchen nach dem
                // Move einen expliziten Import (vorher implizit über das Package).
                // Die FQN-Liste liefert die Spoon-Vorab-Analyse des Tools als Parameter,
                // da die Typ-Attribution ohne Classpath nicht zuverlässig ist.
                if (fullyQualifiedClassName == null || samePackageImports == null
                        || samePackageImports.isBlank() || !isTargetCompilationUnit(c)) {
                    return c;
                }
                List<J.Import> imports = new ArrayList<>(c.getImports());
                boolean changed = false;
                for (String fqn : samePackageImports.split(",")) {
                    String trimmed = fqn.trim();
                    if (trimmed.isEmpty()) continue;
                    boolean already = imports.stream()
                            .anyMatch(i -> trimmed.equals(i.getTypeName()));
                    if (already) continue;
                    J.FieldAccess qualid = buildFieldAccess(trimmed,
                            org.openrewrite.java.tree.Space.SINGLE_SPACE);
                    J.Import newImport = new J.Import(Tree.randomId(),
                            org.openrewrite.java.tree.Space.build("\n", List.of()),
                            Markers.EMPTY,
                            org.openrewrite.java.tree.JLeftPadded.build(false),
                            qualid, null);
                    imports.add(newImport);
                    changed = true;
                }
                return changed ? c.withImports(imports) : c;
            }

            @Override
            public J.Import visitImport(J.Import imp, ExecutionContext ctx) {
                J.Import i = super.visitImport(imp, ctx);
                if (fullyQualifiedClassName == null || targetPackage == null) {
                    return i;
                }
                // Import auf den alten FQN umschreiben (String-Match ist hier
                // robuster als die Typ-Attribution ohne Classpath).
                if (fullyQualifiedClassName.equals(i.getQualid().toString())) {
                    return i.withQualid(buildFieldAccess(getNewFullyQualifiedName(),
                            i.getQualid().getPrefix()));
                }
                return i;
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                return super.visitIdentifier(identifier, ctx);
            }

            /** Checks whether the given CU declares the target class. */
            private boolean isTargetCompilationUnit(J.CompilationUnit cu) {
                if (cu == null || fullyQualifiedClassName == null) {
                    return false;
                }
                String simpleTarget = fullyQualifiedClassName
                        .substring(fullyQualifiedClassName.lastIndexOf('.') + 1);
                for (J.ClassDeclaration c : cu.getClasses()) {
                    if (c.getType() != null) {
                        if (fullyQualifiedClassName.equals(c.getType().getFullyQualifiedName())) {
                            return true;
                        }
                        continue; // attributiert und anderer FQN: kein Simple-Name-Fallback
                    }
                    if (simpleTarget.equals(c.getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            /** Builds a qualified name as a field-access chain (e.g. "com.newpkg.Old"). */
            private J.FieldAccess buildFieldAccess(String fqn, org.openrewrite.java.tree.Space prefix) {
                String[] parts = fqn.split("\\.");
                org.openrewrite.java.tree.Expression current = new J.Identifier(Tree.randomId(),
                        prefix, Markers.EMPTY, List.of(), parts[0], null, null);
                for (int i = 1; i < parts.length; i++) {
                    J.Identifier name = new J.Identifier(Tree.randomId(),
                            org.openrewrite.java.tree.Space.EMPTY, Markers.EMPTY,
                            List.of(), parts[i], null, null);
                    current = new J.FieldAccess(Tree.randomId(),
                            org.openrewrite.java.tree.Space.EMPTY, Markers.EMPTY,
                            current, org.openrewrite.java.tree.JLeftPadded.build(name), null);
                }
                return (J.FieldAccess) current;
            }
        };
    }
}

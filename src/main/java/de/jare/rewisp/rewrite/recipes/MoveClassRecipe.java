/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.marker.Markers;

import java.util.List;

/**
 * OpenRewrite Recipe zum Verschieben einer Klasse in ein anderes Package.
 * Ersetzt die Spoon-basierte Edit-Implementierung (Spoon bleibt für die Vorab-Analyse zuständig).
 *
 * <p>Arbeitsteilung:
 * <ul>
 *   <li>Sub-Recipe {@link org.openrewrite.java.ChangeType} verschiebt die Definition:
 *       Package-Deklaration wird umgeschrieben und der SourcePath der Datei ändert
 *       sich auf das Ziel-Verzeichnis (Engine erkennt das am before/after-Pfad).</li>
 *   <li>Eigener Visitor: Import-Statements auf den alten FQN werden in allen
 *       Dateien auf den neuen FQN umgeschrieben; Referenzen der verschobenen
 *       Klasse auf alte Package-Nachbarn erhalten explizite Importe.</li>
 * </ul>
 * Der physische Datei-Move (Datei schreiben, alte Datei löschen) bleibt Aufgabe
 * des Tools bzw. EditManager.</p>
 *
 * <p>Unterstützt No-Arg-Konstruktor + Setter, damit {@code OpenRewriteEngineImpl}
 * das Recipe per Reflexion aus application.yaml instanziieren kann.</p>
 *
 * @author Janusch Rentenatus
 */
public class MoveClassRecipe extends Recipe {

    private String fullyQualifiedClassName;
    private String targetPackage;
    private String keepTypeName;
    /** Kommaseparierte FQN-Liste: alte Package-Nachbarn, die nach dem Move importiert werden müssen. */
    private String samePackageImports;

    /** No-Arg-Konstruktor für die Reflexions-Instanziierung durch die RewriteEngine. */
    public MoveClassRecipe() {
    }

    /**
     * Erstellt ein neues MoveClassRecipe.
     *
     * @param fullyQualifiedClassName Der vollqualifizierte Klassenname (z.B. "com.example.MyClass")
     * @param targetPackage Das Ziel-Package (z.B. "com.newpackage")
     * @param keepTypeName Ob der Klassenname beibehalten werden soll
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

    /** Neuer FQN nach dem Move. */
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
     * Sub-Recipe: ChangeType verschiebt die Definition (Package-Deklaration +
     * SourcePath). Referenz-Updates in Fremd-Dateien macht der eigene Visitor,
     * da die Typ-Attribution über Import-Referenzen ohne Classpath unzuverlässig ist.
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
                List<J.Import> imports = new java.util.ArrayList<>(c.getImports());
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

            /** Prüft, ob die gegebene CU die Ziel-Klasse deklariert. */
            private boolean isTargetCompilationUnit(J.CompilationUnit cu) {
                if (cu == null || fullyQualifiedClassName == null) {
                    return false;
                }
                String simpleTarget = fullyQualifiedClassName
                        .substring(fullyQualifiedClassName.lastIndexOf('.') + 1);
                for (J.ClassDeclaration c : cu.getClasses()) {
                    if (c.getType() != null
                            && fullyQualifiedClassName.equals(c.getType().getFullyQualifiedName())) {
                        return true;
                    }
                    if (simpleTarget.equals(c.getSimpleName())) {
                        return true;
                    }
                }
                return false;
            }

            /** Baut einen qualifizierten Namen als FieldAccess-Kette (z.B. "com.newpkg.Old"). */
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

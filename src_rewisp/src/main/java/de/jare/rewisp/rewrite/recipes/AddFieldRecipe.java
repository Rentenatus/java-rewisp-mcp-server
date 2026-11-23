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
 * OpenRewrite recipe for adding a new field to a class.
 * Replaces the Spoon-based edit implementation (Spoon remains responsible for the pre-analysis).
 *
 * <p>The visitor silently skips when the class already has a field
 * with the same name or the class is not found. The structured
 * error analysis (FIELD_EXISTS, UNRESOLVED_TYPES) is done in the tool via the Spoon analysis.</p>
 *
 * <p>Supports no-arg constructor + setters so that {@code OpenRewriteEngineImpl}
 * can instantiate the recipe reflectively from application.yaml.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddFieldRecipe extends Recipe {

    private String className;
    private String fieldName;
    private String type;
    private String modifiers;
    private String initializer;
    /** Comma-separated FQN import list (resolved by the Spoon pre-analysis). */
    private String imports;

    /** No-arg constructor for reflective instantiation by the RewriteEngine. */
    public AddFieldRecipe() {
    }

    /**
     * Creates a new AddFieldRecipe.
     *
     * @param className the fully qualified class name
     * @param fieldName the name of the field
     * @param type the type of the field (e.g. "String", "int")
     * @param modifiers optional: modifiers (e.g. "private", "public", "static", "final")
     * @param initializer optional: initializer expression (e.g. "\"hello\"" or "42")
     */
    public AddFieldRecipe(String className, String fieldName, String type,
                         String modifiers, String initializer) {
        this.className = className;
        this.fieldName = fieldName;
        this.type = type;
        this.modifiers = modifiers;
        this.initializer = initializer;
    }

    // ========== Setter für die Reflexions-Instanziierung ==========

    public void setClassName(String className) { this.className = className; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public void setType(String type) { this.type = type; }
    public void setModifiers(String modifiers) { this.modifiers = modifiers; }
    public void setInitializer(String initializer) { this.initializer = initializer; }
    public void setImports(String imports) { this.imports = imports; }

    // ========== Getter ==========

    public String getClassName() { return className; }
    public String getFieldName() { return fieldName; }
    public String getType() { return type; }
    public String getModifiers() { return modifiers; }
    public String getInitializer() { return initializer; }
    public String getImports() { return imports; }

    @Override
    public String getDisplayName() {
        return "Add field " + fieldName + " to " + className;
    }

    @Override
    public String getDescription() {
        return "Adds a new field " + type + " " + fieldName + " to class " + className + ".";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = super.visitBlock(block, ctx);

                // Nur der direkte Class-Body (nicht Methoden-Rümpfe, nicht Initializer-Blöcke
                // innerhalb von Methoden). Der direkte Parent muss die Ziel-Klassendeklaration sein.
                J.ClassDeclaration enclosing = RecipeSupport.enclosingClass(getCursor());
                if (enclosing == null || !matchesClass(enclosing)) {
                    return b;
                }
                if (getCursor().getParentTreeCursor().getValue() != enclosing) {
                    return b;
                }
                if (declaresField(b)) {
                    return b; // Duplikat — Tool prüft das vorab, hier defensiv skippen
                }

                // Imports explizit an der CU registrieren (Template-.imports() allein
                // reicht nicht, wenn der Typ ohne Classpath nicht attribuiert werden kann)
                for (String fqn : splitImports()) {
                    maybeAddImport(fqn);
                }

                return buildFieldTemplate().apply(getCursor(),
                        b.getCoordinates().lastStatement());
            }

            // ========== Helper ==========

            /** Checks whether the declared class matches the className parameter. */
            private boolean matchesClass(J.ClassDeclaration c) {
                return RecipeSupport.matchesClass(className, c);
            }

            /** Checks whether the block already declares a field with the target name. */
            private boolean declaresField(J.Block body) {
                for (Statement stmt : body.getStatements()) {
                    if (stmt instanceof J.VariableDeclarations vars) {
                        for (J.VariableDeclarations.NamedVariable nv : vars.getVariables()) {
                            if (fieldName != null && fieldName.equals(nv.getSimpleName())) {
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            /** Builds the field template incl. import resolution. */
            private JavaTemplate buildFieldTemplate() {
                StringBuilder src = new StringBuilder();
                if (modifiers != null && !modifiers.isBlank()) {
                    src.append(modifiers.trim()).append(' ');
                }
                src.append(type).append(' ').append(fieldName);
                if (initializer != null && !initializer.isBlank()) {
                    src.append(" = ").append(initializer);
                }
                src.append(';');

                JavaTemplate.Builder builder = JavaTemplate.builder(src.toString());
                for (String fqn : splitImports()) {
                    builder = builder.imports(fqn);
                }
                return builder.build();
            }

            private List<String> splitImports() {
                return RecipeSupport.splitImports(imports);
            }
        };
    }
}

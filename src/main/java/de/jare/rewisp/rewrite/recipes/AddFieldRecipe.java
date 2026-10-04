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
 * OpenRewrite Recipe zum Hinzufügen eines neuen Feldes zu einer Klasse.
 * Ersetzt die Spoon-basierte Edit-Implementierung (Spoon bleibt für die Vorab-Analyse zuständig).
 *
 * <p>Der Visitor überspringt stillschweigend, wenn die Klasse bereits ein Feld
 * mit gleichem Namen besitzt oder die Klasse nicht gefunden wird. Die strukturierte
 * Fehleranalyse (FIELD_EXISTS, UNRESOLVED_TYPES) erfolgt im Tool über die Spoon-Analyse.</p>
 *
 * <p>Unterstützt No-Arg-Konstruktor + Setter, damit {@code OpenRewriteEngineImpl}
 * das Recipe per Reflexion aus application.yaml instanziieren kann.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddFieldRecipe extends Recipe {

    private String className;
    private String fieldName;
    private String type;
    private String modifiers;
    private String initializer;
    /** Kommaseparierte FQN-Importliste (von der Spoon-Vorab-Analyse aufgelöst). */
    private String imports;

    /** No-Arg-Konstruktor für die Reflexions-Instanziierung durch die RewriteEngine. */
    public AddFieldRecipe() {
    }

    /**
     * Erstellt ein neues AddFieldRecipe.
     *
     * @param className Der vollqualifizierte Klassenname
     * @param fieldName Der Name des Feldes
     * @param type Der Typ des Feldes (z.B. "String", "int")
     * @param modifiers Optional: Modifier (z.B. "private", "public", "static", "final")
     * @param initializer Optional: Initialisierungsausdruck (z.B. "\"hello\"" oder "42")
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
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
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

            /** Prüft, ob die deklarierte Klasse dem className-Parameter entspricht. */
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

            /** Prüft, ob der Block bereits ein Feld mit dem Zielnamen deklariert. */
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

            /** Baut das Feld-Template inkl. Import-Auflösung. */
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

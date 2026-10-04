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

import java.util.ArrayList;
import java.util.List;

/**
 * OpenRewrite Recipe zum Hinzufügen einer neuen Klasse.
 * Dies ersetzt die Spoon-basierte Implementierung.
 * 
 * Implements class creation by generating the appropriate Java source code
 * and integrating with OpenRewrite's transformation pipeline.
 * 
 * @author Janusch Rentenatus
 */
public class AddClassRecipe extends Recipe {

    private final String packageName;
    private final String className;
    private final String type;
    private final String body;

    /**
     * Erstellt ein neues AddClassRecipe.
     *
     * @param packageName Der Package-Name (z.B. "com.example")
     * @param className Der Klassenname (ohne Package)
     * @param type Der Typ: "class", "enum", "interface", oder "abstract"
     * @param body Optional: Klassenkörper (Felder, Methoden)
     */
    public AddClassRecipe(String packageName, String className, String type, String body) {
        this.packageName = packageName;
        this.className = className;
        this.type = type;
        this.body = body;
    }

    @Override
    public String getDisplayName() {
        return "Add class";
    }

    @Override
    public String getDescription() {
        return "Creates a new class, enum, interface, or abstract class in a package.";
    }

    /**
     * Generates the Java source code for the new class.
     *
     * @return The complete Java source code as a string
     */
    public String generateSourceCode() {
        StringBuilder src = new StringBuilder();
        
        // Package declaration
        src.append("package ").append(packageName).append(";\n\n");
        
        // Determine the declaration keyword
        String declKeyword = "abstract".equals(type) ? "abstract class" : type;
        
        // Class declaration
        src.append(declKeyword).append(" ").append(className).append(" {\n");
        
        // Add body content with proper indentation
        if (body != null && !body.isBlank()) {
            String memberIndent = "    ";
            for (String line : body.split("\n", -1)) {
                if (!line.isBlank()) {
                    src.append(memberIndent);
                }
                src.append(line);
                src.append("\n");
            }
        }
        
        src.append("}\n");
        
        return src.toString();
    }

    /**
     * Gets the fully qualified name of the class to create.
     *
     * @return The fully qualified class name
     */
    public String getFullyQualifiedName() {
        return packageName + "." + className;
    }

    /**
     * Gets the path where the class file should be created.
     *
     * @return The relative path from source root
     */
    public String getRelativePath() {
        return packageName.replace(".", "/") + "/" + className + ".java";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        // For now, return a visitor that does nothing
        // The actual file creation is handled in AddClassTool using CreateSourceFile
        // This is a placeholder for future OpenRewrite integration
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                // Placeholder for actual implementation
                return super.visitCompilationUnit(cu, ctx);
            }
        };
    }

    /**
     * Gibt den Package-Namen zurück.
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Gibt den Klassennamen zurück.
     */
    public String getClassName() {
        return className;
    }

    /**
     * Gibt den Typ zurück.
     */
    public String getType() {
        return type;
    }

    /**
     * Gibt den Körper zurück.
     */
    public String getBody() {
        return body;
    }
}

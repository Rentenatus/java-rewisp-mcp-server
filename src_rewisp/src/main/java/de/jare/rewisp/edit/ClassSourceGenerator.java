/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

/**
 * Generates the source text of a new class, enum, interface or abstract class
 * (pure string generator, no OpenRewrite recipe: creating the file and the parse check
 * happen in {@code AddClassTool}).
 *
 * @author Janusch Rentenatus
 */
public class ClassSourceGenerator {

    private final String packageName;
    private final String className;
    private final String type;
    private final String body;
    private final boolean publicType;

    /**
     * @param packageName the package name (e.g. "com.example")
     * @param className   the class name (without package)
     * @param type        the type: "class", "enum", "interface", or "abstract"
     * @param body        optional: class body (fields, methods)
     */
    public ClassSourceGenerator(String packageName, String className, String type, String body) {
        this(packageName, className, type, body, true);
    }

    /**
     * @param publicType {@code true}: the type is declared {@code public} (default; a new type should be usable
     *                   from other packages, also after a {@code move_class}); {@code false}: package-private
     */
    public ClassSourceGenerator(String packageName, String className, String type, String body, boolean publicType) {
        this.packageName = packageName;
        this.className = className;
        this.type = type;
        this.body = body;
        this.publicType = publicType;
    }

    /**
     * Generates the Java source code for the new class.
     *
     * @return The complete Java source code as a string
     */
    public String generateSourceCode() {
        StringBuilder src = new StringBuilder();

        src.append("package ").append(packageName).append(";\n\n");

        String declKeyword = "abstract".equals(type) ? "abstract class" : type;
        src.append(publicType ? "public " : "").append(declKeyword).append(" ").append(className).append(" {\n");

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

    /** The fully qualified name of the class to create. */
    public String getFullyQualifiedName() {
        return packageName + "." + className;
    }

    /** The path where the class file should be created, relative to the source root. */
    public String getRelativePath() {
        return packageName.replace(".", "/") + "/" + className + ".java";
    }

    public String getPackageName() {
        return packageName;
    }

    public String getClassName() {
        return className;
    }

    public String getType() {
        return type;
    }

    public String getBody() {
        return body;
    }
}

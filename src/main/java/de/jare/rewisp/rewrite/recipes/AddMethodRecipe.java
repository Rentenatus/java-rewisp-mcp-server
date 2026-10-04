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

import java.util.ArrayList;
import java.util.List;

/**
 * OpenRewrite Recipe zum Hinzufügen einer neuen Methode zu einer Klasse oder einem Interface.
 * Ersetzt die Spoon-basierte Edit-Implementierung (Spoon bleibt für die Vorab-Analyse zuständig).
 *
 * <p>Semantik (identisch zur bisherigen Spoon-Implementierung):
 * Interface ohne Body → abstrakte Methode (Semikolon); Interface mit Body → default-Methode;
 * Klasse ohne Body → leerer Methodenrumpf; Konstruktor-Erkennung wenn Name und Rückgabetyp
 * dem Klassennamen entsprechen.</p>
 *
 * <p>Erasure-Kollisionen (ERASURE_COLLISION) und Unresolved-Types prüft das Tool
 * vorab über die Spoon-Analyse.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddMethodRecipe extends Recipe {

    private String className;
    private String methodName;
    private String returnType;
    private String parameters;
    private String modifiers;
    private String body;
    /** Kommaseparierte FQN-Importliste (von der Spoon-Vorab-Analyse aufgelöst). */
    private String imports;

    /** No-Arg-Konstruktor für die Reflexions-Instanziierung durch die RewriteEngine. */
    public AddMethodRecipe() {
    }

    /**
     * Erstellt ein neues AddMethodRecipe.
     *
     * @param className Der vollqualifizierte Klassenname
     * @param methodName Der Name der Methode
     * @param returnType Der Rückgabetyp (z.B. "void", "String")
     * @param parameters Komma-separierte "Type name" Paare (z.B. "int count, String label")
     * @param modifiers Optional: Modifier (z.B. "public", "private", "static")
     * @param body Optional: Methodenkörper (ohne geschweifte Klammern)
     */
    public AddMethodRecipe(String className, String methodName, String returnType,
                         String parameters, String modifiers, String body) {
        this.className = className;
        this.methodName = methodName;
        this.returnType = returnType;
        this.parameters = parameters;
        this.modifiers = modifiers;
        this.body = body;
    }

    // ========== Setter für die Reflexions-Instanziierung ==========

    public void setClassName(String className) { this.className = className; }
    public void setMethodName(String methodName) { this.methodName = methodName; }
    public void setReturnType(String returnType) { this.returnType = returnType; }
    public void setParameters(String parameters) { this.parameters = parameters; }
    public void setModifiers(String modifiers) { this.modifiers = modifiers; }
    public void setBody(String body) { this.body = body; }
    public void setImports(String imports) { this.imports = imports; }

    // ========== Getter ==========

    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public String getReturnType() { return returnType; }
    public String getParameters() { return parameters; }
    public String getModifiers() { return modifiers; }
    public String getBody() { return body; }
    public String getImports() { return imports; }

    @Override
    public String getDisplayName() {
        return "Add method " + methodName + " to " + className;
    }

    @Override
    public String getDescription() {
        return "Adds a new method " + methodName + " to class or interface " + className + ".";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = super.visitBlock(block, ctx);

                // Nur der direkte Class-Body (nicht Methoden-Rümpfe).
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || !matchesClass(enclosing)) {
                    return b;
                }
                if (getCursor().getParentTreeCursor().getValue() != enclosing) {
                    return b;
                }

                // Imports explizit an der CU registrieren (Template-.imports() allein
                // reicht nicht, wenn der Typ ohne Classpath nicht attribuiert werden kann)
                for (String fqn : splitImports()) {
                    maybeAddImport(fqn);
                }

                boolean isInterface = enclosing.getKind() == J.ClassDeclaration.Kind.Type.Interface;
                boolean isConstructor = isConstructor(enclosing);

                // Konstruktoren: Der Template-Parser benennt blanke Konstruktor-
                // Deklarationen nach seiner synthetischen Klasse "Template" um.
                // Trick: Methode mit Dummy-Rückgabetyp generieren, danach den
                // Rückgabetyp entfernen und den Namen korrigieren.
                String methodSrc = buildMethodSource(enclosing, isInterface, isConstructor);

                JavaTemplate.Builder builder = JavaTemplate.builder(methodSrc);
                for (String fqn : splitImports()) {
                    builder = builder.imports(fqn);
                }

                J.Block newBlock = builder.build().apply(getCursor(),
                        b.getCoordinates().lastStatement());

                if (isConstructor) {
                    newBlock = fixUpConstructor(newBlock);
                }
                return newBlock;
            }

            /** Konstruktor-Erkennung: Name + Rückgabetyp == Klassenname (kein Interface). */
            private boolean isConstructor(J.ClassDeclaration enclosing) {
                if (methodName == null || returnType == null) {
                    return false;
                }
                if (enclosing.getKind() == J.ClassDeclaration.Kind.Type.Interface) {
                    return false;
                }
                return methodName.equals(enclosing.getSimpleName())
                        && simpleTypeName(returnType).equals(enclosing.getSimpleName());
            }

            /** Entfernt den Dummy-Rückgabetyp und setzt den Konstruktor-Namen. */
            private J.Block fixUpConstructor(J.Block block) {
                java.util.List<org.openrewrite.java.tree.Statement> stmts = block.getStatements();
                if (stmts.isEmpty()) {
                    return block;
                }
                org.openrewrite.java.tree.Statement last = stmts.get(stmts.size() - 1);
                if (!(last instanceof J.MethodDeclaration method)) {
                    return block;
                }
                J.MethodDeclaration ctor = method
                        .withName(method.getName().withSimpleName(methodName))
                        .withReturnTypeExpression(null)
                        .withMethodType(null);
                java.util.List<org.openrewrite.java.tree.Statement> fixed =
                        new ArrayList<>(stmts.subList(0, stmts.size() - 1));
                fixed.add(ctor);
                return block.withStatements(fixed);
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

            /** Baut die Methoden-Deklaration gemäß der Spoon-Semantik. */
            private String buildMethodSource(J.ClassDeclaration enclosing, boolean isInterface,
                                             boolean isConstructor) {
                boolean hasBody = body != null && !body.isBlank();
                boolean abstractMethod = isInterface && !hasBody;
                boolean defaultMethod = isInterface && hasBody;

                StringBuilder src = new StringBuilder();
                if (defaultMethod) {
                    src.append("default ");
                } else if (modifiers != null && !modifiers.isBlank()) {
                    src.append(modifiers.trim()).append(' ');
                }
                if (isConstructor) {
                    // Dummy-Rückgabetyp; wird nach dem Template-Apply entfernt
                    src.append("void ").append(methodName).append('(');
                } else {
                    src.append(returnType).append(' ').append(methodName).append('(');
                }
                if (parameters != null && !parameters.isBlank()) {
                    src.append(parameters);
                }
                src.append(')');
                if (abstractMethod) {
                    src.append(';');
                } else if (hasBody) {
                    src.append(" {\n");
                    String[] bodyLines = body.stripIndent().split("\n", -1);
                    for (int i = 0; i < bodyLines.length; i++) {
                        if (!bodyLines[i].isBlank()) {
                            src.append("    ").append(bodyLines[i]);
                        }
                        if (i < bodyLines.length - 1) {
                            src.append('\n');
                        }
                    }
                    src.append("\n}");
                } else {
                    // Klasse/Enum ohne Body: leerer Rumpf
                    src.append(" {\n}");
                }
                return src.toString();
            }

            /** Reduziert eine Typ-Referenz auf den einfachen Namen (Generics + Paket). */
            private static String simpleTypeName(String type) {
                if (type == null) return "";
                String t = type.trim();
                int lt = t.indexOf('<');
                if (lt >= 0) t = t.substring(0, lt).trim();
                int lastDot = t.lastIndexOf('.');
                if (lastDot >= 0) t = t.substring(lastDot + 1);
                return t;
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

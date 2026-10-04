/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jare.rewisp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.AutoImportResolver;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteSyncManager;
import de.jare.rewisp.rewrite.RewriteResult;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.recipes.AddClassRecipe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@code AddClassTool} — creates a new class, enum, interface, or abstract class.
 *
 * <p>Architektur (ab 1.1.1-REWRITE): Spoon wird NUR für die Vorab-Analyse verwendet
 * (Auto-Import-Resolution für den Body). Der erzeugte Quelltext durchläuft die
 * OpenRewrite-Pipeline: er wird mit dem OpenRewrite-JavaParser geparst (Validierung)
 * und normalisiert geschrieben. Fehlerhafter Body-Code wird als PARSE_ERROR
 * abgelehnt, bevor irgendeine Datei angefasst wird. Es gibt keinen Spoon-Fallback
 * für Edit-Operationen mehr.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddClassTool extends BaseEditTool {

    private final EditManager editManager;
    private final RewriteEngine rewriteEngine;
    private final RewriteSyncManager rewriteSyncManager;

    /**
     * Konstruktor mit vollständiger OpenRewrite-Unterstützung.
     * OpenRewrite ist Pflicht — es gibt keinen Spoon-Fallback mehr.
     */
    public AddClassTool(ProjectManager manager, EditManager editManager,
                       RewriteEngine rewriteEngine,
                       RewriteSyncManager rewriteSyncManager) {
        super(manager);
        this.editManager = editManager;
        this.rewriteEngine = rewriteEngine;
        this.rewriteSyncManager = rewriteSyncManager;
    }

    @Override
    protected String toolName() {
        return "add_class";
    }

    @Override
    protected String toolDescription() {
        return "[REWRITE] Create a new class, enum, interface, or abstract class in a package. "
                + "The generated source is validated and normalized through the OpenRewrite parser; "
                + "Spoon only for pre-edit analysis (import resolution).";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "packageName", Map.of("type", "string", "description", "Target package"),
            "className", Map.of("type", "string", "description", "New class name"),
            "type", Map.of("type", "string", "description", "'class', 'enum', 'interface', or 'abstract' (default: class)"),
            "body", Map.of("type", "string", "description", "Optional: class body (members, one per line)"),
            "imports", Map.of("type", "array", "description",
                "Optional: list of fully-qualified import names (e.g. ['java.util.List']) "
                + "to disambiguate types with the same simple name in different packages."),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }

    @Override
    protected List<String> toolRequired() {
        return req("name", "packageName", "className");
    }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        String packageName = arg(request, "packageName");
        String className = arg(request, "className");
        String type = arg(request, "type");
        String body = arg(request, "body");
        java.util.List<String> manualImports = stringListArg(request, "imports");
        if (type == null) type = "class";
        if (!type.equals("class") && !type.equals("enum")
                && !type.equals("interface") && !type.equals("abstract")) {
            return domainError("INVALID_TYPE",
                    "type must be 'class', 'enum', 'interface', or 'abstract' (got: " + type + ")",
                    ctx("packageName", packageName, "className", className, "type", type,
                        "validTypes", "class, enum, interface, abstract"));
        }
        if (packageName == null || packageName.isBlank()) {
            return domainError("INVALID_PACKAGE", "Package name cannot be empty",
                    ctx("className", className));
        }
        if (className == null || className.isBlank()) {
            return domainError("INVALID_CLASS_NAME", "Class name cannot be empty",
                    ctx("packageName", packageName));
        }

        ProjectEntry entry = findEntry(name);
        requireEditable(entry);

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        // Ziel-Verzeichnis bestimmen
        Path srcDir = ProjectManager.resolveSourceRoot(entry);
        if (srcDir == null) {
            srcDir = entry.projectDir();
            if (srcDir == null) srcDir = entry.originalProjectDir();
        }
        if (srcDir == null) {
            return domainError("NO_SOURCE_DIR",
                    "Cannot determine source directory for project '" + name + "'.",
                    ctx("packageName", packageName, "className", className,
                        "suggestion", "Ensure the project was loaded from a directory with a recognized source root."));
        }
        Path packageDir = srcDir.resolve(packageName.replace(".", "/"));
        Path file = packageDir.resolve(className + ".java");

        // Existenz-Prüfung über den Snapshot (Dateien, die zum Projekt gehören)
        // und zusätzlich über die Platte (Dateien außerhalb des Snapshots).
        var state = manager.getProjectState(name);
        Path relativeFile = srcDir.relativize(file);
        if (state != null && state.snapshot() != null
                && EditManager.fileExistsInSnapshot(state.snapshot().files(), relativeFile)) {
            return domainError("CLASS_EXISTS",
                    "Class '" + className + "' already exists at " + file,
                    ctx("packageName", packageName, "className", className,
                        "filePath", file.toString(),
                        "suggestion", "Use a different class name or remove the existing file first."));
        }
        if (java.nio.file.Files.exists(file)) {
            return domainError("CLASS_EXISTS",
                    "Class '" + className + "' already exists at " + file,
                    ctx("packageName", packageName, "className", className,
                        "filePath", file.toString(),
                        "suggestion", "Use a different class name or remove the existing file first."));
        }

        // Auto-Import-Resolution für den Body (Spoon-Analyse).
        // Enum-Bodies überspringen (Enum-Konstanten sind keine Typen).
        var importResult = new AutoImportResolver.Result(java.util.List.of(), java.util.List.of());
        if (body != null && !body.isBlank() && !"enum".equals(type)) {
            importResult = new AutoImportResolver().resolve(entry, null, body, manualImports);
            if (!importResult.unresolvedTypes().isEmpty()) {
                return domainError("UNRESOLVED_TYPES",
                        "Cannot resolve type(s) in body: " + importResult.unresolvedTypes()
                                + ". Provide the fully qualified name or add the dependency.",
                        ctx("packageName", packageName, "className", className,
                            "unresolvedTypes", importResult.unresolvedTypes().toString(),
                            "body", body,
                            "suggestion", "Use fully qualified names (e.g. java.awt.Color) or load the dependency."));
            }
        }

        // ========== PHASE 2: OPENREWRITE CONTENT PIPELINE ==========
        // Quelltext erzeugen (AddClassRecipe hält die Generierungs-Logik)
        AddClassRecipe recipe = new AddClassRecipe(packageName, className, type, body);
        String sourceCode = recipe.generateSourceCode();
        String content = insertImports(sourceCode, importResult.importsToAdd());

        // Validierung + Normalisierung durch den OpenRewrite-Parser: Fehlerhafter
        // Body-Code wird abgelehnt, bevor irgendeine Datei geschrieben wird.
        List<String> parseErrors = new CopyOnWriteArrayList<>();
        org.openrewrite.InMemoryExecutionContext openRewriteCtx =
                new org.openrewrite.InMemoryExecutionContext(t -> parseErrors.add(t.getMessage()));
        org.openrewrite.java.JavaParser parser = org.openrewrite.java.JavaParser.fromJavaVersion().build();
        var parsed = parser.parseInputs(
                java.util.List.of(org.openrewrite.Parser.Input.fromString(
                        Path.of(recipe.getRelativePath()), content)),
                null, openRewriteCtx).toList();
        if (!parseErrors.isEmpty()) {
            return domainError("PARSE_ERROR",
                    "Generated source does not compile: " + String.join("; ", parseErrors),
                    ctx("packageName", packageName, "className", className,
                        "body", body != null ? body : "",
                        "suggestion", "Check the body for syntax errors."));
        }
        if (parsed.isEmpty()) {
            return domainError("PARSE_ERROR",
                    "Generated source could not be parsed.",
                    ctx("packageName", packageName, "className", className));
        }
        String normalizedContent = ((org.openrewrite.SourceFile) parsed.get(0)).printAll();

        // ========== DRY RUN: nur Vorschau, keine Datei schreiben ==========
        if (boolArg(request, "dryRun")) {
            StringBuilder sb = new StringBuilder();
            sb.append("DRY RUN - add_class: no files written. Would create:\n");
            sb.append("  create ").append(file).append('\n');
            return ok(sb);
        }

        // ========== PHASE 3: WRITE VIA EDITMANAGER ==========
        editManager.createDirectories(packageDir);
        entry = editManager.writeFile(entry, file, normalizedContent, null);
        manager.updateEntry(entry);
        editManager.logEdit(toolName() + ": " + type + " " + packageName + "." + className
                + " [OpenRewrite]");

        if (rewriteSyncManager != null) {
            RewriteResult syncResult = new RewriteResult(
                    Map.of(), Set.of(), Set.of(file), java.util.List.of(), false);
            rewriteSyncManager.onRewriteComplete(syncResult);
        }

        return ok("Class created: " + packageName + "." + className + " at " + file
                + " [OpenRewrite]\n" + formatMultiModuleWarning(entry));
    }
}

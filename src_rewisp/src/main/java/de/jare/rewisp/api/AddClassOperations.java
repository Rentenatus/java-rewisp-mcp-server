/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.api.RewispDriver.AddClassRequest;
import de.jare.rewisp.api.RewispDriver.AddClassResult;
import de.jare.rewisp.edit.AutoImportResolver;
import de.jare.rewisp.edit.ClassSourceGenerator;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.edit.SourceCodeUtils;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.util.JavaNames;
import de.jare.rewisp.util.PathUtils;
import de.jare.rewisp.util.ProjectLayout;

import static de.jare.rewisp.api.EditSupport.ctx;

/**
 * {@code add_class}: creates a new class, enum, interface or abstract class. Spoon is used
 * only for pre-edit analysis (auto-import for the body). The generated source text is parsed with the
 * OpenRewrite JavaParser (validation) and written in normalized form; broken body code is rejected as
 * {@code PARSE_ERROR} before any file is touched.
 *
 * @author Janusch Rentenatus
 */
final class AddClassOperations {

    private AddClassOperations() {
    }

    /** Syntactic validation of the inputs; happens before project access. */
    static void requireValid(AddClassRequest request) {
        String packageName = request.packageName();
        String className = request.className();
        String type = request.type() == null ? "class" : request.type();
        String body = request.body();
        String visibility = visibilityOf(request);
        if (!visibility.equals("public") && !visibility.equals("package")) {
            throw new RewispException(ErrorType.INVALID_PARAMS,
                    "visibility must be 'public' or 'package' (got: " + visibility + ")",
                    ctx("packageName", String.valueOf(packageName), "className", String.valueOf(className),
                            "visibility", visibility));
        }
        if (!type.equals("class") && !type.equals("enum")
                && !type.equals("interface") && !type.equals("abstract")) {
            throw new RewispException(ErrorType.INVALID_TYPE,
                    "type must be 'class', 'enum', 'interface', or 'abstract' (got: " + type + ")",
                    ctx("packageName", packageName, "className", className, "type", type,
                            "validTypes", "class, enum, interface, abstract"));
        }
        if (!JavaNames.isBalancedSnippet(body)) {
            throw new RewispException(ErrorType.INVALID_PARAMS,
                    "The class body has unbalanced braces/parentheses or an unterminated string/comment",
                    ctx("packageName", String.valueOf(packageName), "className", String.valueOf(className)));
        }
        if (packageName == null || packageName.isBlank()) {
            throw new RewispException(ErrorType.INVALID_PACKAGE, "Package name cannot be empty",
                    ctx("className", className));
        }
        if (className == null || className.isBlank()) {
            throw new RewispException(ErrorType.INVALID_CLASS_NAME, "Class name cannot be empty",
                    ctx("packageName", packageName));
        }
        if (!JavaNames.isValidPackageName(packageName)) {
            throw new RewispException(ErrorType.INVALID_PACKAGE,
                    "Not a valid Java package name: '" + packageName + "'",
                    ctx("packageName", packageName, "className", className));
        }
        if (!JavaNames.isValidIdentifier(className)) {
            throw new RewispException(ErrorType.INVALID_CLASS_NAME,
                    "Not a valid Java class name: '" + className + "'",
                    ctx("packageName", packageName, "className", className));
        }
    }

    private static String visibilityOf(AddClassRequest request) {
        String visibility = request.visibility();
        return visibility == null || visibility.isBlank() ? "public" : visibility;
    }

    static AddClassResult add(ProjectManager manager, EditManager editManager, ProjectEntry entry,
            AddClassRequest request) throws IOException {
        String packageName = request.packageName();
        String className = request.className();
        String type = request.type() == null ? "class" : request.type();
        String body = request.body();
        String visibility = visibilityOf(request);

        EditPipeline.requireEditable(entry);

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        // Ziel-Verzeichnis bestimmen
        Path srcDir = ProjectManager.resolveSourceRoot(entry);
        if (srcDir == null) {
            srcDir = entry.projectDir();
            if (srcDir == null) srcDir = entry.originalProjectDir();
        }
        if (srcDir == null) {
            throw new RewispException(ErrorType.NO_SOURCE_DIR,
                    "Cannot determine source directory for project '" + request.project() + "'.",
                    ctx("packageName", packageName, "className", className,
                            "suggestion", "Ensure the project was loaded from a directory with a recognized source root."));
        }
        Path packageDir = srcDir.resolve(packageName.replace(".", "/"));
        Path file = packageDir.resolve(className + ProjectLayout.JAVA_EXTENSION);

        // Existenz-Prüfung über den Snapshot (Dateien, die zum Projekt gehören)
        // und zusätzlich über die Platte (Dateien außerhalb des Snapshots).
        var state = manager.getProjectState(entry.name());
        Path relativeFile = srcDir.relativize(file);
        if (state != null && state.snapshot() != null
                && EditManager.fileExistsInSnapshot(state.snapshot().files(), relativeFile)) {
            throw new RewispException(ErrorType.CLASS_EXISTS,
                    "Class '" + className + "' already exists at " + file,
                    ctx("packageName", packageName, "className", className,
                            "filePath", file.toString(),
                            "suggestion", "Use a different class name or remove the existing file first."));
        }
        if (Files.exists(file)
                || editManager.getPendingChanges().containsKey(PathUtils.canonical(file))) {
            throw new RewispException(ErrorType.CLASS_EXISTS,
                    "Class '" + className + "' already exists at " + file,
                    ctx("packageName", packageName, "className", className,
                            "filePath", file.toString(),
                            "suggestion", "Use a different class name or remove the existing file first."));
        }

        // Auto-Import-Resolution für den Body (Spoon-Analyse).
        // Enum-Bodies überspringen (Enum-Konstanten sind keine Typen).
        var importResult = new AutoImportResolver.Result(List.of(), List.of());
        if (body != null && !body.isBlank() && !"enum".equals(type)) {
            importResult = new AutoImportResolver().resolve(entry, null, body, request.imports());
            if (!importResult.unresolvedTypes().isEmpty()) {
                throw new RewispException(ErrorType.UNRESOLVED_TYPES,
                        "Cannot resolve type(s) in body: " + importResult.unresolvedTypes()
                                + ". " + AutoImportResolver.importHint(importResult.unresolvedTypes()),
                        ctx("packageName", packageName, "className", className,
                                "unresolvedTypes", importResult.unresolvedTypes().toString(),
                                "body", body,
                                "suggestion", "Use fully qualified names (e.g. java.awt.Color) or load the dependency."));
            }
        }

        // ========== PHASE 2: OPENREWRITE CONTENT PIPELINE ==========
        // Quelltext erzeugen (ClassSourceGenerator hält die Generierungs-Logik)
        ClassSourceGenerator recipe = new ClassSourceGenerator(packageName, className, type, body,
                visibility.equals("public"));
        String sourceCode = recipe.generateSourceCode();
        String content = SourceCodeUtils.insertImports(sourceCode, importResult.importsToAdd());

        // Validierung + Normalisierung durch den OpenRewrite-Parser: Fehlerhafter
        // Body-Code wird abgelehnt, bevor irgendeine Datei geschrieben wird.
        List<String> parseErrors = new CopyOnWriteArrayList<>();
        org.openrewrite.InMemoryExecutionContext openRewriteCtx =
                new org.openrewrite.InMemoryExecutionContext(t -> parseErrors.add(t.getMessage()));
        org.openrewrite.java.JavaParser parser = org.openrewrite.java.JavaParser.fromJavaVersion().build();
        var parsed = parser.parseInputs(
                List.of(org.openrewrite.Parser.Input.fromString(
                        Path.of(recipe.getRelativePath()), content)),
                null, openRewriteCtx).toList();
        if (!parseErrors.isEmpty()) {
            throw new RewispException(ErrorType.PARSE_ERROR,
                    "Generated source does not compile: " + String.join("; ", parseErrors),
                    ctx("packageName", packageName, "className", className,
                            "body", body != null ? body : "",
                            "suggestion", "Check the body for syntax errors."));
        }
        if (parsed.isEmpty()) {
            throw new RewispException(ErrorType.PARSE_ERROR,
                    "Generated source could not be parsed.",
                    ctx("packageName", packageName, "className", className));
        }
        String normalizedContent = ((org.openrewrite.SourceFile) parsed.get(0)).printAll();

        // ========== D2: Ergebnis muss kompilieren, sonst wird nichts geschrieben ==========
        String notCompiling = manager.validateEditCompiles(entry,
                Map.of(PathUtils.relativizeFromRoot(entry.originalProjectDir(), file), normalizedContent),
                Set.of());
        if (notCompiling != null) {
            throw new RewispException(ErrorType.EDIT_BREAKS_COMPILATION,
                    "The new class would make the project stop compiling: " + notCompiling
                            + ". Nothing was written. (Disable this check with -Dedit.validate=false.)",
                    ctx("packageName", packageName, "className", className));
        }

        // ========== DRY RUN: nur Vorschau, keine Datei schreiben ==========
        if (request.dryRun()) {
            return new AddClassResult(entry.name(), true, file, normalizedContent, entry.modulesDetected(),
                    entry.modulesLoaded());
        }

        // ========== PHASE 3: WRITE VIA EDITMANAGER ==========
        editManager.createDirectories(packageDir);
        entry = editManager.writeFile(entry, file, normalizedContent, null);
        manager.updateEntry(entry);
        editManager.logEdit("add_class: " + type + " " + packageName + "." + className
                + " [OpenRewrite]");

        return new AddClassResult(entry.name(), false, file, normalizedContent, entry.modulesDetected(),
                entry.modulesLoaded());
    }
}

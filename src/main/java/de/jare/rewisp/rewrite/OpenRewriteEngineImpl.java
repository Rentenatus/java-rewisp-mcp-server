/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.build.ModuleContext;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.FileSnapshot;
import de.jare.rewisp.snapshot.ContentHasher;
import de.jare.rewisp.util.PathUtils;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Result;
import org.openrewrite.java.JavaParser;
import org.openrewrite.internal.InMemoryLargeSourceSet;

import java.nio.file.Path;
import java.util.*;

/**
 * OpenRewrite-basierte Implementierung der RewriteEngine.
 * Führt Recipes im Speicher aus und liefert Transformationsergebnisse zurück.
 */
public final class OpenRewriteEngineImpl implements RewriteEngine {

    private final RecipeRegistry recipeRegistry;
    private final ContentHasher hasher = new ContentHasher();

    public OpenRewriteEngineImpl(RecipeRegistry recipeRegistry) {
        this.recipeRegistry = recipeRegistry;
    }

    @Override
    public RewriteResult preview(
            ProjectSnapshot snapshot,
            BuildContext buildContext,
            RewriteRequest request
    ) throws RewriteException {

        // 1. Recipe laden
        RecipeDefinition def = recipeRegistry.find(request.recipeId())
                .orElseThrow(() -> new RewriteException("RECIPE_NOT_FOUND",
                        "Recipe not found or not enabled: " + request.recipeId()));
        if (!def.enabled()) {
            throw new RewriteException("RECIPE_DISABLED",
                    "Recipe is disabled: " + request.recipeId());
        }

        // 2. Recipe instanziieren
        Recipe recipe = instantiateRecipe(def, request.parameters());

        // 3. OpenRewrite ExecutionContext erstellen
        ExecutionContext ctx = new InMemoryExecutionContext();
        
        // 4. JavaParser konfigurieren
        JavaParser.Builder parserBuilder = JavaParser.fromJavaVersion();
        
        // Classpath aus BuildContext hinzufügen (Pfade normalisieren für OpenRewrite)
        // In Test-Umgebungen ohne Maven kann buildContext null sein - das ist OK
        if (buildContext != null && !buildContext.modules().isEmpty()) {
            ModuleContext mod = buildContext.modules().get(0);
            if (!mod.compileClasspath().isEmpty()) {
                List<String> normalizedClasspath = mod.compileClasspath().stream()
                        .map(p -> p.replace("\\", "/"))
                        .toList();
                parserBuilder.classpath(normalizedClasspath.toArray(new String[0]));
            }
        }
        JavaParser parser = parserBuilder.build();

        // 5. Source-Dateien in OpenRewrite-SourceFiles parsen.
        //    WICHTIG: ALLE in-scope-Dateien in EINEM parseInputs-Aufruf übergeben,
        //    damit die typ-Attribution über Dateigrenzen funktioniert (z.B. Method-
        //    Invocations in Caller-Dateien benötigen den deklarierenden Typ aus der
        //    Ziel-Datei — egal in welcher Reihenfolge die Dateien im Snapshot liegen).
        List<SourceFile> sourceFiles = new ArrayList<>();
        List<RewriteDiagnostic> diagnostics = new ArrayList<>();
        List<org.openrewrite.Parser.Input> inputs = new ArrayList<>();

        for (Map.Entry<Path, FileSnapshot> entry : snapshot.files().entrySet()) {
            if (isInScope(entry.getKey(), request.scope())) {
                // Parser.Input erzwingt den echten SourcePath (relativer Snapshot-Pfad).
                // parse(content, relativePath) hätte den Pfad als zweite Quelle interpretiert
                // und den SourcePath stattdessen vom Primärklassennamen abgeleitet.
                inputs.add(org.openrewrite.Parser.Input.fromString(entry.getKey(), entry.getValue().content()));
            }
        }

        try {
            parser.parseInputs(inputs, null, ctx).forEach(sourceFiles::add);
        } catch (Exception e) {
            // Parse-Fehler werden als Diagnose gesammelt
            diagnostics.add(new RewriteDiagnostic(
                    "PARSE_ERROR", "ERROR",
                    "Failed to parse sources: " + e.getMessage(), ""));
        }

        // 6. InMemoryLargeSourceSet erstellen und Recipe ausführen
        InMemoryLargeSourceSet largeSourceSet = new InMemoryLargeSourceSet(sourceFiles);
        Collection<Result> results;
        try {
            // In OpenRewrite 8.x: recipe.run(LargeSourceSet, ExecutionContext) -> RecipeRun
            // RecipeRun.getChangeset().getAllResults() -> Collection<Result>
            results = recipe.run(largeSourceSet, ctx).getChangeset().getAllResults();
        } catch (Exception e) {
            throw new RewriteException("REWRITE_FAILED", 
                    "OpenRewrite execution failed: " + e.getMessage(), e);
        }

        // 7. Results in RewriteResult umwandeln
        Map<Path, RewrittenFile> changedFiles = new LinkedHashMap<>();
        Set<Path> deletedFiles = new LinkedHashSet<>();
        Set<Path> createdFiles = new LinkedHashSet<>();

        for (Result result : results) {
            Path beforePath = result.getBefore() != null
                    ? Path.of(result.getBefore().getSourcePath().toString())
                    : null;
            Path afterPath = result.getAfter() != null
                    ? Path.of(result.getAfter().getSourcePath().toString())
                    : null;

            if (result.getAfter() == null && result.getBefore() != null) {
                // Datei gelöscht
                deletedFiles.add(beforePath);
            } else if (result.getBefore() == null && result.getAfter() != null) {
                // Datei neu erstellt
                String newContent = result.getAfter().printAll();
                createdFiles.add(afterPath);
                changedFiles.put(afterPath, new RewrittenFile(
                        newContent, hasher.sha256(newContent), "CREATE"));
            } else if (result.getBefore() != null && result.getAfter() != null) {
                // Datei verändert — Key ist der ORIGINAL-Pfad (before): Template-Anwendungen
                // können dem After-Baum einen synthetischen SourcePath (z.B. "First.java")
                // geben, der nicht der echten Datei entspricht.
                String newContent = result.getAfter().printAll();
                changedFiles.put(beforePath, new RewrittenFile(
                        newContent, hasher.sha256(newContent), "MODIFY"));
            }
        }

        // 8. Prüfe ob Build-Refresh nötig ist (wenn Build-Dateien geändert wurden)
        boolean requiresBuildRefresh = def.mayChangeBuildFiles() &&
                (deletedFiles.stream().anyMatch(this::isBuildFile) ||
                 createdFiles.stream().anyMatch(this::isBuildFile) ||
                 changedFiles.keySet().stream().anyMatch(this::isBuildFile));

        return new RewriteResult(changedFiles, deletedFiles,
                createdFiles, diagnostics, requiresBuildRefresh);
    }

    /**
     * Instanziiere ein Recipe aus seiner Definition.
     * 
     * @param def Die RecipeDefinition
     * @param parameters Parameter für das Recipe
     * @return Die Recipe-Instanz
     * @throws RewriteException bei Instantiierungsfehlern
     */
    private Recipe instantiateRecipe(RecipeDefinition def, Map<String, String> parameters) throws RewriteException {
        try {
            Class<?> clazz = Class.forName(def.recipeClass());
            Recipe recipe = (Recipe) clazz.getDeclaredConstructor().newInstance();
            
            // Parameter setzen (falls das Recipe Setter-Methoden hat)
            for (Map.Entry<String, String> param : parameters.entrySet()) {
                String paramName = param.getKey();
                if (def.allowedParameters().contains(paramName)) {
                    try {
                        // Versuche, den Setter zu finden und aufzurufen
                        String setterName = "set" + paramName.substring(0, 1).toUpperCase() 
                                + paramName.substring(1);
                        clazz.getMethod(setterName, String.class)
                                .invoke(recipe, param.getValue());
                    } catch (Exception e) {
                        // Ignorieren - Parameter wird nicht gesetzt
                    }
                }
            }
            
            return recipe;
        } catch (Exception e) {
            throw new RewriteException("RECIPE_INSTANTIATION_FAILED",
                    "Could not instantiate recipe: " + def.recipeClass(), e);
        }
    }

    /**
     * Prüfe ob eine Datei im Scope liegt.
     */
    private boolean isInScope(Path relativePath, Set<Path> scope) {
        if (scope == null || scope.isEmpty()) return true;
        return scope.contains(relativePath);
    }

    /**
     * Prüfe ob eine Datei eine Build-Datei ist (pom.xml, build.gradle, etc.).
     * Delegates to PathUtils.isBuildFile() for consistent build file detection across the codebase.
     */
    private boolean isBuildFile(Path path) {
        return PathUtils.isBuildFile(path);
    }
}

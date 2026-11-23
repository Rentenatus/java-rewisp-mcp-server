/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import java.nio.file.InvalidPathException;

import java.nio.file.Files;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.build.ModuleContext;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.FileSnapshot;
import de.jare.rewisp.snapshot.ContentHasher;

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
 * OpenRewrite-based implementation of the RewriteEngine.
 * Runs recipes in memory and returns transformation results.
 */
public final class OpenRewriteEngineImpl implements RewriteEngine {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OpenRewriteEngineImpl.class);

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
        long start = de.jare.rewisp.observability.Phases.start();
        try {
            return previewTimed(snapshot, buildContext, request);
        } finally {
            de.jare.rewisp.observability.Phases.end("rewrite-preview", start);
        }
    }

    private RewriteResult previewTimed(
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

        // 1b. Die Anfrage bezieht sich auf einen bestimmten Snapshot: ein anderer Stand wuerde Edits
        //     auf veralteten Inhalten berechnen
        if (request.expectedSnapshot() != null && !request.expectedSnapshot().equals(snapshot.id())) {
            throw new RewriteException("SNAPSHOT_MISMATCH",
                    "Request was prepared for snapshot " + request.expectedSnapshot()
                            + " but the engine received " + snapshot.id());
        }

        // 2. Recipe instanziieren
        Recipe recipe = instantiateRecipe(def, request.parameters());

        // 3. OpenRewrite ExecutionContext erstellen
        ExecutionContext ctx = new InMemoryExecutionContext();
        
        // 4. JavaParser konfigurieren
        JavaParser.Builder parserBuilder = JavaParser.fromJavaVersion();
        
        // Classpath aus BuildContext hinzufügen (Pfade normalisieren für OpenRewrite)
        // In Test-Umgebungen ohne Maven kann buildContext null sein - das ist OK
        List<Path> classpath = classpathOf(buildContext);
        if (!classpath.isEmpty()) {
            // Collection<Path>: die String-Variante erwartet Artefaktnamen der Laufzeit, keine Dateipfade
            parserBuilder.classpath(classpath);
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
            for (SourceFile sf : sourceFiles) {
                if (sf instanceof org.openrewrite.tree.ParseError) {
                    // Nicht parsebare Dateien werden vom Recipe uebersprungen: Aufrufer muessen wissen,
                    // dass Referenzen darin nicht angepasst wurden.
                    diagnostics.add(new RewriteDiagnostic(
                            ErrorType.PARSE_ERROR.name(), "WARNING",
                            "File could not be parsed and was skipped by the recipe", sf.getSourcePath().toString()));
                }
            }
        } catch (Exception e) {
            // Parse-Fehler werden als Diagnose gesammelt
            diagnostics.add(new RewriteDiagnostic(
                    ErrorType.PARSE_ERROR.name(), "ERROR",
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
                rejectNewErrorMarkers(afterPath, "", newContent);
                createdFiles.add(afterPath);
                changedFiles.put(afterPath, new RewrittenFile(
                        newContent, hasher.sha256(newContent), "CREATE"));
            } else if (result.getBefore() != null && result.getAfter() != null) {
                // Datei verändert — Key ist der ORIGINAL-Pfad (before): Template-Anwendungen
                // können dem After-Baum einen synthetischen SourcePath (z.B. "First.java")
                // geben, der nicht der echten Datei entspricht.
                String newContent = result.getAfter().printAll();
                String beforeContent = result.getBefore().printAll();
                if (beforeContent.replace("\r", "").equals(newContent.replace("\r", ""))) {
                    continue; // gleicher Text (nur andere Baumidentitaet/Zeilenenden): keine Aenderung
                }
                rejectNewErrorMarkers(beforePath, beforeContent, newContent);
                changedFiles.put(beforePath, new RewrittenFile(
                        newContent, hasher.sha256(newContent), "MODIFY"));
            }
        }

        return new RewriteResult(changedFiles, deletedFiles,
                createdFiles, diagnostics);
    }

    private static final String ERROR_MARKER = "/*~~(";

    /**
     * OpenRewrite writes parse/type errors as {@code /*~~(...)~~>*&#47;} markers into the source text. An edit that
     * newly produces such markers must not be written to disk (field test: invalid parameter list).
     */
    static void rejectNewErrorMarkers(Path file, String before, String after) throws RewriteException {
        if (count(after, ERROR_MARKER) > count(before, ERROR_MARKER)) {
            int at = after.indexOf(ERROR_MARKER);
            int end = after.indexOf("~~>*/", at);
            String detail = end > at ? after.substring(at + ERROR_MARKER.length(), end) : "";
            throw new RewriteException("INVALID_RESULT",
                    "The edit would produce invalid Java in " + file + " (" + detail.trim() + "). Nothing was written.", null);
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    /**
     * Union of the compile classpaths of ALL modules (order preserved, without duplicates). A
     * multi-module project needs the dependencies of each module so that types are
     * attributed across module boundaries. Non-existent entries are skipped.
     * {@code buildContext} may be {@code null}.
     */
    static List<Path> classpathOf(BuildContext buildContext) {
        if (buildContext == null || buildContext.modules() == null) {
            return List.of();
        }
        Set<Path> all = new LinkedHashSet<>();
        for (ModuleContext module : buildContext.modules()) {
            if (module.compileClasspath() == null) continue;
            for (String p : module.compileClasspath()) {
                try {
                    Path path = Path.of(p);
                    if (Files.exists(path)) {
                        all.add(path);
                    } else {
                        LOG.debug("Skipping missing classpath entry: {}", p);
                    }
                } catch (InvalidPathException e) {
                    LOG.debug("Skipping invalid classpath entry '{}': {}", p, e.getMessage());
                }
            }
        }
        return List.copyOf(all);
    }

    /**
     * Instantiate a recipe from its definition.
     * 
     * @param def the RecipeDefinition
     * @param parameters parameters for the recipe
     * @return the recipe instance
     * @throws RewriteException on instantiation errors
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
                        String setterName = "set" + paramName.substring(0, 1).toUpperCase()
                                + paramName.substring(1);
                        clazz.getMethod(setterName, String.class)
                                .invoke(recipe, param.getValue());
                    } catch (Exception e) {
                        // Ein nicht setzbarer Parameter wuerde das Recipe still mit Defaults laufen lassen
                        throw new RewriteException("RECIPE_PARAM_INVALID",
                                "Recipe parameter '" + paramName + "' could not be set: " + e.getMessage(), e);
                    }
                }
            }

            return recipe;
        } catch (RewriteException e) {
            throw e;
        } catch (Exception e) {
            throw new RewriteException("RECIPE_INSTANTIATION_FAILED",
                    "Could not instantiate recipe: " + def.recipeClass(), e);
        }
    }

    /**
     * Check whether a file lies within the scope.
     */
    private boolean isInScope(Path relativePath, Set<Path> scope) {
        if (scope == null || scope.isEmpty()) return true;
        return scope.contains(relativePath);
    }
}

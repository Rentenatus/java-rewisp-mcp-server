/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.api.RewispDriver.EditResult;
import de.jare.rewisp.api.RewispDriver.MoveClassRequest;
import de.jare.rewisp.api.RewispDriver.MoveClassResult;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.RewrittenFile;
import de.jare.rewisp.util.PathUtils;
import de.jare.rewisp.util.ProjectLayout;

import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import static de.jare.rewisp.api.EditSupport.ctx;

/**
 * {@code move_class}: moves a class to another package. Spoon is used only for pre-edit analysis
 * (class existence, package, source file); the content change (package declaration, imports,
 * fully qualified references) happens via OpenRewrite, the physical file move via the
 * {@link EditManager}.
 *
 * @author Janusch Rentenatus
 */
final class MoveClassOperations {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "move-class";

    private MoveClassOperations() {
    }

    static MoveClassResult move(ProjectManager manager, EditManager editManager, RewriteEngine engine,
            EditPipeline pipeline, ProjectEntry entry, MoveClassRequest request) throws IOException {
        String className = request.className();
        String newPackage = request.newPackage();

        EditPipeline.requireEditable(entry);

        EditSupport.requireEngine(engine, "move_class",
                ctx("tool", "move_class", "className", className, "newPackage", newPackage));

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> targetType = EditSupport.requireType(entry, className);

        Path oldFile = EditSupport.sourceFile(targetType);
        if (oldFile == null) {
            throw new RewispException(ErrorType.NO_SOURCE_FILE,
                    "Cannot determine source file for class '" + className + "'.",
                    ctx("className", className, "newPackage", newPackage));
        }

        String oldPackage = targetType.getPackage() != null
                ? targetType.getPackage().getQualifiedName() : "";
        String simpleName = targetType.getSimpleName();

        if (oldPackage.equals(newPackage)) {
            return new MoveClassResult(entry.name(), true, null, 0, false, null);
        }

        // Ziel-Verzeichnis: gegen den Source-Root auflösen (nicht projectDir)
        Path srcDir = ProjectManager.resolveSourceRoot(entry);
        if (srcDir == null) srcDir = entry.projectDir();
        if (srcDir == null) srcDir = entry.originalProjectDir();
        if (srcDir == null) {
            throw new RewispException(ErrorType.NO_SOURCE_ROOT,
                    "Cannot determine source directory for project: " + request.project(),
                    ctx("className", className, "newPackage", newPackage));
        }
        Path newPackageDir = srcDir.resolve(newPackage.replace(".", "/"));
        Path newFile = newPackageDir.resolve(simpleName + ProjectLayout.JAVA_EXTENSION);

        // Nichts ueberschreiben: Zieldatei auf Platte, gepufferte Zieldatei oder Zieltyp im Modell
        String newQualifiedName = newPackage.isEmpty() ? simpleName : newPackage + "." + simpleName;
        boolean targetExists = Files.exists(newFile)
                || editManager.getPendingChanges().containsKey(PathUtils.canonical(newFile))
                || entry.model().getAllTypes().stream()
                        .anyMatch(t -> t.getQualifiedName().equals(newQualifiedName));
        if (targetExists) {
            throw new RewispException(ErrorType.CLASS_EXISTS,
                    "Target class already exists: " + newQualifiedName + " (" + newFile + ")",
                    ctx("className", className, "newPackage", newPackage));
        }

        // Gleiche-Package-Referenzen der verschobenen Klasse (z.B. Superklassen)
        // waren vorher implizit — nach dem Move brauchen sie explizite Importe.
        Set<String> samePackageImports = collectSamePackageImports(targetType, oldPackage, simpleName);

        // ========== PHASE 2: OPENREWRITE EDIT ==========
        // Gesamtes Projekt im Scope: Import-Updates betreffen potentiell alle Dateien
        Path relativeOldFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), oldFile);

        Map<String, String> params = new HashMap<>();
        params.put("fullyQualifiedClassName", className);
        params.put("targetPackage", newPackage);
        params.put("keepTypeName", "true");
        params.put("samePackageImports", String.join(",", samePackageImports));

        RewriteEdit edit = new RewriteEdit(RECIPE_ID, params, Set.of(), true, true,
                ctx("className", className, "newPackage", newPackage),
                "OpenRewrite produced no changes moving " + className + " to " + newPackage + ".",
                "The class may not be found by the recipe.",
                "move_class: " + className + " from " + oldPackage + " to " + newPackage
                        + " [OpenRewrite]");
        var outcome = pipeline.preview(entry, entry.name(), engine, edit, request.dryRun(),
                EditSupport.DEFAULT_COMPILE_HINT);
        if (outcome.dryRun()) {
            return new MoveClassResult(entry.name(), false, newPackage + "." + simpleName, 0, false,
                    new EditResult(entry.name(), outcome.preview(), outcome.dryRunSnapshot(), true,
                            entry.modulesDetected(), entry.modulesLoaded(), java.util.List.of()));
        }
        var previewResult = outcome.preview();

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        // Die Zieldatei wird am NEUEN Ort geschrieben; alle anderen geänderten
        // Dateien (Import-Updates) an Ort und Stelle.
        editManager.createDirectories(newPackageDir);
        editManager.inheritCharset(oldFile, newFile);
        int importsUpdated = 0;
        Map<Path, String> writes = new LinkedHashMap<>();
        for (Map.Entry<Path, RewrittenFile> changed : previewResult.changedFiles().entrySet()) {
            String content = changed.getValue().content();
            if (changed.getKey().equals(relativeOldFile)) {
                writes.put(newFile, content);
            } else {
                writes.put(PathUtils.resolveReal(entry.originalProjectDir(), changed.getKey()), content);
                importsUpdated++;
            }
        }
        entry = pipeline.writeAll(entry, editManager, writes);
        editManager.logEdit(edit.logEntry());

        // Alte Datei löschen — transaktionsbewusst: innerhalb einer Transaktion
        // puffert deleteFile die Löschung, sie wird erst beim Commit ausgeführt
        // und bei einem Rollback verworfen.
        editManager.deleteFile(oldFile);
        int oldLevels = oldPackage.isEmpty() ? 0 : oldPackage.split("\\.").length;
        editManager.pruneEmptyPackageDirs(oldFile.getParent(), oldLevels);
        manager.markDirty(entry.name());

        return new MoveClassResult(entry.name(), false, newPackage + "." + simpleName, importsUpdated,
                editManager.isInTransaction(),
                new EditResult(entry.name(), previewResult, null, false, entry.modulesDetected(),
                        entry.modulesLoaded(), java.util.List.of()));
    }

    /**
     * SPOON PRE-ANALYSIS: fully qualified type references of the moved class
     * that lie in the OLD package and need an explicit import after the move.
     */
    private static Set<String> collectSamePackageImports(CtType<?> targetType, String oldPackage,
            String simpleName) {
        Set<String> imports = new LinkedHashSet<>();
        String oldQualified = oldPackage + "." + simpleName;
        for (var ref : targetType.getElements(new TypeFilter<>(CtTypeReference.class))) {
            // Typparameter (T, E ...) sind keine importierbaren Typen
            if (ref instanceof CtTypeParameterReference) continue;
            // Importiert wird immer der Top-Level-Typ, auch bei Zugriff auf einen verschachtelten Typ
            var top = ref.getTopLevelType();
            if (top == null || top.getQualifiedName() == null) continue;
            String topName = top.getQualifiedName();
            if (topName.equals(oldQualified)) continue;
            String refPackage = top.getPackage() != null ? top.getPackage().getQualifiedName() : "";
            if (!refPackage.equals(oldPackage)) continue;
            if ("java.lang".equals(refPackage)) continue;
            imports.add(topName);
        }
        return imports;
    }
}

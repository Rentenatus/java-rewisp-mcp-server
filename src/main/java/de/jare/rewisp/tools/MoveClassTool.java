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
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.ProjectState;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.RewriteException;
import de.jare.rewisp.rewrite.RewriteRequest;
import de.jare.rewisp.rewrite.RewriteResult;
import de.jare.rewisp.rewrite.RewriteSyncManager;
import de.jare.rewisp.rewrite.RewrittenFile;
import de.jare.rewisp.util.PathUtils;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import spoon.reflect.declaration.CtType;

/**
 * The {@code MoveClassTool} — moves a class to a different package.
 *
 * <p>Architektur (ab 1.1.1-REWRITE): Spoon wird NUR für die Vorab-Analyse verwendet
 * (Klassen-Existenz, Package-Ermittlung, Quelldatei-Lokalisierung). Die inhaltliche
 * Änderung (Package-Deklaration, Import-Updates, vollqualifizierte Referenzen,
 * Same-Package-Importe) erfolgt AUSSCHLIESSLICH über OpenRewrite (MoveClassRecipe
 * mit ChangeType-Sub-Recipe). Der physische Datei-Move läuft über den EditManager.
 * Es gibt keinen Spoon-Fallback für Edit-Operationen mehr.</p>
 *
 * @author Janusch Rentenatus
 */
public class MoveClassTool extends BaseEditTool {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "move-class";

    private final EditManager editManager;
    private final RewriteEngine rewriteEngine;
    private final RewriteSyncManager rewriteSyncManager;

    /**
     * Konstruktor mit vollständiger OpenRewrite-Unterstützung.
     * OpenRewrite ist Pflicht — es gibt keinen Spoon-Fallback mehr.
     */
    public MoveClassTool(ProjectManager manager, EditManager editManager,
                       RewriteEngine rewriteEngine,
                       RewriteSyncManager rewriteSyncManager) {
        super(manager);
        this.editManager = editManager;
        this.rewriteEngine = rewriteEngine;
        this.rewriteSyncManager = rewriteSyncManager;
    }

    @Override
    protected String toolName() {
        return "move_class";
    }

    @Override
    protected String toolDescription() {
        return "[REWRITE] Move a class to a different package. Rewrites package declaration and updates "
                + "imports across all loaded projects. "
                + "Content via OpenRewrite; Spoon only for pre-edit analysis.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "newPackage", Map.of("type", "string", "description", "Target package name"),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }

    @Override
    protected java.util.List<String> toolRequired() {
        return req("name", "className", "newPackage");
    }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String newPackage = arg(request, "newPackage");

        ProjectEntry entry = findEntry(name);
        requireEditable(entry);

        if (rewriteEngine == null) {
            return domainError("OPENREWRITE_REQUIRED",
                    "move_class requires the OpenRewrite engine. "
                            + "Per architecture decision there is no Spoon fallback for edits.",
                    ctx("tool", toolName(), "className", className, "newPackage", newPackage));
        }

        // ========== PHASE 1: SPOON PRE-ANALYSIS (read-only) ==========
        CtType<?> targetType = entry.model().getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals(className))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Type not found: " + className));

        Path oldFile = targetType.getPosition().getFile() != null
                ? targetType.getPosition().getFile().toPath() : null;
        if (oldFile == null) {
            return domainError("NO_SOURCE_FILE",
                    "Cannot determine source file for class '" + className + "'.",
                    ctx("className", className, "newPackage", newPackage));
        }

        String oldPackage = targetType.getPackage() != null
                ? targetType.getPackage().getQualifiedName() : "";
        String simpleName = targetType.getSimpleName();

        if (oldPackage.equals(newPackage)) {
            return ok("Class " + className + " is already in package " + newPackage + ". No changes needed.");
        }

        // Ziel-Verzeichnis: gegen den Source-Root auflösen (nicht projectDir)
        Path srcDir = ProjectManager.resolveSourceRoot(entry);
        if (srcDir == null) srcDir = entry.projectDir();
        if (srcDir == null) srcDir = entry.originalProjectDir();
        if (srcDir == null) {
            return domainError("NO_SOURCE_ROOT",
                    "Cannot determine source directory for project: " + name,
                    ctx("className", className, "newPackage", newPackage));
        }
        Path newPackageDir = srcDir.resolve(newPackage.replace(".", "/"));
        Path newFile = newPackageDir.resolve(simpleName + ".java");

        // Gleiche-Package-Referenzen der verschobenen Klasse (z.B. Superklassen)
        // waren vorher implizit — nach dem Move brauchen sie explizite Importe.
        Set<String> samePackageImports = collectSamePackageImports(targetType, oldPackage, simpleName);

        // ========== PHASE 2: OPENREWRITE EDIT (mandatory) ==========
        ProjectState state = manager.getProjectState(name);
        if (state == null || state.snapshot() == null) {
            return domainError("NO_SNAPSHOT", "No snapshot available for project: " + name,
                    ctx("className", className, "newPackage", newPackage));
        }

        // Bei dreckigem Projekt (vorherige Edits): Snapshot aktualisieren, damit
        // OpenRewrite die aktuellen Dateiinhalte sieht. Schmaler Scope liest nur
        // die dreckigen Dateien neu (Stale-Map), breiter Scope baut komplett neu auf.
        String refreshError = refreshSnapshotForEdit(entry, true);
        if (refreshError != null) {
            return domainError("SNAPSHOT_REFRESH_FAILED",
                    "Could not refresh project snapshot before edit: " + refreshError,
                        ctx("className", className, "newPackage", newPackage));
        }

        // Gesamtes Projekt im Scope: Import-Updates betreffen potentiell alle Dateien
        Path relativeOldFile = PathUtils.relativizeFromRoot(entry.originalProjectDir(), oldFile);

        Map<String, String> params = new HashMap<>();
        params.put("fullyQualifiedClassName", className);
        params.put("targetPackage", newPackage);
        params.put("keepTypeName", "true");
        params.put("samePackageImports", String.join(",", samePackageImports));

        RewriteRequest rewriteRequest = new RewriteRequest(
                name, RECIPE_ID, params, Set.of(), state.snapshot().id());

        // BuildContext laden (null-Fallback: RAW-Projekte ohne Build laufen weiter)
        de.jare.rewisp.build.BuildContext buildContext = null;
        try {
            buildContext = manager.ensureBuildContext(name);
        } catch (de.jare.rewisp.build.BuildResolutionException e) {
            // Build-Kontext nicht verfuegbar - fuer viele Recipes OK
            // (OpenRewriteEngineImpl behandelt null)
        }

        RewriteResult previewResult;
        try {
            previewResult = rewriteEngine.preview(state.snapshot(), buildContext, rewriteRequest);

        } catch (RewriteException e) {
            return domainError("REWRITE_PREVIEW_FAILED",
                    "OpenRewrite preview failed: " + e.getMessage(),
                    ctx("className", className, "newPackage", newPackage, "errorCode", e.code()));
        }

        // ========== DRY RUN: nur Preview, keine Dateien schreiben ==========
        if (boolArg(request, "dryRun")) {
            StringBuilder sb = new StringBuilder();
            sb.append("DRY RUN - ").append(toolName())
              .append(": no files written. Would change ")
              .append(previewResult.changedFiles().size()).append(" file(s):\n");
            for (Path rel : previewResult.changedFiles().keySet()) {
                sb.append("  modify ").append(rel).append('\n');
            }
            for (Path rel : previewResult.deletedFiles()) {
                sb.append("  delete ").append(rel).append('\n');
            }
            for (Path rel : previewResult.createdFiles()) {
                sb.append("  create ").append(rel).append('\n');
            }
            return ok(sb);
        }

        if (previewResult.changedFiles().isEmpty()) {

            return domainError("NO_CHANGES",
                    "OpenRewrite produced no changes moving " + className + " to " + newPackage + ".",
                    ctx("className", className, "newPackage", newPackage,
                        "suggestion", "The class may not be found by the recipe."));
        }

        // ========== PHASE 3: APPLY VIA EDITMANAGER ==========
        // Die Zieldatei wird am NEUEN Ort geschrieben; alle anderen geänderten
        // Dateien (Import-Updates) an Ort und Stelle.
        editManager.createDirectories(newPackageDir);
        int importsUpdated = 0;
        for (Map.Entry<Path, RewrittenFile> changed : previewResult.changedFiles().entrySet()) {
            String content = changed.getValue().content();
            if (changed.getKey().equals(relativeOldFile)) {
                entry = editManager.writeFile(entry, newFile, content, null);
            } else {
                Path absFile = PathUtils.resolveReal(entry.originalProjectDir(), changed.getKey());
                entry = editManager.writeFile(entry, absFile, content, null);
                importsUpdated++;
            }
        }
        manager.updateEntry(entry);
        editManager.logEdit(toolName() + ": " + className + " from " + oldPackage + " to " + newPackage
                + " [OpenRewrite]");

        // Alte Datei löschen — transaktionsbewusst: innerhalb einer Transaktion
        // puffert deleteFile die Löschung, sie wird erst beim Commit ausgeführt
        // und bei einem Rollback verworfen.
        editManager.deleteFile(oldFile);
        manager.markDirty(name);

        if (rewriteSyncManager != null) {
            rewriteSyncManager.onRewriteComplete(previewResult);
        }

        String newQualified = newPackage + "." + simpleName;
        StringBuilder sb = new StringBuilder();
        sb.append("Moved: ").append(className).append(" -> ").append(newQualified).append(" [OpenRewrite]\n");
        sb.append("Imports updated: ").append(importsUpdated).append("\n");
        if (editManager.isInTransaction()) {
            sb.append("NOTE: old file deletion deferred to commit_transaction.\n");
        }
        sb.append(formatMultiModuleWarning(entry));
        return ok(sb);
    }

    /**
     * SPOON PRE-ANALYSIS: vollqualifizierte Typ-Referenzen der verschobenen Klasse,
     * die im ALTEN Package liegen und nach dem Move einen expliziten Import brauchen.
     */
    private static Set<String> collectSamePackageImports(spoon.reflect.declaration.CtType<?> targetType,
                                                          String oldPackage, String simpleName) {
        Set<String> imports = new java.util.LinkedHashSet<>();
        String oldQualified = oldPackage + "." + simpleName;
        for (var ref : targetType.getElements(
                new spoon.reflect.visitor.filter.TypeFilter<>(spoon.reflect.reference.CtTypeReference.class))) {
            if (ref.getQualifiedName() == null) continue;
            if (ref.getQualifiedName().equals(oldQualified)) continue;
            String refPackage = ref.getPackage() != null ? ref.getPackage().getQualifiedName() : "";
            if (!refPackage.equals(oldPackage)) continue;
            if ("java.lang".equals(refPackage)) continue;
            imports.add(ref.getQualifiedName());
        }
        return imports;
    }
}

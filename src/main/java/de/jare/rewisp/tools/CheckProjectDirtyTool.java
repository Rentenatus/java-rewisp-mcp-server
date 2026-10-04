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
import de.jare.rewisp.VersionLoader;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.ProjectState;
import de.jare.rewisp.snapshot.SnapshotService;
import de.jare.rewisp.snapshot.DirtyChecker;
import de.jare.rewisp.snapshot.DirtyCheckResult;
import de.jare.rewisp.snapshot.FileSnapshot;
import de.jare.rewisp.build.PomUtils;

import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code CheckProjectDirtyTool} class.
 * Uses SnapshotService to capture fresh snapshot and compare against previous state.
 * Architecture: Spoon -> REWRITE (SnapshotService) -> Storage
 *
 * @author Janusch Rentenatus
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class CheckProjectDirtyTool extends BaseReadTool {

    private static final Logger LOG = LoggerFactory.getLogger(CheckProjectDirtyTool.class);
    private final SnapshotService snapshotService;

    public CheckProjectDirtyTool(ProjectManager manager) {
        super(manager);
        this.snapshotService = manager.getSnapshotService();
    }

    @Override
    protected String toolName() {
        return "check_project_dirty";
    }

    @Override
    protected String toolDescription() {
        return "[SPOON] Performs a full scan of the project source root by comparing current snapshot "
            + "with previous state. Detects changed, deleted, and new files since load. "
            + "This is the central status tool — use it to check if a reload is needed.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return propsWithDescription("name", "string", "Project name or alias");
    }

    @Override
    protected List<String> toolRequired() {
        return req("name");
    }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        var entry = findEntry(name);

        ProjectState state = manager.getProjectState(name);
        
        // Capture fresh snapshot from REWRITE layer
        ProjectSnapshot currentSnapshot = captureFreshSnapshot(entry);
        if (currentSnapshot == null) {
            // Cannot create snapshot - return error
            StringBuilder sb = new StringBuilder();
            sb.append("> ❌ **Snapshot capture failed** - unable to check dirty state.\n");
            return ok(sb);
        }

        // Perform dirty check using snapshot comparison
        de.jare.rewisp.snapshot.DirtyCheckResult dirty = DirtyChecker.checkDirtyAgainstSnapshot(currentSnapshot, state);

        // Check pom.xml and parent POM changes using snapshot content
        boolean pomChanged = checkPomChangedFromSnapshot(currentSnapshot, state, entry);
        boolean parentPomChanged = checkParentPomChangedFromSnapshot(currentSnapshot, state, entry);
        
        // Check Gradle build files changes
        boolean gradleChanged = checkGradleFilesChangedFromSnapshot(currentSnapshot, state, entry);

        if (pomChanged || parentPomChanged || gradleChanged) {
            if (state != null) {
                state.dirtyState().markBuildConfiguration();
            }
            manager.invalidateBuildContext(name);

            // Bei Parent-POM-Aenderung: alle Module, die diese Parent nutzen, invalidieren
            if (parentPomChanged) {
                invalidateAllModulesWithParent(entry, state);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Dirty Check: `").append(entry.name()).append("`\n\n");
        sb.append("**MCP Server Version:** ").append(VersionLoader.getVersion()).append("\n\n");

        if (state != null && state.snapshotId() != null) {
            sb.append("**Previous Snapshot:** `").append(state.snapshotId()).append("`\n");
        }
        if (currentSnapshot != null) {
            sb.append("**Current Snapshot:** `").append(currentSnapshot.id()).append("`\n\n");
        }

        if (!dirty.isDirty()) {
            sb.append("> ✅ **Project is clean** — ")
              .append(dirty.filesChecked())
              .append(" source files checked, no changes since load.\n");
            if (state != null) {
                sb.append("\n| Flag | Value |\n|------|-------|\n");
                sb.append("| buildModelDirty | ").append(state.dirtyState().isBuildModelDirty()).append(" |\n");
                sb.append("| classpathDirty | ").append(state.dirtyState().isClasspathDirty()).append(" |\n");
                sb.append("| requiresFullSemanticRefresh | ").append(state.dirtyState().requiresFullSemanticRefresh()).append(" |\n");
            }
            return ok(sb);
        }

        int total = dirty.changedFiles().size() + dirty.deletedFiles().size() + dirty.newFiles().size();
        sb.append("> ⚠️ **Project is dirty** — ")
          .append(dirty.filesChecked()).append(" source files on disk, ")
          .append(entry.sourceFingerprints().size()).append(" in fingerprint map:\n")
          .append("> ").append(dirty.changedFiles().size()).append(" changed, ")
          .append(dirty.deletedFiles().size()).append(" deleted, ")
          .append(dirty.newFiles().size()).append(" new since load.\n\n");

        sb.append("| Status | File | Reason | Origin |\n");
        sb.append("|--------|------|--------|--------|\n");
        for (String f : dirty.changedFiles()) sb.append("| Changed | ").append(f).append(" | CONTENT_CHANGED | SNAPSHOT |\n");
        for (String f : dirty.deletedFiles()) sb.append("| Deleted | ").append(f).append(" | SOURCE_DELETED | SNAPSHOT |\n");
        for (String f : dirty.newFiles()) sb.append("| New | ").append(f).append(" | SOURCE_NEW | SNAPSHOT |\n");

        if (state != null) {
            sb.append("\n| Flag | Value |\n|------|-------|\n");
            sb.append("| buildModelDirty | ").append(state.dirtyState().isBuildModelDirty()).append(" |\n");
            sb.append("| classpathDirty | ").append(state.dirtyState().isClasspathDirty()).append(" |\n");
            sb.append("| pomXmlChanged | ").append(pomChanged).append(" |\n");
            sb.append("| requiresFullSemanticRefresh | ").append(state.dirtyState().requiresFullSemanticRefresh()).append(" |\n");
        }

        sb.append("\nCall `reload_java_project` with name `").append(entry.name())
          .append("` to refresh the model.\n");
        sb.append("\n**Note:** New files created after the initial load are detected via snapshot comparison. ");
        sb.append("After reload, all new, changed, and deleted files are reflected in the model.\n");

        return ok(sb);
    }

    /**
     * Erstellt einen frischen Snapshot ueber REWRITE-Ebene.
     */
    private ProjectSnapshot captureFreshSnapshot(ProjectEntry entry) {
        try {
            long sequence = manager.getSnapshotSequence().incrementAndGet();
            return snapshotService.captureStable(entry, sequence);
        } catch (IOException e) {
            LOG.warn("Failed to capture fresh snapshot for dirty check: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Prueft pom.xml-Aenderung basierend auf Snapshot-Inhalt.
     * Vergleicht aktuellen Snapshot mit vorherigem Snapshot aus ProjectState.
     */
    private boolean checkPomChangedFromSnapshot(ProjectSnapshot currentSnapshot, ProjectState state, ProjectEntry entry) {
        if (state == null || state.snapshot() == null || currentSnapshot == null) return false;
        
        // pom.xml Pfad bestimmen
        Path projectBase = entry.originalProjectDir() != null 
                ? entry.originalProjectDir() 
                : entry.projectDir();
        Path pomPath = projectBase.resolve("pom.xml");
        Path relativePomPath = projectBase.relativize(pomPath);
        
        // Content-Hash aus aktuellem Snapshot holen
        String currentPomHash = null;
        FileSnapshot currentPomSnapshot = currentSnapshot.files().get(relativePomPath);
        if (currentPomSnapshot != null) {
            currentPomHash = currentPomSnapshot.contentHash();
        }
        
        // Content-Hash aus vorherigem Snapshot holen
        String previousPomHash = null;
        FileSnapshot previousPomSnapshot = state.snapshot().files().get(relativePomPath);
        if (previousPomSnapshot != null) {
            previousPomHash = previousPomSnapshot.contentHash();
        }
        
        // Wenn pom.xml in einem der Snapshots fehlt, keine Aenderung
        if (currentPomHash == null || previousPomHash == null) return false;
        
        return !currentPomHash.equals(previousPomHash);
    }

    /**
     * Prueft Parent-POM-Aenderung basierend auf Snapshot-Inhalt.
     * Vergleicht aktuellen Snapshot mit vorherigem Snapshot aus ProjectState.
     * 
     * HINWEIS: Parent-POMs außerhalb des Projektverzeichnisses koennen nicht 
     * erkannt werden, da sie nicht im Snapshot enthalten sind.
     */
    private boolean checkParentPomChangedFromSnapshot(ProjectSnapshot currentSnapshot, ProjectState state, ProjectEntry entry) {
        if (state == null || state.snapshot() == null || currentSnapshot == null) return false;
        
        // Parent-POM Pfad bestimmen
        Path projectBase = entry.originalProjectDir() != null 
                ? entry.originalProjectDir() 
                : entry.projectDir();
        Path parentPomPath = PomUtils.resolveParentPomPath(projectBase);
        if (parentPomPath == null) return false;
        
        // Relativen Pfad zur Parent-POM bestimmen
        Path relativeParentPomPath;
        try {
            relativeParentPomPath = projectBase.relativize(parentPomPath);
        } catch (IllegalArgumentException e) {
            // Parent-POM liegt außerhalb des Projektverzeichnisses
            // -> Kann nicht im Snapshot sein, also keine Prüfung möglich
            LOG.debug("Parent POM {} is outside project directory {}, cannot check for changes via snapshot", 
                    parentPomPath, projectBase);
            return false;
        }
        
        // Content-Hash aus aktuellem Snapshot holen
        String currentParentPomHash = null;
        FileSnapshot currentParentPomSnapshot = currentSnapshot.files().get(relativeParentPomPath);
        if (currentParentPomSnapshot != null) {
            currentParentPomHash = currentParentPomSnapshot.contentHash();
        }
        
        // Content-Hash aus vorherigem Snapshot holen
        String previousParentPomHash = null;
        FileSnapshot previousParentPomSnapshot = state.snapshot().files().get(relativeParentPomPath);
        if (previousParentPomSnapshot != null) {
            previousParentPomHash = previousParentPomSnapshot.contentHash();
        }
        
        // Wenn Parent-POM in einem der Snapshots fehlt, keine Aenderung
        if (currentParentPomHash == null || previousParentPomHash == null) return false;
        
        return !currentParentPomHash.equals(previousParentPomHash);
    }

    /**
     * Prueft, ob sich Gradle-Build-Dateien geaendert haben.
     * Vergleicht Content-Hashes von build.gradle, settings.gradle, gradle.properties
     * zwischen aktuellem und vorherigem Snapshot.
     */
    private boolean checkGradleFilesChangedFromSnapshot(ProjectSnapshot currentSnapshot, ProjectState state, ProjectEntry entry) {
        if (state == null || state.snapshot() == null || currentSnapshot == null) return false;
        
        Path projectBase = entry.originalProjectDir() != null 
                ? entry.originalProjectDir() 
                : entry.projectDir();
        
        // Gradle-Dateien, die Build-Invalidierung ausloesen
        String[] gradleFiles = {"build.gradle", "build.gradle.kts", "settings.gradle", 
                "settings.gradle.kts", "gradle.properties"};
        
        for (String gradleFile : gradleFiles) {
            Path gradlePath = projectBase.resolve(gradleFile);
            Path relativeGradlePath = projectBase.relativize(gradlePath);
            
            // Content-Hash aus aktuellem Snapshot
            String currentHash = null;
            FileSnapshot currentSnapshotFile = currentSnapshot.files().get(relativeGradlePath);
            if (currentSnapshotFile != null) {
                currentHash = currentSnapshotFile.contentHash();
            }
            
            // Content-Hash aus vorherigem Snapshot
            String previousHash = null;
            FileSnapshot previousSnapshotFile = state.snapshot().files().get(relativeGradlePath);
            if (previousSnapshotFile != null) {
                previousHash = previousSnapshotFile.contentHash();
            }
            
            // Wenn die Datei in einem der Snapshots fehlt, überspringen
            if (currentHash == null || previousHash == null) continue;
            
            // Wenn sich der Hash geaendert hat, war die Datei modified
            if (!currentHash.equals(previousHash)) {
                LOG.debug("Gradle file changed: {}", gradleFile);
                return true;
            }
        }
        
        return false;
    }

    /**
     * Invalidiert alle geladenen Module, die dieselbe Parent-POM wie das angegebene Projekt nutzen.
     * Wird aufgerufen, wenn sich die Parent-POM geaendert hat.
     * Vergleich basiert auf dem Parent-POM-Pfad (nicht auf Hashes).
     */
    private void invalidateAllModulesWithParent(ProjectEntry entry, ProjectState state) {
        Path projectBase = entry.originalProjectDir() != null ? entry.originalProjectDir() : entry.projectDir();
        Path parentPomPath = PomUtils.resolveParentPomPath(projectBase);
        if (parentPomPath == null) return;
        
        // Durch alle geladenen Projekte iterieren und die mit derselben Parent-POM invalidieren
        for (var loadedEntry : manager.list()) {
            if (loadedEntry.name().equals(entry.name())) continue;
            
            Path loadedProjectBase = loadedEntry.originalProjectDir() != null 
                    ? loadedEntry.originalProjectDir() 
                    : loadedEntry.projectDir();
            Path loadedParentPomPath = PomUtils.resolveParentPomPath(loadedProjectBase);
            
            if (loadedParentPomPath != null && loadedParentPomPath.equals(parentPomPath)) {
                // Gleiches Parent-POM-Verzeichnis - invalidieren
                ProjectState loadedState = manager.getProjectState(loadedEntry.name());
                if (loadedState != null) {
                    loadedState.dirtyState().markBuildConfiguration();
                    manager.invalidateBuildContext(loadedEntry.name());
                    LOG.info("Invalidated module '{}' due to parent POM change in {}", 
                            loadedEntry.name(), parentPomPath);
                }
            }
        }
    }

}

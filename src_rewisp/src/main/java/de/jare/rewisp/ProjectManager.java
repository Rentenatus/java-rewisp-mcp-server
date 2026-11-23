/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
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

package de.jare.rewisp;

import java.util.Map;
import java.util.Set;

import de.jare.rewisp.model.ErrorType;

import de.jare.rewisp.lombok.LombokDetector;
import de.jare.rewisp.lombok.LombokDelomboker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.analysis.SpoonModelService;
import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.build.PomUtils;
import de.jare.rewisp.build.BuildContextService;
import de.jare.rewisp.build.BuildResolutionException;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.model.ProjectLoadException;
import de.jare.rewisp.project.ProjectRegistry;
import de.jare.rewisp.snapshot.ChangeTracker;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.ProjectState;
import de.jare.rewisp.snapshot.RewriteStateService;
import de.jare.rewisp.snapshot.SnapshotService;
import de.jare.rewisp.snapshot.WorkspaceRegistry;
import de.jare.rewisp.snapshot.WorkspaceWatcher;
import de.jare.rewisp.util.PathUtils;
import de.jare.rewisp.util.ProjectBuildHelpers;
import de.jare.rewisp.util.ProjectCleanupHelper;

import spoon.Launcher;
import spoon.reflect.CtModel;

/**
 * Facade over the project management: loading, reloading and unloading of projects and access to
 * the specialized services. The actual work lives in:
 * <ul>
 *   <li>{@link ProjectRegistry} - loaded projects (lookup, dirty/expiry)</li>
 *   <li>{@link RewriteStateService} - snapshot and effective Rewrite state</li>
 *   <li>{@link SpoonModelService} - Spoon model, built only from the Rewrite state</li>
 *   <li>{@link BuildContextService} - build context cache</li>
 * </ul>
 *
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
public class ProjectManager {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectManager.class);

    private final ProjectRegistry registry = new ProjectRegistry();
    private final RewriteStateService rewriteState = new RewriteStateService();
    private final SpoonModelService spoonModels = new SpoonModelService(registry, rewriteState);
    private final BuildContextService buildContexts = new BuildContextService();

    private final ProjectLoader projectLoader = new ProjectLoader();
    private final BuildDetector buildDetector = new BuildDetector();
    private final LombokDetector lombokDetector = new LombokDetector();
    private final LombokDelomboker lombokDelomboker = new LombokDelomboker();
    /** Default lifetime of a project without expiryDate (minutes); overridable via -Dproject.defaultExpiryMinutes. */
    public static final long DEFAULT_EXPIRY_MINUTES = 60;
    public static final String DEFAULT_EXPIRY_PROPERTY = "project.defaultExpiryMinutes";
    private final Object loadLock = new Object();
    private volatile java.time.Duration evictionGrace =
            java.time.Duration.ofMinutes(Long.getLong("project.evictionGraceMinutes", 5L));

    private de.jare.rewisp.edit.EditManager editManager;
    private WorkspaceRegistry workspaceRegistry;
    private ChangeTracker changeTracker;
    private WorkspaceWatcher workspaceWatcher;

    // ==================== Laden ====================

    /**
     * Loads a Java project with automatic delombok enabled.
     */
    public ProjectEntry load(String source, String alias, Instant expiryDate) throws ProjectLoadException {
        return load(source, alias, expiryDate, true, null);
    }

    /**
     * Loads a Java project, optionally running delombok if Lombok is detected.
     */
    public ProjectEntry load(String source, String alias, Instant expiryDate, boolean autoDelombok)
            throws ProjectLoadException {
        return load(source, alias, expiryDate, autoDelombok, null);
    }

    /**
     * Loads a Java project with explicit editable flag and optional delombok.
     * JAR sources are hard-locked to editable=false regardless of the requested value.
     */
    public ProjectEntry load(String source, String alias, Instant expiryDate, boolean autoDelombok,
                             Boolean editableOverride) throws ProjectLoadException {
        // Laden ist selten und schwer: ein Load nach dem anderen (kein Zusammenspiel von Registry,
        // Rewrite-Zustand und Watcher, das halb aufgebaut ist, wenn ein zweiter Load dazwischenkommt)
        synchronized (loadLock) {
            return loadLocked(source, alias, expiryDate, autoDelombok, editableOverride);
        }
    }

    private ProjectEntry loadLocked(String source, String alias, Instant expiryDate, boolean autoDelombok,
                                    Boolean editableOverride) throws ProjectLoadException {
        markExpired();

        if (expiryDate == null) {
            expiryDate = Instant.now().plus(Long.getLong(DEFAULT_EXPIRY_PROPERTY, DEFAULT_EXPIRY_MINUTES), ChronoUnit.MINUTES);
        }

        // AP-5.2 (RT:P-07): lokale Quellen VOR resolveSource pruefen — resolveSource entpackt
        // lokale Archive als Seiteneffekt, das darf erst nach der Allowlist-Pruefung passieren
        if (workspaceRegistry != null && source != null && !source.isBlank()
                && !source.startsWith("http://") && !source.startsWith("https://")) {
            try {
                Path local = source.startsWith("file:")
                        ? Path.of(java.net.URI.create(source)).normalize().toAbsolutePath()
                        : Path.of(source).normalize().toAbsolutePath();
                if (Files.exists(local) && !workspaceRegistry.isAllowed(local)) {
                    throw new ProjectLoadException(ErrorType.SECURITY_ERROR,
                            "Project path is outside the allowed workspace roots: " + local);
                }
            } catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
                // ungueltige URI / Pfad: resolveSource meldet den Fehler fachlich
            }
        }

        Path projectDir = projectLoader.resolveSource(source);
        // Vor jeder weiteren Verarbeitung pruefen: Delombok startet Maven/Gradle im Projektverzeichnis
        // und wuerde dort Code eines nicht erlaubten Verzeichnisses ausfuehren
        if (workspaceRegistry != null && !workspaceRegistry.isAllowed(projectDir)) {
            projectLoader.cleanup(projectDir);
            throw new ProjectLoadException(ErrorType.SECURITY_ERROR,
                    "Project path is outside the allowed workspace roots: " + projectDir);
        }
        BuildDetector.BuildInfo buildInfo = buildDetector.detect(projectDir);
        LOG.info("Build detected: {} ({}) for {}", buildInfo.type(), buildInfo.detail(), projectDir);

        String name = ProjectBuildHelpers.deriveName(projectDir, source);
        if (alias == null) alias = name;
        // Alias darf weder ein fremder Projektname noch der Alias eines anderen Projekts sein
        ProjectEntry aliasOwner = registry.find(alias);
        if (aliasOwner != null && !aliasOwner.name().equals(name)) {
            throw new ProjectLoadException(ErrorType.INVALID_PARAMS,
                    "Alias '" + alias + "' is already used by project '" + aliasOwner.name() + "'.");
        }

        // Der alte Eintrag bleibt bestehen, bis der neue vollstaendig aufgebaut ist: scheitert das Laden,
        // darf das Projekt nicht verloren gehen (Bug #14).
        ProjectEntry old = registry.findByName(name);

        boolean delomboked = false;
        String lombokVersion = null;
        Path sourceToAnalyze = projectDir;
        LombokDetector.LombokInfo lombokInfo = lombokDetector.detect(projectDir);
        boolean lombokInSources = lombokInfo.present() || lombokDetector.hasLombokInSources(projectDir);

        if (lombokInSources && autoDelombok) {
            try {
                Path delombokedDir = lombokDelomboker.delombok(projectDir, buildInfo.type(), lombokInfo);
                sourceToAnalyze = delombokedDir;
                delomboked = true;
                lombokVersion = lombokInfo.version();
                LOG.info("Lombok detected (version={}, build={}). Using delomboked source: {}",
                        lombokVersion, buildInfo.type(), delombokedDir);
            } catch (ProjectLoadException e) {
                LOG.warn("Auto-delombok failed ({}). Falling back to original source. "
                        + "Lombok-generated members will be missing from the model.", e.getMessage());
            }
        } else if (lombokInSources) {
            LOG.info("Lombok detected but auto-delombok disabled. Lombok-generated members "
                    + "will be missing from the model.");
        }

        try {
            if (workspaceRegistry != null) {
                try {
                    projectDir = workspaceRegistry.register(name, projectDir);
                } catch (SecurityException e) {
                    throw new ProjectLoadException(ErrorType.SECURITY_ERROR, e.getMessage());
                }
            }

            Launcher launcher;
            if (delomboked) {
                // Das Delombok-Verzeichnis enthaelt nur Quellen, kein pom.xml: ein MavenLauncher waere dort nicht
                // baubar. Stattdessen noclasspath ueber die Quellen, ergaenzt um den Klassenpfad des Originals.
                BuildDetector.BuildInfo plain = new BuildDetector.BuildInfo(
                        BuildDetector.BuildType.RAW, buildInfo.detail(), sourceToAnalyze, buildInfo.detectedMarkers());
                launcher = createLauncher(sourceToAnalyze, plain);
                ProjectBuildHelpers.addToClasspath(launcher,
                        lombokDelomboker.dependencyClasspath(projectDir, buildInfo.type()));
            } else {
                launcher = createLauncher(sourceToAnalyze, buildInfo);
            }
            // Bei Reload bewusst ohne den alten Build-Kontext (er wird mit dem alten Eintrag verworfen)
            ProjectBuildHelpers.enrichClasspathFromBuild(launcher, old == null ? buildContexts.cached(name) : null);
            ProjectBuildHelpers.applySourceEncoding(launcher, sourceToAnalyze);
            CtModel model = launcher.buildModel();
            List<Path> spoonSourceDirs = SpoonModelService.sourceRootsOf(model);
            cleanupSpoonClasspathDumps(projectDir);

            boolean isJar = source != null && source.toLowerCase().endsWith(".jar");
            boolean editable = isJar ? false : (editableOverride != null ? editableOverride : true);
            int modulesDetected = ProjectBuildHelpers.countModules(projectDir, buildInfo);
            int modulesLoaded = 1;

            ProjectEntry entry = new ProjectEntry(name, alias, expiryDate,
                    sourceToAnalyze, launcher, model, buildInfo.type().name(),
                    delomboked, lombokVersion,
                    projectDir, source, ProjectBuildHelpers.buildFingerprints(projectDir), false, editable,
                    modulesDetected, modulesLoaded, Set.of(), Set.of());
            // Erst jetzt den alten Eintrag ersetzen und aufraeumen
            if (old != null) {
                registry.removeByName(name);
                rewriteState.remove(name);
                buildContexts.invalidate(name);
                cleanupReplaced(old, entry);
                LOG.info("Replaced previously loaded project '{}'", name);
                if (workspaceRegistry != null) {
                    workspaceRegistry.register(name, projectDir); // das Aufraeumen hat die Registrierung entfernt
                }
            }
            registry.put(entry);
            ProjectState state = rewriteState.captureInitial(entry, buildInfo, spoonSourceDirs);

            if (changeTracker != null && workspaceWatcher != null && workspaceRegistry != null) {
                changeTracker.registerProject(name, projectDir, state.dirtyState());
                try {
                    workspaceWatcher.watch(name, projectDir);
                    LOG.info("Workspace watcher started for project '{}'", name);
                } catch (IOException e) {
                    LOG.warn("Could not start workspace watcher for '{}': {}", name, e.getMessage());
                }
            }

            LOG.info("Project '{}' loaded successfully ({} types, delomboked={})",
                    name, model.getAllTypes().size(), delomboked);
            return entry;
        } catch (ProjectLoadException e) {
            discardFailedLoad(old, name, projectDir, sourceToAnalyze, delomboked);
            throw e; // fachlichen Fehlercode (z.B. SECURITY_ERROR) nicht in PARSE_ERROR umdeuten
        } catch (Exception e) {
            discardFailedLoad(old, name, projectDir, sourceToAnalyze, delomboked);
            throw new ProjectLoadException(ErrorType.PARSE_ERROR, "Failed to parse project: " + e.getMessage());
        }
    }

    /**
     * Reloads a single project from its stored original source.
     */
    public ProjectEntry reload(String nameOrAlias) throws ProjectLoadException {
        ProjectEntry old = find(nameOrAlias);
        if (old == null) {
            throw new ProjectLoadException(ErrorType.NOT_FOUND,
                "No project found with name or alias '" + nameOrAlias + "'. Call load_java_project first.");
        }
        return load(old.originalSource(), old.alias(), null, old.delomboked(), old.editable());
    }

    /**
     * Reloads all expired projects from their stored original sources.
     */
    public List<ProjectEntry> reloadExpired() throws ProjectLoadException {
        List<ProjectEntry> reloaded = new ArrayList<>();
        for (var entry : registry.snapshotOfEntries()) {
            if (entry.expired()) {
                reloaded.add(load(entry.originalSource(), entry.alias(), null, entry.delomboked(), entry.editable()));
            }
        }
        return reloaded;
    }

    /**
     * Removes and cleans up a loaded project.
     */
    public ProjectEntry remove(String nameOrAlias) {
        ProjectEntry found = find(nameOrAlias); // loest auch Aliase auf
        ProjectEntry entry = found != null ? registry.removeByName(found.name()) : null;
        if (entry != null) {
            rewriteState.remove(entry.name());
            buildContexts.invalidate(entry.name());
            cleanup(entry);
            LOG.info("Unloaded project '{}'", nameOrAlias);
        }
        return entry;
    }

    /**
     * Removes and cleans up ALL loaded projects. Discards every in-memory model,
     * launcher, and fingerprint. Used by the reboot_spoon tool to recover from
     * parse errors or corrupted model state without restarting the JVM.
     *
     * @return the names of the projects that were removed
     */
    public List<String> removeAll() {
        List<String> removed = new ArrayList<>();
        for (var entry : registry.snapshotOfEntries()) {
            removed.add(entry.name());
            cleanup(entry);
        }
        registry.clear();
        buildContexts.clear();
        rewriteState.clear();
        LOG.info("Reboot: removed {} project(s): {}", removed.size(), removed);
        return removed;
    }

    /**
     * Cleans up a replaced entry. Directories that the new entry continues to use
     * (e.g. the same delombok cache) are kept.
     */
    private void cleanupReplaced(ProjectEntry old, ProjectEntry replacement) {
        boolean sharesDir = old.projectDir() != null && replacement.projectDir() != null
                && PathUtils.canonical(old.projectDir()).equals(PathUtils.canonical(replacement.projectDir()));
        boolean sharesOriginal = isSameDir(old.originalProjectDir(), replacement.originalProjectDir());
        if (!sharesDir) {
            ProjectCleanupHelper.cleanupAnalyzedDirectory(old, projectLoader, lombokDelomboker);
        }
        if (!sharesOriginal) {
            ProjectCleanupHelper.cleanupOriginalDirectory(old, projectLoader);
        }
        ProjectCleanupHelper.cleanupBackup(old, editManager);
        ProjectCleanupHelper.unregisterFromTracking(old, changeTracker, workspaceWatcher, workspaceRegistry);
    }

    /**
     * After a failed load, cleans up only what the new load attempt created.
     * Directories still used by the retained old entry remain untouched.
     */
    private void discardFailedLoad(ProjectEntry old, String name, Path projectDir, Path sourceToAnalyze,
                                   boolean delomboked) {
            // Verzeichnisse, die der (erhalten gebliebene) alte Eintrag noch nutzt, nicht anfassen
            boolean usedByOld = old != null
                    && (isSameDir(old.projectDir(), sourceToAnalyze) || isSameDir(old.originalProjectDir(), projectDir));
            if (!usedByOld) {
                if (delomboked) {
                    lombokDelomboker.cleanup(sourceToAnalyze);
                }
                projectLoader.cleanup(projectDir);
            }
            if (old == null && workspaceRegistry != null) {
                workspaceRegistry.unregister(name); // fehlgeschlagenes Laden hinterlaesst keine Registrierung
            }

    }

    private static boolean isSameDir(Path a, Path b) {
        return a != null && b != null && PathUtils.canonical(a).equals(PathUtils.canonical(b));
    }

    private void cleanup(ProjectEntry entry) {
        ProjectCleanupHelper.cleanupProject(
                entry, projectLoader, lombokDelomboker, editManager,
                changeTracker, workspaceWatcher, workspaceRegistry);
    }

    /**
     * Removes Spoon MavenLauncher artifacts (spoon.classpath-*.tmp) from the
     * project directory. Spoon writes these temp dumps into the project directory during the classpath
     * build and does not reliably clean them up - without
     * cleanup every loaded Maven project would be littered with an empty file.
     */
    private static void cleanupSpoonClasspathDumps(Path projectDir) {
        if (projectDir == null) return;
        try (var stream = Files.newDirectoryStream(projectDir, "spoon.classpath-*.tmp")) {
            for (Path dump : stream) {
                try {
                    Files.deleteIfExists(dump);
                    LOG.info("Cleaned up Spoon classpath dump: {}", dump);
                } catch (IOException e) {
                    LOG.warn("Could not remove Spoon classpath dump {}: {}", dump, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.debug("No Spoon classpath dumps to clean in {}", projectDir);
        }
    }

    // ==================== Registry ====================

    /**
     * Finds a loaded project by name or alias.
     */
    public ProjectEntry find(String nameOrAlias) {
        return registry.find(nameOrAlias);
    }

    /**
     * Returns all currently loaded projects.
     */
    public Collection<ProjectEntry> list() {
        return registry.list();
    }

    /**
     * Updates a loaded project entry in place (used by edit tools to set modelDirty).
     */
    public void updateEntry(ProjectEntry entry) {
        registry.put(entry);
    }

    /**
     * Marks a loaded project as dirty (model is stale after edits): all source files become dirty.
     */
    public void markDirty(String nameOrAlias) {
        registry.markDirty(nameOrAlias);
    }

    /**
     * Marks all projects whose expiry date has passed as expired (without deleting them).
     * Returns the names of newly expired projects for AI-agent notification.
     */
    public List<String> markExpired() {
        List<String> newlyExpired = registry.markExpired();
        spoonModels.evictExpired(evictionGrace);
        return newlyExpired;
    }

    /** Grace period after expiry before a project's Spoon model is released. */
    /** Grace period after a project expires before its Spoon model is released. */
    public java.time.Duration evictionGrace() {
        return evictionGrace;
    }

    void setEvictionGrace(java.time.Duration grace) {
        this.evictionGrace = grace;
    }

    // ==================== Rewrite-Zustand und Spoon-Modell ====================

    /**
     * Returns the ProjectState for a project, or null when not present.
     */
    public ProjectState getProjectState(String nameOrAlias) {
        ProjectEntry entry = find(nameOrAlias);
        return entry != null ? rewriteState.state(entry.name()) : null;
    }

    public AtomicLong getSnapshotSequence() {
        return rewriteState.sequence();
    }

    public SnapshotService getSnapshotService() {
        return rewriteState.snapshotService();
    }

    /**
     * The effective rewrite state of a project (snapshot + dirty files + transaction overlay).
     *
     * @see RewriteStateService#effectiveSnapshot(ProjectEntry, boolean)
     */
    public ProjectSnapshot effectiveSnapshot(ProjectEntry entry) throws IOException {
        return rewriteState.effectiveSnapshot(entry);
    }

    public ProjectSnapshot effectiveSnapshot(ProjectEntry entry, boolean broadScope) throws IOException {
        return rewriteState.effectiveSnapshot(entry, broadScope);
    }

    /** true when dirty files or a transaction overlay could have overtaken the snapshot. */
    public boolean hasUnsyncedChanges(ProjectEntry entry) {
        return rewriteState.hasUnsyncedChanges(entry);
    }

    /**
     * Ensures the Spoon model matches the Rewrite state (Spoon reads only from Rewrite).
     *
     * @see SpoonModelService#ensureModel(String)
     */
    public ProjectEntry ensureSpoonModel(String nameOrAlias) {
        return spoonModels.ensureModel(nameOrAlias);
    }

    /** Number of Spoon models built so far (diagnostics/tests). */
    public int spoonBuildCount() {
        return spoonModels.buildCount();
    }

    /**
     * Checks before writing whether an edit would make the project uncompilable.
     *
     * @return the compiler message or {@code null} when the edit is harmless (or not checkable)
     * @see SpoonModelService#validateEdit
     */
    public String validateEditCompiles(ProjectEntry entry, Map<Path, String> changed, Set<Path> deleted) {
        return spoonModels.validateEdit(entry, changed, deleted);
    }

    /**
     * Resolves the source root directory for a loaded project, used by edit tools that create
     * new files (add_class, add_package).
     */
    public static Path resolveSourceRoot(ProjectEntry entry) {
        return SpoonModelService.resolveSourceRoot(entry);
    }

    // ==================== Build-Kontext ====================

    /**
     * Invalidates the cached BuildContext for a project (e.g. on pom.xml changes).
     */
    public void invalidateBuildContext(String nameOrAlias) {
        ProjectEntry entry = find(nameOrAlias);
        if (entry != null) {
            buildContexts.invalidate(entry.name());
        }
    }

    /**
     * Resolves the build context on demand (see {@link BuildContextService#ensure}).
     */
    public BuildContext ensureBuildContext(String nameOrAlias) throws BuildResolutionException {
        ProjectEntry entry = find(nameOrAlias);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown project: " + nameOrAlias);
        }
        ProjectState state = rewriteState.state(entry.name());
        boolean resolves = state == null || state.dirtyState().isBuildModelDirty() || buildContexts.cached(entry.name()) == null;
        BuildContext ctx = buildContexts.ensure(entry, state);
        if (resolves) {
            rewriteState.acceptBuildFiles(entry); // der Build-Kontext entspricht jetzt dem Stand der Build-Dateien
        }
        return ctx;
    }

    /** Build files (pom.xml, Gradle, parent POM) that changed since the last build context. */
    public List<String> changedBuildFiles(ProjectEntry entry) {
        return rewriteState.changedBuildFiles(entry);
    }

    /**
     * Marks the build configuration of other loaded projects with the same parent POM as stale.
     */
    public void invalidateProjectsSharingParentPom(ProjectEntry entry) {
        Path parent = PomUtils.resolveParentPomPath(entry.originalProjectDir());
        if (parent == null) return;
        for (ProjectEntry other : registry.snapshotOfEntries()) {
            if (other.name().equals(entry.name())) continue;
            Path otherParent = PomUtils.resolveParentPomPath(other.originalProjectDir());
            if (parent.equals(otherParent)) {
                ProjectState s = rewriteState.state(other.name());
                if (s != null) s.dirtyState().markBuildConfiguration();
                buildContexts.invalidate(other.name());
                LOG.info("Invalidated project '{}' because the shared parent POM {} changed", other.name(), parent);
            }
        }
    }

    // ==================== Verdrahtung / Dienste ====================

    public void setEditManager(de.jare.rewisp.edit.EditManager editManager) {
        this.editManager = editManager;
        this.rewriteState.setOverlay(editManager);
    }

    /**
     * Delivers the connected EditManager (or null).
     */
    public de.jare.rewisp.edit.EditManager editManager() {
        return editManager;
    }

    public void setWorkspaceRegistry(WorkspaceRegistry workspaceRegistry) {
        this.workspaceRegistry = workspaceRegistry;
        if (workspaceRegistry != null) {
            // Klone/Downloads landen in der Temp-Basis und duerfen nicht an der Allowlist scheitern (RT:P-07)
            workspaceRegistry.allowRecursively(ProjectLoader.tempBase());
        }
    }

    public void setChangeTracker(ChangeTracker changeTracker) {
        this.changeTracker = changeTracker;
    }

    public void setWorkspaceWatcher(WorkspaceWatcher workspaceWatcher) {
        this.workspaceWatcher = workspaceWatcher;
    }

    // ==================== Hilfsfunktionen ====================

    /**
     * Creates a Spoon Launcher for the project, using MavenLauncher for Maven projects.
     */
    public static Launcher createLauncher(Path projectDir, BuildDetector.BuildInfo buildInfo) {
        return ProjectBuildHelpers.createLauncher(projectDir, buildInfo);
    }
}

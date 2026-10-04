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

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.model.ProjectEntryFactory;
import de.jare.rewisp.model.ProjectLoadException;
import de.jare.rewisp.snapshot.ProjectState;
import de.jare.rewisp.snapshot.SnapshotService;
import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.build.BuildResolutionException;
import de.jare.rewisp.build.BuildResolutionErrorCode;
import de.jare.rewisp.build.BuildResolver;
import de.jare.rewisp.build.GradleBuildResolver;
import de.jare.rewisp.build.MavenBuildResolver;
import de.jare.rewisp.build.ModuleContext;
import de.jare.rewisp.build.ResolveOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import de.jare.rewisp.util.ProjectBuildHelpers;
import de.jare.rewisp.util.ProjectCleanupHelper;
import de.jare.rewisp.util.PathUtils;
import java.util.Collection;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spoon.Launcher;
import spoon.MavenLauncher;
import spoon.reflect.CtModel;
import de.jare.rewisp.rewrite.SpoonEngine;
import de.jare.rewisp.rewrite.SpoonEngineImpl;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.RecipeRegistry;
import de.jare.rewisp.rewrite.EffectiveSourceOverlay;
import de.jare.rewisp.rewrite.YamlRecipeRegistry;
import de.jare.rewisp.rewrite.OpenRewriteEngineImpl;
import de.jare.rewisp.snapshot.WorkspaceRegistry;
import de.jare.rewisp.snapshot.ChangeTracker;
import de.jare.rewisp.snapshot.WorkspaceWatcher;

/**
 * The {@code ProjectManager} class.
 *
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
public class ProjectManager {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectManager.class);

    private final ConcurrentHashMap<String, ProjectEntry> entries = new ConcurrentHashMap<>();
    private final ProjectLoader projectLoader = new ProjectLoader();
    private final BuildDetector buildDetector = new BuildDetector();
    private final LombokDetector lombokDetector = new LombokDetector();
    private final LombokDelomboker lombokDelomboker = new LombokDelomboker();
    private de.jare.rewisp.edit.EditManager editManager;
    private final SnapshotService snapshotService = new SnapshotService();
    private final java.util.concurrent.atomic.AtomicLong snapshotSequence = new java.util.concurrent.atomic.AtomicLong(0);
    private final ConcurrentHashMap<String, ProjectState> projectStates = new ConcurrentHashMap<>();
    public java.util.concurrent.atomic.AtomicLong getSnapshotSequence() {
        return snapshotSequence;
    }

    public SnapshotService getSnapshotService() {
        return snapshotService;
    }
    private final MavenBuildResolver mavenBuildResolver = new MavenBuildResolver();
    private final GradleBuildResolver gradleBuildResolver = new GradleBuildResolver();
    private final ConcurrentHashMap<String, BuildContext> buildContexts = new ConcurrentHashMap<>();
    private final SpoonEngine spoonEngine = new SpoonEngineImpl();

    // Phase 4: OpenRewrite Engine
    private final RewriteEngine rewriteEngine;
    private final RecipeRegistry recipeRegistry;
    private final EffectiveSourceOverlay effectiveSourceOverlay = new EffectiveSourceOverlay();

    // Phase 8: Hardening und Observability
    private WorkspaceRegistry workspaceRegistry;
    private ChangeTracker changeTracker;
    private WorkspaceWatcher workspaceWatcher;
    private de.jare.rewisp.observability.MetricsCollector metricsCollector;
    
    // Phase 10: REWRITE-Spoon Synchronisation
    private de.jare.rewisp.rewrite.RewriteSyncManager rewriteSyncManager;

    /**
     * Standard-Konstruktor für ProjectManager.
     * Erstellt eine Standard-Instanz ohne OpenRewrite-Integration.
     * Für Tests und ältere Code-Teile.
     */
    public ProjectManager() {
        this(new OpenRewriteEngineImpl(new YamlRecipeRegistry()),
             new YamlRecipeRegistry());
    }

    /**
     * Konstruktor für ProjectManager mit OpenRewrite-Integration.
     * 
     * @param rewriteEngine Die RewriteEngine für OpenRewrite-Transformationen
     * @param recipeRegistry Die RecipeRegistry für verfügbare Recipes
     */
    public ProjectManager(RewriteEngine rewriteEngine, RecipeRegistry recipeRegistry) {
        this.rewriteEngine = Objects.requireNonNull(rewriteEngine, "rewriteEngine must not be null");
        this.recipeRegistry = Objects.requireNonNull(recipeRegistry, "recipeRegistry must not be null");
    }

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
    public ProjectEntry load(String source, String alias, Instant expiryDate, boolean autoDelombok, Boolean editableOverride)
            throws ProjectLoadException {
        markExpired();

        if (expiryDate == null) {
            expiryDate = Instant.now().plus(10, ChronoUnit.MINUTES);
        }

        Path projectDir = projectLoader.resolveSource(source);
        BuildDetector.BuildInfo buildInfo = buildDetector.detect(projectDir);
        LOG.info("Build detected: {} ({}) for {}", buildInfo.type(), buildInfo.detail(), projectDir);

        String name = ProjectBuildHelpers.deriveName(projectDir, source);
        if (alias == null) alias = name;

        ProjectEntry old = entries.remove(name);
        if (old != null) {
            projectStates.remove(name);
            buildContexts.remove(name);
            ProjectCleanupHelper.cleanupProject(
                    old, projectLoader, lombokDelomboker, editManager,
                    changeTracker, workspaceWatcher, workspaceRegistry);
            LOG.info("Replaced previously loaded project '{}'", name);
        }

        boolean delomboked = false;
        String lombokVersion = null;
        Path sourceToAnalyze = projectDir;
        LombokDetector.LombokInfo lombokInfo = lombokDetector.detect(projectDir);
        boolean lombokInSources = lombokInfo.present() || lombokDetector.hasLombokInSources(projectDir);

        if (lombokInSources && autoDelombok) {
            try {
                Path delombokedDir = lombokDelomboker.delombok(projectDir, buildInfo.type());
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
            // Phase 8: WorkspaceRegistry validierung
            if (workspaceRegistry != null) {
                try {
                    projectDir = workspaceRegistry.register(name, projectDir);
                } catch (SecurityException e) {
                    throw new ProjectLoadException("SECURITY_ERROR", e.getMessage());
                }
            }

            Launcher launcher = createLauncher(sourceToAnalyze, buildInfo);
            enrichClasspathFromBuild(name, launcher);
            CtModel model = launcher.buildModel();
            cleanupSpoonClasspathDumps(projectDir);

            // Determine editable: JAR sources are hard-locked to false
            boolean isJar = source != null && source.toLowerCase().endsWith(".jar");
            boolean editable = isJar ? false : (editableOverride != null ? editableOverride : true);
            int modulesDetected = ProjectBuildHelpers.countModules(projectDir, buildInfo);
            int modulesLoaded = 1;

            ProjectEntry entry = new ProjectEntry(name, alias, expiryDate,
                    sourceToAnalyze, launcher, model, buildInfo.type().name(),
                    delomboked, lombokVersion,
                    projectDir, source, ProjectBuildHelpers.buildFingerprints(projectDir), false, editable,
                    modulesDetected, modulesLoaded, java.util.Set.of(), java.util.Set.of());
            entries.put(name, entry);
            captureInitialSnapshot(entry, buildInfo);
            
            // Phase 8: ChangeTracker und WorkspaceWatcher registrieren
            if (changeTracker != null && workspaceWatcher != null && workspaceRegistry != null) {
                ProjectState state = projectStates.get(name);
                if (state != null) {
                    changeTracker.registerProject(name, projectDir, state.dirtyState());
                    try {
                        workspaceWatcher.watch(name, projectDir);
                        LOG.info("Workspace watcher started for project '{}'", name);
                    } catch (IOException e) {
                        LOG.warn("Could not start workspace watcher for '{}': {}", name, e.getMessage());
                    }
                }
            }
            
            LOG.info("Project '{}' loaded successfully ({} types, delomboked={})",
                    name, model.getAllTypes().size(), delomboked);
            return entry;
        } catch (Exception e) {
            if (delomboked) {
                lombokDelomboker.cleanup(sourceToAnalyze);
            }
            projectLoader.cleanup(projectDir);
            throw new ProjectLoadException("PARSE_ERROR", "Failed to parse project: " + e.getMessage());
        }
    }

    /**
     * Nimmt einen initialen Snapshot beim Laden eines Projekts auf.
     * Speichert SnapshotId und Content-Hashes im ProjectState.
     */
    private void captureInitialSnapshot(ProjectEntry entry, de.jare.rewisp.BuildDetector.BuildInfo buildInfo) {
        try {
            ProjectState state = new ProjectState();
            var snapshot = snapshotService.captureStable(entry, snapshotSequence.incrementAndGet());
            state.setSnapshotId(snapshot.id());
            state.setSnapshot(snapshot);
            state.setBuildInfo(buildInfo);
            projectStates.put(entry.name(), state);
            LOG.info("Initial snapshot captured: {}", snapshot.id());
        } catch (IOException e) {
            LOG.warn("Initial snapshot failed for project '{}': {}", entry.name(), e.getMessage());
            ProjectState state = new ProjectState();
            state.setBuildInfo(buildInfo);
            projectStates.put(entry.name(), state);
        }
    }

    /**
     * Liefert den ProjectState fuer ein Projekt, oder null wenn nicht vorhanden.
     */
    public ProjectState getProjectState(String nameOrAlias) {
        ProjectEntry entry = find(nameOrAlias);
        return entry != null ? projectStates.get(entry.name()) : null;
    }

    /**
     * Liefert den gecachten BuildContext fuer ein Projekt, oder null wenn nicht vorhanden.
     */
    public BuildContext getBuildContext(String nameOrAlias) {
        ProjectEntry entry = find(nameOrAlias);
        return entry != null ? buildContexts.get(entry.name()) : null;
    }

    /**
     * Invalidiert den gecachten BuildContext fuer ein Projekt.
     * Wird bei pom.xml-Aenderungen aufgerufen.
     */
    public void invalidateBuildContext(String nameOrAlias) {
        ProjectEntry entry = find(nameOrAlias);
        if (entry != null) {
            buildContexts.remove(entry.name());
        }
    }

    /**
     * Loest den Build-Kontext on-demand auf. Verwendet den Cache, wenn der
     * Build-Zustand nicht dirty ist. Nach erfolgreicher Aufloesung wird der
     * Build-Dirty-State zurueckgesetzt.
     */
    public BuildContext ensureBuildContext(String nameOrAlias) throws BuildResolutionException {
        ProjectEntry entry = find(nameOrAlias);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown project: " + nameOrAlias);
        }

        String name = entry.name();
        ProjectState state = projectStates.get(name);

        // Pruefe ob Build-Kontext vorhanden und nicht dirty
        if (state != null && !state.dirtyState().isBuildModelDirty()) {
            BuildContext existing = buildContexts.get(name);
            if (existing != null) return existing;
        }

        // Neu aufloesen
        Path projectRoot = entry.originalProjectDir() != null
                ? entry.originalProjectDir() : entry.projectDir();
        
        // Polymorphe Resolver-Auswahl: Maven oder Gradle
        BuildResolver resolver;
        if (mavenBuildResolver.supports(projectRoot)) {
            resolver = mavenBuildResolver;
        } else if (gradleBuildResolver.supports(projectRoot)) {
            resolver = gradleBuildResolver;
        } else {
            throw new BuildResolutionException(BuildResolutionErrorCode.UNSUPPORTED,
                    "No supported build system for " + projectRoot);
        }

        BuildContext ctx = resolver.resolve(projectRoot, ResolveOptions.defaults());
        buildContexts.put(name, ctx);

        // Dirty-State zuruecksetzen
        if (state != null) {
            state.dirtyState().clearBuildDirty();
        }

        LOG.info("Build context resolved for '{}': {} modules, fingerprint={}",
                name, ctx.modules().size(), ctx.fingerprint().compositeHash().substring(0, 16));
        return ctx;
    }

    public void setEditManager(de.jare.rewisp.edit.EditManager editManager) {
        this.editManager = editManager;
    }

    /**
     * Liefert den verbundenen EditManager (oder null). Basis-Tools nutzen
     * ihn, um in aktiven Transaktionen gepufferte Inhalte in den
     * Snapshot-Refresh zu überlagern.
     */
    public de.jare.rewisp.edit.EditManager editManager() {
        return editManager;
    }

    // Phase 8: Hardening und Observability Setter
    public void setWorkspaceRegistry(WorkspaceRegistry workspaceRegistry) {
        this.workspaceRegistry = workspaceRegistry;
    }

    public void setChangeTracker(ChangeTracker changeTracker) {
        this.changeTracker = changeTracker;
    }

    public void setWorkspaceWatcher(WorkspaceWatcher workspaceWatcher) {
        this.workspaceWatcher = workspaceWatcher;
    }

    public void setMetricsCollector(de.jare.rewisp.observability.MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    public de.jare.rewisp.observability.MetricsCollector metricsCollector() {
        return metricsCollector;
    }

    public SpoonEngine spoonEngine() {
        return spoonEngine;
    }

    // Phase 10: REWRITE-Spoon Synchronisation
    public void setRewriteSyncManager(de.jare.rewisp.rewrite.RewriteSyncManager rewriteSyncManager) {
        this.rewriteSyncManager = rewriteSyncManager;
    }

    public de.jare.rewisp.rewrite.RewriteSyncManager rewriteSyncManager() {
        return rewriteSyncManager;
    }

    // Phase 4: OpenRewrite Getter
    public RewriteEngine rewriteEngine() {
        return rewriteEngine;
    }

    public RecipeRegistry recipeRegistry() {
        return recipeRegistry;
    }

    public EffectiveSourceOverlay effectiveSourceOverlay() {
        return effectiveSourceOverlay;
    }

/**
 * Updates a loaded project entry in place (used by edit tools to set modelDirty).
 */
    public void updateEntry(ProjectEntry entry) {
        entries.put(entry.name(), entry);
    }

/**
 * Marks a loaded project as dirty (model is stale after edits).
 * All source files become dirty, since "mark dirty" means the whole model
 * is considered stale.
 */
    public void markDirty(String nameOrAlias) {
        ProjectEntry entry = find(nameOrAlias);
        if (entry == null) return;
        if (entry.modelDirty()) return;
        entries.put(entry.name(), ProjectEntryFactory.withAllFilesDirty(entry));
    }

/**
 * Marks all loaded projects as dirty.
 */
    public void markAllDirty() {
        for (var entry : new ArrayList<>(entries.values())) {
            if (!entry.modelDirty()) {
                entries.put(entry.name(), ProjectEntryFactory.withAllFilesDirty(entry));
            }
        }
    }

/**
 * Finds a loaded project by name or alias.
 */
    public ProjectEntry find(String nameOrAlias) {
        for (ProjectEntry entry : entries.values()) {
            if (entry.alias() != null && entry.alias().equals(nameOrAlias)) {
                return entry;
            }
        }
        return entries.get(nameOrAlias);
    }

/**
 * Reloads a single project from its stored original source.
 */
    public ProjectEntry reload(String nameOrAlias) throws ProjectLoadException {
        ProjectEntry old = find(nameOrAlias);
        if (old == null) {
            throw new ProjectLoadException("NOT_FOUND",
                "No project found with name or alias '" + nameOrAlias + "'. Call load_java_project first.");
        }
        return load(old.originalSource(), old.alias(), null, old.delomboked(), old.editable());
    }

/**
 * Reloads all expired projects from their stored original sources.
 */
    public List<ProjectEntry> reloadExpired() throws ProjectLoadException {
        List<ProjectEntry> reloaded = new ArrayList<>();
        for (var entry : new ArrayList<>(entries.values())) {
            if (entry.expired()) {
                reloaded.add(load(entry.originalSource(), entry.alias(), null, entry.delomboked()));
            }
        }
        return reloaded;
    }

/**
 * Removes and cleans up a loaded project.
 */
    public ProjectEntry remove(String nameOrAlias) {
        ProjectEntry entry = entries.remove(nameOrAlias);
        if (entry != null) {
            projectStates.remove(entry.name());
            buildContexts.remove(entry.name());
            
            ProjectCleanupHelper.cleanupProject(
                    entry, projectLoader, lombokDelomboker, editManager,
                    changeTracker, workspaceWatcher, workspaceRegistry);
            LOG.info("Unloaded project '{}'", nameOrAlias);
        }
        return entry;
    }

/**
 * Returns all currently loaded projects.
 */
    public Collection<ProjectEntry> list() {
        return entries.values();
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
        for (var entry : new ArrayList<>(entries.values())) {
            removed.add(entry.name());
            ProjectCleanupHelper.cleanupProject(
                    entry, projectLoader, lombokDelomboker, editManager,
                    changeTracker, workspaceWatcher, workspaceRegistry);
        }
        entries.clear();
        buildContexts.clear();
        projectStates.clear();
        LOG.info("Reboot: removed {} project(s): {}", removed.size(), removed);
        return removed;
    }

/**
 * Marks all projects whose expiry date has passed as expired (without deleting them).
 * Returns the names of newly expired projects for agent notification.
 */
    public List<String> markExpired() {
        Instant now = Instant.now();
        List<String> newlyExpired = new ArrayList<>();
        for (var entry : entries.values()) {
            if (!entry.expired() && entry.expiryDate() != null && now.isAfter(entry.expiryDate())) {
                entries.put(entry.name(), ProjectEntryFactory.withExpired(entry, true));
                LOG.info("Project '{}' expired at {}", entry.name(), entry.expiryDate());
                newlyExpired.add(entry.name());
            }
        }
        return newlyExpired;
    }



/**
 * Entfernt Spoon-MavenLauncher-Artefakte (spoon.classpath-*.tmp) aus dem
 * Projektverzeichnis. Spoon schreibt diese Temp-Dumps beim Classpath-Build
 * ins Projektverzeichnis und raeumt sie nicht zuverlaessig weg — ohne
 * Cleanup wuerde jedes geladene Maven-Projekt mit einer leeren Datei
 * verschmutzt.
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

/**
 * Creates a Spoon Launcher for the project, using MavenLauncher for Maven projects.
 */
    public static Launcher createLauncher(Path projectDir, BuildDetector.BuildInfo buildInfo) {
        return ProjectBuildHelpers.createLauncher(projectDir, buildInfo);
    }

/**
 * Ergaenzt den Launcher-Classpath aus dem gecachten BuildContext, wenn vorhanden.
 * Wird in {@link #load} nach {@link #createLauncher} und vor {@code buildModel} aufgerufen.
 * Bei Reload kann ein zuvor aufgeloester BuildContext den MavenLauncher-Classpath ergaenzen.
 */
    private void enrichClasspathFromBuild(String name, Launcher launcher) {
        BuildContext buildCtx = buildContexts.get(name);
        ProjectBuildHelpers.enrichClasspathFromBuild(launcher, buildCtx);
    }

/**
 * Resolves the source root directory for a loaded project, used by edit tools
 * that create new files (add_class, add_package). Derives the root from an
 * existing type's on-disk position so it works regardless of build system
 * (Maven, Gradle, RAW), falling back to the {@code src/main/java} convention
 * and finally to the project directory itself.
 */
    public static Path resolveSourceRoot(ProjectEntry entry) {
        if (entry == null || entry.projectDir() == null) return null;
        if (entry.model() != null) {
            for (var t : entry.model().getAllTypes()) {
                if (t.getPosition() == null || t.getPosition().getFile() == null) continue;
                Path file = t.getPosition().getFile().toPath().normalize();
                String pkg = (t.getPackage() != null) ? t.getPackage().getQualifiedName() : "";
                int segs = pkg.isEmpty() ? 0 : (int) pkg.chars().filter(c -> c == '.').count() + 1;
                Path root = file.getParent();
                for (int i = 0; i < segs && root != null; i++) root = root.getParent();
                if (root != null && Files.isDirectory(root)) {
                    LOG.info("resolveSourceRoot: derived from {} -> {}", t.getSimpleName(), root);
                    return root;
                }
                break;
            }
        }
        Path conv = entry.projectDir().resolve("src/main/java");
        if (Files.isDirectory(conv)) {
            LOG.info("resolveSourceRoot: using convention src/main/java -> {}", conv);
            return conv;
        }
        LOG.info("resolveSourceRoot: fallback to projectDir -> {}", entry.projectDir());
        return entry.projectDir();
    }

/**
 * Derives a project name from the project directory name.
 */
    public static String deriveName(Path projectDir, String source) {
        return ProjectBuildHelpers.deriveName(projectDir, source);
    }

}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.analysis;

import java.util.concurrent.atomic.AtomicInteger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.project.ProjectRegistry;
import de.jare.rewisp.snapshot.IgnoreRules;
import de.jare.rewisp.snapshot.ProjectState;
import de.jare.rewisp.snapshot.RewriteStateService;
import de.jare.rewisp.util.PathUtils;
import de.jare.rewisp.util.ProjectLayout;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;
import spoon.support.compiler.VirtualFile;

/**
 * Builds and updates the Spoon model. Architecture rule: <strong>Spoon reads only from
 * Rewrite.</strong> The model is built from the effective Rewrite state
 * ({@link RewriteStateService#effectiveSnapshot}) as in-memory sources ({@link VirtualFile}),
 * not from disk - inside and outside transactions alike.
 *
 * <p>Exceptions: delomboked projects (the sources in the snapshot are not the analyzed ones)
 * keep their disk model; generated source directories (e.g. {@code target/generated-sources})
 * are still read from disk.</p>
 *
 * @author Janusch Rentenatus
 */
public final class SpoonModelService {

    private static final Logger LOG = LoggerFactory.getLogger(SpoonModelService.class);

    private final ProjectRegistry registry;
    private final RewriteStateService rewriteState;
    /** Last access per project (for the eviction of expired projects). */
    private final Map<String, Instant> lastUse = new ConcurrentHashMap<>();

    public SpoonModelService(ProjectRegistry registry, RewriteStateService rewriteState) {
        this.registry = registry;
        this.rewriteState = rewriteState;
    }

    /**
     * Ensures the Spoon model matches the Rewrite state; rebuilds it when needed.
     *
     * @return the current entry (with the new model, if it was rebuilt)
     */
    public synchronized ProjectEntry ensureModel(String nameOrAlias) {
        ProjectEntry entry = registry.find(nameOrAlias);
        if (entry == null) return null;
        lastUse.put(entry.name(), Instant.now());
        ProjectEntry result = rebuildIfNeeded(entry);
        if (result.model() == null) {
            throw new IllegalStateException("The Spoon model of '" + entry.name()
                    + "' was released after expiry and could not be rebuilt from the Rewrite state. "
                    + "Call reload_java_project.");
        }
        return result;
    }

    /**
     * Releases the Spoon model of expired projects when more than {@code grace} has passed since expiry
     * or last access. The model is only a derived view: Rewrite state, transactions and registration
     * remain, {@link #ensureModel} rebuilds it when needed. Delomboked projects stay untouched (their
     * model does not come from the Rewrite state).
     *
     * @return names of the projects whose model was released
     */
    public synchronized List<String> evictExpired(Duration grace) {
        Instant now = Instant.now();
        List<String> evicted = new ArrayList<>();
        for (ProjectEntry e : registry.snapshotOfEntries()) {
            if (!e.expired() || e.delomboked() || e.model() == null || e.launcher() == null
                    || e.expiryDate() == null) {
                continue;
            }
            Instant since = lastUse.getOrDefault(e.name(), e.expiryDate());
            if (since.isBefore(e.expiryDate())) since = e.expiryDate();
            if (now.isBefore(since.plus(grace))) continue;
            ProjectState state = rewriteState.state(e.name());
            if (state == null) continue;
            registry.put(new ProjectEntry(e.name(), e.alias(), e.expiryDate(), e.projectDir(),
                    newLauncherLike(e.launcher()), null, e.buildType(), e.delomboked(), e.lombokVersion(),
                    e.originalProjectDir(), e.originalSource(), e.sourceFingerprints(), e.expired(),
                    e.editable(), e.modulesDetected(), e.modulesLoaded(), e.dirtyFiles(), e.editedFiles()));
            state.setSpoonSource(null, null);
            if (pending != null && pending.project().equals(e.name())) pending = null;
            evicted.add(e.name());
            LOG.info("Spoon model of expired project '{}' released (rebuilt on next access)", e.name());
        }
        return evicted;
    }

    private ProjectEntry rebuildIfNeeded(ProjectEntry entry) {
        if (entry.delomboked() || entry.launcher() == null) return entry;
        ProjectState state = rewriteState.state(entry.name());
        if (state == null) return entry;
        try {
            var eff = rewriteState.effectiveSnapshot(entry);
            if (eff == null) return entry;
            String key = eff.id().rootHash();
            if (entry.model() != null && entry.model() == state.spoonModel() && key.equals(state.spoonSourceKey())) {
                // Quelltext entspricht wieder dem zuletzt gebauten Modell (z.B. nach Reparatur): frueherer Fehler ist erledigt
                state.setSpoonRebuildError(null);
                return entry;
            }

            long rebuildStart = de.jare.rewisp.observability.Phases.start();
            Map<Path, String> contents = new LinkedHashMap<>();
            eff.files().forEach((p, f) -> contents.put(p, f.content()));
            Built built = takeValidatedCandidate(entry, contents);
            if (built == null) built = build(entry, state, contents);
            Launcher fresh = built.launcher();
            CtModel model = built.model();
            int count = built.sourceCount();
            de.jare.rewisp.observability.Phases.end("spoon-rebuild", rebuildStart);

            ProjectEntry updated = new ProjectEntry(entry.name(), entry.alias(), entry.expiryDate(),
                    entry.projectDir(), fresh, model, entry.buildType(), entry.delomboked(),
                    entry.lombokVersion(), entry.originalProjectDir(), entry.originalSource(),
                    entry.sourceFingerprints(), entry.expired(), entry.editable(),
                    entry.modulesDetected(), entry.modulesLoaded(), entry.dirtyFiles(), entry.editedFiles());
            registry.put(updated);
            state.setSpoonSource(model, key);
            LOG.info("Spoon model for '{}' rebuilt from Rewrite state ({} sources)", entry.name(), count);
            return updated;
        } catch (Exception e) {
            LOG.warn("Spoon model for '{}' could not be rebuilt from Rewrite state: {}", entry.name(), e.getMessage());
            state.setSpoonRebuildError(String.valueOf(e.getMessage()));
            return entry;
        }
    }

    /** Result of a model build: launcher, model and number of source files. */
    private record Built(Launcher launcher, CtModel model, int sourceCount) {
    }

    /**
     * The candidate model built by {@link #validateEdit}, which can be adopted after the write
     * instead of building again. Only valid when the Rewrite state afterwards exactly matches the validated
     * sources (fingerprint) and the project's launcher stayed the same.
     */
    private record Candidate(String project, Launcher baseLauncher, String fingerprint, Built built) {
    }

    private Candidate pending;
    private final AtomicInteger buildCount = new AtomicInteger();

    /** Number of Spoon models built so far (candidates and regular rebuilds); for tests/diagnostics. */
    public int buildCount() {
        return buildCount.get();
    }

    /** Fingerprint of the sources: hash over all paths and contents (independent of snapshot IDs). */
    private static String fingerprint(Map<Path, String> contents) {
        de.jare.rewisp.snapshot.ContentHasher hasher = new de.jare.rewisp.snapshot.ContentHasher();
        Map<Path, String> hashes = new HashMap<>();
        contents.forEach((p, c) -> hashes.put(p, hasher.sha256(c)));
        return hasher.rootHash(hashes);
    }

    /** Delivers the matching validated candidate model (and consumes it) or {@code null} for the regular rebuild. */
    private Built takeValidatedCandidate(ProjectEntry entry, Map<Path, String> contents) {
        Candidate c = pending;
        pending = null;
        if (c == null || !c.project().equals(entry.name()) || c.baseLauncher() != entry.launcher()) return null;
        if (!c.fingerprint().equals(fingerprint(contents))) return null;
        LOG.info("Spoon model for '{}' taken over from the pre-write validation (no second build)", entry.name());
        return c.built();
    }

    /**
     * Builds a Spoon model from the given sources (relative path -> content) as in-memory sources.
     * Throws on non-compilable source text (Spoon model build error); changes neither registry nor state.
     */
    private Built build(ProjectEntry entry, ProjectState state, Map<Path, String> contents) {
        buildCount.incrementAndGet();
        Launcher fresh = newLauncherLike(entry.launcher());
        Path root = PathUtils.canonical(entry.originalProjectDir());
        List<Path> inputs = state.spoonSourceDirs();
        for (Path input : inputs) {
            if (IgnoreRules.defaults().isIgnored(root, input)) {
                fresh.addInputResource(input.toString()); // generierte Quellen: nicht im Snapshot
            }
        }
        int count = 0;
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        Set<String> tempBefore = SpoonTempFiles.snapshot(tmpDir);
        Set<String> sourceNames = new HashSet<>();
        for (var e : contents.entrySet()) {
            Path abs = root.resolve(e.getKey()).normalize();
            boolean inScope = inputs.isEmpty() || inputs.stream().anyMatch(abs::startsWith);
            if (!inScope) continue;
            fresh.addInputResource(new VirtualFile(e.getValue(), abs.toString()));
            sourceNames.add(abs.getFileName().toString());
            count++;
        }
        if (count == 0 && !contents.isEmpty()) {
            LOG.warn("Spoon build for '{}': no source lies inside the source dirs {} (files: {}, root: {})",
                    entry.name(), inputs, contents.keySet(), root);
        }
        CtModel model;
        try {
            model = fresh.buildModel();
        } finally {
            SpoonTempFiles.deleteNewSince(tmpDir, tempBefore, sourceNames); // Spoon laesst Kopien bis zum JVM-Ende liegen
        }
        return new Built(fresh, model, count);
    }

    /** System property {@code edit.validate=false} switches off the check before writing. */
    public static final String VALIDATE_PROPERTY = "edit.validate";

    /**
     * System property {@code edit.validate.maxSources}: from this number of source files on, no pre-write check
     * happens, because every check builds a complete Spoon model (default 3000; measured ~1.5 s per 250 classes).
     */
    public static final String VALIDATE_MAX_SOURCES_PROPERTY = "edit.validate.maxSources";

    /**
     * Checks before writing whether the Rewrite state after {@code changed}/{@code deleted} still compiles:
     * builds a candidate model without registering or writing anything.
     *
     * <p>Only when the current model is healthy (otherwise there is no baseline - repairs must
     * remain possible), the project is not delomboked and the check is not disabled.
     * Errors other than model-build errors (e.g. I/O) do not block the edit.</p>
     *
     * @return the compiler message if the edit would introduce new compile errors; otherwise {@code null}
     */
    public synchronized String validateEdit(ProjectEntry entry, Map<Path, String> changed, Set<Path> deleted) {
        pending = null;
        if (entry == null || !Boolean.parseBoolean(System.getProperty(VALIDATE_PROPERTY, "true"))) return null;
        if (entry.delomboked() || entry.launcher() == null || entry.model() == null) return null;
        ProjectState state = rewriteState.state(entry.name());
        if (state == null || state.spoonRebuildError() != null) return null;
        try {
            var eff = rewriteState.effectiveSnapshot(entry);
            if (eff == null) return null;
            Map<Path, String> contents = new LinkedHashMap<>();
            eff.files().forEach((p, f) -> contents.put(p, f.content()));
            for (Path d : deleted) contents.remove(d);
            contents.putAll(changed);
            int maxSources = Integer.getInteger(VALIDATE_MAX_SOURCES_PROPERTY, 3000);
            if (contents.size() > maxSources) {
                LOG.info("Pre-write validation for '{}' skipped: {} sources exceed -D{}={}", entry.name(),
                        contents.size(), VALIDATE_MAX_SOURCES_PROPERTY, maxSources);
                return null;
            }
            Built candidate = build(entry, state, contents);
            pending = new Candidate(entry.name(), entry.launcher(), fingerprint(contents), candidate);
            return null;
        } catch (spoon.compiler.ModelBuildingException e) {
            return String.valueOf(e.getMessage());
        } catch (Exception e) {
            LOG.warn("Pre-write validation for '{}' skipped: {}", entry.name(), e.getMessage());
            return null;
        }
    }
    /** New launcher with the same settings (classpath, compliance, comments) as {@code old}. */
    private static Launcher newLauncherLike(Launcher old) {
        Launcher fresh = new Launcher();
        var env = fresh.getEnvironment();
        var oldEnv = old.getEnvironment();
        env.setNoClasspath(oldEnv.getNoClasspath());
        env.setAutoImports(oldEnv.isAutoImports());
        env.setCommentEnabled(oldEnv.isCommentsEnabled());
        env.setComplianceLevel(oldEnv.getComplianceLevel());
        String[] cp = oldEnv.getSourceClasspath();
        if (cp != null && cp.length > 0) env.setSourceClasspath(cp);
        return fresh;
    }

    /**
     * Source root of a type: file path minus package segments; {@code null} without position.
     */
    public static Path sourceRootOf(CtType<?> type) {
        if (type.getPosition() == null || type.getPosition().getFile() == null) return null;
        Path dir = PathUtils.canonical(type.getPosition().getFile().toPath()).getParent();
        String pkg = type.getPackage() != null ? type.getPackage().getQualifiedName() : "";
        int segments = ProjectLayout.packageSegments(pkg);
        for (int i = 0; i < segments && dir != null; i++) dir = dir.getParent();
        return dir;
    }

    /**
     * Source roots of a built model. Spoon's own input list is not suited for this: after the build it
     * only contains the directories of the individual files.
     */
    public static List<Path> sourceRootsOf(CtModel model) {
        Set<Path> roots = new LinkedHashSet<>();
        for (CtType<?> type : model.getAllTypes()) {
            try {
                Path root = sourceRootOf(type);
                if (root != null) roots.add(root);
            } catch (RuntimeException e) {
                LOG.debug("No source root for {}: {}", type.getQualifiedName(), e.getMessage());
            }
        }
        return new ArrayList<>(roots);
    }

    /**
     * Source directory for new files (add_class, add_package): derived from an existing type
     * (independent of the build system), otherwise {@code src/main/java}, otherwise the
     * project directory.
     */
    public static Path resolveSourceRoot(ProjectEntry entry) {
        if (entry == null || entry.projectDir() == null) return null;
        if (entry.model() != null) {
            for (CtType<?> t : entry.model().getAllTypes()) {
                Path root = sourceRootOf(t);
                if (root != null && Files.isDirectory(root)) {
                    LOG.info("resolveSourceRoot: derived from {} -> {}", t.getSimpleName(), root);
                    return root;
                }
                if (t.getPosition() != null && t.getPosition().getFile() != null) break;
            }
        }
        Path conv = entry.projectDir().resolve(ProjectLayout.MAIN_JAVA_DIR);
        if (Files.isDirectory(conv)) {
            LOG.info("resolveSourceRoot: using convention {} -> {}", ProjectLayout.MAIN_JAVA_DIR, conv);
            return conv;
        }
        LOG.info("resolveSourceRoot: fallback to projectDir -> {}", entry.projectDir());
        return entry.projectDir();
    }
}

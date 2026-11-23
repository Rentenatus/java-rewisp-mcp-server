/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import java.util.function.LongSupplier;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.ProjectState;

/**
 * Cache of the resolved {@link BuildContext}s per project and selection of the matching
 * {@link BuildResolver} (Maven or Gradle).
 *
 * @author Janusch Rentenatus
 */
public final class BuildContextService {

    private static final Logger LOG = LoggerFactory.getLogger(BuildContextService.class);

    /**
     * Wait time after the first failure before resolution is started again (unless the build files
     * change). Each further consecutive failure doubles it up to {@link #MAX_FAILURE_BACKOFF_MILLIS}: where Maven
     * permanently fails (e.g. plugin not available offline), not every edit costs a new Maven run.
     */
    static final long FAILURE_BACKOFF_MILLIS = 60_000;
    static final long MAX_FAILURE_BACKOFF_MILLIS = 30 * 60_000L;

    private final BuildResolver mavenBuildResolver;
    private final BuildResolver gradleBuildResolver;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, BuildContext> contexts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Failure> failures = new ConcurrentHashMap<>();

    private record Failure(BuildResolutionException error, long atMillis, long dirtyVersion, int consecutive) {
        long backoffMillis() {
            long factor = 1L << Math.min(consecutive - 1, 20);
            return Math.min(FAILURE_BACKOFF_MILLIS * factor, MAX_FAILURE_BACKOFF_MILLIS);
        }
    }

    public BuildContextService() {
        this(new MavenBuildResolver(), new GradleBuildResolver(), System::currentTimeMillis);
    }

    BuildContextService(BuildResolver maven, BuildResolver gradle, LongSupplier clock) {
        this.mavenBuildResolver = maven;
        this.gradleBuildResolver = gradle;
        this.clock = clock;
    }

    /** Cached context of the project (exact name) or {@code null}. */
    public BuildContext cached(String projectName) {
        return projectName != null ? contexts.get(projectName) : null;
    }

    public void invalidate(String projectName) {
        contexts.remove(projectName);
        failures.remove(projectName);
    }

    public void clear() {
        contexts.clear();
        failures.clear();
    }

    /**
     * Returns the build context; uses the cache as long as the build state is not dirty.
     * After successful resolution the build dirty state is reset.
     *
     * @param state Rewrite state of the project (may be {@code null})
     */
    public BuildContext ensure(ProjectEntry entry, ProjectState state) throws BuildResolutionException {
        // Je Projekt serialisieren: parallele Edits starten sonst mehrere Maven/Gradle-Prozesse
        synchronized (locks.computeIfAbsent(entry.name(), k -> new Object())) {
            return ensureLocked(entry, state);
        }
    }

    private BuildContext ensureLocked(ProjectEntry entry, ProjectState state) throws BuildResolutionException {
        String name = entry.name();
        long dirtyVersion = state != null ? state.dirtyState().buildDirtyVersion() : 0;
        Failure failure = failures.get(name);
        int previousFailures = 0;
        if (failure != null) {
            boolean unchanged = failure.dirtyVersion() == dirtyVersion;
            if (unchanged && clock.getAsLong() - failure.atMillis() < failure.backoffMillis()) {
                // Negativ-Cache: kein Maven-Neustart bei jedem Edit
                throw failure.error();
            }
            failures.remove(name);
            // Aenderung der Build-Dateien: neu zaehlen; sonst steigt die Wartezeit mit jedem weiteren Fehlschlag
            previousFailures = unchanged ? failure.consecutive() : 0;
        }
        if (state != null && !state.dirtyState().isBuildModelDirty()) {
            BuildContext existing = contexts.get(name);
            if (existing != null) return existing;
        }

        Path projectRoot = entry.originalProjectDir() != null
                ? entry.originalProjectDir() : entry.projectDir();

        BuildResolver resolver;
        if (mavenBuildResolver.supports(projectRoot)) {
            resolver = mavenBuildResolver;
        } else if (gradleBuildResolver.supports(projectRoot)) {
            resolver = gradleBuildResolver;
        } else {
            throw new BuildResolutionException(BuildResolutionErrorCode.UNSUPPORTED,
                    "No supported build system for " + projectRoot);
        }

        long observedVersion = dirtyVersion;
        BuildContext ctx;
        try {
            ctx = resolver.resolve(projectRoot, ResolveOptions.defaults());
        } catch (BuildResolutionException e) {
            failures.put(name, new Failure(e, clock.getAsLong(), dirtyVersion, previousFailures + 1));
            throw e;
        }
        contexts.put(name, ctx);
        if (state != null && !state.dirtyState().clearBuildDirtyIfUnchanged(observedVersion)) {
            LOG.info("Build configuration of '{}' changed during resolution; staying dirty", name);
        }
        LOG.info("Build context resolved for '{}': {} modules, fingerprint={}",
                name, ctx.modules().size(), ctx.fingerprint().compositeHash().substring(0, 16));
        return ctx;
    }
}

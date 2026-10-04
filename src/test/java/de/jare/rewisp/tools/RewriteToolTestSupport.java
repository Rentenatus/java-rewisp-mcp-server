/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools;

import de.jare.rewisp.rewrite.OpenRewriteEngineImpl;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.RewriteSyncManager;
import de.jare.rewisp.rewrite.YamlRecipeRegistry;

/**
 * Test-Unterstützung für OpenRewrite-basierte Edit-Tools.
 *
 * <p>Baut eine vollständige RewriteEngine (YamlRecipeRegistry + OpenRewriteEngineImpl),
 * damit Tests die produktive Pipeline ohne Spoon-Fallback abdecken.</p>
 *
 * @author Janusch Rentenatus
 */
public final class RewriteToolTestSupport {

    private RewriteToolTestSupport() {
    }

    /**
     * Erstellt eine RewriteEngine mit den Recipes aus application.yaml
     * (auf dem Test-Classpath vorhanden).
     */
    public static RewriteEngine newEngine() {
        YamlRecipeRegistry registry = new YamlRecipeRegistry();
        registry.loadFromClasspath(RewriteToolTestSupport.class.getClassLoader());
        return new OpenRewriteEngineImpl(registry);
    }

    /** Erstellt einen frischen RewriteSyncManager. */
    public static RewriteSyncManager newSyncManager() {
        return new RewriteSyncManager();
    }
}

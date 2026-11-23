/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.testsupport;

import de.jare.rewisp.rewrite.OpenRewriteEngineImpl;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.YamlRecipeRegistry;

/**
 * Test-Unterstützung für OpenRewrite-basierte Edit-Tools.
 *
 * <p>Baut eine vollständige RewriteEngine (YamlRecipeRegistry + OpenRewriteEngineImpl),
 * damit Tests die produktive Pipeline ohne Spoon-Fallback abdecken.</p>
 *
 * @author Janusch Rentenatus
 */
public final class RewriteTestSupport {

    private RewriteTestSupport() {
    }

    /**
     * Erstellt eine RewriteEngine mit den Recipes aus application.yaml
     * (auf dem Test-Classpath vorhanden).
     */
    public static RewriteEngine newEngine() {
        YamlRecipeRegistry registry = new YamlRecipeRegistry();
        registry.loadFromClasspath(RewriteTestSupport.class.getClassLoader());
        return new OpenRewriteEngineImpl(registry);
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.openrewrite.java.JavaParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spoon.Launcher;
import spoon.support.compiler.VirtualFile;

/**
 * Warms up OpenRewrite (javac, classpath scan) and Spoon in the background at server start. Measured: the first
 * edit of a JVM costs ~12 s of startup time, each subsequent one ~2.5 s. With warmup, the startup cost no longer
 * hits the AI agent's first edit. Can be disabled with {@code -Dwarmup=false}.
 *
 * @author Janusch Rentenatus
 */
public final class Warmup {

    private static final Logger LOG = LoggerFactory.getLogger(Warmup.class);

    private Warmup() {
    }

    /** Starts warmup in a daemon thread; {@code null} when it is switched off. */
    public static Thread start() {
        return start(null);
    }

    /**
     * Like {@link #start()}; with a rewrite engine, an additional real recipe runs over a minimal class,
     * so the recipe/template startup costs also do not hit the first edit.
     */
    public static Thread start(de.jare.rewisp.rewrite.RewriteEngine engine) {
        if (!Boolean.parseBoolean(System.getProperty("warmup", "true"))) return null;
        Thread t = new Thread(() -> run(engine), "rewisp-warmup");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Runs the warmup synchronously. @return true when all parts ran without errors */
    public static boolean run() {
        return run(null);
    }

    /** Like {@link #run()}, with an optional recipe run over the given engine. */
    public static boolean run(de.jare.rewisp.rewrite.RewriteEngine engine) {
        long start = System.currentTimeMillis();
        boolean ok = true;
        try {
            JavaParser.fromJavaVersion().build().parse("class Warmup { int f() { return 1; } }").forEach(sf -> { });
        } catch (Exception | LinkageError e) {
            ok = false;
            LOG.debug("OpenRewrite warm-up failed: {}", e.getMessage());
        }
        try {
            Launcher launcher = new Launcher();
            launcher.getEnvironment().setNoClasspath(true);
            launcher.addInputResource(new VirtualFile("class Warmup { int f() { return 1; } }", "Warmup.java"));
            launcher.buildModel();
        } catch (Exception | LinkageError e) {
            ok = false;
            LOG.debug("Spoon warm-up failed: {}", e.getMessage());
        }
        if (engine != null && !runRecipe(engine)) {
            ok = false;
        }
        LOG.info("Warm-up finished in {} ms (ok={})", System.currentTimeMillis() - start, ok);
        return ok;
    }

    /** Runs the {@code add-field} recipe as a preview on a minimal class (without build context). */
    private static boolean runRecipe(de.jare.rewisp.rewrite.RewriteEngine engine) {
        try {
            Path file = Path.of("Warmup.java");
            String content = "class Warmup { int f() { return 1; } }";
            var id = new de.jare.rewisp.snapshot.SnapshotId("warmup", 0, "warmup");
            var files = Map.of(file, new de.jare.rewisp.snapshot.FileSnapshot(file, content, "warmup",
                    content.length(), 0L, false, false));
            var snapshot = new de.jare.rewisp.snapshot.ProjectSnapshot(id, "warmup", files, java.time.Instant.now());
            var params = new HashMap<String, String>();
            params.put("className", "Warmup");
            params.put("fieldName", "w");
            params.put("type", "int");
            params.put("modifiers", "");
            params.put("initializer", "");
            params.put("imports", "");
            engine.preview(snapshot, null, new de.jare.rewisp.rewrite.RewriteRequest("warmup", "add-field", params,
                    Set.of(file), id));
            return true;
        } catch (Exception | LinkageError e) {
            LOG.debug("Recipe warm-up failed: {}", e.getMessage());
            return false;
        }
    }
}

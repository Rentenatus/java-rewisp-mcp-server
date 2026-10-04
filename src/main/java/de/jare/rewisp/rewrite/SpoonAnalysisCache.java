package de.jare.rewisp.rewrite;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.snapshot.EffectiveProjectSources;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Einfacher Cache fuer {@link SpoonAnalysis}-Ergebnisse.
 *
 * <p>Cache-Schluessel: (projectId, fileCount, buildFingerprint, resolutionMode).
 * Bei Ueberschreitung von {@code MAX_ENTRIES} wird der Cache komplett
 * geleert (MVP-Strategie).
 */
public final class SpoonAnalysisCache {

    private static final int MAX_ENTRIES = 16;

    private final Map<String, SpoonAnalysis> cache = new ConcurrentHashMap<>();

    public SpoonAnalysis get(String key) {
        return cache.get(key);
    }

    public void put(String key, SpoonAnalysis analysis) {
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(key, analysis);
    }

    public void invalidate(String key) {
        cache.remove(key);
    }

    public void clear() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }

    public String buildKey(EffectiveProjectSources sources, BuildContext ctx) {
        return sources.projectId() + ":" + sources.fileCount() + ":"
                + (ctx != null ? ctx.fingerprint().compositeHash() : "no-ctx") + ":"
                + sources.resolutionMode();
    }
}

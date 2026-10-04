package de.jare.rewisp.rewrite;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.build.BuildFingerprint;
import de.jare.rewisp.build.ModuleContext;
import de.jare.rewisp.snapshot.EffectiveProjectSources;
import de.jare.rewisp.snapshot.ResolutionMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpoonAnalysisCacheTest {

    private final SpoonAnalysisCache cache = new SpoonAnalysisCache();

    private static SpoonAnalysis sampleAnalysis() {
        return new SpoonAnalysis(
                ResolutionMode.NO_CLASSPATH, List.of(), List.of(),
                Duration.ofMillis(10), true);
    }

    private static BuildContext sampleBuildContext(String hash) {
        BuildFingerprint fp = new BuildFingerprint("MAVEN", hash, "cp", "sr", "tc", "");
        return new BuildContext("MAVEN", List.of(), fp, List.of());
    }

    private static EffectiveProjectSources sources(String projectId, int fileCount) {
        Map<Path, String> src = new java.util.HashMap<>();
        for (int i = 0; i < fileCount; i++) {
            src.put(Path.of("src/File" + i + ".java"), "class File" + i + " {}");
        }
        return new EffectiveProjectSources(projectId, src, ResolutionMode.NO_CLASSPATH);
    }

    @Test
    void putAndGet_returnsSameAnalysis() {
        SpoonAnalysis analysis = sampleAnalysis();
        cache.put("key1", analysis);

        assertSame(analysis, cache.get("key1"));
    }

    @Test
    void invalidate_removesEntry() {
        cache.put("key1", sampleAnalysis());
        cache.invalidate("key1");

        assertNull(cache.get("key1"));
    }

    @Test
    void clear_emptiesCache() {
        cache.put("key1", sampleAnalysis());
        cache.put("key2", sampleAnalysis());
        cache.clear();

        assertEquals(0, cache.size());
    }

    @Test
    void buildKey_changesWithDifferentFileCount() {
        EffectiveProjectSources s1 = sources("proj", 1);
        EffectiveProjectSources s2 = sources("proj", 2);
        BuildContext ctx = sampleBuildContext("hash1");

        String key1 = cache.buildKey(s1, ctx);
        String key2 = cache.buildKey(s2, ctx);

        assertNotEquals(key1, key2);
    }

    @Test
    void buildKey_changesWithDifferentBuildFingerprint() {
        EffectiveProjectSources src = sources("proj", 1);
        BuildContext ctx1 = sampleBuildContext("hash1");
        BuildContext ctx2 = sampleBuildContext("hash2");

        String key1 = cache.buildKey(src, ctx1);
        String key2 = cache.buildKey(src, ctx2);

        assertNotEquals(key1, key2);
    }
}

package de.jare.rewisp.rewrite;

import de.jare.rewisp.snapshot.EffectiveProjectSources;
import de.jare.rewisp.snapshot.ResolutionMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpoonEngineImplTest {

    private final SpoonEngine engine = new SpoonEngineImpl();

    private static final String SIMPLE_CLASS =
            "package com.example;\npublic class Foo { public String bar() { return \"baz\"; } }";

    private static final String BROKEN_CLASS =
            "package com.example;\n public class Broken { this is not valid java }";

    @Test
    void emptySources_modelBuiltWithZeroTypes() {
        EffectiveProjectSources sources = new EffectiveProjectSources(
                "test", Map.of(), ResolutionMode.NO_CLASSPATH);
        SpoonAnalysisOptions options = new SpoonAnalysisOptions(
                ResolutionMode.NO_CLASSPATH, 21, true, true, 30);

        SpoonAnalysis result = engine.analyze(sources, null, options);

        assertTrue(result.modelBuilt());
        assertEquals(ResolutionMode.NO_CLASSPATH, result.resolutionMode());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void singleClass_modelBuiltWithOneType() {
        EffectiveProjectSources sources = new EffectiveProjectSources(
                "test", Map.of(Path.of("src/Foo.java"), SIMPLE_CLASS),
                ResolutionMode.NO_CLASSPATH);
        SpoonAnalysisOptions options = new SpoonAnalysisOptions(
                ResolutionMode.NO_CLASSPATH, 21, true, true, 30);

        SpoonAnalysis result = engine.analyze(sources, null, options);

        assertTrue(result.modelBuilt());
    }

    @Test
    void syntaxError_modelNotBuiltWithParseErrorDiagnostic() {
        EffectiveProjectSources sources = new EffectiveProjectSources(
                "test", Map.of(Path.of("src/Broken.java"), BROKEN_CLASS),
                ResolutionMode.NO_CLASSPATH);
        SpoonAnalysisOptions options = new SpoonAnalysisOptions(
                ResolutionMode.NO_CLASSPATH, 21, true, true, 30);

        SpoonAnalysis result = engine.analyze(sources, null, options);

        assertFalse(result.modelBuilt());
        assertFalse(result.diagnostics().isEmpty());
        SpoonDiagnostic diag = result.diagnostics().get(0);
        assertEquals("PARSE_ERROR", diag.code());
        assertEquals("ERROR", diag.severity());
    }

    @Test
    void noClasspathMode_modelBuiltWithoutClasspath() {
        EffectiveProjectSources sources = new EffectiveProjectSources(
                "test", Map.of(Path.of("src/Foo.java"), SIMPLE_CLASS),
                ResolutionMode.NO_CLASSPATH);
        SpoonAnalysisOptions options = new SpoonAnalysisOptions(
                ResolutionMode.NO_CLASSPATH, 21, true, true, 30);

        SpoonAnalysis result = engine.analyze(sources, null, options);

        assertTrue(result.modelBuilt());
        assertEquals(ResolutionMode.NO_CLASSPATH, result.resolutionMode());
    }

    @Test
    void fullClasspathModeWithoutContext_emitsClasspathStaleWarning() {
        EffectiveProjectSources sources = new EffectiveProjectSources(
                "test", Map.of(Path.of("src/Foo.java"), SIMPLE_CLASS),
                ResolutionMode.FULL_CLASSPATH);
        SpoonAnalysisOptions options = new SpoonAnalysisOptions(
                ResolutionMode.FULL_CLASSPATH, 21, true, true, 30);

        SpoonAnalysis result = engine.analyze(sources, null, options);

        assertTrue(result.modelBuilt());
        assertEquals(ResolutionMode.FULL_CLASSPATH, result.resolutionMode());
        boolean hasClasspathStale = result.diagnostics().stream()
                .anyMatch(d -> "CLASSPATH_STALE".equals(d.code()));
        assertTrue(hasClasspathStale, "Should emit CLASSPATH_STALE warning");
    }

    @Test
    void partialClasspathMode_modelBuiltWithNoClasspathTrue() {
        EffectiveProjectSources sources = new EffectiveProjectSources(
                "test", Map.of(Path.of("src/Foo.java"), SIMPLE_CLASS),
                ResolutionMode.PARTIAL_CLASSPATH);
        SpoonAnalysisOptions options = new SpoonAnalysisOptions(
                ResolutionMode.PARTIAL_CLASSPATH, 21, true, true, 30);

        SpoonAnalysis result = engine.analyze(sources, null, options);

        assertTrue(result.modelBuilt());
        assertEquals(ResolutionMode.PARTIAL_CLASSPATH, result.resolutionMode());
    }

    @Test
    void duration_isPositive() {
        EffectiveProjectSources sources = new EffectiveProjectSources(
                "test", Map.of(Path.of("src/Foo.java"), SIMPLE_CLASS),
                ResolutionMode.NO_CLASSPATH);
        SpoonAnalysisOptions options = new SpoonAnalysisOptions(
                ResolutionMode.NO_CLASSPATH, 21, true, true, 30);

        SpoonAnalysis result = engine.analyze(sources, null, options);

        assertTrue(result.duration().toMillis() >= 0,
                "Duration should be non-negative");
    }

    @Test
    void virtualFileSources_correctlyParsed() {
        String class1 = "package com.example.a;\npublic class ClassA {}";
        String class2 = "package com.example.b;\npublic class ClassB {}";
        EffectiveProjectSources sources = new EffectiveProjectSources(
                "test",
                Map.of(Path.of("src/A.java"), class1, Path.of("src/B.java"), class2),
                ResolutionMode.NO_CLASSPATH);
        SpoonAnalysisOptions options = new SpoonAnalysisOptions(
                ResolutionMode.NO_CLASSPATH, 21, true, true, 30);

        SpoonAnalysis result = engine.analyze(sources, null, options);

        assertTrue(result.modelBuilt());
        assertTrue(result.diagnostics().isEmpty(),
                "No diagnostics expected for valid sources");
    }
}

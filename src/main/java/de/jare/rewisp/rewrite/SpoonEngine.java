package de.jare.rewisp.rewrite;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.snapshot.EffectiveProjectSources;

/**
 * Service-Interface fuer Request-spezifische Spoon-Analyse.
 * Baut ein frisches {@code CtModel} aus In-Memory-Quellen (VirtualFile),
 * unabhaengig vom Singleton-Modell in {@code ProjectEntry}.
 */
public interface SpoonEngine {

    SpoonAnalysis analyze(
            EffectiveProjectSources sources,
            BuildContext buildContext,
            SpoonAnalysisOptions options
    );
}

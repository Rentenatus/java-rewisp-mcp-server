package de.jare.rewisp.snapshot;

import java.nio.file.Path;
import java.util.Map;

/**
 * Ein effektiver Source-Bestand, der Original-Quellen und
 * OpenRewrite-Transformationen zusammenfuehrt.
 *
 * @param projectId       Projekt-Kennung
 * @param sources         relativePath -> content (immutable Kopie)
 * @param resolutionMode  Classpath-Aufloesungsmodus
 */
public record EffectiveProjectSources(
        String projectId,
        Map<Path, String> sources,
        ResolutionMode resolutionMode
) {
    public EffectiveProjectSources {
        sources = Map.copyOf(sources);
    }

    public int fileCount() {
        return sources.size();
    }
}

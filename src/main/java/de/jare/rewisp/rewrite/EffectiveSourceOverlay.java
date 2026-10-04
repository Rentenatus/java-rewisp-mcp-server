/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.FileSnapshot;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kombiniert Original-Sources mit Rewrite-Ergebnissen zu einem effektiven Source-Bestand.
 * Wird für die Spoon-Postvalidierung in Phase 5 benötigt.
 */
public final class EffectiveSourceOverlay {

    /**
     * Wende das RewriteResult auf das Original-Snapshot an.
     * 
     * @param original Das ursprüngliche ProjectSnapshot
     * @param rewrite Das Ergebnis der Rewrite-Transformation
     * @return Map mit allen effektiven Sources (original + modifiziert + neu)
     */
    public Map<Path, String> apply(ProjectSnapshot original, RewriteResult rewrite) {
        Map<Path, String> result = new LinkedHashMap<>();

        // Originalquellen übernehmen (außer gelöschte)
        for (Map.Entry<Path, FileSnapshot> entry : original.files().entrySet()) {
            if (!rewrite.deletedFiles().contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue().content());
            }
        }

        // Gelöschte entfernen (falls sie im Original waren)
        rewrite.deletedFiles().forEach(result::remove);

        // Geänderte überschreiben
        rewrite.changedFiles().forEach((path, file) ->
                result.put(path, file.content()));

        return Map.copyOf(result);
    }
}

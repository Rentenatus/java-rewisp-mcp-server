/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.snapshot.ProjectSnapshot;

/**
 * Engine für OpenRewrite-Transformationen.
 * Führt Recipes im Speicher aus und liefert Transformationsergebnisse zurück.
 */
public interface RewriteEngine {

    /**
     * Führe eine Preview-Transformation aus.
     * 
     * @param snapshot Der aktuelle ProjectSnapshot
     * @param buildContext Der Build-Kontext (für Classpath)
     * @param request Die Rewrite-Anfrage
     * @return Das RewriteResult mit allen Änderungen
     * @throws RewriteException bei Fehlern
     */
    RewriteResult preview(
            ProjectSnapshot snapshot,
            BuildContext buildContext,
            RewriteRequest request
    ) throws RewriteException;
}

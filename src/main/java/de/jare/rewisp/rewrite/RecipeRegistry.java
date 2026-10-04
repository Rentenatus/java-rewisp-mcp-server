/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import java.util.List;
import java.util.Optional;

/**
 * Registry für OpenRewrite Recipes.
 * Erlaubt das Auffinden und Auflisten von verfügbaren Recipes.
 */
public interface RecipeRegistry {
    
    /**
     * Finde ein Recipe nach seiner ID.
     * 
     * @param recipeId Die eindeutige ID des Recipes
     * @return Optional mit RecipeDefinition, oder empty wenn nicht gefunden
     */
    Optional<RecipeDefinition> find(String recipeId);
    
    /**
     * Liste aller aktivierten Recipes.
     * 
     * @return Liste von aktivierten RecipeDefinitions, sortiert nach ID
     */
    List<RecipeDefinition> listEnabled();
    
    /**
     * Registriere ein Recipe manuell.
     * 
     * @param definition Die RecipeDefinition
     */
    void register(RecipeDefinition definition);
    
    /**
     * Lade Recipes aus dem Classpath (application.yaml).
     * 
     * @param classLoader Der ClassLoader zum Laden der Ressource
     */
    default void loadFromClasspath(ClassLoader classLoader) {
        // Default-Implementierung: tut nichts
    }
}

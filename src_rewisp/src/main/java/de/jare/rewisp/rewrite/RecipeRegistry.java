/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import java.util.List;
import java.util.Optional;

/**
 * Registry for OpenRewrite recipes.
 * Allows finding and listing the available recipes.
 */
public interface RecipeRegistry {
    
    /**
     * Find a recipe by its ID.
     * 
     * @param recipeId the unique ID of the recipe
     * @return Optional with RecipeDefinition, or empty when not found
     */
    Optional<RecipeDefinition> find(String recipeId);
    
    /**
     * List of all enabled recipes.
     * 
     * @return list of enabled RecipeDefinitions, sorted by ID
     */
    List<RecipeDefinition> listEnabled();
    
    /**
     * Register a recipe manually.
     * 
     * @param definition the RecipeDefinition
     */
    void register(RecipeDefinition definition);
    
    /**
     * Load recipes from the classpath (application.yaml).
     * 
     * @param classLoader the ClassLoader for loading the resource
     */
    default void loadFromClasspath(ClassLoader classLoader) {
        // Default implementation: does nothing
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import java.util.Set;

/**
 * Definition eines OpenRewrite Recipe.
 * Wird aus der application.yaml geladen und im RecipeRegistry verwaltet.
 */
public record RecipeDefinition(
        String id,
        String displayName,
        String description,
        String recipeClass,
        Set<String> allowedParameters,
        ChangeRisk risk,
        boolean mayChangeBuildFiles,
        boolean requiresFullProject,
        boolean enabled
) {
    public RecipeDefinition {
        allowedParameters = Set.copyOf(allowedParameters);
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementierung von RecipeRegistry, die Recipes aus einer YAML-Datei lädt.
 * 
 * <p>YAML-Format:
 * <pre>
 * recipes:
 *   - id: renameMethod
 *     displayName: Rename Method
 *     description: Renames a method in all classes
 *     class: de.jare.rewisp.rewrite.RenameMethodRecipe
 *     risk: MEDIUM
 *     mayChangeBuildFiles: false
 *     requiresFullProject: false
 *     enabled: true
 *     allowedParameters:
 *       - methodName
 *       - newName
 * </pre>
 */
public final class YamlRecipeRegistry implements RecipeRegistry {

    private final Map<String, RecipeDefinition> recipes = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public void loadFromYaml(InputStream yamlStream) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(yamlStream);
        if (root == null) return;

        List<Map<String, Object>> recipeList = (List<Map<String, Object>>) root.get("recipes");
        if (recipeList == null) {
            // Recipes können unter "rewrite.recipes" verschachtelt sein (application.yaml-Struktur)
            Object rewriteSection = root.get("rewrite");
            if (rewriteSection instanceof Map<?, ?> rewriteMap) {
                recipeList = (List<Map<String, Object>>) rewriteMap.get("recipes");
            }
        }
        if (recipeList == null) return;

        for (Map<String, Object> entry : recipeList) {
            String id = (String) entry.get("id");
            if (id == null || id.isBlank()) continue;
            
            String recipeClass = (String) entry.get("class");
            if (recipeClass == null || recipeClass.isBlank()) continue;
            
            String riskStr = (String) entry.getOrDefault("risk", "MEDIUM");
            ChangeRisk risk = parseRisk(riskStr);
            boolean enabled = getBoolean(entry, "enabled", true);
            boolean mayChangeBuildFiles = getBoolean(entry, "mayChangeBuildFiles", false);
            boolean requiresFullProject = getBoolean(entry, "requiresFullProject", false);
            
            Set<String> allowedParameters = new LinkedHashSet<>();
            Object paramsObj = entry.get("allowedParameters");
            if (paramsObj instanceof List) {
                for (Object param : (List<?>) paramsObj) {
                    if (param instanceof String) {
                        allowedParameters.add((String) param);
                    }
                }
            }

            RecipeDefinition def = new RecipeDefinition(
                    id,
                    (String) entry.getOrDefault("displayName", id),
                    (String) entry.getOrDefault("description", ""),
                    recipeClass,
                    allowedParameters,
                    risk,
                    mayChangeBuildFiles,
                    requiresFullProject,
                    enabled
            );
            recipes.put(id, def);
        }
    }

    private ChangeRisk parseRisk(String riskStr) {
        try {
            return ChangeRisk.valueOf(riskStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ChangeRisk.MEDIUM;
        }
    }

    private boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    @Override
    public Optional<RecipeDefinition> find(String recipeId) {
        return Optional.ofNullable(recipes.get(recipeId));
    }

    @Override
    public List<RecipeDefinition> listEnabled() {
        return recipes.values().stream()
                .filter(RecipeDefinition::enabled)
                .sorted(Comparator.comparing(RecipeDefinition::id))
                .toList();
    }

    @Override
    public void register(RecipeDefinition definition) {
        recipes.put(definition.id(), definition);
    }

    /**
     * Lade Recipes aus der Classpath-Ressource /application.yaml.
     * 
     * @param classLoader Der ClassLoader zum Laden der Ressource
     */
    public void loadFromClasspath(ClassLoader classLoader) {
        try (InputStream in = classLoader.getResourceAsStream("application.yaml")) {
            if (in != null) {
                loadFromYaml(in);
            }
        } catch (Exception e) {
            // Ignorieren - Recipes können auch manuell registriert werden
        }
    }

    /**
     * Gibt die Anzahl aller registrierten Recipes zurück.
     */
    public int size() {
        return recipes.size();
    }
}

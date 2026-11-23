/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class YamlRecipeRegistryTest {

    @Test
    void find_recipeById() {
        String yaml = """
recipes:
  - id: testRecipe
    displayName: Test Recipe
    description: A test recipe
    class: com.example.TestRecipe
    risk: LOW
    enabled: true
""";
        YamlRecipeRegistry registry = new YamlRecipeRegistry();
        registry.loadFromYaml(yamlStream(yaml));

        Optional<RecipeDefinition> found = registry.find("testRecipe");
        assertTrue(found.isPresent());
        assertEquals("testRecipe", found.get().id());
        assertEquals("Test Recipe", found.get().displayName());
        assertEquals("A test recipe", found.get().description());
        assertEquals("com.example.TestRecipe", found.get().recipeClass());
        assertEquals(ChangeRisk.LOW, found.get().risk());
        assertTrue(found.get().enabled());
    }

    @Test
    void listEnabled_returnsOnlyEnabled() {
        String yaml = """
recipes:
  - id: enabledRecipe
    displayName: Enabled
    class: com.example.Enabled
    enabled: true
  - id: disabledRecipe
    displayName: Disabled
    class: com.example.Disabled
    enabled: false
""";
        YamlRecipeRegistry registry = new YamlRecipeRegistry();
        registry.loadFromYaml(yamlStream(yaml));

        List<RecipeDefinition> enabled = registry.listEnabled();
        assertEquals(1, enabled.size());
        assertEquals("enabledRecipe", enabled.get(0).id());
    }

    @Test
    void find_nonExistentRecipe_returnsEmpty() {
        YamlRecipeRegistry registry = new YamlRecipeRegistry();
        Optional<RecipeDefinition> found = registry.find("nonexistent");
        assertFalse(found.isPresent());
    }

    @Test
    void register_addsRecipe() {
        YamlRecipeRegistry registry = new YamlRecipeRegistry();
        RecipeDefinition def = new RecipeDefinition(
                "manual", "Manual", "Manual recipe", "com.example.Manual",
                java.util.Set.of(), ChangeRisk.MEDIUM, false, false, true);
        registry.register(def);

        Optional<RecipeDefinition> found = registry.find("manual");
        assertTrue(found.isPresent());
        assertEquals("Manual", found.get().displayName());
    }

    @Test
    void risk_defaultsToMedium() {
        String yaml = """
recipes:
  - id: test
    class: com.example.Test
""";
        YamlRecipeRegistry registry = new YamlRecipeRegistry();
        registry.loadFromYaml(yamlStream(yaml));

        Optional<RecipeDefinition> found = registry.find("test");
        assertTrue(found.isPresent());
        assertEquals(ChangeRisk.MEDIUM, found.get().risk());
    }

    private InputStream yamlStream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}

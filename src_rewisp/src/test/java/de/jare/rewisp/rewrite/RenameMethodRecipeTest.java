/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import de.jare.rewisp.rewrite.recipes.RenameMethodRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RenameMethodRecipeTest {

    @Test
    void recipe_hasCorrectDisplayName() {
        RenameMethodRecipe recipe = new RenameMethodRecipe();
        recipe.setOldMethodName("getStartDate");
        recipe.setNewMethodName("getContractStart");
        assertEquals("Rename method getStartDate to getContractStart",
                recipe.getDisplayName());
    }

    @Test
    void recipe_hasCorrectDescription() {
        RenameMethodRecipe recipe = new RenameMethodRecipe();
        recipe.setOldMethodName("getStartDate");
        recipe.setNewMethodName("getContractStart");
        assertTrue(recipe.getDescription().contains("getStartDate"));
        assertTrue(recipe.getDescription().contains("getContractStart"));
    }

    @Test
    void customParameters_areApplied() {
        RenameMethodRecipe recipe = new RenameMethodRecipe();
        recipe.setOldMethodName("oldName");
        recipe.setNewMethodName("newName");

        assertEquals("Rename method oldName to newName", recipe.getDisplayName());
    }

    @Test
    void recipe_isValidRecipe() {
        RenameMethodRecipe recipe = new RenameMethodRecipe();
        // Hauptsächlich prüfen, dass das Recipe korrekt instanziiert werden kann
        assertNotNull(recipe);
        assertNotNull(recipe.getVisitor());
    }

    @Test
    void displayNameChangesWithParameters() {
        RenameMethodRecipe recipe = new RenameMethodRecipe();
        recipe.setOldMethodName("foo");
        recipe.setNewMethodName("bar");
        
        assertEquals("Rename method foo to bar", recipe.getDisplayName());
        
        recipe.setOldMethodName("alpha");
        recipe.setNewMethodName("beta");
        
        assertEquals("Rename method alpha to beta", recipe.getDisplayName());
    }

    @Test
    void descriptionContainsBothMethodNames() {
        RenameMethodRecipe recipe = new RenameMethodRecipe();
        recipe.setOldMethodName("getStartDate");
        recipe.setNewMethodName("getContractStart");
        String desc = recipe.getDescription();

        assertTrue(desc.contains("getStartDate"));
        assertTrue(desc.contains("getContractStart"));
    }

    @Test
    void visitorIsNotNull() {
        RenameMethodRecipe recipe = new RenameMethodRecipe();
        assertNotNull(recipe.getVisitor(), "Recipe visitor should not be null");
    }
}

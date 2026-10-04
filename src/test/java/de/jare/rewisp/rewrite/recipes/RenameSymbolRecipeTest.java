/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RenameSymbolRecipe.
 * 
 * @author Janusch Rentenatus
 */
class RenameSymbolRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        RenameSymbolRecipe recipe = new RenameSymbolRecipe(
            "com.example.MyClass.method",
            "newMethod",
            RenameSymbolRecipe.SymbolType.METHOD,
            true
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass.method", recipe.getSymbolPath());
        assertEquals("newMethod", recipe.getNewName());
        assertEquals(RenameSymbolRecipe.SymbolType.METHOD, recipe.getSymbolType());
        assertTrue(recipe.isUpdateCallSites());
    }

    @Test
    void testConstructorWithUpdateCallSitesFalse() {
        RenameSymbolRecipe recipe = new RenameSymbolRecipe(
            "com.example.MyClass.field",
            "newField",
            RenameSymbolRecipe.SymbolType.FIELD,
            false
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass.field", recipe.getSymbolPath());
        assertEquals("newField", recipe.getNewName());
        assertEquals(RenameSymbolRecipe.SymbolType.FIELD, recipe.getSymbolType());
        assertFalse(recipe.isUpdateCallSites());
    }

    @Test
    void testDisplayName() {
        RenameSymbolRecipe recipe = new RenameSymbolRecipe(
            "com.example.MyClass",
            "NewClass",
            RenameSymbolRecipe.SymbolType.CLASS,
            true
        );
        
        assertEquals("Rename class MyClass to NewClass", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        RenameSymbolRecipe recipe = new RenameSymbolRecipe(
            "com.example.MyClass.method",
            "newMethod",
            RenameSymbolRecipe.SymbolType.METHOD,
            true
        );
        
        assertEquals("Renames method com.example.MyClass.method to newMethod.", 
            recipe.getDescription());
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        RenameSymbolRecipe recipe = new RenameSymbolRecipe(
            "com.example.MyClass",
            "NewClass",
            RenameSymbolRecipe.SymbolType.CLASS,
            true
        );
        
        assertNotNull(recipe.getVisitor());
    }

    @Test
    void testDetectSymbolTypeClass() {
        assertEquals(RenameSymbolRecipe.SymbolType.CLASS,
            RenameSymbolRecipe.detectSymbolType("com.example.MyClass"));
    }

    @Test
    void testDetectSymbolTypeMethod() {
        assertEquals(RenameSymbolRecipe.SymbolType.METHOD,
            RenameSymbolRecipe.detectSymbolType("com.example.MyClass.method()"));
    }

    @Test
    void testDetectSymbolTypeField() {
        assertEquals(RenameSymbolRecipe.SymbolType.FIELD,
            RenameSymbolRecipe.detectSymbolType("com.example.MyClass.field"));
    }
}

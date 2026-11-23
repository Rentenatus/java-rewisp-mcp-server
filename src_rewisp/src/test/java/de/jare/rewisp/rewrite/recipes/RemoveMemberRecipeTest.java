/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RemoveMemberRecipe.
 * 
 * @author Janusch Rentenatus
 */
class RemoveMemberRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        RemoveMemberRecipe recipe = new RemoveMemberRecipe(
            "com.example.MyClass",
            "myMethod",
            "method",
            "int, String",
            true
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myMethod", recipe.getMemberName());
        assertEquals("method", recipe.getScope());
        assertEquals("int, String", recipe.getSignature());
        assertTrue(recipe.isHard());
    }

    @Test
    void testConstructorWithHardModeFalse() {
        RemoveMemberRecipe recipe = new RemoveMemberRecipe(
            "com.example.MyClass",
            "myField",
            "field",
            null,
            false
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myField", recipe.getMemberName());
        assertEquals("field", recipe.getScope());
        assertNull(recipe.getSignature());
        assertFalse(recipe.isHard());
    }

    @Test
    void testConstructorWithNoSignature() {
        RemoveMemberRecipe recipe = new RemoveMemberRecipe(
            "com.example.MyClass",
            "myMethod",
            "method",
            null,
            false
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myMethod", recipe.getMemberName());
        assertEquals("method", recipe.getScope());
        assertNull(recipe.getSignature());
        assertFalse(recipe.isHard());
    }

    @Test
    void testDisplayName() {
        RemoveMemberRecipe recipe = new RemoveMemberRecipe(
            "com.example.MyClass",
            "myMethod",
            "method",
            null,
            false
        );
        
        assertEquals("Remove method myMethod from com.example.MyClass", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        RemoveMemberRecipe recipe = new RemoveMemberRecipe(
            "com.example.MyClass",
            "myMethod",
            "method",
            null,
            false
        );
        
        assertEquals("Removes method myMethod from class com.example.MyClass.",
            recipe.getDescription());
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        RemoveMemberRecipe recipe = new RemoveMemberRecipe(
            "com.example.MyClass",
            "myMethod",
            "method",
            null,
            false
        );
        
        assertNotNull(recipe.getVisitor());
    }

    @Test
    void testFieldScope() {
        RemoveMemberRecipe recipe = new RemoveMemberRecipe(
            "com.example.MyClass",
            "myField",
            "field",
            null,
            true
        );
        
        assertEquals("field", recipe.getScope());
        assertEquals("myField", recipe.getMemberName());
        assertTrue(recipe.isHard());
    }

    @Test
    void testMethodScopeWithSignature() {
        RemoveMemberRecipe recipe = new RemoveMemberRecipe(
            "com.example.MyClass",
            "overloadedMethod",
            "method",
            "int, int",
            false
        );
        
        assertEquals("method", recipe.getScope());
        assertEquals("overloadedMethod", recipe.getMemberName());
        assertEquals("int, int", recipe.getSignature());
        assertFalse(recipe.isHard());
    }
}

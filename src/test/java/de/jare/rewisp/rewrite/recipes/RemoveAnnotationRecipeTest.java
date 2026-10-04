/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RemoveAnnotationRecipe.
 * 
 * @author Janusch Rentenatus
 */
class RemoveAnnotationRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        RemoveAnnotationRecipe recipe = new RemoveAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override"
        );
        
        assertNotNull(recipe);
        assertEquals("method", recipe.getTargetType());
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myMethod", recipe.getTargetName());
        assertEquals("Override", recipe.getAnnotation());
    }

    @Test
    void testConstructorWithClassTarget() {
        RemoveAnnotationRecipe recipe = new RemoveAnnotationRecipe(
            "class",
            "com.example.MyClass",
            null,
            "Deprecated"
        );
        
        assertNotNull(recipe);
        assertEquals("class", recipe.getTargetType());
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertNull(recipe.getTargetName());
        assertEquals("Deprecated", recipe.getAnnotation());
    }

    @Test
    void testConstructorWithFieldTarget() {
        RemoveAnnotationRecipe recipe = new RemoveAnnotationRecipe(
            "field",
            "com.example.MyClass",
            "myField",
            "NotNull"
        );
        
        assertNotNull(recipe);
        assertEquals("field", recipe.getTargetType());
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myField", recipe.getTargetName());
        assertEquals("NotNull", recipe.getAnnotation());
    }

    @Test
    void testDisplayName() {
        RemoveAnnotationRecipe recipe = new RemoveAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override"
        );
        
        assertEquals("Remove annotation @Override from com.example.MyClass", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        RemoveAnnotationRecipe recipe = new RemoveAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override"
        );
        
        assertEquals("Removes @Override from method com.example.MyClass.myMethod.", 
            recipe.getDescription());
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        RemoveAnnotationRecipe recipe = new RemoveAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override"
        );
        
        assertNotNull(recipe.getVisitor());
    }
}

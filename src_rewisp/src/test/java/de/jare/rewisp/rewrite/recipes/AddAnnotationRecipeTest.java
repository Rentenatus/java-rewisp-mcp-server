/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AddAnnotationRecipe.
 * 
 * @author Janusch Rentenatus
 */
class AddAnnotationRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        AddAnnotationRecipe recipe = new AddAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override",
            null
        );
        
        assertNotNull(recipe);
        assertEquals("method", recipe.getTargetType());
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myMethod", recipe.getTargetName());
        assertEquals("Override", recipe.getAnnotation());
        assertNull(recipe.getAttributes());
    }

    @Test
    void testConstructorWithAttributes() {
        AddAnnotationRecipe recipe = new AddAnnotationRecipe(
            "class",
            "com.example.MyClass",
            null,
            "Deprecated",
            "value=\"old\""
        );
        
        assertNotNull(recipe);
        assertEquals("class", recipe.getTargetType());
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertNull(recipe.getTargetName());
        assertEquals("Deprecated", recipe.getAnnotation());
        assertEquals("value=\"old\"", recipe.getAttributes());
    }

    @Test
    void testConstructorWithFieldTarget() {
        AddAnnotationRecipe recipe = new AddAnnotationRecipe(
            "field",
            "com.example.MyClass",
            "myField",
            "NotNull",
            null
        );
        
        assertNotNull(recipe);
        assertEquals("field", recipe.getTargetType());
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myField", recipe.getTargetName());
        assertEquals("NotNull", recipe.getAnnotation());
    }

    @Test
    void testDisplayName() {
        AddAnnotationRecipe recipe = new AddAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override",
            null
        );
        
        assertEquals("Add annotation @Override", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        AddAnnotationRecipe recipe = new AddAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override",
            null
        );
        
        assertEquals("Adds @Override to method com.example.MyClass.myMethod.", 
            recipe.getDescription());
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        AddAnnotationRecipe recipe = new AddAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override",
            null
        );
        
        assertNotNull(recipe.getVisitor());
    }
}

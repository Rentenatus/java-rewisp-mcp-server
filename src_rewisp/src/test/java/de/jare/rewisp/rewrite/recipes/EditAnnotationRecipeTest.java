/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EditAnnotationRecipe.
 * 
 * @author Janusch Rentenatus
 */
class EditAnnotationRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        EditAnnotationRecipe recipe = new EditAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override",
            "value=true"
        );
        
        assertNotNull(recipe);
        assertEquals("method", recipe.getTargetType());
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myMethod", recipe.getTargetName());
        assertEquals("Override", recipe.getAnnotation());
        assertEquals("value=true", recipe.getNewAttributes());
    }

    @Test
    void testConstructorWithClassTarget() {
        EditAnnotationRecipe recipe = new EditAnnotationRecipe(
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
        assertEquals("value=\"old\"", recipe.getNewAttributes());
    }

    @Test
    void testConstructorWithFieldTarget() {
        EditAnnotationRecipe recipe = new EditAnnotationRecipe(
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
        assertNull(recipe.getNewAttributes());
    }

    @Test
    void testDisplayName() {
        EditAnnotationRecipe recipe = new EditAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override",
            "value=true"
        );
        
        assertEquals("Edit annotation @Override in com.example.MyClass", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        EditAnnotationRecipe recipe = new EditAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override",
            "value=true"
        );
        
        assertEquals("Edits attributes of @Override on method com.example.MyClass.myMethod.", 
            recipe.getDescription());
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        EditAnnotationRecipe recipe = new EditAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Override",
            "value=true"
        );
        
        assertNotNull(recipe.getVisitor());
    }

    @Test
    void testConstructorWithEmptyAttributes() {
        EditAnnotationRecipe recipe = new EditAnnotationRecipe(
            "class",
            "com.example.MyClass",
            null,
            "Deprecated",
            ""
        );
        
        assertNotNull(recipe);
        assertEquals("", recipe.getNewAttributes());
    }

    @Test
    void testConstructorWithMultipleAttributes() {
        EditAnnotationRecipe recipe = new EditAnnotationRecipe(
            "method",
            "com.example.MyClass",
            "myMethod",
            "Transactional",
            "value=true, rollbackFor=Exception.class"
        );
        
        assertNotNull(recipe);
        assertEquals("value=true, rollbackFor=Exception.class", recipe.getNewAttributes());
    }
}

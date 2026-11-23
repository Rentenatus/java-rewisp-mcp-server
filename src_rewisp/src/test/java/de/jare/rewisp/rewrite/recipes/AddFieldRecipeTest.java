/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AddFieldRecipe.
 * 
 * @author Janusch Rentenatus
 */
class AddFieldRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        AddFieldRecipe recipe = new AddFieldRecipe(
            "com.example.MyClass",
            "count",
            "int",
            "private static",
            "0"
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("count", recipe.getFieldName());
        assertEquals("int", recipe.getType());
        assertEquals("private static", recipe.getModifiers());
        assertEquals("0", recipe.getInitializer());
    }

    @Test
    void testConstructorWithMinimalParameters() {
        AddFieldRecipe recipe = new AddFieldRecipe(
            "com.example.MyClass",
            "name",
            "String",
            null,
            null
        );
        
        assertNotNull(recipe);
        assertNull(recipe.getModifiers());
        assertNull(recipe.getInitializer());
    }

    @Test
    void testConstructorWithNoInitializer() {
        AddFieldRecipe recipe = new AddFieldRecipe(
            "com.example.MyClass",
            "active",
            "boolean",
            "private",
            ""
        );
        
        assertNotNull(recipe);
        assertEquals("", recipe.getInitializer());
    }

    @Test
    void testDisplayName() {
        AddFieldRecipe recipe = new AddFieldRecipe(
            "com.example.MyClass",
            "value",
            "double",
            null,
            null
        );
        
        assertEquals("Add field value to com.example.MyClass", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        AddFieldRecipe recipe = new AddFieldRecipe(
            "com.example.MyClass",
            "value",
            "double",
            null,
            null
        );
        
        assertEquals("Adds a new field double value to class com.example.MyClass.",
                recipe.getDescription());
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        AddFieldRecipe recipe = new AddFieldRecipe(
            "com.example.MyClass",
            "value",
            "int",
            "public final",
            "42"
        );
        
        assertNotNull(recipe.getVisitor());
    }

    @Test
    void testConstructorWithFinalField() {
        AddFieldRecipe recipe = new AddFieldRecipe(
            "com.example.MyClass",
            "CONSTANT",
            "String",
            "public static final",
            "\"constant\""
        );
        
        assertNotNull(recipe);
        assertEquals("public static final", recipe.getModifiers());
        assertEquals("\"constant\"", recipe.getInitializer());
    }

    @Test
    void testConstructorWithGenericType() {
        AddFieldRecipe recipe = new AddFieldRecipe(
            "com.example.MyClass",
            "items",
            "List<String>",
            "private",
            "new ArrayList<>()"
        );
        
        assertNotNull(recipe);
        assertEquals("List<String>", recipe.getType());
        assertEquals("new ArrayList<>()", recipe.getInitializer());
    }
}

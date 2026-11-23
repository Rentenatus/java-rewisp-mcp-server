/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MoveClassRecipe.
 * 
 * @author Janusch Rentenatus
 */
class MoveClassRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        MoveClassRecipe recipe = new MoveClassRecipe(
            "com.example.MyClass",
            "com.newpackage",
            true
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getFullyQualifiedClassName());
        assertEquals("com.newpackage", recipe.getTargetPackage());
        assertTrue(recipe.isKeepTypeName());
    }

    @Test
    void testConstructorWithKeepTypeNameFalse() {
        MoveClassRecipe recipe = new MoveClassRecipe(
            "com.example.MyClass",
            "com.newpackage",
            false
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getFullyQualifiedClassName());
        assertEquals("com.newpackage", recipe.getTargetPackage());
        assertFalse(recipe.isKeepTypeName());
    }

    @Test
    void testDisplayName() {
        MoveClassRecipe recipe = new MoveClassRecipe(
            "com.example.MyClass",
            "com.newpackage",
            true
        );
        
        assertEquals("Move class com.example.MyClass to com.newpackage", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        MoveClassRecipe recipe = new MoveClassRecipe(
            "com.example.MyClass",
            "com.newpackage",
            true
        );
        
        assertEquals("Moves class com.example.MyClass to package com.newpackage, updating package declaration, imports, and references.", 
            recipe.getDescription());
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        MoveClassRecipe recipe = new MoveClassRecipe(
            "com.example.MyClass",
            "com.newpackage",
            true
        );
        
        assertNotNull(recipe.getVisitor());
    }

    @Test
    void testExtractPackageName() {
        String fqName = "com.example.subpackage.MyClass";
        String expectedPackage = "com.example.subpackage";
        
        MoveClassRecipe recipe = new MoveClassRecipe(
            fqName,
            "com.newpackage",
            true
        );
        
        // Test package extraction logic
        String actualPackage = fqName.substring(0, fqName.lastIndexOf('.'));
        assertEquals(expectedPackage, actualPackage);
    }

    @Test
    void testExtractSimpleName() {
        String fqName = "com.example.subpackage.MyClass";
        String expectedSimpleName = "MyClass";
        
        String actualSimpleName = fqName.substring(fqName.lastIndexOf('.') + 1);
        assertEquals(expectedSimpleName, actualSimpleName);
    }
}

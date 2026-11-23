/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RewriteSignatureRecipe.
 * 
 * @author Janusch Rentenatus
 */
class RewriteSignatureRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        RewriteSignatureRecipe recipe = new RewriteSignatureRecipe(
            "com.example.MyClass",
            "myMethod",
            "void",
            "int count, String label",
            "signature_only"
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myMethod", recipe.getMethodName());
        assertEquals("void", recipe.getNewReturnType());
        assertEquals("int count, String label", recipe.getNewParameters());
        assertEquals("signature_only", recipe.getMode());
    }

    @Test
    void testConstructorWithNullParameters() {
        RewriteSignatureRecipe recipe = new RewriteSignatureRecipe(
            "com.example.MyClass",
            "myMethod",
            null,
            null,
            "signature_and_callers"
        );
        
        assertNotNull(recipe);
        assertNull(recipe.getNewReturnType());
        assertNull(recipe.getNewParameters());
        assertEquals("signature_and_callers", recipe.getMode());
    }

    @Test
    void testConstructorWithEmptyClassName() {
        RewriteSignatureRecipe recipe = new RewriteSignatureRecipe(
            "",
            "myMethod",
            "void",
            "int x",
            "signature_only"
        );
        
        assertNotNull(recipe);
        assertEquals("", recipe.getClassName());
    }

    @Test
    void testDisplayName() {
        RewriteSignatureRecipe recipe = new RewriteSignatureRecipe(
            "com.example.MyClass",
            "myMethod",
            "void",
            "int x",
            "signature_only"
        );
        
        assertEquals("Rewrite signature of myMethod in com.example.MyClass", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        RewriteSignatureRecipe recipe = new RewriteSignatureRecipe(
            "com.example.MyClass",
            "myMethod",
            "void",
            "int x",
            "signature_only"
        );
        
        assertEquals("Changes the signature (return type and/or parameters) of method myMethod in class com.example.MyClass.",
                recipe.getDescription());
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        RewriteSignatureRecipe recipe = new RewriteSignatureRecipe(
            "com.example.MyClass",
            "myMethod",
            "void",
            "int x",
            "signature_only"
        );
        
        assertNotNull(recipe.getVisitor());
    }

    @Test
    void testConstructorWithComplexSignature() {
        RewriteSignatureRecipe recipe = new RewriteSignatureRecipe(
            "com.example.MyClass",
            "process",
            "List<String>",
            "Map<String, Integer> params, Set<Long> ids",
            "signature_and_callers"
        );
        
        assertNotNull(recipe);
        assertEquals("List<String>", recipe.getNewReturnType());
        assertEquals("Map<String, Integer> params, Set<Long> ids", recipe.getNewParameters());
    }
}

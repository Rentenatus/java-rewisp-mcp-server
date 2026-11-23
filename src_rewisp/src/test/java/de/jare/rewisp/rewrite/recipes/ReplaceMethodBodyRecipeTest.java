/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ReplaceMethodBodyRecipe.
 * 
 * @author Janusch Rentenatus
 */
class ReplaceMethodBodyRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        ReplaceMethodBodyRecipe recipe = new ReplaceMethodBodyRecipe(
            "com.example.MyClass",
            "myMethod",
            "int, String",
            "return 42;"
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myMethod", recipe.getMethodName());
        assertEquals("int, String", recipe.getSignature());
        assertEquals("return 42;", recipe.getNewBody());
    }

    @Test
    void testConstructorWithNoSignature() {
        ReplaceMethodBodyRecipe recipe = new ReplaceMethodBodyRecipe(
            "com.example.MyClass",
            "myMethod",
            null,
            "System.out.println(\"Hello\");"
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myMethod", recipe.getMethodName());
        assertNull(recipe.getSignature());
        assertEquals("System.out.println(\"Hello\");", recipe.getNewBody());
    }

    @Test
    void testConstructorWithEmptyBody() {
        ReplaceMethodBodyRecipe recipe = new ReplaceMethodBodyRecipe(
            "com.example.MyClass",
            "myMethod",
            "",
            ""
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("myMethod", recipe.getMethodName());
        assertEquals("", recipe.getSignature());
        assertEquals("", recipe.getNewBody());
    }

    @Test
    void testDisplayName() {
        ReplaceMethodBodyRecipe recipe = new ReplaceMethodBodyRecipe(
            "com.example.MyClass",
            "myMethod",
            null,
            "return 0;"
        );
        
        assertEquals("Replace body of myMethod in com.example.MyClass", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        ReplaceMethodBodyRecipe recipe = new ReplaceMethodBodyRecipe(
            "com.example.MyClass",
            "myMethod",
            null,
            "return 0;"
        );
        
        assertEquals("Replaces the entire body of method myMethod in class com.example.MyClass.", 
            recipe.getDescription());
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        ReplaceMethodBodyRecipe recipe = new ReplaceMethodBodyRecipe(
            "com.example.MyClass",
            "myMethod",
            null,
            "return 0;"
        );
        
        assertNotNull(recipe.getVisitor());
    }

    @Test
    void testComplexBody() {
        ReplaceMethodBodyRecipe recipe = new ReplaceMethodBodyRecipe(
            "com.example.MyClass",
            "complexMethod",
            "int, int",
            "if (x > y) { return x; } else { return y; }"
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("complexMethod", recipe.getMethodName());
        assertEquals("int, int", recipe.getSignature());
        assertEquals("if (x > y) { return x; } else { return y; }", recipe.getNewBody());
    }
}

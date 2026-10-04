/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AddMethodRecipe.
 * 
 * @author Janusch Rentenatus
 */
class AddMethodRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        AddMethodRecipe recipe = new AddMethodRecipe(
            "com.example.MyClass",
            "newMethod",
            "void",
            "int count, String label",
            "public static",
            "return count + label.length();"
        );
        
        assertNotNull(recipe);
        assertEquals("com.example.MyClass", recipe.getClassName());
        assertEquals("newMethod", recipe.getMethodName());
        assertEquals("void", recipe.getReturnType());
        assertEquals("int count, String label", recipe.getParameters());
        assertEquals("public static", recipe.getModifiers());
        assertEquals("return count + label.length();", recipe.getBody());
    }

    @Test
    void testConstructorWithMinimalParameters() {
        AddMethodRecipe recipe = new AddMethodRecipe(
            "com.example.MyClass",
            "simpleMethod",
            "void",
            null,
            null,
            null
        );
        
        assertNotNull(recipe);
        assertNull(recipe.getParameters());
        assertNull(recipe.getModifiers());
        assertNull(recipe.getBody());
    }

    @Test
    void testConstructorWithNoParameters() {
        AddMethodRecipe recipe = new AddMethodRecipe(
            "com.example.MyClass",
            "noParamsMethod",
            "void",
            "",
            "private",
            ""
        );
        
        assertNotNull(recipe);
        assertEquals("", recipe.getParameters());
    }

    @Test
    void testDisplayName() {
        AddMethodRecipe recipe = new AddMethodRecipe(
            "com.example.MyClass",
            "testMethod",
            "String",
            "int x",
            "public",
            "return String.valueOf(x);"
        );
        
        assertEquals("Add method testMethod to com.example.MyClass", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        AddMethodRecipe recipe = new AddMethodRecipe(
            "com.example.MyClass",
            "testMethod",
            "String",
            "int x",
            "public",
            "return String.valueOf(x);"
        );
        
        assertEquals("Adds a new method testMethod to class or interface com.example.MyClass.",
                recipe.getDescription());
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        AddMethodRecipe recipe = new AddMethodRecipe(
            "com.example.MyClass",
            "testMethod",
            "void",
            "int x",
            null,
            null
        );
        
        assertNotNull(recipe.getVisitor());
    }

    @Test
    void testConstructorWithConstructorLike() {
        AddMethodRecipe recipe = new AddMethodRecipe(
            "com.example.MyClass",
            "MyClass",
            "MyClass",
            "String name",
            "public",
            "this.name = name;"
        );
        
        assertNotNull(recipe);
        assertEquals("MyClass", recipe.getMethodName());
        assertEquals("MyClass", recipe.getReturnType());
    }

    @Test
    void testConstructorWithGenericTypes() {
        AddMethodRecipe recipe = new AddMethodRecipe(
            "com.example.MyClass",
            "genericMethod",
            "List<String>",
            "Map<String, Integer> map, Set<Long> set",
            "public",
            "return new ArrayList<>(map.keySet());"
        );
        
        assertNotNull(recipe);
        assertEquals("List<String>", recipe.getReturnType());
        assertEquals("Map<String, Integer> map, Set<Long> set", recipe.getParameters());
    }
}

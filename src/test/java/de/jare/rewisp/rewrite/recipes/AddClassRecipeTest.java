/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AddClassRecipe.
 * 
 * @author Janusch Rentenatus
 */
class AddClassRecipeTest {

    @Test
    void testConstructorWithAllParameters() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyClass",
            "class",
            "private int field;\npublic void method() {}"
        );
        
        assertNotNull(recipe);
        assertEquals("com.example", recipe.getPackageName());
        assertEquals("MyClass", recipe.getClassName());
        assertEquals("class", recipe.getType());
        assertEquals("private int field;\npublic void method() {}", recipe.getBody());
    }

    @Test
    void testConstructorWithMinimalParameters() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.test",
            "SimpleClass",
            "class",
            null
        );
        
        assertNotNull(recipe);
        assertNull(recipe.getBody());
    }

    @Test
    void testConstructorWithAbstractType() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "AbstractClass",
            "abstract",
            "public abstract void abstractMethod();"
        );
        
        assertNotNull(recipe);
        assertEquals("abstract", recipe.getType());
    }

    @Test
    void testConstructorWithInterfaceType() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyInterface",
            "interface",
            "void method1();\nint method2();"
        );
        
        assertNotNull(recipe);
        assertEquals("interface", recipe.getType());
    }

    @Test
    void testConstructorWithEnumType() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyEnum",
            "enum",
            "VALUE1, VALUE2, VALUE3"
        );
        
        assertNotNull(recipe);
        assertEquals("enum", recipe.getType());
    }

    @Test
    void testDisplayName() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyClass",
            "class",
            null
        );
        
        assertEquals("Add class", recipe.getDisplayName());
    }

    @Test
    void testDescription() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyClass",
            "class",
            null
        );
        
        assertEquals("Creates a new class, enum, interface, or abstract class in a package.",
                recipe.getDescription());
    }

    @Test
    void testGetFullyQualifiedName() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example.subpackage",
            "MyClass",
            "class",
            null
        );
        
        assertEquals("com.example.subpackage.MyClass", recipe.getFullyQualifiedName());
    }

    @Test
    void testGetRelativePath() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example.subpackage",
            "MyClass",
            "class",
            null
        );
        
        assertEquals("com/example/subpackage/MyClass.java", recipe.getRelativePath());
    }

    @Test
    void testGetRelativePathWithSimplePackage() {
        AddClassRecipe recipe = new AddClassRecipe(
            "simple",
            "Test",
            "class",
            null
        );
        
        assertEquals("simple/Test.java", recipe.getRelativePath());
    }

    @Test
    void testGenerateSourceCodeSimpleClass() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "SimpleClass",
            "class",
            null
        );
        
        String sourceCode = recipe.generateSourceCode();
        
        assertNotNull(sourceCode);
        assertTrue(sourceCode.contains("package com.example;"));
        assertTrue(sourceCode.contains("class SimpleClass {"));
        assertTrue(sourceCode.contains("}"));
    }

    @Test
    void testGenerateSourceCodeWithBody() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyClass",
            "class",
            "private int field;\npublic void method() {}"
        );
        
        String sourceCode = recipe.generateSourceCode();
        
        assertNotNull(sourceCode);
        assertTrue(sourceCode.contains("package com.example;"));
        assertTrue(sourceCode.contains("class MyClass {"));
        assertTrue(sourceCode.contains("    private int field;"));
        assertTrue(sourceCode.contains("    public void method() {}"));
        assertTrue(sourceCode.contains("}"));
    }

    @Test
    void testGenerateSourceCodeAbstractClass() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "AbstractClass",
            "abstract",
            "public abstract void method();"
        );
        
        String sourceCode = recipe.generateSourceCode();
        
        assertNotNull(sourceCode);
        assertTrue(sourceCode.contains("abstract class AbstractClass {"));
    }

    @Test
    void testGenerateSourceCodeInterface() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyInterface",
            "interface",
            "void method1();\nint method2();"
        );
        
        String sourceCode = recipe.generateSourceCode();
        
        assertNotNull(sourceCode);
        assertTrue(sourceCode.contains("interface MyInterface {"));
        assertTrue(sourceCode.contains("    void method1();"));
        assertTrue(sourceCode.contains("    int method2();"));
    }

    @Test
    void testGenerateSourceCodeEnum() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyEnum",
            "enum",
            "VALUE1, VALUE2"
        );
        
        String sourceCode = recipe.generateSourceCode();
        
        assertNotNull(sourceCode);
        assertTrue(sourceCode.contains("enum MyEnum {"));
        assertTrue(sourceCode.contains("    VALUE1, VALUE2"));
    }

    @Test
    void testGenerateSourceCodeWithMultilineBody() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyClass",
            "class",
            "private int x;\n\npublic int getX() {\n    return x;\n}"
        );
        
        String sourceCode = recipe.generateSourceCode();
        
        assertNotNull(sourceCode);
        assertTrue(sourceCode.contains("private int x;"));
        assertTrue(sourceCode.contains("public int getX() {"));
        assertTrue(sourceCode.contains("    return x;"));
    }

    @Test
    void testGenerateSourceCodeEmptyBody() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "EmptyClass",
            "class",
            ""
        );
        
        String sourceCode = recipe.generateSourceCode();
        
        assertNotNull(sourceCode);
        assertTrue(sourceCode.contains("class EmptyClass {"));
        assertTrue(sourceCode.contains("}"));
        // Should not have extra newlines between braces
        assertFalse(sourceCode.contains("{\n\n}"));
    }

    @Test
    void testGenerateSourceCodeBodyWithBlankLines() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyClass",
            "class",
            "private int a;\n\nprivate int b;"
        );
        
        String sourceCode = recipe.generateSourceCode();
        
        assertNotNull(sourceCode);
        assertTrue(sourceCode.contains("private int a;"));
        assertTrue(sourceCode.contains("private int b;"));
        // Blank line should be preserved
        assertTrue(sourceCode.contains("\n\n"));
    }

    @Test
    void testGetVisitorReturnsNonNull() {
        AddClassRecipe recipe = new AddClassRecipe(
            "com.example",
            "MyClass",
            "class",
            null
        );
        
        assertNotNull(recipe.getVisitor());
    }
}

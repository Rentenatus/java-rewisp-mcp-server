/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClassSourceGenerator.
 * 
 * @author Janusch Rentenatus
 */
class ClassSourceGeneratorTest {

    @Test
    void testConstructorWithAllParameters() {
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
            "com.example",
            "MyEnum",
            "enum",
            "VALUE1, VALUE2, VALUE3"
        );
        
        assertNotNull(recipe);
        assertEquals("enum", recipe.getType());
    }

    @Test
    void testGetFullyQualifiedName() {
        ClassSourceGenerator recipe = new ClassSourceGenerator(
            "com.example.subpackage",
            "MyClass",
            "class",
            null
        );
        
        assertEquals("com.example.subpackage.MyClass", recipe.getFullyQualifiedName());
    }

    @Test
    void testGetRelativePath() {
        ClassSourceGenerator recipe = new ClassSourceGenerator(
            "com.example.subpackage",
            "MyClass",
            "class",
            null
        );
        
        assertEquals("com/example/subpackage/MyClass.java", recipe.getRelativePath());
    }

    @Test
    void testGetRelativePathWithSimplePackage() {
        ClassSourceGenerator recipe = new ClassSourceGenerator(
            "simple",
            "Test",
            "class",
            null
        );
        
        assertEquals("simple/Test.java", recipe.getRelativePath());
    }

    @Test
    void testGenerateSourceCodeSimpleClass() {
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
        ClassSourceGenerator recipe = new ClassSourceGenerator(
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
}

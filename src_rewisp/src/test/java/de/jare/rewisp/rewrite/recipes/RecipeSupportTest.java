/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * AP-4.7: Signatur-Parsing ist generics- und varargs-bewusst.
 *
 * @author Janusch Rentenatus
 */
class RecipeSupportTest {

    @Test
    void splitTopLevel_keepsGenericCommasTogether() {
        assertEquals(List.of("Map<String, Integer>", "int"),
                RecipeSupport.splitTopLevel("Map<String, Integer>, int"));
        assertEquals(List.of("Map<String, List<Integer>>", "String..."),
                RecipeSupport.splitTopLevel(" Map<String, List<Integer>> , String... "));
        assertTrue(RecipeSupport.splitTopLevel(null).isEmpty());
        assertTrue(RecipeSupport.splitTopLevel("  ").isEmpty());
    }

    @Test
    void eraseType_handlesGenericsArraysVarargsAndPackages() {
        assertEquals("Map", RecipeSupport.eraseType("java.util.Map<String, Integer>"));
        assertEquals("List[]", RecipeSupport.eraseType("List<String>[]"));
        assertEquals("int[]", RecipeSupport.eraseType("int..."));
        assertEquals("String[]", RecipeSupport.eraseType("java.lang.String ..."));
        assertEquals("Entry", RecipeSupport.eraseType("Map.Entry<K, V>"));
        assertEquals("", RecipeSupport.eraseType(null));
    }

    @Test
    void splitImportsTrimsAndSkipsBlanks() {
        assertEquals(List.of("java.util.List", "java.util.Map"), RecipeSupport.splitImports(" java.util.List, ,java.util.Map "));
        assertTrue(RecipeSupport.splitImports(null).isEmpty());
        assertTrue(RecipeSupport.splitImports("  ").isEmpty());
    }

    @Test
    void simpleNameIsTheLastSegment() {
        assertEquals("Foo", RecipeSupport.simpleName("a.b.Foo"));
        assertEquals("Foo", RecipeSupport.simpleName("Foo"));
    }}

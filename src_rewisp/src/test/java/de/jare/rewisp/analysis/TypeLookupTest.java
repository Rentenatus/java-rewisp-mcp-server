/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.support.compiler.VirtualFile;

/**
 * AP-5.x/3.6: Typ-Lookup mit Nested-Type-Support ({@code a.Outer.Inner} und {@code a.Outer$Inner}).
 *
 * @author Janusch Rentenatus
 */
class TypeLookupTest {

    private static CtModel model;

    @BeforeAll
    static void build() {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(new VirtualFile(
                "package a; public class Outer { public static class Inner { public static class Deep {} } }",
                "a/Outer.java"));
        launcher.addInputResource(new VirtualFile("package a; public class Plain {}", "a/Plain.java"));
        model = launcher.buildModel();
    }

    @Test
    void findsTopLevelTypes() {
        assertEquals("a.Plain", TypeLookup.find(model, "a.Plain").getQualifiedName());
    }

    @Test
    void findsNestedTypesWithDotAndDollarNotation() {
        assertEquals("a.Outer$Inner", TypeLookup.find(model, "a.Outer.Inner").getQualifiedName());
        assertEquals("a.Outer$Inner", TypeLookup.find(model, "a.Outer$Inner").getQualifiedName());
        assertNotNull(TypeLookup.find(model, "a.Outer.Inner.Deep"));
        assertNotNull(TypeLookup.find(model, "a.Outer$Inner$Deep"));
    }

    @Test
    void unknownOrBlankNamesYieldNull() {
        assertNull(TypeLookup.find(model, "a.Missing"));
        assertNull(TypeLookup.find(model, "a.Outer.Missing"));
        assertNull(TypeLookup.find(model, ""));
        assertNull(TypeLookup.find(model, null));
        assertNull(TypeLookup.find(null, "a.Plain"));
    }

    @Test
    void requireThrowsWithPrefixAndName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TypeLookup.require(model, "a.Missing", "Class not found: "));
        assertEquals("Class not found: a.Missing", e.getMessage());
    }

    @Test
    void topLevelNameIndexes() {
        assertEquals(java.util.Set.of("a.Outer", "a.Plain"), TypeLookup.topLevelNames(model));
        assertEquals(java.util.List.of("a.Plain"), TypeLookup.topLevelNamesBySimpleName(model, "Plain"));
        assertEquals(java.util.List.of(), TypeLookup.topLevelNamesBySimpleName(model, "Inner"));
        assertEquals(java.util.Set.of(), TypeLookup.topLevelNames(null));
    }
}

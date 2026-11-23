/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Syntaktische Pruefung von Typausdruecken und Feld-Modifikatoren (Praxistest-Fund: add_field schrieb Muell).
 *
 * @author Janusch Rentenatus
 */
class JavaNamesTypeTest {

    @Test
    void acceptsValidTypes() {
        for (String t : new String[] {"int", "String", "int[]", "String[][]", "java.util.List<String>",
                "Map<String, List<Integer>>", "List<? extends Number>", "Map<?, ? super Integer>",
                "Outer.Inner", "java.util.Map.Entry<String,Integer>[]", " List < String > "}) {
            assertTrue(JavaNames.isValidTypeExpression(t), t);
        }
    }

    @Test
    void rejectsInvalidTypes() {
        for (String t : new String[] {"", "  ", null, "List<String", "List<>", "int; int hacked", "Listlt;;Stringgt;;",
                "java.util.List&lt;String&gt;", "a..b", "1abc", "List<String>>", "int[", "class", "a.", "String x"}) {
            assertFalse(JavaNames.isValidTypeExpression(t), String.valueOf(t));
        }
    }

    @Test
    void validatesFieldModifiers() {
        assertTrue(JavaNames.isValidFieldModifiers(null));
        assertTrue(JavaNames.isValidFieldModifiers(""));
        assertTrue(JavaNames.isValidFieldModifiers("private static final"));
        assertTrue(JavaNames.isValidFieldModifiers("public, static"));
        assertFalse(JavaNames.isValidFieldModifiers("private private"));
        assertFalse(JavaNames.isValidFieldModifiers("abstract"));
        assertFalse(JavaNames.isValidFieldModifiers("private; int x"));
    }
}
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
 * Praxistest Q1/Q4/Q8: Parameterlisten, Methoden-Modifikatoren, Annotationsnamen und Code-Fragmente werden
 * syntaktisch geprueft, bevor sie in den Quelltext gelangen.
 *
 * @author Janusch Rentenatus
 */
class JavaNamesSnippetTest {

    @Test
    void parameterLists() {
        for (String ok : new String[] {"", "  ", "int a", "int a, String b", "Map<String, Integer> m, int... xs",
                "final List<? extends Number> l", "java.util.List<String>[] arr", "int[] a, long b"}) {
            assertTrue(JavaNames.isValidParameterList(ok), ok);
        }
        for (String bad : new String[] {"int", "int a) { System.exit(1); } void x(int b", "a b c", "int a,", ",int a",
                "int a; int b", "int 1a", "Map<String int> m", "void a"}) {
            assertFalse(JavaNames.isValidParameterList(bad), bad);
        }
    }

    @Test
    void methodModifiers() {
        assertTrue(JavaNames.isValidMethodModifiers(null));
        assertTrue(JavaNames.isValidMethodModifiers("public static final"));
        assertTrue(JavaNames.isValidMethodModifiers("protected abstract"));
        assertFalse(JavaNames.isValidMethodModifiers("public private"));
        assertFalse(JavaNames.isValidMethodModifiers("abstract final"));
        assertFalse(JavaNames.isValidMethodModifiers("abstract static"));
        assertFalse(JavaNames.isValidMethodModifiers("public public"));
        assertFalse(JavaNames.isValidMethodModifiers("volatile"));
    }

    @Test
    void annotationNames() {
        assertTrue(JavaNames.isValidAnnotationName("Deprecated"));
        assertTrue(JavaNames.isValidAnnotationName("@Override"));
        assertTrue(JavaNames.isValidAnnotationName("java.lang.SuppressWarnings"));
        assertFalse(JavaNames.isValidAnnotationName("Not A Name"));
        assertFalse(JavaNames.isValidAnnotationName("A(\"x\")"));
        assertFalse(JavaNames.isValidAnnotationName(null));
    }

    @Test
    void balancedSnippets() {
        assertTrue(JavaNames.isBalancedSnippet(null));
        assertTrue(JavaNames.isBalancedSnippet("return a[0] + (b * 2);"));
        assertTrue(JavaNames.isBalancedSnippet("if (x) { y(); } else { z(\"}\"); } // }"));
        assertTrue(JavaNames.isBalancedSnippet("String s = \"\"\"\n  } {\n  \"\"\"; char c = '}';"));
        assertTrue(JavaNames.isBalancedSnippet("/* } */ int a;"));
        assertFalse(JavaNames.isBalancedSnippet("return 1; } void injected() {"));
        assertFalse(JavaNames.isBalancedSnippet("}\n    void injected() {"));
        assertFalse(JavaNames.isBalancedSnippet("foo(1, 2"));
        assertFalse(JavaNames.isBalancedSnippet("String s = \"open;"));
        assertFalse(JavaNames.isBalancedSnippet("/* open"));
        assertFalse(JavaNames.isBalancedSnippet("(]"));
    }

    @Test
    void expressionSnippets() {
        assertTrue(JavaNames.isExpressionSnippet("new ArrayList<>()"));
        assertTrue(JavaNames.isExpressionSnippet("\"a;b\""));
        assertTrue(JavaNames.isExpressionSnippet("() -> { int a = 1; return a; }"));
        assertTrue(JavaNames.isExpressionSnippet(null));
        assertFalse(JavaNames.isExpressionSnippet("1; int hacked"));
        assertFalse(JavaNames.isExpressionSnippet("\"x\") int hacked; @SuppressWarnings(\"y\""));
    }
}
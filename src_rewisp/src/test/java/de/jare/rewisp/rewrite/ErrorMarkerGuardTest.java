/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Praxistest Q8: Ein Edit, der OpenRewrite-Fehlermarker neu in den Quelltext bringt, wird abgelehnt.
 *
 * @author Janusch Rentenatus
 */
class ErrorMarkerGuardTest {

    private static final String MARKED =
            "class A { /*~~(Only variable declarations may be part of a method declaration's parameter list:intint)~~>*/void f(int a) {} }";

    @Test
    void newMarkerIsRejectedWithDetail() {
        RewriteException e = assertThrows(RewriteException.class,
                () -> OpenRewriteEngineImpl.rejectNewErrorMarkers(Path.of("A.java"), "class A { void f() {} }", MARKED));
        assertTrue(e.getMessage().contains("Only variable declarations"), e.getMessage());
        assertTrue(e.getMessage().contains("Nothing was written"), e.getMessage());
    }

    @Test
    void preexistingMarkerIsNotBlamedOnTheEdit() {
        assertDoesNotThrow(() -> OpenRewriteEngineImpl.rejectNewErrorMarkers(Path.of("A.java"), MARKED, MARKED));
    }

    @Test
    void cleanResultPasses() {
        assertDoesNotThrow(() -> OpenRewriteEngineImpl.rejectNewErrorMarkers(Path.of("A.java"), "class A {}", "class A { int x; }"));
    }
}
/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JavaNamesTest {

    @Test
    void validIdentifiers() {
        assertTrue(JavaNames.isValidIdentifier("Foo"));
        assertTrue(JavaNames.isValidIdentifier("_x1"));
        assertTrue(JavaNames.isValidIdentifier("$Gen"));
        assertTrue(JavaNames.isValidIdentifier("Größe"));
    }

    @Test
    void invalidIdentifiers() {
        assertFalse(JavaNames.isValidIdentifier(null));
        assertFalse(JavaNames.isValidIdentifier(""));
        assertFalse(JavaNames.isValidIdentifier("1abc"));
        assertFalse(JavaNames.isValidIdentifier("a-b"));
        assertFalse(JavaNames.isValidIdentifier("a/b"));
        assertFalse(JavaNames.isValidIdentifier("..\\x"));
        assertFalse(JavaNames.isValidIdentifier("class"));
    }

    @Test
    void packageNames() {
        assertTrue(JavaNames.isValidPackageName("com.example.build"));
        assertTrue(JavaNames.isValidPackageName("single"));
        assertFalse(JavaNames.isValidPackageName("com..example"));
        assertFalse(JavaNames.isValidPackageName(".com"));
        assertFalse(JavaNames.isValidPackageName("com."));
        assertFalse(JavaNames.isValidPackageName("com/../../x"));
        assertFalse(JavaNames.isValidPackageName("com.int.x"));
        assertFalse(JavaNames.isValidPackageName(""));
    }
}

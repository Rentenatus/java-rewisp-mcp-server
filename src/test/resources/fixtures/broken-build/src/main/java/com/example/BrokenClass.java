package com.example;

/**
 * Klasse mit Syntaxfehler - soll Compile-Fehler verursachen.
 * Wird für Tests verwendet, die Compile-Validierung testen.
 */
public class BrokenClass {

    public void methodWithError() {
        // Absichtlicher Syntaxfehler: fehlendes Semikolon
        int x = 5
    }
}

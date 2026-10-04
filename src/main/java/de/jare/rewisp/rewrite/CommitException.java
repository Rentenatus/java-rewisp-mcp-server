/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

/**
 * Exception für Fehler beim Commit-Vorgang.
 */
public class CommitException extends Exception {
    public CommitException(String code, String message) {
        super(code + ": " + message);
    }
    public CommitException(String code, String message, Throwable cause) {
        super(code + ": " + message, cause);
    }
}

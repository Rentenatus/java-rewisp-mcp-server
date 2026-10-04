/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

/**
 * Fehler-Codes für Build-Resolution.
 * Wird von BuildResolutionException verwendet, um die Art des Fehlers zu klassifizieren.
 */
public enum BuildResolutionErrorCode {
    /** Maven Goal ist nicht in der Allowlist */
    GOAL_NOT_ALLOWED,
    /** Maven-Process Timeout */
    TIMEOUT,
    /** Maven Build fehlgeschlagen */
    BUILD_FAILED,
    /** I/O Fehler */
    IO_ERROR,
    /** Prozess unterbrochen */
    INTERRUPTED,
    /** Output-Limit überschritten */
    OUTPUT_LIMIT,
    /** Build-System nicht unterstützt */
    UNSUPPORTED
}

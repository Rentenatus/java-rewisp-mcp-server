/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

/**
 * Error codes for build resolution.
 * Used by BuildResolutionException to classify the kind of error.
 */
public enum BuildResolutionErrorCode {
    /** Maven goal is not in the allowlist */
    GOAL_NOT_ALLOWED,
    /** Maven-Process Timeout */
    TIMEOUT,
    /** Maven Build fehlgeschlagen */
    BUILD_FAILED,
    /** I/O Fehler */
    IO_ERROR,
    /** Prozess unterbrochen */
    INTERRUPTED,
    /** Output limit exceeded */
    OUTPUT_LIMIT,
    /** Build system not supported */
    UNSUPPORTED
}

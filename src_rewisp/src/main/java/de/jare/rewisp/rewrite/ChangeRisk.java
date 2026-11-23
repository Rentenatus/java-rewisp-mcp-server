/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

/**
 * Risk classification for OpenRewrite recipes.
 * Used for the allowlist and user warnings.
 */
public enum ChangeRisk {
    /**
     * Only cosmetic changes (e.g. code formatting).
     * No behavior is changed.
     */
    LOW,
    
    /**
     * Changes with medium risk (e.g. rewriting API calls).
     * Can change behavior, but usually safe.
     */
    MEDIUM,
    
    /**
     * High-risk changes (e.g. changing method signatures).
     * Can cause breaking changes.
     */
    HIGH,
    
    /**
     * Requires explicit approval by the user.
     * Potentially dangerous changes.
     */
    REQUIRES_EXPLICIT_APPROVAL
}

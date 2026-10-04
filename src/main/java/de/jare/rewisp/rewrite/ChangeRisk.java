/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

/**
 * Risikoklassifizierung für OpenRewrite Recipes.
 * Wird für die Allowlist und Benutzer-Warnungen verwendet.
 */
public enum ChangeRisk {
    /**
     * Nur kosmetische Änderungen (z.B. Code-Formatierung).
     * Kein Verhalten wird geändert.
     */
    LOW,
    
    /**
     * Änderungen mit mittlerem Risiko (z.B. API-Aufrufe umstellen).
     * Kann Verhalten ändern, aber normalerweise sicher.
     */
    MEDIUM,
    
    /**
     * Hohe Risiko-Änderungen (z.B. Methodensignaturen ändern).
     * Kann Breaking Changes verursachen.
     */
    HIGH,
    
    /**
     * Erfordert explizite Freigabe durch den Benutzer.
     * Potenziell gefährliche Änderungen.
     */
    REQUIRES_EXPLICIT_APPROVAL
}

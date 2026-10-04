/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

/**
 * Diagnose-Codes für Build-Operationen.
 * Wird von BuildDiagnostic verwendet, um die Art der Diagnose zu klassifizieren.
 */
public enum BuildDiagnosticCode {
    /** Plugin fehlt (z.B. maven-dependency-plugin) */
    MISSING_PLUGIN,
    /** Build-Konfiguration ungültig */
    INVALID_CONFIGURATION,
    /** Dependency nicht verfügbar */
    MISSING_DEPENDENCY,
    /** Network-Zugriff deaktiviert */
    NETWORK_ACCESS_DENIED,
    /** Module nicht gefunden */
    MODULE_NOT_FOUND,
    /** Classpath können nicht aufgelöst werden */
    CLASSPATH_RESOLUTION_FAILED,
    /** Source-Roots nicht gefunden */
    SOURCE_ROOTS_NOT_FOUND,
    /** Maven-Version inkompatibel */
    INADEQUATE_MAVEN_VERSION,
    /** Gradle Wrapper fehlt */
    GRADLE_WRAPPER_MISSING,
    /** Gradle Build-Datei fehlt */
    GRADLE_BUILD_FILE_MISSING,
    /** Abhängigkeiten-Leer */
    DEPENDENCIES_EMPTY
}

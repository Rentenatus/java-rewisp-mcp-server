/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

/**
 * Diagnostic codes for build operations.
 * Used by BuildDiagnostic to classify the kind of diagnostic.
 */
public enum BuildDiagnosticCode {
    /** Gradle wrapper missing */
    GRADLE_WRAPPER_MISSING,
    /** Gradle build file missing */
    GRADLE_BUILD_FILE_MISSING,
    /** Dependencies empty */
    DEPENDENCIES_EMPTY,
    /** The classpath of a Maven module could not be resolved (the module is in the context without classpath) */
    MODULE_CLASSPATH_FAILED,
    /** There are more modules than are resolved (see {@code -Dbuild.maxModules}) or a declared module is missing */
    MODULES_INCOMPLETE
}

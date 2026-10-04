package de.jare.rewisp.snapshot;

/**
 * Classpath-Aufloesungsmodus fuer die Spoon-Analyse.
 *
 * <ul>
 *   <li>{@code FULL_CLASSPATH} — Classpath aus BuildContext, noClasspath=false</li>
 *   <li>{@code NO_CLASSPATH} — noClasspath=true, kein Classpath</li>
 *   <li>{@code PARTIAL_CLASSPATH} — noClasspath=true, aber verfuegbare Classpath-Eintraege werden gesetzt</li>
 * </ul>
 */
public enum ResolutionMode {
    FULL_CLASSPATH,
    NO_CLASSPATH,
    PARTIAL_CLASSPATH
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verwaltet ein Set von Dateien, die durch REWRITE-Operationen
 * geaendert wurden und damit in Spoon nicht mehr synchron sind.
 * 
 * <p>Dieser Manager dient als "Refresh-Tracker" zwischen REWRITE und Spoon.
 * Jede REWRITE-Operation (Preview/Apply) markiert die geaenderten Dateien.
 * Spoon-Tools koennen dann pruefen, ob ihre betroffenen Dateien stale sind
 * und nur dann Spoon neu aufbauen.
 * 
 * <p>Verwendung:
 * <ul>
 *   <li>REWRITE-Tools (Preview/Apply) rufen {@link #onRewriteComplete(RewriteResult)} auf</li>
 *   <li>Spoon-Tools rufen {@link #needsRebuild(Path)} oder {@link #needsRebuild()} auf</li>
 *   <li>Spoon-Tools rufen {@link #markSynced()} auf, nach dem Spoon neu gebaut wurde</li>
 * </ul>
 */
public final class RewriteSyncManager {

    /**
     * Set von Dateipfaden, die durch REWRITE geaendert wurden
     * und damit nicht mehr mit dem Spoon-Modell synchron sind.
     * Thread-safe durch ConcurrentHashMap.
     */
    private final Set<Path> staleFiles = ConcurrentHashMap.newKeySet();

    /**
     * Wird von REWRITE-Tools aufgerufen, um geaenderte Dateien zu markieren.
     * 
     * @param result Das Ergebnis der REWRITE-Operation
     */
    public void onRewriteComplete(RewriteResult result) {
        if (result == null) {
            return;
        }
        
        // Alle geaenderten Dateien als stale markieren
        if (result.changedFiles() != null) {
            staleFiles.addAll(result.changedFiles().keySet());
        }
        
        // Alle geloeschten Dateien als stale markieren
        if (result.deletedFiles() != null) {
            staleFiles.addAll(result.deletedFiles());
        }
        
        // Alle neuen Dateien als stale markieren
        if (result.createdFiles() != null) {
            staleFiles.addAll(result.createdFiles());
        }
    }

    /**
     * Prueft, ob eine spezifische Datei stale ist (nicht synchron mit Spoon).
     * 
     * <p>Verwendung: Einzel-Datei-Tools wie {@code inspect_class}, 
     * {@code inspect_method}, etc.
     * 
     * @param file Der zu pruefende Dateipfad
     * @return true, wenn die Datei stale ist und Spoon neu aufgebaut werden muss
     */
    public boolean needsRebuild(Path file) {
        if (file == null) {
            return false;
        }
        return staleFiles.contains(file);
    }

    /**
     * Prueft, ob ueberhaupt Dateien stale sind.
     * 
     * <p>Verwendung: Multi-Datei-Tools wie {@code find_references},
     * {@code list_classes}, etc.
     * 
     * @return true, wenn mindestens eine Datei stale ist
     */
    public boolean needsRebuild() {
        return !staleFiles.isEmpty();
    }

    /**
     * Setzt das stale-Set zurueck, nachdem Spoon neu gebaut wurde.
     * 
     * <p>Wird von Spoon-Tools aufgerufen, nachdem sie Spoon aufgrund
     * von stale Dateien neu aufgebaut haben.
     */
    public void markSynced() {
        staleFiles.clear();
    }

    /**
     * Gibt die Anzahl der stale Dateien zurueck (für Monitoring/Debugging).
     * 
     * @return die Anzahl der stale Dateien
     */
    public int getStaleFileCount() {
        return staleFiles.size();
    }

    /**
     * Gibt eine Kopie des stale-Set zurueck (für Testing/Debugging).
     * 
     * @return eine Kopie des Set von stale Dateien
     */
    public Set<Path> getStaleFiles() {
        return Set.copyOf(staleFiles);
    }

    /**
     * Setzt das stale-Set komplett zurueck (für Tests oder Reset).
     */
    public void reset() {
        staleFiles.clear();
    }
}

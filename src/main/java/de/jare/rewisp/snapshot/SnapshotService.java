package de.jare.rewisp.snapshot;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.event.EditEventBus;
import de.jare.rewisp.event.SnapshotEvent;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

public class SnapshotService {

    private final ContentHasher hasher = new ContentHasher();
    private static final int MAX_RETRIES = 3;

    /**
     * Nimmt einen konsistenten Projekt-Snapshot auf.
     * Erkennt Raced-Conditions (Dateiaenderung waehrend Aufnahme)
     * und wiederholt bis MAX_RETRIES.
     *
     * <p>Veröffentlicht Events:
     * <ul>
     *   <li>SNAPSHOT_RACE_DETECTED - wenn Race-Condition erkannt wird</li>
     *   <li>SNAPSHOT_COMPLETED - wenn Snapshot erfolgreich erstellt wurde</li>
     * </ul>
     */
    public ProjectSnapshot captureStable(ProjectEntry entry, long sequence) throws IOException {
        Path sourceRoot = entry.originalProjectDir();
        EditEventBus eventBus = EditEventBus.getInstance();

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            Map<Path, FileSnapshot> firstRead = readAllFiles(sourceRoot);
            Map<Path, String> firstHashes = extractHashes(firstRead);

            // Zweite Lesung zur Race-Erkennung
            Map<Path, FileSnapshot> secondRead = readAllFiles(sourceRoot);
            Map<Path, String> secondHashes = extractHashes(secondRead);

            if (firstHashes.equals(secondHashes)) {
                String rootHash = hasher.rootHash(firstHashes);
                SnapshotId id = new SnapshotId(entry.name(), sequence, rootHash);
                ProjectSnapshot snapshot = new ProjectSnapshot(id, entry.name(), firstRead, Instant.now());
                
                // Event für erfolgreichen Snapshot veröffentlichen
                eventBus.publish(new SnapshotEvent(
                        this, entry.name(), id, snapshot, attempt + 1, false
                ));
                
                return snapshot;
            }

            // Race erkannt — Event veröffentlichen und wiederholen
            eventBus.publish(new SnapshotEvent(
                    this, entry.name(), null, null, attempt + 1, true
            ));
        }

        throw new IOException("SNAPSHOT_RACE: could not capture stable snapshot after "
                + MAX_RETRIES + " attempts for project " + entry.name());
    }

    /**
     * Inkrementeller Snapshot-Refresh: ersetzt nur die angegebenen Dateien
     * (Pfade relativ zum Projekt-Root) im Basis-Snapshot durch frische
     * Platteninhalte. Dateien, die nicht mehr existieren, werden aus dem
     * Snapshot entfernt.
     *
     * <p>Deutlich schneller als {@link #captureStable}, weil nur die
     * dreckigen Dateien gelesen werden — ausreichend fuer Edits mit Scope
     * auf eine Zieldatei. Breite Scopes (projektweiter Rewrite-Scope,
     * z.B. rename mit Caller-Updates oder move_class) brauchen weiterhin
     * captureStable.</p>
     *
     * @param entry das Projekt
     * @param base der bestehende Snapshot als Basis
     * @param relativeFiles die zu aktualisierenden Dateien (relativ)
     * @param sequence die Snapshot-Sequenznummer
     * @return der aktualisierte Snapshot mit neuer SnapshotId
     * @throws IOException wenn eine Datei nicht gelesen werden kann
     */
    public ProjectSnapshot refreshFiles(ProjectEntry entry, ProjectSnapshot base,
                                        Set<Path> relativeFiles, long sequence) throws IOException {
        Path sourceRoot = entry.originalProjectDir();
        Map<Path, FileSnapshot> files = new LinkedHashMap<>(base.files());
        for (Path rel : relativeFiles) {
            Path abs = sourceRoot.resolve(rel);
            if (Files.isRegularFile(abs) && isSourceFile(abs, sourceRoot)) {
                String content = Files.readString(abs);
                String hash = hasher.sha256(content);
                files.put(rel, new FileSnapshot(rel, content, hash,
                        content.length(), Files.getLastModifiedTime(abs).toMillis(),
                        false, false));
            } else {
                files.remove(rel);
            }
        }
        String rootHash = hasher.rootHash(extractHashes(files));
        SnapshotId id = new SnapshotId(entry.name(), sequence, rootHash);
        return new ProjectSnapshot(id, entry.name(), files, Instant.now());
    }

    /**
     * Überlagert gegebene Inhalte (Pfade relativ zum Projekt-Root) direkt
     * auf einen Basis-Snapshot — ohne Plattenzugriff. Wird für aktive
     * Transaktionen benutzt: Dort liegen gepufferte Änderungen nur im
     * Speicher; ohne Overlay wäre jeder folgende Edit einer Transaktion
     * blind für seine Vorgänger und würde sie mit dem Platten-Zustand
     * überschreiben.
     */
    public ProjectSnapshot applyContents(ProjectEntry entry, ProjectSnapshot base,
                                         Map<Path, String> contents, long sequence) {
        Map<Path, FileSnapshot> files = new LinkedHashMap<>(base.files());
        for (Map.Entry<Path, String> e : contents.entrySet()) {
            Path rel = e.getKey();
            String content = e.getValue();
            files.put(rel, new FileSnapshot(rel, content, hasher.sha256(content),
                    content.length(), System.currentTimeMillis(), false, false));
        }
        String rootHash = hasher.rootHash(extractHashes(files));
        SnapshotId id = new SnapshotId(entry.name(), sequence, rootHash);
        return new ProjectSnapshot(id, entry.name(), files, Instant.now());
    }

    protected Map<Path, FileSnapshot> readAllFiles(Path root) throws IOException {
        Map<Path, FileSnapshot> files = new LinkedHashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java") && isSourceFile(file, root)) {
                    Path relative = root.relativize(file).normalize();
                    try {
                        String content = Files.readString(file);
                        String hash = hasher.sha256(content);
                        files.put(relative, new FileSnapshot(
                                relative, content, hash,
                                content.length(), attrs.lastModifiedTime().toMillis(),
                                false, false));
                    } catch (IOException e) {
                        // Datei kann zwischen Walk und Read geloescht worden sein
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private boolean isSourceFile(Path file, Path root) {
        String s = file.toString();
        return !s.contains("target") && !s.contains("build")
                && !s.contains(".git") && !s.contains(".gradle");
    }

    private Map<Path, String> extractHashes(Map<Path, FileSnapshot> files) {
        Map<Path, String> hashes = new LinkedHashMap<>();
        files.forEach((p, fs) -> hashes.put(p, fs.contentHash()));
        return hashes;
    }

    /**
     * Liefert den Dateiinhalt aus einem Snapshot fuer eine gegebene Datei.
     * Architekturvorgabe: Spoon liest nur von REWRITE.
     */
    public String getFileContent(ProjectSnapshot snapshot, Path relativePath) {
        if (snapshot == null || relativePath == null) return null;
        FileSnapshot fs = snapshot.files().get(relativePath);
        return fs != null ? fs.content() : null;
    }

    /**
     * Liefert den Dateiinhalt fuer einen Typen.
     */
    public String getFileContentForType(ProjectSnapshot snapshot, String qualifiedName) {
        if (snapshot == null || qualifiedName == null || qualifiedName.isBlank()) return null;
        String pkgPath = qualifiedName.replace(".", "/");
        for (Path path : snapshot.files().keySet()) {
            if (path.toString().endsWith(pkgPath + ".java")) {
                return snapshot.files().get(path).content();
            }
        }
        return null;
    }
}

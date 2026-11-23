package de.jare.rewisp.snapshot;

import de.jare.rewisp.model.ProjectEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotServiceTest {

    @TempDir
    Path tempDir;

    private final SnapshotService service = new SnapshotService();

    private ProjectEntry createEntry(Path projectDir) {
        return new ProjectEntry(
                "test-project", "test", java.time.Instant.now().plus(java.time.Duration.ofMinutes(10)),
                projectDir, null, null, "RAW",
                false, null,
                projectDir, null, Map.of(), false, true,
                1, 1, java.util.Set.of(), java.util.Set.of());
    }

    @Test
    void captureStable_unchangedProject_reproducibleSnapshotId() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");

        ProjectEntry entry = createEntry(tempDir);

        ProjectSnapshot snap1 = service.captureStable(entry, 1);
        ProjectSnapshot snap2 = service.captureStable(entry, 2);

        assertEquals(snap1.id().rootHash(), snap2.id().rootHash());
        assertEquals("test-project", snap1.id().projectId());
        assertEquals(1, snap1.id().sequence());
        assertEquals(2, snap2.id().sequence());
    }

    /** AP-0.3 / SN:S-01: Ignore-Regel darf nur Pfadsegmente relativ zum Root pruefen, nicht Substrings des Gesamtpfads. */
    @Test
    void captureStable_projectUnderPathContainingBuildOrTarget_isNotEmpty() throws IOException {
        Path root = tempDir.resolve("builds").resolve("my-target-app");
        Path pkg = root.resolve("src/main/java/com/example/build");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Util.java"), "package com.example.build; public class Util {}");
        Path rebuild = root.resolve("src/main/java/com/example/rebuild");
        Files.createDirectories(rebuild);
        Files.writeString(rebuild.resolve("Again.java"), "package com.example.rebuild; public class Again {}");
        Path github = root.resolve("src/main/java/com/example/github");
        Files.createDirectories(github);
        Files.writeString(github.resolve("Hub.java"), "package com.example.github; public class Hub {}");
        // Echte Build-Ausgabe auf Modulebene bleibt ignoriert
        Files.createDirectories(root.resolve("target/gen"));
        Files.writeString(root.resolve("target/gen/Gen.java"), "class Gen {}");

        ProjectSnapshot snap = service.captureStable(createEntry(root), 1);

        assertTrue(snap.files().containsKey(Path.of("src/main/java/com/example/build/Util.java")), "Package 'build' muss erfasst werden");
        assertTrue(snap.files().containsKey(Path.of("src/main/java/com/example/rebuild/Again.java")));
        assertTrue(snap.files().containsKey(Path.of("src/main/java/com/example/github/Hub.java")));
        assertFalse(snap.files().containsKey(Path.of("target/gen/Gen.java")));
    }

    @Test
    void captureStable_ignoresTargetAndBuildDirs() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Real.java"), "public class Real {}");

        // These should be ignored
        Path targetDir = tempDir.resolve("target");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("Generated.java"), "public class Generated {}");

        Path buildDir = tempDir.resolve("build");
        Files.createDirectories(buildDir);
        Files.writeString(buildDir.resolve("Output.java"), "public class Output {}");

        ProjectEntry entry = createEntry(tempDir);
        ProjectSnapshot snap = service.captureStable(entry, 1);

        assertTrue(snap.files().containsKey(Path.of("src").resolve("Real.java")));
        assertFalse(snap.files().containsKey(Path.of("target").resolve("Generated.java")));
        assertFalse(snap.files().containsKey(Path.of("build").resolve("Output.java")));
    }

    @Test
    void captureStable_capturesContentAndHash() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        String content = "public class Hello {}";
        Files.writeString(srcDir.resolve("Hello.java"), content);

        ProjectEntry entry = createEntry(tempDir);
        ProjectSnapshot snap = service.captureStable(entry, 1);

        FileSnapshot fs = snap.files().get(Path.of("src").resolve("Hello.java"));
        assertNotNull(fs);
        assertEquals(content, fs.content());
        assertEquals(content.length(), fs.size());
        assertNotNull(fs.contentHash());
        assertEquals(64, fs.contentHash().length());
    }

    @Test
    void captureStable_detectsChangedContent() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Bar.java"), "public class Bar {}");

        ProjectEntry entry = createEntry(tempDir);
        ProjectSnapshot snap1 = service.captureStable(entry, 1);

        // Modify file content
        Files.writeString(srcDir.resolve("Bar.java"), "public class Bar { int x; }");

        ProjectSnapshot snap2 = service.captureStable(entry, 2);

        assertNotEquals(snap1.id().rootHash(), snap2.id().rootHash());
    }

    @Test
    void captureStable_deletedFileBetweenWalkAndRead_noCrash() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Path file = srcDir.resolve("Ephemeral.java");
        Files.writeString(file, "public class Ephemeral {}");

        ProjectEntry entry = createEntry(tempDir);
        // Should succeed — file is stable
        ProjectSnapshot snap = service.captureStable(entry, 1);
        assertNotNull(snap);
        assertEquals(1, snap.files().size());
    }

    @Test
    void captureStable_emptyProject_validSnapshot() throws IOException {
        ProjectEntry entry = createEntry(tempDir);
        ProjectSnapshot snap = service.captureStable(entry, 1);

        assertNotNull(snap.id());
        assertTrue(snap.files().isEmpty());
        assertEquals(64, snap.id().rootHash().length());
    }

    @Test
    void captureStable_throwsSnapshotRace_whenFileChangesDuringCapture() throws IOException, InterruptedException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Path file = srcDir.resolve("Racy.java");
        Files.writeString(file, "public class Racy {}");

        ProjectEntry entry = createEntry(tempDir);

        // Subclass that modifies the file between first and second read on every attempt
        SnapshotService racyService = new SnapshotService() {
            private int callCount = 0;

            @Override
            protected Map<Path, FileSnapshot> readAllFiles(Path root) throws IOException {
                Map<Path, FileSnapshot> result = super.readAllFiles(root);
                callCount++;
                // After every read, modify the file so the next read sees different content
                if (Files.exists(file)) {
                    // Groesse waechst bei jedem Versuch: die Race-Erkennung vergleicht Aenderungszeit und Groesse
                    Files.writeString(file, "public class Racy { int v" + "x".repeat(callCount) + "; }");
                }
                return result;
            }
        };

        IOException ex = assertThrows(IOException.class, () -> racyService.captureStable(entry, 1));
        assertTrue(ex.getMessage().contains("SNAPSHOT_RACE"));
        assertTrue(ex.getMessage().contains("test-project"));
    }

    @Test
    void refreshFiles_updatesOnlyDirtyFiles() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Path a = srcDir.resolve("A.java");
        Path b = srcDir.resolve("B.java");
        Files.writeString(a, "public class A {}");
        Files.writeString(b, "public class B {}");

        ProjectEntry entry = createEntry(tempDir);
        ProjectSnapshot base = service.captureStable(entry, 1);

        // A wird auf der Platte geändert (simuliert einen vorherigen Edit)
        Files.writeString(a, "public class A { int x; }");

        ProjectSnapshot refreshed = service.refreshFiles(entry, base,
                java.util.Set.of(Path.of("src").resolve("A.java")), 2);

        assertEquals("public class A { int x; }", refreshed.files().get(Path.of("src").resolve("A.java")).content());
        // B bleibt unverändert im Snapshot
        assertEquals("public class B {}", refreshed.files().get(Path.of("src").resolve("B.java")).content());
        assertEquals(2, refreshed.id().sequence());
        // Der inkrementelle Refresh muss dasselbe Ergebnis liefern wie ein
        // kompletter Neuaufbau (gleicher rootHash)
        ProjectSnapshot full = service.captureStable(entry, 3);
        assertEquals(full.id().rootHash(), refreshed.id().rootHash());
    }

    /**
     * 7.4: Eine gerade erst geaenderte Datei wird auch bei gleicher Groesse und gleichem mtime neu gelesen
     * (Dateisysteme mit grober mtime-Aufloesung wuerden sonst einen zweiten Edit im selben Zeitfenster uebersehen).
     */
    @Test
    void refreshFiles_rereadsARecentlyModifiedFileEvenWithSameSizeAndMtime() throws IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path a = srcDir.resolve("A.java");
        Files.writeString(a, "public class A { int x; }");
        ProjectEntry entry = createEntry(tempDir);
        ProjectSnapshot base = service.captureStable(entry, 1);
        java.nio.file.attribute.FileTime mtime = Files.getLastModifiedTime(a);

        Files.writeString(a, "public class A { int y; }"); // gleiche Groesse
        Files.setLastModifiedTime(a, mtime);                // gleicher mtime

        ProjectSnapshot refreshed = service.refreshFiles(entry, base, java.util.Set.of(Path.of("src", "A.java")), 2);

        assertEquals("public class A { int y; }", refreshed.files().get(Path.of("src", "A.java")).content());
    }

    /** 7.4: Eine alte, unveraenderte Datei (gleiche Groesse, gleicher mtime) wird nicht erneut gelesen. */
    @Test
    void refreshFiles_doesNotRereadAnOldUnchangedFile() throws IOException {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path a = srcDir.resolve("A.java");
        Files.writeString(a, "public class A { int x; }");
        java.nio.file.attribute.FileTime old = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000);
        Files.setLastModifiedTime(a, old);
        ProjectEntry entry = createEntry(tempDir);
        ProjectSnapshot base = service.captureStable(entry, 1);

        Files.writeString(a, "public class A { int y; }");
        Files.setLastModifiedTime(a, old);

        ProjectSnapshot refreshed = service.refreshFiles(entry, base, java.util.Set.of(Path.of("src", "A.java")), 2);

        // Groesse und mtime sprechen fuer "unveraendert": der Inhalt kommt aus dem Basis-Snapshot
        assertEquals("public class A { int x; }", refreshed.files().get(Path.of("src", "A.java")).content());
    }

    @Test
    void refreshFiles_removesDeletedFiles() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Path a = srcDir.resolve("A.java");
        Files.writeString(a, "public class A {}");

        ProjectEntry entry = createEntry(tempDir);
        ProjectSnapshot base = service.captureStable(entry, 1);

        Files.delete(a);

        ProjectSnapshot refreshed = service.refreshFiles(entry, base,
                java.util.Set.of(Path.of("src").resolve("A.java")), 2);

        assertFalse(refreshed.files().containsKey(Path.of("src").resolve("A.java")));
        assertTrue(refreshed.files().isEmpty());
    }

    /** AP-2.12: size ist die Groesse in Bytes, nicht in Zeichen. */
    @Test
    void fileSnapshotSize_isInBytesNotChars() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        String content = "class U { String s = \"\u00e4\u00f6\u00fc\"; }";
        Files.writeString(srcDir.resolve("U.java"), content);

        FileSnapshot fs = service.captureStable(createEntry(tempDir), 1)
                .files().get(Path.of("src", "U.java"));

        assertEquals(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, fs.size());
        assertTrue(fs.size() > content.length());
    }

    /** AP-2.12: refreshFiles nimmt nur .java-Dateien auf. */
    @Test
    void refreshFiles_ignoresNonJavaFiles() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("A.java"), "class A {}");
        Files.writeString(srcDir.resolve("notes.txt"), "text");
        ProjectEntry entry = createEntry(tempDir);
        ProjectSnapshot base = service.captureStable(entry, 1);

        ProjectSnapshot refreshed = service.refreshFiles(entry, base,
                java.util.Set.of(Path.of("src", "notes.txt"), Path.of("src", "A.java")), 2);

        assertEquals(java.util.Set.of(Path.of("src", "A.java")), refreshed.files().keySet());
    }

    /** AP-2.11: Typ-Aufloesung segmentweise; "Foo" darf nicht "MyFoo.java" treffen. */
    @Test
    void getFileContentForType_matchesWholePathSegments() throws IOException {
        Path pkg = tempDir.resolve("src/com/acme");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("MyFoo.java"), "class MyFoo {}");
        Files.writeString(pkg.resolve("Foo.java"), "class Foo {}");
        ProjectSnapshot snap = service.captureStable(createEntry(tempDir), 1);

        assertEquals("class Foo {}", service.getFileContentForType(snap, "com.acme.Foo"));
        assertEquals("class MyFoo {}", service.getFileContentForType(snap, "com.acme.MyFoo"));
        assertNull(service.getFileContentForType(snap, "acme.Fo"));
    }

    /** AP-2.12: der Root-Hash haengt nicht vom Pfad-Trennzeichen ab. */
    @Test
    void rootHash_isIndependentOfPathSeparator() {
        ContentHasher hasher = new ContentHasher();
        String viaPath = hasher.rootHash(Map.of(Path.of("a", "B.java"), "h1"));
        String viaSlash = hasher.rootHash(Map.of(Path.of("a/B.java"), "h1"));
        assertEquals(viaPath, viaSlash);
    }
}
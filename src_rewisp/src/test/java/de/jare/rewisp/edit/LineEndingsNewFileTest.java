/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.model.ProjectEntry;

/**
 * AP-1.18 / ED:E-55: Neue Dateien uebernehmen die Zeilenenden ihrer Nachbarn.
 *
 * @author Janusch Rentenatus
 */
class LineEndingsNewFileTest {

    @TempDir
    Path root;

    @Test
    void newFileNextToCrlfFile_usesCrlf() throws Exception {
        Path dir = root.resolve("src/p");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("A.java"), "package p;\r\nclass A {}\r\n");

        assertEquals("\r\n", LineEndings.detectForNewFile(dir.resolve("B.java")));
    }

    @Test
    void newFileInNewPackage_usesNeighborInAncestorDirectory() throws Exception {
        Path dir = root.resolve("src/p");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("A.java"), "package p;\r\nclass A {}\r\n");

        assertEquals("\r\n", LineEndings.detectForNewFile(root.resolve("src/p/sub/deeper/C.java")));
    }

    @Test
    void withoutNeighbors_defaultsToLf() throws Exception {
        // tief genug verschachtelt, damit die Suche (max. 4 Ebenen aufwaerts) das @TempDir nicht verlaesst
        // und fremde .java-Dateien im Temp-Verzeichnis das Ergebnis nicht beeinflussen
        Path deep = Files.createDirectories(root.resolve("x/y/z/src"));
        assertEquals("\n", LineEndings.detectForNewFile(deep.resolve("X.java")));
    }

    @Test
    void writeFile_forNewFile_writesCrlfInCrlfProject() throws Exception {
        Path dir = root.resolve("src/p");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("A.java"), "package p;\r\nclass A {}\r\n");
        ProjectEntry entry = new ProjectEntry("n", "n", java.time.Instant.now().plusSeconds(60), root, null, null,
                "RAW", false, null, root, null, Map.of(), false, true, 1, 1, Set.of(), Set.of());

        new EditManager(root.resolveSibling("bk-" + root.getFileName()))
                .writeFile(entry, dir.resolve("B.java"), "package p;\nclass B {\n}\n", null);

        String written = Files.readString(dir.resolve("B.java"));
        assertTrue(written.contains("\r\n") && !written.replace("\r\n", "").contains("\n"), written);
    }
}

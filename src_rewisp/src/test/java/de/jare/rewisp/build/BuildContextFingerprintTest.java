/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;

/**
 * AP-5.25 / BT:I-03: Der Build-Context-Cache wird nicht nur ueber das Dirty-Flag,
 * sondern ueber einen Fingerprint ALLER Build-Dateien (inkl. Modul-POMs/-Gradle-Dateien)
 * geprueft. Aendert sich eine Modul-Build-Datei, ohne dass der Watcher das Dirty-Flag
 * setzt, darf kein veralteter Kontext ausgeliefert werden.
 *
 * @author Janusch Rentenatus
 */
class BuildContextFingerprintTest {

    @TempDir
    Path tempDir;

    private static final class CountingResolver implements BuildResolver {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean supports(Path projectRoot) {
            return true;
        }

        @Override
        public BuildContext resolve(Path projectRoot, ResolveOptions options) {
            calls.incrementAndGet();
            return new BuildContext("MAVEN", List.of(),
                    new BuildFingerprint("MAVEN", ("x" + calls.get()).repeat(40), "y".repeat(40), "z".repeat(40), "t".repeat(40), ""), List.of());
        }
    }

    private static Path multiModuleProject(Path base) throws Exception {
        Path p = base.resolve("p");
        Files.createDirectories(p.resolve("module-a/src"));
        Files.writeString(p.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                        + "<artifactId>p</artifactId><version>1</version>"
                        + "<packaging>pom</packaging><modules><module>module-a</module></modules></project>");
        Files.writeString(p.resolve("module-a/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><parent><groupId>g</groupId>"
                        + "<artifactId>p</artifactId><version>1</version></parent><artifactId>module-a</artifactId></project>");
        Files.writeString(p.resolve("module-a/src/A.java"), "class A {}\n");
        return p;
    }

    @Test
    void moduleBuildFileChangeInvalidatesCacheEvenWithoutDirtyFlag() throws Exception {
        Path p = multiModuleProject(tempDir);
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(p.toString(), null, null, false, true);
        var state = mgr.getProjectState(entry.name());

        CountingResolver maven = new CountingResolver();
        BuildContextService service = new BuildContextService(maven, new CountingResolver(), System::currentTimeMillis);

        service.ensure(entry, state);
        service.ensure(entry, state);
        assertEquals(1, maven.calls.get(), "zweiter Aufruf muss den Cache treffen");

        // Modul-pom aendern, OHNE das Dirty-Flag zu setzen (Watcher laeuft im Test nicht)
        Files.writeString(p.resolve("module-a/pom.xml"),
                Files.readString(p.resolve("module-a/pom.xml")).replace("module-a", "module-a-renamed"));
        service.ensure(entry, state);
        assertEquals(2, maven.calls.get(),
                "Aenderung an einer Modul-Build-Datei muss den Cache invalidieren (Fingerprint-Vergleich)");
        mgr.remove(entry.name());
    }

    @Test
    void unchangedBuildFilesKeepTheCache() throws Exception {
        Path p = multiModuleProject(tempDir);
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(p.toString(), null, null, false, true);
        var state = mgr.getProjectState(entry.name());

        CountingResolver maven = new CountingResolver();
        BuildContextService service = new BuildContextService(maven, new CountingResolver(), System::currentTimeMillis);

        service.ensure(entry, state);
        // nur eine Quelldatei aendern — Build-Dateien bleiben gleich
        Files.writeString(p.resolve("module-a/src/A.java"), "class A { int x; }\n");
        service.ensure(entry, state);
        assertEquals(1, maven.calls.get(), "Quelldatei-Aenderung darf den Build-Cache nicht invalidieren");
        mgr.remove(entry.name());
    }
}

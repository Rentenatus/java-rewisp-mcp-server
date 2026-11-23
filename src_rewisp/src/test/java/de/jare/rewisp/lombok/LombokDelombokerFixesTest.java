/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectLoader;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.util.ProjectCleanupHelper;

/**
 * AP-5.8/5.9: Klassenpfad-Cache-Schluessel, Gradle-Ausgabe zeilenweise und nur existierende Eintraege,
 * Original-Clone wird beim Aufraeumen eines delomboked Projekts mit geloescht.
 *
 * @author Janusch Rentenatus
 */
class LombokDelombokerFixesTest {

    @TempDir
    Path tempDir;

    @Test
    void cacheKey_differsForSameNamedProjectsInDifferentDirectories() throws Exception {
        Path a = Files.createDirectories(tempDir.resolve("a/app"));
        Path b = Files.createDirectories(tempDir.resolve("b/app"));

        assertNotEquals(LombokDelomboker.cacheKey(a), LombokDelomboker.cacheKey(b));
        assertEquals(LombokDelomboker.cacheKey(a), LombokDelomboker.cacheKey(a));
    }

    @Test
    void cacheKey_changesWhenTheBuildFileChanges() throws Exception {
        Path app = Files.createDirectories(tempDir.resolve("app"));
        Files.writeString(app.resolve("pom.xml"), "<project>1</project>");
        String before = LombokDelomboker.cacheKey(app);

        Files.writeString(app.resolve("pom.xml"), "<project>2</project>");

        assertNotEquals(before, LombokDelomboker.cacheKey(app));
    }

    @Test
    void existingEntries_ignoresNoiseAndReadsAllLines() throws Exception {
        Path jar1 = Files.createFile(tempDir.resolve("a.jar"));
        Path jar2 = Files.createFile(tempDir.resolve("b.jar"));
        String output = "WARNING: deprecated Gradle features were used\n"
                + jar1 + File.pathSeparator + tempDir.resolve("missing.jar") + "\n"
                + "\n"
                + jar2 + File.pathSeparator + jar1 + "\n";

        assertEquals(jar1 + File.pathSeparator + jar2, LombokDelomboker.existingEntries(output));
        assertEquals("", LombokDelomboker.existingEntries("just a warning"));
        assertNull(LombokDelomboker.existingEntries(null));
    }

    @Test
    void cleanup_removesTheOriginalCloneOfADelombokedProject() throws Exception {
        Path base = Path.of(System.getProperty("java.io.tmpdir"), "java-mcp-projects");
        Path original = Files.createDirectories(base.resolve("fixes-test_" + System.nanoTime()));
        Files.writeString(original.resolve("A.java"), "class A {}");
        Path delombokDir = Files.createDirectories(tempDir.resolve("delomboked")); // ausserhalb: wird nicht angefasst
        ProjectEntry entry = new ProjectEntry("p", "p", Instant.now(), delombokDir, null, null, "RAW",
                true, null, original, "https://x/p.zip", Map.of(), false, true, 1, 1, Set.of(), Set.of());

        ProjectCleanupHelper.cleanupProjectDirectory(entry, new ProjectLoader(), new LombokDelomboker());

        assertFalse(Files.exists(original), "the original clone must not leak into the temp directory");
    }
}

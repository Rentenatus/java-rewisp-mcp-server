/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.model.ProjectLoadException;
import de.jare.rewisp.snapshot.WorkspaceRegistry;

/**
 * AP-5.2 Rest (RT:P-07): (1) Klone/Downloads landen in der Temp-Basis von
 * {@link ProjectLoader} und duerfen bei gesetzter Allowlist nicht daran scheitern
 * (Temp-Basis implizit erlauben). (2) Lokale Quellen duerfen erst nach der
 * Allowlist-Pruefung verarbeitet werden — ein nicht erlaubtes lokales Archiv darf
 * nicht schon entpackt werden.
 *
 * @author Janusch Rentenatus
 */
class WorkspaceAllowlistTempTest {

    @TempDir
    Path root;

    private static Path project(Path base, String dir) throws Exception {
        Path src = base.resolve(dir).resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}\n");
        return base.resolve(dir);
    }

    private static Path zipProject(Path base, String name) throws Exception {
        Path zip = base.resolve(name + ".zip");
        try (OutputStream os = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry(name + "/src/A.java"));
            zos.write("class A {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zip;
    }

    @Test
    void tempBaseIsImplicitlyAllowed_whenAllowlistIsSet() throws Exception {
        Path allowed = root.resolve("allowed");
        Files.createDirectories(allowed);
        // Projekt liegt in der Temp-Basis (wohin Klone/Downloads entpackt werden)
        Path inTemp = project(ProjectLoader.tempBase(), "temp-proj");

        ProjectManager mgr = new ProjectManager();
        mgr.setWorkspaceRegistry(new WorkspaceRegistry(List.of(allowed.toString())));

        ProjectEntry entry = mgr.load(inTemp.toString(), null, null, false, true);
        assertNotNull(mgr.find(entry.name()), "Projekt aus der Temp-Basis muss trotz Allowlist ladbar sein");
        mgr.remove(entry.name());
    }

    @Test
    void disallowedLocalArchiveIsRejectedBeforeExtraction() throws Exception {
        Path allowed = root.resolve("allowed");
        Files.createDirectories(allowed);
        Path forbidden = root.resolve("forbidden");
        Files.createDirectories(forbidden);
        Path zip = zipProject(forbidden, "evilproj");

        long tempEntriesBefore = Files.list(ProjectLoader.tempBase()).count();

        ProjectManager mgr = new ProjectManager();
        mgr.setWorkspaceRegistry(new WorkspaceRegistry(List.of(allowed.toString())));

        ProjectLoadException e = assertThrows(ProjectLoadException.class,
                () -> mgr.load(zip.toString(), null, null, false, true));
        assertEquals("SECURITY_ERROR", e.getType(), e.getMessage());

        long tempEntriesAfter = Files.list(ProjectLoader.tempBase()).count();
        assertEquals(tempEntriesBefore, tempEntriesAfter,
                "nicht erlaubtes Archiv darf nicht entpackt werden");
    }
}

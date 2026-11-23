/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.model.ProjectLoadException;

/**
 * AP-3.15 und AP-3.17: Alias-Kollisionen werden abgelehnt, ein exakter Projektname hat Vorrang vor
 * einem Alias, und das Neuladen abgelaufener Projekte uebernimmt {@code editable}.
 *
 * @author Janusch Rentenatus
 */
class ProjectManagerAliasTest {

    @TempDir
    Path root;

    private Path project(String dir) throws Exception {
        Path src = root.resolve(dir).resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "class A {}\n");
        return root.resolve(dir);
    }

    @Test
    void aliasOfAnotherProject_isRejected() throws Exception {
        ProjectManager mgr = new ProjectManager();
        mgr.load(project("one").toString(), "shared", null, false, true);

        ProjectLoadException e = assertThrows(ProjectLoadException.class,
                () -> mgr.load(project("two").toString(), "shared", null, false, true));

        assertEquals("INVALID_PARAMS", e.getType());
        assertEquals("one", mgr.find("shared").name());
        mgr.removeAll();
    }

    @Test
    void aliasEqualToOtherProjectsName_isRejected() throws Exception {
        ProjectManager mgr = new ProjectManager();
        mgr.load(project("alpha").toString(), null, null, false, true);

        assertThrows(ProjectLoadException.class,
                () -> mgr.load(project("beta").toString(), "alpha", null, false, true));
        mgr.removeAll();
    }

    @Test
    void reloadingSameProjectWithItsOwnAlias_isAllowed() throws Exception {
        ProjectManager mgr = new ProjectManager();
        mgr.load(project("same").toString(), "mine", null, false, true);

        ProjectEntry again = mgr.load(project("same").toString(), "mine", null, false, true);

        assertEquals("same", again.name());
        mgr.removeAll();
    }

    @Test
    void reloadExpired_keepsEditableFalse() throws Exception {
        ProjectManager mgr = new ProjectManager();
        mgr.load(project("ro").toString(), null, Instant.now().minusSeconds(5), false, false);
        mgr.markExpired();
        assertTrue(mgr.find("ro").expired());

        List<ProjectEntry> reloaded = mgr.reloadExpired();

        assertEquals(1, reloaded.size());
        assertFalse(reloaded.get(0).editable(), "editable=false muss beim Neuladen erhalten bleiben");
        mgr.removeAll();
    }
}

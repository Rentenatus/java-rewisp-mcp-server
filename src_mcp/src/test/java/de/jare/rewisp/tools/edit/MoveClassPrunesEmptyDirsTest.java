/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * V3: nach move_class bleibt kein leeres altes Paketverzeichnis zurueck; nicht leere Pakete und der Source-Root bleiben.
 *
 * @author Janusch Rentenatus
 */
class MoveClassPrunesEmptyDirsTest {

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    private void move(Path src, String cls, String pkg) throws Exception {
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(src.toString(), null, null, true, true);
        var tool = new MoveClassTool(mgr, new EditManager(backups), RewriteTestSupport.newEngine());
        Map<String, Object> a = new HashMap<>();
        a.put("name", entry.name());
        a.put("className", cls);
        a.put("newPackage", pkg);
        var r = tool.handle(null, new CallToolRequest("move_class", a));
        assertFalse(r.isError(), r.content().toString());
        mgr.remove(entry.name());
    }

    @Test
    void emptyOldPackageDirectoriesAreRemovedButNotTheSourceRoot() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("com/old/deep"));
        Files.writeString(src.resolve("com/old/deep/Only.java"), "package com.old.deep;\n\nclass Only {\n}\n");

        move(src, "com.old.deep.Only", "com.fresh");

        assertTrue(Files.exists(src.resolve("com/fresh/Only.java")));
        assertFalse(Files.exists(src.resolve("com/old")), "leere Ahnen-Pakete (com/old/deep, com/old) entfallen");
        assertTrue(Files.isDirectory(src.resolve("com")), "com enthaelt jetzt fresh");
        assertTrue(Files.isDirectory(src));
    }

    @Test
    void nonEmptyOldPackageIsKept() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("com/old"));
        Files.writeString(src.resolve("com/old/A.java"), "package com.old;\n\nclass A {\n}\n");
        Files.writeString(src.resolve("com/old/B.java"), "package com.old;\n\nclass B {\n}\n");

        move(src, "com.old.A", "com.fresh");

        assertTrue(Files.exists(src.resolve("com/old/B.java")));
        assertTrue(Files.exists(src.resolve("com/fresh/A.java")));
    }
}
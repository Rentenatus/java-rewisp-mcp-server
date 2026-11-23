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
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * AP-1.14 / ED:E-51: {@code move_class} darf das Ziel nur innerhalb des Projekts
 * anlegen. Ein absoluter {@code newPackage}-Pfad (z. B. {@code C:\evil} oder
 * {@code /tmp/evil}) darf nicht als Ziel-Verzeichnis verwendet werden; Package-Namen
 * müssen gültige Java-Identifier sein.
 *
 * @author Janusch Rentenatus
 */
class MoveClassContainmentTest {

    @TempDir
    Path tempDir;

    private ProjectEntry loadProject(ProjectManager manager, String dirName) throws Exception {
        Path src = tempDir.resolve(dirName + "/src");
        Files.createDirectories(src.resolve("a"));
        Files.writeString(src.resolve("a/A.java"), "package a;\npublic class A {}\n");
        return manager.load(src.toString(), null, null, true, true);
    }

    private CallToolResult move(ProjectManager mgr, String projectName, String newPackage) throws Exception {
        MoveClassTool tool = new MoveClassTool(mgr, new EditManager(tempDir.resolve("backups")),
                RewriteTestSupport.newEngine());
        Map<String, Object> args = new HashMap<>();
        args.put("name", projectName);
        args.put("className", "a.A");
        args.put("newPackage", newPackage);
        return tool.handle(null, new CallToolRequest("move_class", args));
    }

    @Test
    void absoluteNewPackageIsRejectedAndWritesNothing() throws Exception {
        ProjectManager manager = new ProjectManager();
        ProjectEntry entry = loadProject(manager, "proj");
        Path src = tempDir.resolve("proj/src");
        Path evil = tempDir.resolve("evil");

        CallToolResult r = move(manager, entry.name(), evil.toString());

        assertTrue(r.isError(), "absolute newPackage must be rejected: " + r.content());
        assertTrue(r.content().toString().contains("SECURITY_ERROR"), r.content().toString());
        assertFalse(Files.exists(evil.resolve("A.java")), "no file may be written outside the project");
        assertTrue(Files.exists(src.resolve("a/A.java")), "original file must stay in place");
        manager.remove(entry.name());
    }

    @Test
    void invalidPackageNameIsRejected() throws Exception {
        ProjectManager manager = new ProjectManager();
        ProjectEntry entry = loadProject(manager, "proj2");
        Path src = tempDir.resolve("proj2/src");

        CallToolResult r = move(manager, entry.name(), "not a package!");

        assertTrue(r.isError(), "invalid package name must be rejected: " + r.content());
        assertTrue(r.content().toString().contains("SECURITY_ERROR")
                || r.content().toString().contains("INVALID"), r.content().toString());
        assertTrue(Files.exists(src.resolve("a/A.java")), "original file must stay in place");
        manager.remove(entry.name());
    }
}

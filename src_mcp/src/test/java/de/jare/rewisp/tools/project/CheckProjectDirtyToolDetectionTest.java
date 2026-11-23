/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.tools.edit.EditLineTool;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * AP-2.9/2.10: check_project_dirty trennt externe Aenderungen von eigenen Edits und erkennt geaenderte
 * Build-Dateien (pom.xml), die nicht im Snapshot stehen.
 *
 * @author Janusch Rentenatus
 */
class CheckProjectDirtyToolDetectionTest {

    @TempDir
    Path tempDir;
    @TempDir
    Path backups; // ausserhalb des Projekts, sonst zaehlen die Backup-Kopien als neue Dateien
    private ProjectManager mgr;
    private ProjectEntry entry;
    private Path source;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<groupId>t</groupId><artifactId>dirty-demo</artifactId><version>1</version></project>");
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/p"));
        source = pkg.resolve("A.java");
        Files.writeString(source, "package p;\npublic class A {\n    int v = 1;\n}\n");
        Files.writeString(pkg.resolve("B.java"), "package p;\npublic class B {}\n");
        entry = mgr.load(tempDir.toString(), null, null, false, true);
    }

    @AfterEach
    void cleanup() {
        mgr.remove(entry.name());
    }

    private String check() throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        var result = new CheckProjectDirtyTool(mgr).handle(null, new CallToolRequest("check_project_dirty", args));
        assertFalse(result.isError(), result.content().toString());
        return result.content().toString();
    }

    @Test
    void unchangedProjectHasNoExternalChanges() throws Exception {
        String out = check();

        assertTrue(out.contains("No external changes"), out);
        assertFalse(out.contains("Build files changed"), out);
    }

    @Test
    void externalChangeIsReported() throws Exception {
        Files.writeString(source, "package p;\npublic class A {\n    int v = 2;\n}\n");

        String out = check();

        assertTrue(out.contains("External changes detected"), out);
        assertTrue(out.contains("A.java"), out);
    }

    @Test
    void ownEditsAreNotExternalChanges() throws Exception {
        EditLineTool editLine = new EditLineTool(mgr, new EditManager(backups));
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("file", source.toString());
        args.put("lineNumber", 3);
        args.put("newContent", "    int v = 3;");
        assertFalse(editLine.handle(null, new CallToolRequest("edit_line", args)).isError());

        String out = check();

        assertTrue(out.contains("No external changes"), out);
        assertTrue(out.contains("Edited through rewisp since load:** 1"), out);
    }

    @Test
    void changedPomIsDetectedAndInvalidatesTheBuildContext() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                + "<groupId>t</groupId><artifactId>dirty-demo</artifactId><version>2</version></project>");

        String out = check();

        assertTrue(out.contains("Build files changed:** pom.xml"), out);
        assertTrue(mgr.getProjectState(entry.name()).dirtyState().isBuildModelDirty());
        assertTrue(check().contains("Build files changed:** pom.xml"), "stays reported until the build context is refreshed");
    }
}

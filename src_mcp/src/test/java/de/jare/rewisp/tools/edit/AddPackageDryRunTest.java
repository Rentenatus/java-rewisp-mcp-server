/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

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
 * 8.4: {@code add_package} mit {@code dryRun} schreibt nichts und zeigt, was entstehen wuerde.
 *
 * @author Janusch Rentenatus
 */
class AddPackageDryRunTest {

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    private CallToolResult run(ProjectManager mgr, EditManager em, ProjectEntry e, boolean info, boolean dry) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("name", e.name());
        args.put("packageName", "p.sub");
        if (info) args.put("generatePackageInfo", true);
        if (dry) args.put("dryRun", true);
        return new AddPackageTool(mgr, em).handle(null, new CallToolRequest("add_package", args));
    }

    @Test
    void dryRunWritesNothingAndRealRunCreatesThePackage() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("proj"));
        Files.writeString(root.resolve("pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>t</artifactId><version>1</version>"
                + "<properties><maven.compiler.source>21</maven.compiler.source>"
                + "<maven.compiler.target>21</maven.compiler.target></properties></project>");
        Files.writeString(Files.createDirectories(root.resolve("src/main/java/p")).resolve("A.java"),
                "package p;\npublic class A {}\n");
        ProjectManager mgr = new ProjectManager();
        EditManager em = new EditManager(backups);
        mgr.setEditManager(em);
        ProjectEntry e = mgr.load(root.toString(), null, null, true, true);
        try {
            Path pkg = root.resolve("src/main/java/p/sub");

            CallToolResult dry = run(mgr, em, e, true, true);
            String t = dry.content().toString();
            assertFalse(dry.isError(), t);
            assertTrue(t.contains("DRY RUN") && t.contains("mkdir p/sub") || t.contains("mkdir p\\sub"), t);
            assertTrue(t.contains("package-info.java") && t.contains("+ package p.sub;"), t);
            assertFalse(Files.exists(pkg), "dry run must not create the directory");

            CallToolResult real = run(mgr, em, e, true, false);
            assertFalse(real.isError(), real.content().toString());
            assertTrue(Files.exists(pkg.resolve("package-info.java")));
        } finally {
            mgr.remove(e.name());
        }
    }
}

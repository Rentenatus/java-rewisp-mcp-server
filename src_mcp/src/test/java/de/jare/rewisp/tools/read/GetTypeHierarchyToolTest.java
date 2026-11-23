/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.read;

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
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * AP-3.9: get_type_hierarchy ist rekursiv, kennt Interfaces von Interfaces und geerbte Interfaces und
 * findet Subtypen ueber Superklasse und Interfaces (auch geschachtelte Typen).
 *
 * @author Janusch Rentenatus
 */
class GetTypeHierarchyToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private ProjectEntry entry;
    private GetTypeHierarchyTool tool;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        tool = new GetTypeHierarchyTool(mgr);
        Path pkg = Files.createDirectories(tempDir.resolve("src/p"));
        Files.writeString(pkg.resolve("Base.java"), "package p;\npublic interface Base {}\n");
        Files.writeString(pkg.resolve("Mid.java"), "package p;\npublic interface Mid extends Base {}\n");
        Files.writeString(pkg.resolve("Abs.java"), "package p;\npublic abstract class Abs implements Mid {}\n");
        Files.writeString(pkg.resolve("Impl.java"),
                "package p;\npublic class Impl extends Abs {\n  public static class Nested extends Impl {}\n}\n");
        Files.writeString(pkg.resolve("Direct.java"), "package p;\npublic class Direct implements Base {}\n");
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
    }

    @AfterEach
    void cleanup() {
        mgr.remove(entry.name());
    }

    private String hierarchy(String className) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", className);
        var result = tool.handle(null, new CallToolRequest("get_type_hierarchy", args));
        assertFalse(result.isError(), result.content().toString());
        return result.content().toString();
    }

    @Test
    void classShowsInheritedAndSuperInterfaces() {
        String out = hierarchy("p.Impl");

        assertTrue(out.contains("p.Abs"), out);
        assertTrue(out.contains("p.Mid"), "interface inherited from Abs: " + out);
        assertTrue(out.contains("p.Base"), "interface of interface: " + out);
    }

    @Test
    void interfaceSubtypesAreFoundRecursivelyViaInterfacesAndSuperclasses() {
        String out = hierarchy("p.Base");

        assertTrue(out.contains("p.Mid"), out);
        assertTrue(out.contains("p.Direct"), out);
        assertTrue(out.contains("p.Abs"), "class implementing Mid: " + out);
        assertTrue(out.contains("p.Impl"), "class extending Abs: " + out);
        assertTrue(out.contains("p.Impl$Nested"), "nested subtype: " + out);
    }

    @Test
    void nestedTypeCanBeInspected() {
        String out = hierarchy("p.Impl.Nested");

        assertTrue(out.contains("p.Impl"), out);
        assertTrue(out.contains("p.Abs"), out);
    }
}

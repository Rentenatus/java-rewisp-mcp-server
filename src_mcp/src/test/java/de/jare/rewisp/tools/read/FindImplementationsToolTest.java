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
 * AP-3.10: find_implementations trennt konkrete Klassen, abstrakte Zwischenklassen und Sub-Interfaces
 * und findet auch geschachtelte Implementierungen.
 *
 * @author Janusch Rentenatus
 */
class FindImplementationsToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private ProjectEntry entry;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        Path pkg = Files.createDirectories(tempDir.resolve("src/p"));
        Files.writeString(pkg.resolve("Base.java"), "package p;\npublic interface Base {}\n");
        Files.writeString(pkg.resolve("Mid.java"), "package p;\npublic interface Mid extends Base {}\n");
        Files.writeString(pkg.resolve("Abs.java"), "package p;\npublic abstract class Abs implements Mid {}\n");
        Files.writeString(pkg.resolve("Impl.java"),
                "package p;\npublic class Impl extends Abs {\n  public static class Nested implements Base {}\n}\n");
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
    }

    @AfterEach
    void cleanup() {
        mgr.remove(entry.name());
    }

    @Test
    void separatesConcreteAbstractAndSubInterfaces() {
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "p.Base");
        var result = new FindImplementationsTool(mgr).handle(null, new CallToolRequest("find_implementations", args));
        assertFalse(result.isError(), result.content().toString());
        String out = result.content().toString();

        int concrete = out.indexOf("Concrete implementations");
        int abstractSection = out.indexOf("Abstract classes");
        int subIfaces = out.indexOf("Sub-interfaces");
        assertTrue(concrete >= 0 && abstractSection > concrete && subIfaces > abstractSection, out);
        assertTrue(out.indexOf("p.Impl`") > concrete && out.indexOf("p.Impl`") < abstractSection, out);
        assertTrue(out.contains("p.Impl$Nested"), "nested implementation: " + out);
        assertTrue(out.indexOf("p.Abs`") > abstractSection && out.indexOf("p.Abs`") < subIfaces, out);
        assertTrue(out.indexOf("p.Mid`") > subIfaces, out);
    }
}

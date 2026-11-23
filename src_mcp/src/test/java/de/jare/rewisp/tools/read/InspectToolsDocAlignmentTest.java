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
import de.jare.rewisp.tools.project.ProjectMetadataTool;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

/**
 * AP-3.20: list_classes (innere Klassen, Unterpackages), project_metadata (Module, Original-Verzeichnis),
 * inspect_class (Konstruktoren, Annotation-Attribute) und inspect_method (volle Signatur, Original-Body).
 *
 * @author Janusch Rentenatus
 */
class InspectToolsDocAlignmentTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private ProjectEntry entry;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        Path pkg = Files.createDirectories(tempDir.resolve("src/p/sub"));
        Files.writeString(tempDir.resolve("src/p/Svc.java"),
                "package p;\n"
                + "import java.io.IOException;\n"
                + "@SuppressWarnings(\"unchecked\")\n"
                + "public class Svc {\n"
                + "    public Svc() {}\n"
                + "    protected Svc(int x) {}\n"
                + "    public static final class Inner {}\n"
                + "    public static synchronized <T extends Comparable<T>> T max(T a, T... rest) throws IOException {\n"
                + "        // keep this comment\n"
                + "        return a;\n"
                + "    }\n"
                + "}\n");
        Files.writeString(pkg.resolve("Deep.java"), "package p.sub;\npublic class Deep {}\n");
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
    }

    @AfterEach
    void cleanup() {
        mgr.remove(entry.name());
    }

    private String call(io.modelcontextprotocol.spec.McpSchema.Tool unused, String tool, Map<String, Object> extra,
                        java.util.function.Function<CallToolRequest, io.modelcontextprotocol.spec.McpSchema.CallToolResult> h) {
        Map<String, Object> args = new HashMap<>(extra);
        args.put("name", entry.name());
        var result = h.apply(new CallToolRequest(tool, args));
        assertFalse(result.isError(), result.content().toString());
        return result.content().toString();
    }

    @Test
    void listClassesSupportsNestedAndSubPackages() {
        var tool = new ListClassesTool(mgr);
        String exact = call(null, "list_classes", Map.of("package", "p"), r -> tool.handle(null, r));
        assertTrue(exact.contains("p.Svc") && !exact.contains("p.sub.Deep") && !exact.contains("Inner"), exact);

        String recursive = call(null, "list_classes", Map.of("package", "p", "recursive", "true"), r -> tool.handle(null, r));
        assertTrue(recursive.contains("p.sub.Deep"), recursive);

        String nested = call(null, "list_classes", Map.of("includeNested", "true"), r -> tool.handle(null, r));
        assertTrue(nested.contains("p.Svc$Inner"), nested);
    }

    @Test
    void metadataShowsModulesAndOriginalDirectory() {
        var tool = new ProjectMetadataTool(mgr);
        String out = call(null, "project_metadata", Map.of(), r -> tool.handle(null, r));

        assertTrue(out.contains("**Modules**"), out);
        assertTrue(out.contains(entry.originalProjectDir().toString()), out);
    }

    @Test
    void inspectClassListsConstructorsAnnotationAttributesAndNestedTypes() {
        var tool = new InspectClassTool(mgr);
        String out = call(null, "inspect_class", Map.of("className", "p.Svc"), r -> tool.handle(null, r));

        assertTrue(out.contains("## Constructors (2)"), out);
        assertTrue(out.contains("protected Svc(int x)"), out);
        assertTrue(out.contains("@SuppressWarnings(\"unchecked\")"), out);
        assertTrue(out.contains("p.Svc$Inner"), out);
        assertTrue(out.contains("synchronized") && out.contains("throws IOException"), out);
    }

    @Test
    void inspectMethodShowsFullSignatureAndOriginalBody() {
        var tool = new InspectMethodTool(mgr);
        String out = call(null, "inspect_method", Map.of("className", "p.Svc", "methodName", "max"),
                r -> tool.handle(null, r));

        assertTrue(out.contains("public static synchronized <T extends Comparable<T>>"), out);
        assertTrue(out.contains("T... rest"), out);
        assertTrue(out.contains("throws IOException"), out);
        assertTrue(out.contains("// keep this comment"), "original body keeps comments: " + out);
    }
}

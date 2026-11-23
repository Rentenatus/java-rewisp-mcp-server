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
 * AP-4.15: Nach {@code move_class} wird fuer Typen im alten Package der Top-Level-Typ importiert
 * (nicht der verschachtelte Typ), Typparameter erzeugen keinen Import.
 *
 * @author Janusch Rentenatus
 */
class MoveClassNestedImportTest {

    @TempDir
    Path tempDir;

    @Test
    void nestedTypeOfOldPackage_importsTopLevelType() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("a"));
        Files.createDirectories(src.resolve("b"));
        Files.writeString(src.resolve("a/Helper.java"),
                "package a;\npublic class Helper { public static class Inner {} }\n");
        Path mover = src.resolve("a/Mover.java");
        Files.writeString(mover,
                "package a;\n"
              + "public class Mover<T> {\n"
              + "    Helper.Inner inner;\n"
              + "    T value;\n"
              + "}\n");
        ProjectManager mgr = new ProjectManager();
        ProjectEntry entry = mgr.load(src.toString(), null, null, true, true);
        MoveClassTool tool = new MoveClassTool(mgr, new EditManager(tempDir.resolve("backups")),
                RewriteTestSupport.newEngine());

        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        args.put("className", "a.Mover");
        args.put("newPackage", "b");
        CallToolResult r = tool.handle(null, new CallToolRequest("move_class", args));

        assertFalse(r.isError(), r.content().toString());
        Path moved = src.resolve("b/Mover.java");
        assertTrue(Files.exists(moved), "moved file must exist");
        String content = Files.readString(moved);
        assertTrue(content.contains("import a.Helper;"), content);
        assertFalse(content.contains("import a.Helper.Inner;"), content);
        assertFalse(content.contains("import a.T;"), content);
        mgr.remove(entry.name());
    }
}

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
 * Q5: remove_member einer Interface-Methode beruecksichtigt die ueberschreibenden/implementierenden Methoden.
 *
 * @author Janusch Rentenatus
 */
class RemoveMemberOverrideTest {

    @TempDir
    Path tempDir;

    private CallToolResult remove(ProjectManager mgr, EditManager em, String project, String mode) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("name", project);
        args.put("className", "Shape");
        args.put("memberName", "area");
        args.put("mode", mode);
        return new RemoveMemberTool(mgr, em, RewriteTestSupport.newEngine())
                .handle(null, new CallToolRequest("remove_member", args));
    }

    private ProjectEntry setup(ProjectManager mgr) throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Shape.java"), "interface Shape {\n  int area();\n}\n");
        Files.writeString(src.resolve("Square.java"),
                "class Square implements Shape {\n  @Override public int area() { return 4; }\n}\n");
        Files.writeString(src.resolve("Other.java"), "class Other {\n  int area() { return 0; }\n}\n");
        return mgr.load(src.toString(), null, null, true, true);
    }

    @Test
    void safeModeRefusesWhenImplementationsExist() throws Exception {
        ProjectManager mgr = new ProjectManager();
        EditManager em = new EditManager(tempDir.resolve("bk"));
        mgr.setEditManager(em);
        ProjectEntry entry = setup(mgr);

        CallToolResult r = remove(mgr, em, entry.name(), "safe");
        String text = r.content().toString();

        assertTrue(r.isError(), text);
        assertTrue(text.contains("Square") && text.contains("overrides/implements"), text);
        assertFalse(text.contains("Other"), "unrelated class with same method name: " + text);
        assertTrue(Files.readString(tempDir.resolve("src/Shape.java")).contains("int area();"));
        mgr.remove(entry.name());
    }

    @Test
    void hardModeRemovesAndWarnsAboutImplementations() throws Exception {
        ProjectManager mgr = new ProjectManager();
        EditManager em = new EditManager(tempDir.resolve("bk"));
        mgr.setEditManager(em);
        ProjectEntry entry = setup(mgr);

        CallToolResult r = remove(mgr, em, entry.name(), "hard");
        String text = r.content().toString();

        assertFalse(r.isError(), text);
        assertTrue(text.contains("WARNING") && text.contains("Square"), text);
        assertFalse(Files.readString(tempDir.resolve("src/Shape.java")).contains("area"));
        mgr.remove(entry.name());
    }
}

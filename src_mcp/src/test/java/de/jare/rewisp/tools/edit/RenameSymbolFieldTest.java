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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * AP-4.2 / AP-4.4: {@code rename_symbol scope=field} benennt auch unqualifizierte Zugriffe um, und
 * ein gleichnamiger Typ in einem anderen Package wird nicht mit umbenannt.
 *
 * @author Janusch Rentenatus
 */
class RenameSymbolFieldTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private RenameSymbolTool rename;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        rename = new RenameSymbolTool(mgr, new EditManager(tempDir.resolve("backups")),
                RewriteTestSupport.newEngine());
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir.resolve("a"));
        Files.createDirectories(srcDir.resolve("b"));
    }

    private CallToolResult run(String name, String cls, String oldName, String newName, String scope) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("className", cls);
        args.put("oldName", oldName);
        args.put("newName", newName);
        args.put("scope", scope);
        return rename.handle(null, new CallToolRequest("rename_symbol", args));
    }

    @Test
    void fieldRename_updatesUnqualifiedAndQualifiedUsages() throws Exception {
        Path foo = srcDir.resolve("a/Foo.java");
        Files.writeString(foo,
                "package a;\n"
              + "public class Foo {\n"
              + "    public int count = 1;\n"
              + "    int twice() { return count * 2; }\n"
              + "    int viaThis() { return this.count; }\n"
              + "}\n");
        Path user = srcDir.resolve("a/User.java");
        Files.writeString(user,
                "package a;\n"
              + "class User {\n"
              + "    int read(Foo f) { return f.count; }\n"
              + "    int local() { int count = 5; return count; }\n"
              + "}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult r = run(entry.name(), "a.Foo", "count", "total", "field");

        assertFalse(r.isError(), r.content().toString());
        String fooSrc = Files.readString(foo);
        assertTrue(fooSrc.contains("public int total = 1;"), fooSrc);
        assertTrue(fooSrc.contains("return total * 2;"), "unqualified usage must be renamed: " + fooSrc);
        assertTrue(fooSrc.contains("this.total"), fooSrc);
        String userSrc = Files.readString(user);
        assertTrue(userSrc.contains("f.total"), userSrc);
        assertTrue(userSrc.contains("int count = 5; return count;"), "local variable must stay: " + userSrc);
        mgr.remove(entry.name());
    }

    @Test
    void methodRename_doesNotTouchSameSimpleNameInOtherPackage() throws Exception {
        Path fooA = srcDir.resolve("a/Foo.java");
        Files.writeString(fooA, "package a;\npublic class Foo { public void run() {} }\n");
        Path fooB = srcDir.resolve("b/Foo.java");
        Files.writeString(fooB, "package b;\npublic class Foo { public void run() {} }\n");
        Path user = srcDir.resolve("b/User.java");
        Files.writeString(user,
                "package b;\n"
              + "class User {\n"
              + "    void go(Foo f, a.Foo g) { f.run(); g.run(); }\n"
              + "}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult r = run(entry.name(), "a.Foo", "run", "execute", "method");

        assertFalse(r.isError(), r.content().toString());
        assertTrue(Files.readString(fooA).contains("void execute()"));
        assertTrue(Files.readString(fooB).contains("void run()"), "b.Foo.run must stay");
        String userSrc = Files.readString(user);
        assertTrue(userSrc.contains("f.run()"), "call on b.Foo must stay: " + userSrc);
        assertTrue(userSrc.contains("g.execute()"), "call on a.Foo must be renamed: " + userSrc);
        mgr.remove(entry.name());
    }
}

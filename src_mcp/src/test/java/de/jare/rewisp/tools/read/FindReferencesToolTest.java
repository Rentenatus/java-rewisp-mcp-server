/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.read;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * AP-3.7: find_references scannt den ganzen Typ (Konstruktoren, Initializer, Felder, Nested),
 * unterscheidet Overloads und liefert Datei:Zeile.
 *
 * @author Janusch Rentenatus
 */
class FindReferencesToolTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private FindReferencesTool tool;
    private ProjectEntry entry;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        tool = new FindReferencesTool(mgr);
        Path pkg = Files.createDirectories(tempDir.resolve("src/p"));
        Files.writeString(pkg.resolve("Target.java"),
                "package p;\n"
                + "public class Target {\n"
                + "    public int counter;\n"
                + "    public Target() {}\n"
                + "    public Target(int x) { this(); }\n"
                + "    public void run(String s) {}\n"
                + "    public void run(int i) {}\n"
                + "    public static class Inner { public void ping() {} }\n"
                + "}\n");
        Files.writeString(pkg.resolve("User.java"),
                "package p;\n"
                + "public class User {\n"
                + "    private final Target field = new Target(3);\n"
                + "    static { new Target().run(\"static\"); }\n"
                + "    public User() { field.counter = 1; }\n"
                + "    void use() {\n"
                + "        Target t = new Target();\n"
                + "        t.run(1);\n"
                + "        int c = t.counter;\n"
                + "        Target.Inner in = new Target.Inner();\n"
                + "        in.ping();\n"
                + "        Runnable r = () -> t.run(\"lambda\");\n"
                + "    }\n"
                + "}\n");
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
    }

    @AfterEach
    void cleanup() {
        mgr.remove(entry.name());
    }

    private String run(String... kv) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", entry.name());
        for (int i = 0; i < kv.length; i += 2) args.put(kv[i], kv[i + 1]);
        var result = tool.handle(null, new CallToolRequest("find_references", args));
        assertFalse(result.isError(), result.content().toString());
        return result.content().toString();
    }

    @Test
    void methodReferencesCoverInitializersLambdasAndShowFileAndLine() {
        String out = run("className", "p.Target", "memberName", "run", "memberKind", "method");

        assertTrue(out.contains("User.java:4"), "static initializer: " + out);
        assertTrue(out.contains("User.java:8"), out);
        assertTrue(out.contains("User.java:12"), "lambda: " + out);
        assertTrue(out.contains("initializer of User"), out);
        assertTrue(out.contains("calls run(String)") && out.contains("calls run(int)"), out);
    }

    @Test
    void parameterTypesSelectOneOverload() {
        String out = run("className", "p.Target", "memberName", "run", "parameterTypes", "int");

        assertTrue(out.contains("calls run(int)"), out);
        assertFalse(out.contains("calls run(String)"), out);
    }

    @Test
    void constructorReferencesIncludeFieldInitializerAndThisCall() {
        String out = run("className", "p.Target", "memberName", "<init>");

        assertTrue(out.contains("User.java:3"), "field initializer: " + out);
        assertTrue(out.contains("User.java:7"), out);
        assertTrue(out.contains("Target.java:5"), "this(...) delegation: " + out);
    }

    @Test
    void fieldReadsAndWritesAreDistinguished() {
        String out = run("className", "p.Target", "memberName", "counter", "memberKind", "field");

        assertTrue(out.contains("writes field counter"), out);
        assertTrue(out.contains("reads field counter"), out);
    }

    @Test
    void nestedTypeCanBeTargeted() {
        String out = run("className", "p.Target.Inner", "memberName", "ping");

        assertTrue(out.contains("User.java:11"), out);
    }

    @Test
    void invalidMemberKindIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> run("className", "p.Target", "memberKind", "bogus"));
    }
}

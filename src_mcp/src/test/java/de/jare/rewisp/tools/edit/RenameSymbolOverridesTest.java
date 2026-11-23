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
 * AP-4.3: {@code rename_symbol} beruecksichtigt Overrides/Implementierungen, Methodenreferenzen,
 * statische Imports und Enum-Konstanten.
 *
 * @author Janusch Rentenatus
 */
class RenameSymbolOverridesTest {

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private RenameSymbolTool rename;
    private Path src;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        rename = new RenameSymbolTool(mgr, new EditManager(tempDir.resolve("backups")),
                RewriteTestSupport.newEngine());
        src = tempDir.resolve("src/p");
        Files.createDirectories(src);
    }

    private ProjectEntry load() throws Exception {
        return mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
    }

    private CallToolResult run(ProjectEntry e, String cls, String oldName, String newName, String scope) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("name", e.name());
        args.put("className", cls);
        args.put("oldName", oldName);
        args.put("newName", newName);
        args.put("scope", scope);
        return rename.handle(null, new CallToolRequest("rename_symbol", args));
    }

    @Test
    void methodRename_renamesOverridesAndImplementations() throws Exception {
        Files.writeString(src.resolve("Base.java"),
                "package p;\npublic class Base { public void run() {} }\n");
        Files.writeString(src.resolve("Sub.java"),
                "package p;\npublic class Sub extends Base { @Override public void run() {} }\n");
        Files.writeString(src.resolve("Iface.java"),
                "package p;\npublic interface Iface { void work(); }\n");
        Files.writeString(src.resolve("Impl.java"),
                "package p;\npublic class Impl implements Iface { public void work() {} }\n");
        Files.writeString(src.resolve("Use.java"),
                "package p;\nclass Use { void go(Base b, Sub s, Iface i) { b.run(); s.run(); i.work(); } }\n");
        ProjectEntry e = load();

        assertFalse(run(e, "p.Base", "run", "execute", "method").isError());
        assertFalse(run(e, "p.Iface", "work", "perform", "method").isError());

        assertTrue(Files.readString(src.resolve("Sub.java")).contains("void execute()"),
                Files.readString(src.resolve("Sub.java")));
        assertTrue(Files.readString(src.resolve("Impl.java")).contains("void perform()"),
                Files.readString(src.resolve("Impl.java")));
        String use = Files.readString(src.resolve("Use.java"));
        assertTrue(use.contains("b.execute()") && use.contains("s.execute()") && use.contains("i.perform()"), use);
        mgr.remove(e.name());
    }

    @Test
    void methodRename_updatesMethodReferencesAndStaticImports() throws Exception {
        Files.writeString(src.resolve("Util.java"),
                "package p;\npublic class Util { public static int helper() { return 1; } public int inst() { return 2; } }\n");
        Files.writeString(src.resolve("Use.java"),
                "package p;\n"
              + "import static p.Util.helper;\n"
              + "import java.util.function.Supplier;\n"
              + "class Use {\n"
              + "    int a() { return helper(); }\n"
              + "    Supplier<Integer> b() { return Util::helper; }\n"
              + "    java.util.function.Function<Util, Integer> c() { return Util::inst; }\n"
              + "}\n");
        ProjectEntry e = load();

        assertFalse(run(e, "p.Util", "helper", "assist", "method").isError());
        assertFalse(run(e, "p.Util", "inst", "instance", "method").isError());

        String use = Files.readString(src.resolve("Use.java"));
        assertTrue(use.contains("import static p.Util.assist;"), use);
        assertTrue(use.contains("return assist();"), use);
        assertTrue(use.contains("Util::assist"), use);
        assertTrue(use.contains("Util::instance"), use);
        mgr.remove(e.name());
    }

    @Test
    void fieldRename_updatesEnumConstantsAndStaticImports() throws Exception {
        Files.writeString(src.resolve("Color.java"),
                "package p;\npublic enum Color { RED, GREEN }\n");
        Files.writeString(src.resolve("Consts.java"),
                "package p;\npublic class Consts { public static final int LIMIT = 3; }\n");
        Files.writeString(src.resolve("Use.java"),
                "package p;\n"
              + "import static p.Consts.LIMIT;\n"
              + "class Use { Color c = Color.RED; int l = LIMIT; }\n");
        ProjectEntry e = load();

        assertFalse(run(e, "p.Color", "RED", "CRIMSON", "field").isError());
        assertFalse(run(e, "p.Consts", "LIMIT", "MAX", "field").isError());

        assertTrue(Files.readString(src.resolve("Color.java")).contains("CRIMSON"),
                Files.readString(src.resolve("Color.java")));
        String use = Files.readString(src.resolve("Use.java"));
        assertTrue(use.contains("Color.CRIMSON"), use);
        assertTrue(use.contains("import static p.Consts.MAX;"), use);
        assertTrue(use.contains("int l = MAX;"), use);
        mgr.remove(e.name());
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import de.jare.rewisp.analysis.SpoonModelService;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.tools.base.BaseTool;
import de.jare.rewisp.tools.read.ListClassesTool;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * D2 (Spoon-Pruefung vor dem Schreiben) und R3 (Stale-Warnung nur fuer das Projekt des Aufrufs).
 * R1 aus dem Praxistest: {@code return "text";} in einer {@code int}-Methode wurde als Erfolg gemeldet.
 *
 * @author Janusch Rentenatus
 */
class EditCompileValidationTest {

    private static final String POM = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + "<modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>t</artifactId><version>1</version>"
            + "<properties><maven.compiler.source>21</maven.compiler.source>"
            + "<maven.compiler.target>21</maven.compiler.target></properties></project>";

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    private ProjectManager mgr;
    private EditManager em;
    private final java.util.List<ProjectEntry> entries = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        mgr = new ProjectManager();
        em = new EditManager(backups);
        mgr.setEditManager(em);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SpoonModelService.VALIDATE_PROPERTY);
        System.clearProperty(SpoonModelService.VALIDATE_MAX_SOURCES_PROPERTY);
        for (ProjectEntry e : entries) mgr.remove(e.name());
    }

    /** Legt ein kleines Maven-Projekt {@code dir} mit der Klasse {@code p.<cls>} an und laedt es. */
    private Path project(String dir, String cls) throws Exception {
        return projectWith(dir, cls, Map.of());
    }

    /** Wie {@link #project}, mit weiteren Quelldateien (Name -> Inhalt, im Paket {@code p}) vor dem Laden. */
    private Path projectWith(String dir, String cls, Map<String, String> more) throws Exception {
        Path root = Files.createDirectories(tempDir.resolve(dir));
        Files.writeString(root.resolve("pom.xml"), POM);
        Path f = Files.createDirectories(root.resolve("src/main/java/p")).resolve(cls + ".java");
        Files.writeString(f, "package p;\npublic class " + cls + " {\n    int f() {\n        return 1;\n    }\n}\n");
        for (var e : more.entrySet()) {
            Files.writeString(f.resolveSibling(e.getKey() + ".java"), e.getValue());
        }
        entries.add(mgr.load(root.toString(), null, null, true, true));
        return f;
    }

    private String name(int i) {
        return entries.get(i).name();
    }

    private static CallToolResult call(BaseTool tool, String toolName, Object... kv) throws Exception {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) args.put((String) kv[i], kv[i + 1]);
        }
        return tool.handle(null, new CallToolRequest(toolName, args));
    }

    private static String text(CallToolResult r) {
        return r.content().toString();
    }

    private CallToolResult replaceBody(int project, String cls, String body, Object... extra) throws Exception {
        Object[] kv = new Object[8 + extra.length];
        Object[] base = {"name", name(project), "className", "p." + cls, "methodName", "f", "newBody", body};
        System.arraycopy(base, 0, kv, 0, 8);
        System.arraycopy(extra, 0, kv, 8, extra.length);
        return call(new ReplaceMethodBodyTool(mgr, em, RewriteTestSupport.newEngine()), "replace_method_body", kv);
    }

    @Test
    void typeErrorIsRejectedAndNothingIsWritten() throws Exception {
        Path f = project("a", "A");
        String before = Files.readString(f);

        CallToolResult r = replaceBody(0, "A", "return \"not an int\";");

        assertTrue(r.isError(), text(r));
        assertTrue(text(r).contains("EDIT_BREAKS_COMPILATION") && text(r).contains("Nothing was written"), text(r));
        assertEquals(before, Files.readString(f), "file must be untouched");
        // das Modell bleibt gesund: keine Stale-Warnung
        assertFalse(text(call(new ListClassesTool(mgr), "list_classes", "name", name(0))).contains("Stale structure model"));
    }

    @Test
    void dryRunReportsTheSameRejection() throws Exception {
        project("a", "A");
        CallToolResult r = replaceBody(0, "A", "return \"x\";", "dryRun", true);
        assertTrue(r.isError(), text(r));
        assertTrue(text(r).contains("EDIT_BREAKS_COMPILATION"), text(r));
    }

    @Test
    void validEditStillWorks() throws Exception {
        Path f = project("a", "A");
        CallToolResult r = replaceBody(0, "A", "return 2;");
        assertFalse(r.isError(), text(r));
        assertTrue(Files.readString(f).contains("return 2;"));
    }

    @Test
    void repairRemainsPossibleWhenTheProjectAlreadyDoesNotCompile() throws Exception {
        Path f = project("a", "A");
        // edit_line ist die Reparatur-/Notfalltour und wird nicht geprueft
        CallToolResult broken = call(new EditLineTool(mgr, em), "edit_line", "name", name(0), "file", f.toString(),
                "lineNumber", 4, "newContent", "        return undefinedVariable;");
        assertFalse(broken.isError(), text(broken));

        CallToolResult fixed = replaceBody(0, "A", "return 3;");
        assertFalse(fixed.isError(), text(fixed));
        assertTrue(Files.readString(f).contains("return 3;"));
    }

    @Test
    void propertyDisablesTheCheck() throws Exception {
        Path f = project("a", "A");
        System.setProperty(SpoonModelService.VALIDATE_PROPERTY, "false");
        CallToolResult r = replaceBody(0, "A", "return \"x\";");
        assertFalse(r.isError(), text(r));
        assertTrue(Files.readString(f).contains("return \"x\";"));
    }

    @Test
    void validationIsSkippedForProjectsAboveTheSourceLimit() throws Exception {
        Path f = project("a", "A");
        System.setProperty(SpoonModelService.VALIDATE_MAX_SOURCES_PROPERTY, "0");
        CallToolResult r = replaceBody(0, "A", "return \"x\";");
        assertFalse(r.isError(), text(r));
        assertTrue(Files.readString(f).contains("return \"x\";"), "above the limit the check is skipped");
    }

    @Test
    void addClassWithBrokenBodyIsRejected() throws Exception {
        project("a", "A");
        CallToolResult r = call(new AddClassTool(mgr, em, RewriteTestSupport.newEngine()), "add_class",
                "name", name(0), "packageName", "p", "className", "Broken", "body", "int x = \"s\";");
        assertTrue(r.isError(), text(r));
        assertTrue(text(r).contains("EDIT_BREAKS_COMPILATION"), text(r));
        assertFalse(Files.exists(tempDir.resolve("a/src/main/java/p/Broken.java")));
    }

    // ---- Kandidaten-Modell wird nach dem Schreiben uebernommen (kein zweiter Build), sonst Fallback ----
    @Test
    void validatedCandidateModelIsAdoptedWithoutASecondBuild() throws Exception {
        project("a", "A");
        mgr.ensureSpoonModel(name(0));
        int before = mgr.spoonBuildCount();

        assertFalse(replaceBody(0, "A", "return 7;").isError());
        ProjectEntry after = mgr.ensureSpoonModel(name(0));

        assertEquals(1, mgr.spoonBuildCount() - before, "validation build is reused, no rebuild afterwards");
        assertTrue(after.model().getAllTypes().stream().anyMatch(t -> t.getQualifiedName().equals("p.A")));
        assertTrue(text(call(new ListClassesTool(mgr), "list_classes", "name", name(0))).contains("p.A"));
        assertEquals(1, mgr.spoonBuildCount() - before, "a further read does not build again");
        // das uebernommene Modell zeigt den neuen Inhalt
        assertTrue(after.model().getAllTypes().iterator().next().getMethodsByName("f").get(0)
                .getBody().toString().contains("return 7"));
    }

    @Test
    void candidateIsDiscardedAndModelRebuiltWhenTheWrittenStateDiffers() throws Exception {
        Path f = project("a", "A");
        mgr.ensureSpoonModel(name(0));
        // validiert "return 7", geschrieben wird aber etwas anderes (nicht-validierter Pfad edit_line)
        java.nio.file.Path rel = tempDir.resolve("a").relativize(f);
        assertNull(mgr.validateEditCompiles(entries.get(0), Map.of(rel, "package p;\npublic class A {\n    int f() {\n        return 7;\n    }\n}\n"),
                java.util.Set.of()));
        int before = mgr.spoonBuildCount();
        assertFalse(call(new EditLineTool(mgr, em), "edit_line", "name", name(0), "file", f.toString(),
                "lineNumber", 4, "newContent", "        return 8;").isError());

        ProjectEntry after = mgr.ensureSpoonModel(name(0));

        assertEquals(1, mgr.spoonBuildCount() - before, "mismatching candidate must not be adopted: normal rebuild");
        assertTrue(after.model().getAllTypes().iterator().next().getMethodsByName("f").get(0)
                .getBody().toString().contains("return 8"));
    }

    @Test
    void editWithoutValidationStillRebuildsNormally() throws Exception {
        Path f = project("a", "A");
        mgr.ensureSpoonModel(name(0));
        int before = mgr.spoonBuildCount();
        assertFalse(call(new EditLineTool(mgr, em), "edit_line", "name", name(0), "file", f.toString(),
                "lineNumber", 4, "newContent", "        return 5;").isError());
        mgr.ensureSpoonModel(name(0));
        assertEquals(1, mgr.spoonBuildCount() - before);
    }

    // ---- Live-Befunde: Ausnahmen, in denen ein (vorerst) nicht kompilierbarer oder verschobener Stand gewollt ist ----
    private static final String CALLER = "package p;\npublic class C {\n    int g() {\n        return new A().f();\n    }\n}\n";

    @Test
    void renamingAPublicClassMovesItsFileAndPassesTheCheck() throws Exception {
        Path f = project("a", "A");
        CallToolResult r = call(new RenameSymbolTool(mgr, em, RewriteTestSupport.newEngine()), "rename_symbol",
                "name", name(0), "className", "p.A", "oldName", "A", "newName", "B", "scope", "class");
        assertFalse(r.isError(), text(r));
        assertFalse(Files.exists(f), "old file must be gone");
        assertTrue(Files.readString(f.resolveSibling("B.java")).contains("public class B"));
    }

    @Test
    void renamingAClassAlsoRenamesItsConstructorsAndTheirCallers() throws Exception {
        projectWith("a", "Z", Map.of(
                "A", "package p;\npublic class A {\n    private final int v;\n    public A(int v) {\n        this.v = v;\n    }\n"
                        + "    int f() {\n        return v;\n    }\n}\n",
                "C", "package p;\npublic class C {\n    A make() {\n        return new A(1);\n    }\n}\n"));
        Path f = tempDir.resolve("a/src/main/java/p/A.java");

        CallToolResult r = call(new RenameSymbolTool(mgr, em, RewriteTestSupport.newEngine()), "rename_symbol",
                "name", name(0), "className", "p.A", "oldName", "A", "newName", "B", "scope", "class");

        assertFalse(r.isError(), text(r));
        assertFalse(text(r).contains("Callers updated: 0"), "the usages in C are counted: " + text(r));
        String renamed = Files.readString(f.resolveSibling("B.java"));
        assertTrue(renamed.contains("public B(int v)"), renamed);
        assertFalse(renamed.contains("public A("), renamed);
        assertTrue(Files.readString(f.resolveSibling("C.java")).contains("new B(1)"));
    }

    @Test
    void removeMemberHardSkipsTheCompileCheckWhileSafeModeRefuses() throws Exception {
        Path f = projectWith("a", "A", Map.of("C", CALLER));
        RemoveMemberTool tool = new RemoveMemberTool(mgr, em, RewriteTestSupport.newEngine());

        CallToolResult safe = call(tool, "remove_member", "name", name(0), "className", "p.A", "memberName", "f");
        assertTrue(safe.isError() && text(safe).contains("REFERENCES_EXIST"), text(safe));

        CallToolResult hard = call(tool, "remove_member", "name", name(0), "className", "p.A", "memberName", "f",
                "mode", "hard");
        assertFalse(hard.isError(), text(hard));
        assertFalse(Files.readString(f).contains("int f()"));
    }

    @Test
    void rewriteSignatureThatBreaksCallersNeedsAllowBreakingChanges() throws Exception {
        Path f = projectWith("a", "A", Map.of("C", CALLER));
        RewriteSignatureTool tool = new RewriteSignatureTool(mgr, em, RewriteTestSupport.newEngine());

        CallToolResult rejected = call(tool, "rewrite_signature", "name", name(0), "className", "p.A",
                "methodName", "f", "newParameters", "int x", "mode", "signature_only");
        assertTrue(rejected.isError() && text(rejected).contains("EDIT_BREAKS_COMPILATION"), text(rejected));
        assertTrue(text(rejected).contains("allowBreakingChanges=true"), text(rejected));
        assertTrue(Files.readString(f).contains("int f()"), "nothing written");

        CallToolResult allowed = call(tool, "rewrite_signature", "name", name(0), "className", "p.A",
                "methodName", "f", "newParameters", "int x", "mode", "signature_only", "allowBreakingChanges", true);
        assertFalse(allowed.isError(), text(allowed));
        assertTrue(Files.readString(f).contains("int f(int x)"), Files.readString(f));
    }

    @Test
    void addClassIsPublicByDefaultAndPackagePrivateOnRequest() throws Exception {
        project("a", "A");
        AddClassTool tool = new AddClassTool(mgr, em, RewriteTestSupport.newEngine());
        assertFalse(call(tool, "add_class", "name", name(0), "packageName", "p", "className", "Pub").isError());
        assertFalse(call(tool, "add_class", "name", name(0), "packageName", "p", "className", "Pkg",
                "visibility", "package").isError());
        assertTrue(call(tool, "add_class", "name", name(0), "packageName", "p", "className", "Bad",
                "visibility", "private").isError());

        Path dir = tempDir.resolve("a/src/main/java/p");
        assertTrue(Files.readString(dir.resolve("Pub.java")).contains("public class Pub"));
        String pkg = Files.readString(dir.resolve("Pkg.java"));
        assertTrue(pkg.contains("class Pkg") && !pkg.contains("public class Pkg"), pkg);
        assertFalse(Files.exists(dir.resolve("Bad.java")));
    }

    // ---- R3: Stale-Warnung gehoert nur in Antworten, die das betroffene Projekt betreffen ----
    @Test
    void staleWarningAppearsOnlyForTheProjectOfTheCall() throws Exception {
        Path fa = project("a", "A");
        project("b", "B");
        assertFalse(call(new EditLineTool(mgr, em), "edit_line", "name", name(0), "file", fa.toString(),
                "lineNumber", 4, "newContent", "        return undefinedVariable;").isError());

        ListClassesTool list = new ListClassesTool(mgr);
        assertTrue(text(call(list, "list_classes", "name", name(0))).contains("Stale structure model"));
        assertFalse(text(call(list, "list_classes", "name", name(1))).contains("Stale structure model"),
                "project b is healthy and must not show a's warning");
    }

    // ---- R4: nicht aufgeloeste JDK-Typen -> Hinweis auf 'imports' mit konkretem Vorschlag ----
    @Test
    void unresolvedJdkTypeNamesTheImportsParameterAndASuggestion() throws Exception {
        project("a", "A");
        CallToolResult r = call(new AddFieldTool(mgr, em, RewriteTestSupport.newEngine()), "add_field",
                "name", name(0), "className", "p.A", "fieldName", "names", "type", "List<String>");
        assertTrue(r.isError(), text(r));
        assertTrue(text(r).contains("'imports' parameter"), text(r));
        assertTrue(text(r).contains("java.util.List"), text(r));
    }

    @Test
    void importHintForUnknownNameGivesOnlyTheGeneralAdvice() {
        String hint = de.jare.rewisp.edit.AutoImportResolver.importHint(java.util.List.of("NoSuchThingAnywhere"));
        assertTrue(hint.contains("'imports' parameter"), hint);
        assertFalse(hint.contains("e.g."), hint);
    }
}
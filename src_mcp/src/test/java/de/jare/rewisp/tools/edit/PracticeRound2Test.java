/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.testsupport.RewriteTestSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.tools.project.CheckProjectDirtyTool;
import de.jare.rewisp.tools.read.ListClassesTool;
import de.jare.rewisp.tools.txn.TransactionTools;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Praxistest, zweiter Durchlauf (Q1, Q3, Q4, Q7-Q13): Jeder Fund hat hier einen Test, der ohne den Fix rot war.
 *
 * @author Janusch Rentenatus
 */
class PracticeRound2Test {

    @TempDir
    Path tempDir;
    @TempDir
    Path backups;

    private ProjectManager mgr;
    private EditManager em;
    private Path src;
    private ProjectEntry entry;

    @BeforeEach
    void setUp() throws Exception {
        src = Files.createDirectories(tempDir.resolve("src/p"));
        mgr = new ProjectManager();
        em = new EditManager(backups);
        mgr.setEditManager(em); // wie im Server: der Manager kennt den EditManager
    }

    @AfterEach
    void tearDown() {
        if (entry != null) mgr.remove(entry.name());
    }

    private Path write(String name, String content) throws Exception {
        Path f = src.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    private void load() throws Exception {
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
    }

    private static CallToolResult call(de.jare.rewisp.tools.base.BaseTool tool, String toolName, Object... kv) throws Exception {
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) args.put((String) kv[i], kv[i + 1]);
        }
        return tool.handle(null, new CallToolRequest(toolName, args));
    }

    private static String text(CallToolResult r) {
        return r.content().toString();
    }

    private AddMethodTool addMethod() {
        return new AddMethodTool(mgr, em, RewriteTestSupport.newEngine());
    }

    // ---- Q1: add_method nimmt keinen Freitext, der Code einschleust ----
    @Test
    void addMethodRejectsInjectionAndKeepsFile() throws Exception {
        Path f = write("A.java", "package p;\npublic class A {\n}\n");
        load();
        byte[] before = Files.readAllBytes(f);

        String[][] bad = {
                {"evil", "void", "int a) { System.exit(1); } void x(int b", null, null},
                {"m", "void", null, "}\n    void injected() {"},
                {"m", "void m3() {} private int", null, null},
                {"m", "void", null, null, "public abstract final"},
                {"bad name", "void", null, null},
        };
        for (String[] b : bad) {
            CallToolResult r = call(addMethod(), "add_method", "name", entry.name(), "className", "p.A",
                    "methodName", b[0], "returnType", b[1], "parameters", b[2], "body", b[3],
                    "modifiers", b.length > 4 ? b[4] : null);
            assertTrue(r.isError(), String.join("|", java.util.Arrays.stream(b).map(String::valueOf).toList()));
            assertTrue(text(r).contains("INVALID_PARAMS"), text(r));
        }
        assertArrayEquals(before, Files.readAllBytes(f));
    }

    // ---- Q3: Nicht-void ohne Body kompiliert; Generics/Varargs-Parameter sind erlaubt ----
    @Test
    void addMethodStubsNonVoidAndAcceptsGenericParameters() throws Exception {
        Path f = write("B.java", "package p;\npublic abstract class B {\n}\n");
        load();

        CallToolResult stub = call(addMethod(), "add_method", "name", entry.name(), "className", "p.B",
                "methodName", "count", "returnType", "int", "parameters", "java.util.Map<String, Integer> m, int... xs");
        assertFalse(stub.isError(), text(stub));
        CallToolResult abs = call(addMethod(), "add_method", "name", entry.name(), "className", "p.B",
                "methodName", "g", "returnType", "int", "modifiers", "public abstract");
        assertFalse(abs.isError(), text(abs));

        String written = Files.readString(f);
        assertTrue(written.contains("int count(java.util.Map<String, Integer> m, int... xs)"), written);
        assertTrue(written.contains("throw new UnsupportedOperationException"), written);
        assertTrue(written.contains("public abstract int g();"), written);
    }

    // ---- Q4: unausgeglichener Rumpf wird abgelehnt statt still abgeschnitten ----
    @Test
    void replaceBodyRejectsUnbalancedBody() throws Exception {
        Path f = write("C.java", "package p;\npublic class C {\n    int f() {\n        return 1;\n    }\n}\n");
        load();
        byte[] before = Files.readAllBytes(f);

        CallToolResult r = call(new ReplaceMethodBodyTool(mgr, em, RewriteTestSupport.newEngine()), "replace_method_body",
                "name", entry.name(), "className", "p.C", "methodName", "f",
                "newBody", "return 2; } int injected() {");

        assertTrue(r.isError(), text(r));
        assertTrue(text(r).contains("unbalanced"), text(r));
        assertArrayEquals(before, Files.readAllBytes(f));
    }

    // ---- Q7/Q8: rewrite_signature lehnt Muell ab (kein Fehlermarker im Code) und erhaelt die Einrueckung ----
    @Test
    void rewriteSignatureRejectsInvalidParametersAndKeepsIndentation() throws Exception {
        Path f = write("D.java", "package p;\npublic class D {\n    void m(int a) {}\n    void caller() {\n        m(1);\n    }\n}\n");
        load();
        byte[] before = Files.readAllBytes(f);
        RewriteSignatureTool tool = new RewriteSignatureTool(mgr, em, RewriteTestSupport.newEngine());

        for (String bad : new String[] {"int", "int a) { System.exit(2); } private void y(int b", "a b c"}) {
            CallToolResult r = call(tool, "rewrite_signature", "name", entry.name(), "className", "p.D",
                    "methodName", "m", "newParameters", bad, "mode", "signature_only");
            assertTrue(r.isError(), bad + " -> " + text(r));
        }
        assertArrayEquals(before, Files.readAllBytes(f));

        CallToolResult ok = call(tool, "rewrite_signature", "name", entry.name(), "className", "p.D",
                "methodName", "m", "newParameters", "int a, int b");
        assertFalse(ok.isError(), text(ok));
        String written = Files.readString(f);
        assertFalse(written.contains("/*~~("), written);
        assertTrue(written.matches("(?s).*\\n {8}// TODO: signature of m changed - review arguments\\n {8}m\\(1\\);.*"), written);
    }

    // ---- Q4: Annotations-Attribute duerfen keinen weiteren Code einschleusen ----
    @Test
    void annotationAttributesAndNamesAreValidated() throws Exception {
        Path f = write("E.java", "package p;\npublic class E {\n    int color;\n}\n");
        load();
        byte[] before = Files.readAllBytes(f);
        AddAnnotationTool tool = new AddAnnotationTool(mgr, em, RewriteTestSupport.newEngine());

        CallToolResult attrs = call(tool, "add_annotation", "name", entry.name(), "targetType", "field", "className", "p.E",
                "targetName", "color", "annotation", "SuppressWarnings", "attributes", "\"x\") int hacked; @SuppressWarnings(\"y\"");
        CallToolResult nameBad = call(tool, "add_annotation", "name", entry.name(), "targetType", "class", "className", "p.E",
                "annotation", "Not A Name");

        assertTrue(attrs.isError(), text(attrs));
        assertTrue(nameBad.isError(), text(nameBad));
        assertTrue(text(nameBad).contains("INVALID_PARAMS"), text(nameBad));
        assertArrayEquals(before, Files.readAllBytes(f));
    }

    // ---- Q10: zurueckgerollte Edits stehen nicht im Edit-Protokoll ----
    @Test
    void rollbackRemovesEditsFromTheSummary() throws Exception {
        write("F.java", "package p;\npublic class F {\n}\n");
        load();
        var begin = new TransactionTools.Begin(mgr, em);
        var rollback = new TransactionTools.Rollback(mgr, em);
        var summary = new TransactionTools.GetEditSummary(mgr, em);
        AddFieldTool addField = new AddFieldTool(mgr, em, RewriteTestSupport.newEngine());

        assertFalse(call(addField, "add_field", "name", entry.name(), "className", "p.F", "fieldName", "kept", "type", "int").isError());
        call(begin, "begin_transaction");
        assertFalse(call(addField, "add_field", "name", entry.name(), "className", "p.F", "fieldName", "discarded", "type", "int").isError());
        call(rollback, "rollback_transaction");

        String s = text(call(summary, "get_edit_summary"));
        assertTrue(s.contains("kept"), s);
        assertFalse(s.contains("discarded"), s);
    }

    // ---- Q11/Q12: externe Aenderung/Loeschung bearbeiteter Dateien wird gemeldet ----
    @Test
    void dirtyCheckSeesExternalChangesToFilesEditedThroughRewisp() throws Exception {
        Path a = write("A1.java", "package p;\npublic class A1 {\n    int x;\n}\n");
        Path b = write("B1.java", "package p;\npublic class B1 {\n    int y;\n}\n");
        load();
        EditLineTool edit = new EditLineTool(mgr, em);
        for (Path f : new Path[] {a, b}) {
            assertFalse(call(edit, "edit_line", "name", entry.name(), "file", f.toString(), "lineNumber", 3,
                    "newContent", "    int z;").isError());
        }
        CheckProjectDirtyTool dirty = new CheckProjectDirtyTool(mgr);
        assertTrue(text(call(dirty, "check_project_dirty", "name", entry.name())).contains("No external changes"));

        Files.writeString(a, "package p;\npublic class A1 {\n    int changed = 1;\n}\n");
        Files.delete(b);

        String report = text(call(dirty, "check_project_dirty", "name", entry.name()));
        assertTrue(report.contains("External changes detected"), report);
        assertTrue(report.matches("(?s).*\\| Changed \\| [^|]*A1\\.java.*"), report);
        assertTrue(report.matches("(?s).*\\| Deleted \\| [^|]*B1\\.java.*"), report);
    }

    @Test
    void fileDeletedByRewispItselfIsNotReportedAsExternal() throws Exception {
        write("Moved.java", "package p;\npublic class Moved {\n}\n");
        load();
        CallToolResult mv = call(new MoveClassTool(mgr, em, RewriteTestSupport.newEngine()), "move_class",
                "name", entry.name(), "className", "p.Moved", "newPackage", "q");
        assertFalse(mv.isError(), text(mv));

        String report = text(call(new CheckProjectDirtyTool(mgr), "check_project_dirty", "name", entry.name()));
        assertFalse(report.contains("| Deleted |"), report);
    }

    // ---- Q9: move_class zaehlt nur wirklich geaenderte Dateien ----
    @Test
    void moveClassCountsOnlyRealImportUpdates() throws Exception {
        // Wie im TicTacToe-Beispiel: Impl kennt den Typ nur ueber die Oberklasse und braucht keinen Import
        write("Moved.java", "package p;\npublic interface Moved {\n    String label();\n}\n");
        write("Abs.java", "package p;\npublic abstract class Abs implements Moved {\n}\n");
        write("Impl.java", "package p;\npublic class Impl extends Abs {\n    @Override\n    public String label() {\n        return \"x\";\n    }\n}\n");
        write("Other.java", "package p;\npublic class Other {\n}\n");
        load();
        MoveClassTool tool = new MoveClassTool(mgr, em, RewriteTestSupport.newEngine());

        CallToolResult dry = call(tool, "move_class", "name", entry.name(), "className", "p.Moved", "newPackage", "q", "dryRun", true);
        assertTrue(text(dry).contains("Would change 2 file(s)"), text(dry)); // Abs (Import) und die verschobene Datei

        CallToolResult r = call(tool, "move_class", "name", entry.name(), "className", "p.Moved", "newPackage", "q");
        assertFalse(r.isError(), text(r));
        assertTrue(text(r).contains("Imports updated: 1"), text(r));
    }

    // ---- Q13: nicht kompilierbarer Quelltext -> Lesetools warnen statt still das alte Modell zu liefern ----
    @Test
    void readToolsWarnWhenTheModelCannotBeRebuilt() throws Exception {
        Files.writeString(tempDir.resolve("src/pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>t</artifactId><version>1</version>"
                + "<properties><maven.compiler.source>21</maven.compiler.source><maven.compiler.target>21</maven.compiler.target></properties></project>");
        Path f = Files.createDirectories(tempDir.resolve("src/src/main/java/p")).resolve("G.java");
        Files.writeString(f, "package p;\npublic class G {\n    int f() {\n        return 1;\n    }\n}\n");
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
        ListClassesTool list = new ListClassesTool(mgr);
        assertFalse(text(call(list, "list_classes", "name", entry.name())).contains("Stale structure model"));

        CallToolResult edit = call(new EditLineTool(mgr, em), "edit_line", "name", entry.name(), "file", f.toString(),
                "lineNumber", 4, "newContent", "        return undefinedVariable;");
        assertFalse(edit.isError(), text(edit));

        String out = text(call(list, "list_classes", "name", entry.name()));
        assertTrue(out.contains("Stale structure model"), out);

        // Reparatur hebt die Warnung wieder auf
        String fp = de.jare.rewisp.edit.FingerprintValidator.currentFingerprint(mgr.find(entry.name()), f);
        assertFalse(call(new EditLineTool(mgr, em), "edit_line", "name", entry.name(), "file", f.toString(),
                "lineNumber", 4, "newContent", "        return 2;", "expectedFingerprint", fp).isError());
        assertFalse(text(call(list, "list_classes", "name", entry.name())).contains("Stale structure model"));
    }

    // Reparatur auf exakt den zuletzt gebauten Inhalt: Modell ist aktuell, die Warnung muss trotzdem verschwinden
    @Test
    void staleWarningClearsWhenSourceReturnsToLastBuiltContent() throws Exception {
        Files.writeString(tempDir.resolve("src/pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>t</artifactId><version>1</version>"
                + "<properties><maven.compiler.source>21</maven.compiler.source><maven.compiler.target>21</maven.compiler.target></properties></project>");
        Path f = Files.createDirectories(tempDir.resolve("src/src/main/java/p")).resolve("H.java");
        Files.writeString(f, "package p;\npublic class H {\n    int f() {\n        return 1;\n    }\n}\n");
        entry = mgr.load(tempDir.resolve("src").toString(), null, null, true, true);
        ListClassesTool list = new ListClassesTool(mgr);
        assertFalse(text(call(list, "list_classes", "name", entry.name())).contains("Stale structure model"));

        assertFalse(call(new EditLineTool(mgr, em), "edit_line", "name", entry.name(), "file", f.toString(),
                "lineNumber", 4, "newContent", "        return undefinedVariable;").isError());
        assertTrue(text(call(list, "list_classes", "name", entry.name())).contains("Stale structure model"));

        String fp = de.jare.rewisp.edit.FingerprintValidator.currentFingerprint(mgr.find(entry.name()), f);
        assertFalse(call(new EditLineTool(mgr, em), "edit_line", "name", entry.name(), "file", f.toString(),
                "lineNumber", 4, "newContent", "        return 1;", "expectedFingerprint", fp).isError());
        assertFalse(text(call(list, "list_classes", "name", entry.name())).contains("Stale structure model"));
    }
}
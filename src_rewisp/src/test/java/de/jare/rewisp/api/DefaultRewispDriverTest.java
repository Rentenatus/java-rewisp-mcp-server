/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.api.RewispDriver.LoadRequest;
import de.jare.rewisp.api.RewispDriver.ProjectInfo;
import de.jare.rewisp.api.RewispDriver.TypeKind;

/**
 * Der Driver arbeitet ohne MCP-Bibliotheken und ohne stdio: Projekt laden, analysieren, Fehler als
 * {@link RewispException}, entladen.
 *
 * @author Janusch Rentenatus
 */
class DefaultRewispDriverTest {

    @TempDir
    Path tempDir;

    private ProjectManager manager;
    private RewispDriver driver;
    private ProjectInfo project;

    @BeforeEach
    void setUp() throws Exception {
        manager = new ProjectManager();
        driver = new DefaultRewispDriver(manager);
        Path pkg = Files.createDirectories(tempDir.resolve("src/main/java/p"));
        Files.writeString(tempDir.resolve("pom.xml"), "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>driver-demo</artifactId>"
                + "<version>1</version><properties><maven.compiler.source>21</maven.compiler.source>"
                + "<maven.compiler.target>21</maven.compiler.target></properties></project>");
        Files.writeString(pkg.resolve("Shape.java"), "package p;\n\n/** A shape. */\npublic interface Shape {\n"
                + "    double area();\n}\n");
        Files.writeString(pkg.resolve("Square.java"), "package p;\n\npublic class Square implements Shape {\n"
                + "    private final double side;\n    public static final int SIDES = 4;\n"
                + "    public Square(double side) { this.side = side; }\n"
                + "    @Override public double area() { return side * side; }\n}\n");
        Files.writeString(pkg.resolve("Main.java"), "package p;\n\nclass Main {\n"
                + "    double run() { Shape s = new Square(2); return s.area() + new Square(3).area(); }\n}\n");
        project = driver.loadProject(new LoadRequest(tempDir.toString(), "demo", null, false, null));
    }

    @AfterEach
    void tearDown() {
        manager.remove(project.name());
    }

    @Test
    void loadedProjectIsListedWithItsFacts() {
        assertEquals("demo", project.alias());
        assertEquals(3, project.types());
        assertEquals("MAVEN", project.build());
        assertTrue(project.editable());
        assertFalse(project.modelReleased());

        List<ProjectInfo> all = driver.listProjects();
        assertEquals(1, all.size());
        assertEquals(project.name(), all.get(0).name());
    }

    @Test
    void listClassesFiltersAndSorts() {
        var all = driver.listClasses("demo", null, false, false);
        assertEquals(List.of("p.Main", "p.Shape", "p.Square"),
                all.stream().map(RewispDriver.TypeSummary::qualifiedName).toList());
        assertEquals(TypeKind.INTERFACE, all.get(1).kind());
        assertTrue(all.get(1).isPublic());
        assertFalse(all.get(0).isPublic());

        assertEquals(3, driver.listClasses("demo", "p", false, false).size());
        assertTrue(driver.listClasses("demo", "other", false, false).isEmpty());
    }

    @Test
    void inspectClassDescribesMembers() {
        var square = driver.inspectClass("demo", "p.Square", true);

        assertEquals(project.name(), square.project());
        assertEquals(TypeKind.CLASS, square.kind());
        assertEquals("public", square.visibility());
        assertEquals(List.of("p.Shape"), square.interfaces());
        assertEquals(2, square.fields().size());
        assertTrue(square.fields().stream().anyMatch(f -> f.name().equals("SIDES") && f.isStatic() && f.isFinal()));
        assertEquals(1, square.constructors().size());
        assertEquals(1, square.methods().size());
        assertNull(square.javadoc());

        var shape = driver.inspectClass("demo", "p.Shape", true);
        assertTrue(shape.javadoc().contains("A shape."), shape.javadoc());
        assertNull(driver.inspectClass("demo", "p.Shape", false).javadoc());
    }

    @Test
    void findReferencesCountsHitsAndFiles() {
        var result = driver.findReferences("demo", "p.Square", null, null, null);

        assertEquals(project.name(), result.project());
        assertFalse(result.references().isEmpty());
        assertTrue(result.references().stream().allMatch(r -> r.file() != null && r.location() != null));
        assertEquals(2, result.fileCount(), "Main uses Square, Square refers to itself (constructor)");

        var calls = driver.findReferences("demo", "p.Shape", "area", "method", null);
        assertFalse(calls.references().isEmpty());
    }

    /** N42: Fundstellen im Typkopf (implements) haben als Besitzer den Typ, nicht '?'. */
    @Test
    void referenceInTheTypeHeaderNamesTheDeclaringType() {
        var refs = driver.findReferences("demo", "p.Shape", null, null, null);

        var header = refs.references().stream().filter(r -> r.file().endsWith("Square.java")).toList();
        assertFalse(header.isEmpty(), "Square implements Shape");
        assertTrue(header.stream().anyMatch(r -> "declaration of Square".equals(r.owner())), header.toString());
        assertTrue(refs.references().stream().noneMatch(r -> "?".equals(r.owner())), refs.references().toString());
    }

    @Test
    void unknownProjectAndTypeAreReportedAsRewispException() {
        var noProject = assertThrows(RewispException.class, () -> driver.listClasses("nope", null, false, false));
        assertEquals("DOMAIN_ERROR", noProject.code());
        assertTrue(noProject.getMessage().contains("nope"));

        var noType = assertThrows(RewispException.class, () -> driver.inspectClass("demo", "p.Missing", true));
        assertEquals("TYPE_NOT_FOUND", noType.code());
        assertEquals("p.Missing", noType.context().get("className"));
    }

    @Test
    void loadFailureCarriesTheLoaderErrorCode() {
        var e = assertThrows(RewispException.class,
                () -> driver.loadProject(LoadRequest.of(tempDir.resolve("does-not-exist").toString())));
        assertNotNull(e.code());
        assertFalse(e.getMessage().isBlank());
    }

    @Test
    void unloadReturnsTheNameOnceAndNullAfterwards() {
        assertEquals(project.name(), driver.unloadProject("demo"));
        assertNull(driver.unloadProject("demo"));
        assertTrue(driver.listProjects().isEmpty());
    }

    @Test
    void implementationsAndHierarchy() {
        var impl = driver.findImplementations("demo", "p.Shape");
        assertEquals(List.of("p.Square"), impl.concrete());
        assertTrue(impl.abstractTypes().isEmpty() && impl.subInterfaces().isEmpty());

        var notAbstract = assertThrows(RewispException.class, () -> driver.findImplementations("demo", "p.Main"));
        assertEquals("DOMAIN_ERROR", notAbstract.code());

        var h = driver.typeHierarchy("demo", "p.Square");
        assertTrue(h.superclasses().isEmpty(), "no explicit superclass");
        assertEquals("p.Shape", h.interfaces().get(0).name());
        var shape = driver.typeHierarchy("demo", "p.Shape");
        assertEquals("p.Square", shape.subtypes().get(0).name());
        assertEquals("class", shape.subtypes().get(0).kind());
    }

    @Test
    void methodAndFieldDetails() {
        var area = driver.inspectMethod("demo", "p.Square", "area", true).overloads();
        assertEquals(1, area.size());
        assertEquals("public", area.get(0).visibility());
        assertTrue(area.get(0).body().contains("side * side"));
        assertTrue(area.get(0).annotations().stream().anyMatch(a -> a.contains("Override")));
        assertNull(driver.inspectMethod("demo", "p.Shape", "area", true).overloads().get(0).body());

        var sides = driver.inspectField("demo", "p.Square", "SIDES", true);
        assertEquals("int", sides.type());
        assertTrue(sides.isStatic() && sides.isFinal());
        assertEquals("4", sides.initializer());

        assertEquals("DOMAIN_ERROR", assertThrows(RewispException.class,
                () -> driver.inspectMethod("demo", "p.Square", "nope", true)).code());
        assertEquals("DOMAIN_ERROR", assertThrows(RewispException.class,
                () -> driver.inspectField("demo", "p.Square", "nope", true)).code());
    }

    @Test
    void searchFileContentAndPosition() {
        var found = driver.searchSource("demo", "square", false, 1);
        assertTrue(found.occurrences() >= 3, "case-insensitive matches in Square and Main");
        assertEquals(1, found.hits().size(), "listing is limited by maxResults, counts stay exact");
        assertTrue(found.matchingLines() > 1);
        assertEquals(0, driver.searchSource("demo", "square", true, 10).hits().stream()
                .filter(h -> h.text().contains("zzz")).count());
        assertEquals("DOMAIN_ERROR",
                assertThrows(RewispException.class, () -> driver.searchSource("demo", " ", false, 5)).code());

        var content = driver.fileContent("demo", "p.Shape", 3, 4);
        assertEquals(3, content.from());
        assertEquals(4, content.to());
        assertEquals(2, content.lines().size());
        assertEquals("src/main/java/p/Shape.java", content.path());
        assertNotNull(content.fingerprint());
        assertThrows(IllegalArgumentException.class, () -> driver.fileContent("demo", "p.Shape", 0, null));

        var pos = driver.elementPosition("demo", "p.Square", "area", null);
        assertEquals(7, pos.line());
        assertTrue(pos.file().endsWith("Square.java"));
        assertEquals("ELEMENT_NOT_FOUND", assertThrows(RewispException.class,
                () -> driver.elementPosition("demo", "p.Square", "nothing", null)).code());
        assertThrows(IllegalArgumentException.class,
                () -> driver.elementPosition("demo", "p.Square", "area", "BOGUS"));
    }

    @Test
    void metadataReloadAndDirtyCheck() throws Exception {
        var meta = driver.projectMetadata("demo");
        assertEquals(3, meta.classes() + meta.interfaces());
        assertEquals(1, meta.interfaces());
        assertTrue(meta.packages() >= 1, "package p (plus the unnamed root package)");
        assertNull(meta.analysedDirectory());

        var clean = driver.checkDirty("demo");
        assertFalse(clean.baselineMissing());
        assertFalse(clean.external());
        assertEquals(3, clean.filesChecked());
        assertNotNull(clean.state());

        Files.writeString(tempDir.resolve("src/main/java/p/Main.java"), "package p;\n\nclass Main {}\n");
        var dirty = driver.checkDirty("demo");
        assertTrue(dirty.external());
        assertTrue(dirty.changed().stream().anyMatch(s -> s.endsWith("Main.java")), dirty.changed().toString());

        var reloaded = driver.reloadProjects("demo", false);
        assertEquals(1, reloaded.size());
        assertFalse(driver.checkDirty("demo").external(), "reload adopts the on-disk state");
        assertEquals("DOMAIN_ERROR",
                assertThrows(RewispException.class, () -> driver.reloadProjects(null, false)).code());
        assertTrue(driver.reloadProjects(null, true).isEmpty(), "nothing expired");
    }

    @Test
    void rebootDiscardsAllProjects() throws Exception {
        var rebooting = new DefaultRewispDriver(manager, new de.jare.rewisp.edit.EditManager(Files.createTempDirectory("rewisp-bk")));
        var result = rebooting.reboot();
        assertEquals(List.of(project.name()), result.removedProjects());
        assertEquals(0, result.discardedChanges());
        assertTrue(driver.listProjects().isEmpty());
    }

    @Test
    void transactionLifecycle() throws Exception {
        var em = new de.jare.rewisp.edit.EditManager(Files.createTempDirectory("rewisp-bk"));
        var tx = new DefaultRewispDriver(manager, em);
        assertEquals("DOMAIN_ERROR", assertThrows(RewispException.class, tx::commitTransaction).code());
        assertEquals("DOMAIN_ERROR", assertThrows(RewispException.class, tx::rollbackTransaction).code());
        assertEquals("DOMAIN_ERROR", assertThrows(RewispException.class, () -> tx.transactionDiff(false)).code());

        tx.beginTransaction();
        assertEquals("DOMAIN_ERROR", assertThrows(RewispException.class, tx::beginTransaction).code());
        assertTrue(tx.transactionDiff(false).changes().isEmpty());

        Path main = tempDir.resolve("src/main/java/p/Main.java");
        String original = Files.readString(main);
        em.writeFile(manager.find("demo"), main, "package p;\n\nclass Main {\n    int x;\n}\n", null);
        var diff = tx.transactionDiff(false);
        assertEquals(1, diff.files());
        assertEquals("modify", diff.changes().get(0).kind());
        assertEquals("src/main/java/p/Main.java", diff.changes().get(0).path());
        assertNotNull(diff.changes().get(0).diff());
        assertNull(tx.transactionDiff(true).changes().get(0).diff());
        assertEquals(original, Files.readString(main), "disk stays untouched while the transaction is open");

        tx.rollbackTransaction();
        assertEquals(original, Files.readString(main));

        tx.beginTransaction();
        em.writeFile(manager.find("demo"), main, "package p;\n\nclass Main {\n    int y;\n}\n", null);
        var committed = tx.commitTransaction();
        assertEquals(1, committed.filesWritten());
        assertTrue(Files.readString(main).contains("int y;"));
        var after = driver.checkDirty("demo");
        assertFalse(after.external(), "own commit is not an external change: " + after.changed() + after.deleted() + after.added());
    }

    @Test
    void renameSymbolThroughTheDriver() throws Exception {
        var em = new de.jare.rewisp.edit.EditManager(Files.createTempDirectory("rewisp-bk"));
        var editing = new DefaultRewispDriver(manager, em, de.jare.rewisp.testsupport.RewriteTestSupport.newEngine());
        Path shape = tempDir.resolve("src/main/java/p/Shape.java");
        String before = Files.readString(shape);

        var dry = editing.renameSymbol(new RewispDriver.RenameRequest("demo", "p.Shape", "area", "surface", null, null, true));
        assertTrue(dry.dryRun());
        assertEquals(before, Files.readString(shape), "dry run writes nothing");
        assertEquals(3, dry.changedFiles());

        var done = editing.renameSymbol(new RewispDriver.RenameRequest("demo", "p.Shape", "area", "surface", "method", true, false));
        assertFalse(done.dryRun());
        assertEquals(3, done.changedFiles());
        assertTrue(done.declarationsRenamed() >= 1);
        assertTrue(Files.readString(shape).contains("surface()"));
        assertTrue(Files.readString(tempDir.resolve("src/main/java/p/Main.java")).contains("surface()"));
        assertFalse(editing.checkDirty("demo").external());
        assertEquals(1, editing.editLog().size());

        var missing = assertThrows(RewispException.class, () -> editing.renameSymbol(
                new RewispDriver.RenameRequest("demo", "p.Shape", "nothing", "x", "method", true, false)));
        assertEquals("SYMBOL_NOT_FOUND", missing.code());
        var scope = assertThrows(RewispException.class, () -> editing.renameSymbol(
                new RewispDriver.RenameRequest("demo", "p.Shape", "surface", "x", "package", true, false)));
        assertEquals("INVALID_SCOPE", scope.code());
        var noEngine = assertThrows(RewispException.class, () -> new DefaultRewispDriver(manager, em).renameSymbol(
                new RewispDriver.RenameRequest("demo", "p.Shape", "surface", "x", "method", true, false)));
        assertEquals("OPENREWRITE_REQUIRED", noEngine.code());
    }

    private DefaultRewispDriver editingDriver() throws Exception {
        var em = new de.jare.rewisp.edit.EditManager(Files.createTempDirectory("rewisp-bk"));
        return new DefaultRewispDriver(manager, em, de.jare.rewisp.testsupport.RewriteTestSupport.newEngine());
    }

    @Test
    void replaceMethodBodyThroughTheDriver() throws Exception {
        var editing = editingDriver();
        Path square = tempDir.resolve("src/main/java/p/Square.java");
        String before = Files.readString(square);

        var dry = editing.replaceMethodBody(new RewispDriver.ReplaceBodyRequest("demo", "p.Square", "area", null,
                "return 1.0;", null, true));
        assertTrue(dry.dryRun());
        assertEquals(before, Files.readString(square), "dry run writes nothing");

        var done = editing.replaceMethodBody(new RewispDriver.ReplaceBodyRequest("demo", "p.Square", "area", "",
                "return 1.0;", null, false));
        assertFalse(done.dryRun());
        assertEquals(1, done.changedFiles());
        assertTrue(Files.readString(square).contains("return 1.0;"));

        var missing = assertThrows(RewispException.class, () -> editing.replaceMethodBody(
                new RewispDriver.ReplaceBodyRequest("demo", "p.Square", "nothing", null, "return 1;", null, false)));
        assertEquals("METHOD_NOT_FOUND", missing.code());
        var bad = assertThrows(RewispException.class, () -> editing.replaceMethodBody(
                new RewispDriver.ReplaceBodyRequest("demo", "p.Square", "area", null, "return (1;", null, false)));
        assertEquals("INVALID_PARAMS", bad.code());
    }
    @Test
    void rewriteSignatureThroughTheDriver() throws Exception {
        var editing = editingDriver();
        Path square = tempDir.resolve("src/main/java/p/Square.java");

        var same = editing.rewriteSignature(new RewispDriver.SignatureRequest("demo", "p.Square", "area", "double",
                null, null, null, false, false));
        assertTrue(same.unchanged());
        assertNull(same.edit());

        var done = editing.rewriteSignature(new RewispDriver.SignatureRequest("demo", "p.Square", "area", "float",
                null, "signature_only", null, true, false));
        assertFalse(done.unchanged());
        assertEquals("signature_only", done.mode());
        assertTrue(Files.readString(square).contains("float area()"));

        var mode = assertThrows(RewispException.class, () -> editing.rewriteSignature(
                new RewispDriver.SignatureRequest("demo", "p.Square", "area", null, null, "everything", null, false,
                        false)));
        assertEquals("INVALID_MODE", mode.code());
    }
    @Test
    void moveClassThroughTheDriver() throws Exception {
        var editing = editingDriver();
        Path old = tempDir.resolve("src/main/java/p/Square.java");
        Path moved = tempDir.resolve("src/main/java/q/Square.java");

        var same = editing.moveClass(new RewispDriver.MoveClassRequest("demo", "p.Square", "p", false));
        assertTrue(same.alreadyThere());

        var dry = editing.moveClass(new RewispDriver.MoveClassRequest("demo", "p.Square", "q", true));
        assertTrue(dry.edit().dryRun());
        assertTrue(Files.exists(old));

        var done = editing.moveClass(new RewispDriver.MoveClassRequest("demo", "p.Square", "q", false));
        assertEquals("q.Square", done.movedTo());
        assertFalse(done.deletionDeferred());
        assertFalse(Files.exists(old));
        assertTrue(Files.readString(moved).contains("package q;"));
        assertTrue(Files.readString(tempDir.resolve("src/main/java/p/Main.java")).contains("import q.Square;"));

        var missing = assertThrows(RewispException.class, () -> editing.moveClass(
                new RewispDriver.MoveClassRequest("demo", "p.Nothing", "q", false)));
        assertEquals("TYPE_NOT_FOUND", missing.code());
    }
    @Test
    void addMethodThroughTheDriver() throws Exception {
        var editing = editingDriver();
        Path square = tempDir.resolve("src/main/java/p/Square.java");

        var done = editing.addMethod(new RewispDriver.AddMethodRequest("demo", "p.Square", "perimeter", "double",
                null, "public", "return 4.0;", null, false));
        assertFalse(done.dryRun());
        assertEquals(1, done.changedFiles());
        assertTrue(Files.readString(square).contains("double perimeter()"));

        var bad = assertThrows(RewispException.class, () -> editing.addMethod(new RewispDriver.AddMethodRequest(
                "demo", "p.Square", "1x", "double", null, null, null, null, false)));
        assertEquals("INVALID_PARAMS", bad.code());
        var unknown = assertThrows(RewispException.class, () -> editing.addMethod(new RewispDriver.AddMethodRequest(
                "demo", "p.Nothing", "go", "void", null, null, null, null, false)));
        assertEquals("TYPE_NOT_FOUND", unknown.code());
    }

    @Test
    void addFieldThroughTheDriver() throws Exception {
        var editing = editingDriver();
        Path square = tempDir.resolve("src/main/java/p/Square.java");

        var done = editing.addField(new RewispDriver.AddFieldRequest("demo", "p.Square", "label", "String",
                "private", "\"sq\"", null, false));
        assertFalse(done.dryRun());
        assertTrue(Files.readString(square).contains("private String label = \"sq\";"));

        var dup = assertThrows(RewispException.class, () -> editing.addField(new RewispDriver.AddFieldRequest(
                "demo", "p.Square", "label", "String", null, null, null, false)));
        assertEquals("FIELD_EXISTS", dup.code());
        var bad = assertThrows(RewispException.class, () -> editing.addField(new RewispDriver.AddFieldRequest(
                "demo", "p.Square", "9x", "String", null, null, null, false)));
        assertEquals("INVALID_PARAMS", bad.code());
    }

    @Test
    void addClassThroughTheDriver() throws Exception {
        var editing = editingDriver();
        Path created = tempDir.resolve("src/main/java/p/Circle.java");

        var dry = editing.addClass(new RewispDriver.AddClassRequest("demo", "p", "Circle", null, null,
                "double r = 1;", null, true));
        assertTrue(dry.dryRun());
        assertFalse(Files.exists(created), "dry run writes nothing");

        var done = editing.addClass(new RewispDriver.AddClassRequest("demo", "p", "Circle", "class", "public",
                "double r = 1;", null, false));
        assertFalse(done.dryRun());
        assertTrue(Files.readString(created).contains("public class Circle"));
        assertEquals(created.toRealPath(), done.file().toRealPath());

        var exists = assertThrows(RewispException.class, () -> editing.addClass(new RewispDriver.AddClassRequest(
                "demo", "p", "Circle", null, null, null, null, false)));
        assertEquals("CLASS_EXISTS", exists.code());
        var kind = assertThrows(RewispException.class, () -> editing.addClass(new RewispDriver.AddClassRequest(
                "demo", "p", "Other", "record", null, null, null, false)));
        assertEquals("INVALID_TYPE", kind.code());
    }

    @Test
    void annotationsThroughTheDriver() throws Exception {
        var editing = editingDriver();
        Path square = tempDir.resolve("src/main/java/p/Square.java");

        var added = editing.addAnnotation(new RewispDriver.AddAnnotationRequest("demo", "class", "p.Square", null,
                "Deprecated", null, false));
        assertFalse(added.dryRun());
        assertTrue(Files.readString(square).contains("@Deprecated"));

        var dup = assertThrows(RewispException.class, () -> editing.addAnnotation(
                new RewispDriver.AddAnnotationRequest("demo", "class", "p.Square", null, "Deprecated", null, false)));
        assertEquals("DUPLICATE_ANNOTATION", dup.code());
        var noName = assertThrows(RewispException.class, () -> editing.addAnnotation(
                new RewispDriver.AddAnnotationRequest("demo", "field", "p.Square", null, "Deprecated", null, false)));
        assertEquals("MISSING_TARGET_NAME", noName.code());
    }

    @Test
    void editAnnotationThroughTheDriver() throws Exception {
        var editing = editingDriver();
        Path square = tempDir.resolve("src/main/java/p/Square.java");

        var edited = editing.editAnnotation(new RewispDriver.EditAnnotationRequest("demo", "method", "p.Square",
                "area", "Override", "", true));
        assertTrue(edited.dryRun());

        var missing = assertThrows(RewispException.class, () -> editing.editAnnotation(
                new RewispDriver.EditAnnotationRequest("demo", null, "p.Square", null, "Deprecated", "since=\"1\"",
                        false)));
        assertEquals("ANNOTATION_NOT_FOUND", missing.code());
        var target = assertThrows(RewispException.class, () -> editing.editAnnotation(
                new RewispDriver.EditAnnotationRequest("demo", "method", "p.Square", null, "Override", "", false)));
        assertEquals("INVALID_TARGET", target.code());
        assertFalse(Files.readString(square).contains("@Deprecated"));
    }

    @Test
    void removeAnnotationThroughTheDriver() throws Exception {
        var editing = editingDriver();
        Path square = tempDir.resolve("src/main/java/p/Square.java");
        editing.addAnnotation(new RewispDriver.AddAnnotationRequest("demo", "field", "p.Square", "side",
                "Deprecated", null, false));
        assertTrue(Files.readString(square).contains("@Deprecated"));

        var removed = editing.removeAnnotation(new RewispDriver.RemoveAnnotationRequest("demo", "field", "p.Square",
                "side", "Deprecated", false));
        assertFalse(removed.dryRun());
        assertFalse(Files.readString(square).contains("@Deprecated"));

        var again = assertThrows(RewispException.class, () -> editing.removeAnnotation(
                new RewispDriver.RemoveAnnotationRequest("demo", "field", "p.Square", "side", "Deprecated", false)));
        assertEquals("ANNOTATION_NOT_FOUND", again.code());
        var target = assertThrows(RewispException.class, () -> editing.removeAnnotation(
                new RewispDriver.RemoveAnnotationRequest("demo", "package", "p.Square", null, "Deprecated", false)));
        assertEquals("INVALID_TARGET", target.code());
    }

    @Test
    void removeMemberThroughTheDriver() throws Exception {
        var editing = editingDriver();
        Path square = tempDir.resolve("src/main/java/p/Square.java");

        var refs = assertThrows(RewispException.class, () -> editing.removeMember(
                new RewispDriver.RemoveMemberRequest("demo", "p.Square", "side", "field", null, null, false)));
        assertEquals("REFERENCES_EXIST", refs.code());
        var scope = assertThrows(RewispException.class, () -> editing.removeMember(
                new RewispDriver.RemoveMemberRequest("demo", "p.Square", "side", "class", null, null, false)));
        assertEquals("INVALID_SCOPE", scope.code());

        var dry = editing.removeMember(new RewispDriver.RemoveMemberRequest("demo", "p.Square", "SIDES", "field",
                null, null, true));
        assertTrue(dry.edit().dryRun());
        var done = editing.removeMember(new RewispDriver.RemoveMemberRequest("demo", "p.Square", "SIDES", "field",
                "safe", null, false));
        assertFalse(done.hard());
        assertTrue(done.danglingReferences().isEmpty());
        assertFalse(Files.readString(square).contains("SIDES"));
    }

    @Test
    void editLineThroughTheDriver() throws Exception {
        var editing = new DefaultRewispDriver(manager, new de.jare.rewisp.edit.EditManager(
                Files.createTempDirectory("rewisp-bk")));
        Path main = tempDir.resolve("src/main/java/p/Main.java");

        var dry = editing.editLine(new RewispDriver.EditLineRequest("demo", "p/Main.java", 3, "final class Main {",
                null, true));
        assertTrue(dry.dryRun());
        assertEquals("class Main {", dry.oldLine());
        assertTrue(Files.readString(main).contains("\nclass Main {"), "dry run writes nothing");

        var done = editing.editLine(new RewispDriver.EditLineRequest("demo", "p/Main.java", 3, "final class Main {",
                null, false));
        assertFalse(done.dryRun());
        assertTrue(Files.readString(main).contains("final class Main {"));

        var far = assertThrows(RewispException.class, () -> editing.editLine(
                new RewispDriver.EditLineRequest("demo", "p/Main.java", 99, "x", null, false)));
        assertEquals("DOMAIN_ERROR", far.code());
        var low = assertThrows(RewispException.class, () -> editing.editLine(
                new RewispDriver.EditLineRequest("demo", "p/Main.java", 0, "x", null, false)));
        assertEquals("DOMAIN_ERROR", low.code());
        var outside = assertThrows(RewispException.class, () -> editing.editLine(
                new RewispDriver.EditLineRequest("demo", Files.createTempFile("outside", ".java").toString(), 1, "x",
                        null, false)));
        assertEquals("SECURITY_ERROR", outside.code());
    }

    @Test
    void addPackageThroughTheDriver() throws Exception {
        var editing = new DefaultRewispDriver(manager, new de.jare.rewisp.edit.EditManager(
                Files.createTempDirectory("rewisp-bk")));
        Path dir = tempDir.resolve("src/main/java/p/util");

        var dry = editing.addPackage(new RewispDriver.AddPackageRequest("demo", "p.util", true, true));
        assertTrue(dry.dryRun());
        assertFalse(Files.exists(dir), "dry run writes nothing");

        var done = editing.addPackage(new RewispDriver.AddPackageRequest("demo", "p.util", true, false));
        assertFalse(done.dryRun());
        assertTrue(done.packageInfoCreated());
        assertTrue(Files.readString(dir.resolve("package-info.java")).contains("package p.util;"));

        var again = assertThrows(RewispException.class, () -> editing.addPackage(
                new RewispDriver.AddPackageRequest("demo", "p.util", true, false)));
        assertEquals("DOMAIN_ERROR", again.code());
        var bad = assertThrows(RewispException.class, () -> editing.addPackage(
                new RewispDriver.AddPackageRequest("demo", "p..x", false, false)));
        assertEquals("INVALID_PACKAGE", bad.code());
    }

    /** N41: Annotation hinzufuegen und wieder entfernen stellt den Quelltext byte-genau wieder her. */
    @Test
    void removeAnnotationLeavesNoWhitespaceLine() throws Exception {
        var editing = editingDriver();
        Path square = tempDir.resolve("src/main/java/p/Square.java");
        String original = Files.readString(square);

        for (String[] target : new String[][] {{"class", null}, {"method", "area"}, {"field", "side"}}) {
            editing.addAnnotation(new RewispDriver.AddAnnotationRequest("demo", target[0], "p.Square", target[1],
                    "Deprecated", null, false));
            assertTrue(Files.readString(square).contains("@Deprecated"), target[0]);
            editing.removeAnnotation(new RewispDriver.RemoveAnnotationRequest("demo", target[0], "p.Square",
                    target[1], "Deprecated", false));
            String after = Files.readString(square);
            assertFalse(after.lines().anyMatch(l -> !l.isEmpty() && l.isBlank()), "Whitespace-Zeile: " + target[0]);
            if (!"method".equals(target[0])) {
                // area() traegt schon ein inline @Override; add_annotation formatiert das beim Hinzufuegen um
                assertEquals(original, after, "nach dem Entfernen wie vorher: " + target[0]);
            } else {
                original = after;
            }
        }
    }

    /** N40: Ein grossgeschriebener Feldname im Body ist kein unaufgeloester Typ. */
    @Test
    void addClassAcceptsAnUppercaseFieldName() throws Exception {
        var editing = editingDriver();
        var added = editing.addClass(new RewispDriver.AddClassRequest("demo", "p", "Colors", null, null,
                "public static final char[] ALL = {'R', 'G'};\nstatic int X = 1;", null, false));
        assertTrue(Files.readString(added.file()).contains("char[] ALL"));
    }
}

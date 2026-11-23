/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.jare.rewisp.snapshot.FileSnapshot;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.SnapshotId;

/**
 * AP-4.8 / 4.10 / 4.11: Parse-Fehler erscheinen als Diagnose, nicht setzbare Parameter fuehren zu
 * {@code RECIPE_PARAM_INVALID}, und das Ergebnis behaelt seine Reihenfolge.
 *
 * @author Janusch Rentenatus
 */
class OpenRewriteEngineDiagnosticsTest {

    private static OpenRewriteEngineImpl engine(Set<String> allowedParams) {
        RecipeRegistry registry = new YamlRecipeRegistry();
        registry.register(new RecipeDefinition(
                "renameMethod", "Rename method", "",
                "de.jare.rewisp.rewrite.recipes.RenameMethodRecipe",
                allowedParams, ChangeRisk.MEDIUM, false, false, true));
        return new OpenRewriteEngineImpl(registry);
    }

    private static ProjectSnapshot snapshot(Map<String, String> files) {
        Map<Path, FileSnapshot> map = new HashMap<>();
        files.forEach((p, c) -> map.put(Path.of(p), new FileSnapshot(
                Path.of(p), c, "h-" + p, c.length(), 0L, false, false)));
        return new ProjectSnapshot(new SnapshotId("t", 1, "x"), "t", map, Instant.now());
    }

    @Test
    void unparsableFile_isReportedAsDiagnostic() throws Exception {
        ProjectSnapshot snap = snapshot(Map.of(
                "Good.java", "public class Good { void a() {} }",
                "Broken.java", "public class Broken { void a( { "));
        RewriteRequest req = new RewriteRequest("t", "renameMethod",
                Map.of("oldMethodName", "a", "newMethodName", "b"), Set.of(), snap.id());

        RewriteResult result = engine(Set.of("oldMethodName", "newMethodName")).preview(snap, null, req);

        assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> "PARSE_ERROR".equals(d.code()) && d.path().contains("Broken.java")),
                result.diagnostics().toString());
    }

    @Test
    void unsettableParameter_isRejected() {
        ProjectSnapshot snap = snapshot(Map.of("A.java", "public class A {}"));
        RewriteRequest req = new RewriteRequest("t", "renameMethod",
                Map.of("noSuchSetter", "x"), Set.of(), snap.id());

        RewriteException e = assertThrows(RewriteException.class,
                () -> engine(Set.of("noSuchSetter")).preview(snap, null, req));

        assertEquals("RECIPE_PARAM_INVALID", e.code());
    }

    @Test
    void classpathIsTheUnionOfAllModules_withoutMissingEntries() throws Exception {
        Path x = java.nio.file.Files.createFile(tempDir.resolve("x.jar"));
        Path shared = java.nio.file.Files.createFile(tempDir.resolve("shared.jar"));
        Path y = java.nio.file.Files.createFile(tempDir.resolve("y.jar"));
        var m1 = new de.jare.rewisp.build.ModuleContext("a", Path.of("a"), Set.of(), Set.of(), Set.of(),
                List.of(x.toString(), shared.toString(), tempDir.resolve("missing.jar").toString()), "21");
        var m2 = new de.jare.rewisp.build.ModuleContext("b", Path.of("b"), Set.of(), Set.of(), Set.of(),
                List.of(shared.toString(), y.toString()), "21");
        var ctx = new de.jare.rewisp.build.BuildContext("MAVEN", List.of(m1, m2), null, List.of());

        assertEquals(List.of(x, shared, y), OpenRewriteEngineImpl.classpathOf(ctx));
        assertTrue(OpenRewriteEngineImpl.classpathOf(null).isEmpty());
    }

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    @Test
    void classpathJarTypesAreAttributed_soCallersOfLibraryTypesCanBeRenamed() throws Exception {
        Path libSrc = tempDir.resolve("libsrc/lib");
        java.nio.file.Files.createDirectories(libSrc);
        Path libJava = libSrc.resolve("Lib.java");
        java.nio.file.Files.writeString(libJava, "package lib;\npublic class Lib { public void ping() {} }\n");
        Path libOut = tempDir.resolve("libout");
        java.nio.file.Files.createDirectories(libOut);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", libOut.toString(), libJava.toString()));
        Path jar = tempDir.resolve("lib.jar");
        try (var out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.zip.ZipEntry("lib/Lib.class"));
            out.write(java.nio.file.Files.readAllBytes(libOut.resolve("lib/Lib.class")));
            out.closeEntry();
        }

        RecipeRegistry registry = new YamlRecipeRegistry();
        registry.register(new RecipeDefinition("renameSymbol", "Rename", "",
                "de.jare.rewisp.rewrite.recipes.RenameSymbolRecipe",
                Set.of("className", "oldName", "newName", "scope", "updateCallers"),
                ChangeRisk.MEDIUM, false, false, true));
        var module = new de.jare.rewisp.build.ModuleContext("m", Path.of("m"), Set.of(), Set.of(), Set.of(),
                List.of(jar.toString()), "21");
        var ctx = new de.jare.rewisp.build.BuildContext("MAVEN", List.of(module), null, List.of());
        ProjectSnapshot snap = snapshot(Map.of("A.java",
                "import lib.Lib;\nclass A { void x(Lib l) { l.ping(); } }\n"));
        RewriteRequest req = new RewriteRequest("t", "renameSymbol",
                Map.of("className", "lib.Lib", "oldName", "ping", "newName", "pong",
                        "scope", "method", "updateCallers", "true"), Set.of(), snap.id());

        RewriteResult result = new OpenRewriteEngineImpl(registry).preview(snap, ctx, req);

        RewrittenFile changed = result.changedFiles().get(Path.of("A.java"));
        assertTrue(changed != null && changed.content().contains("l.pong()"),
                "library type must be attributed from the classpath jar: " + result.changedFiles());
    }

    @Test
    void snapshotMismatch_isRejected() {
        ProjectSnapshot snap = snapshot(Map.of("A.java", "public class A {}"));
        SnapshotId other = new SnapshotId("t", 2, "other-hash");
        RewriteRequest req = new RewriteRequest("t", "renameMethod",
                Map.of("oldMethodName", "a", "newMethodName", "b"), Set.of(), other);

        RewriteException e = assertThrows(RewriteException.class,
                () -> engine(Set.of("oldMethodName", "newMethodName")).preview(snap, null, req));

        assertEquals("SNAPSHOT_MISMATCH", e.code());
    }

    @Test
    void resultKeepsInsertionOrder() {
        Map<Path, RewrittenFile> changed = new LinkedHashMap<>();
        for (String n : List.of("z/Z.java", "a/A.java", "m/M.java", "b/B.java", "y/Y.java")) {
            changed.put(Path.of(n), new RewrittenFile("c", "h", "MODIFY"));
        }

        RewriteResult result = new RewriteResult(changed, Set.of(), Set.of(), List.of());

        assertEquals(List.copyOf(changed.keySet()), List.copyOf(result.changedFiles().keySet()));
    }
}

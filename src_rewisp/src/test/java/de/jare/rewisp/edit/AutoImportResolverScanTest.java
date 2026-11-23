/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;

import spoon.reflect.declaration.CtType;

/**
 * AP-4.14: Typ-Erkennung im Text ignoriert Typparameter, verschachtelte Typen, Textbloecke und
 * wertet {@code import}-Zeilen exakt aus; Importe werden gegen den Projekt-Classpath validiert.
 *
 * @author Janusch Rentenatus
 */
class AutoImportResolverScanTest {

    @TempDir
    Path tempDir;

    private ProjectEntry entry;
    private CtType<?> type;
    private ProjectManager mgr;

    private void load(String fileName, String source) throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        // Dateiname = Typname; Package-Verzeichnisse aus der Quelle ableiten
        Path target = src.resolve(fileName);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source);
        mgr = new ProjectManager();
        entry = mgr.load(src.toString(), null, null, true, true);
        String simple = target.getFileName().toString().replace(".java", "");
        type = entry.model().getAllTypes().stream()
                .filter(t -> t.getSimpleName().equals(simple)).findFirst().orElseThrow();
    }

    @Test
    void typeParametersAreNotReportedAsUnresolved() throws Exception {
        load("Box.java", "class Box<E> { E value; }");

        var classParam = new AutoImportResolver().resolve(entry, type, "E get() { return value; }");
        var methodParam = new AutoImportResolver().resolve(entry, type,
                "<T extends Number> T pick(T a, T b) { return a; }");

        assertTrue(classParam.unresolvedTypes().isEmpty(), classParam.unresolvedTypes().toString());
        assertTrue(methodParam.unresolvedTypes().isEmpty(), methodParam.unresolvedTypes().toString());
        mgr.remove(entry.name());
    }

    @Test
    void nestedTypeAccessOnlyNeedsTheOuterType() throws Exception {
        load("Holder.java", "import java.util.Map;\nclass Holder { }");

        var result = new AutoImportResolver().resolve(entry, type,
                "Map.Entry<String, Integer> e = null;");

        assertTrue(result.unresolvedTypes().isEmpty(), result.unresolvedTypes().toString());
        mgr.remove(entry.name());
    }

    @Test
    void textBlockContentIsIgnored() throws Exception {
        load("Tb.java", "class Tb { }");

        String text = "String s = \"\"\"\n  Hello \"World\" Failed Foo\n  \"\"\";";
        var result = new AutoImportResolver().resolve(entry, type, text);

        assertTrue(result.unresolvedTypes().isEmpty(), result.unresolvedTypes().toString());
        mgr.remove(entry.name());
    }

    @Test
    void onDemandImportOfPackageContainingImportWord_isRecognized() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("com/important"));
        Files.writeString(src.resolve("com/important/Thing.java"),
                "package com.important;\npublic class Thing { }\n");
        load("app/User.java", "package app;\nimport com.important.*;\nclass User { }");

        var result = new AutoImportResolver().resolve(entry, type, "Thing t = null;");

        assertTrue(result.importsToAdd().isEmpty(), "already covered by import com.important.*: "
                + result.importsToAdd());
        assertTrue(result.unresolvedTypes().isEmpty(), result.unresolvedTypes().toString());
        mgr.remove(entry.name());
    }

    @Test
    void manualImportIsValidatedAgainstProjectClasspath() throws Exception {
        Path libSrc = tempDir.resolve("libsrc/lib");
        Files.createDirectories(libSrc);
        Path libJava = libSrc.resolve("Lib.java");
        Files.writeString(libJava, "package lib;\npublic class Lib { }\n");
        Path libOut = tempDir.resolve("libout");
        Files.createDirectories(libOut);
        int rc = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", libOut.toString(), libJava.toString());
        assertEquals(0, rc, "test fixture must compile");

        load("Use.java", "class Use { }");
        var env = type.getFactory().getEnvironment();
        List<String> cp = new java.util.ArrayList<>();
        if (env.getSourceClasspath() != null) cp.addAll(List.of(env.getSourceClasspath()));
        cp.add(libOut.toString());
        env.setSourceClasspath(cp.toArray(new String[0]));

        var result = new AutoImportResolver().resolve(entry, type, "Lib l = null;", List.of("lib.Lib"));

        assertEquals(List.of("lib.Lib"), result.importsToAdd(), result.unresolvedTypes().toString());
        assertTrue(result.unresolvedTypes().isEmpty(), result.unresolvedTypes().toString());
        Set<String> unused = result.importsToAdd().stream().collect(Collectors.toSet());
        assertTrue(unused.contains("lib.Lib"));
        mgr.remove(entry.name());
    }
}

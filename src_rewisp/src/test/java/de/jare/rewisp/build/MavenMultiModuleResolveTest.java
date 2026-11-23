/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Multi-Module-Aufloesung des {@link MavenBuildResolver} (Plan 5.24): jedes in {@code <modules>} deklarierte Modul
 * (rekursiv) steht mit eigenem Classpath und getrennten Main-/Test-Source-Roots im Kontext; Probleme einzelner
 * Module werden als Diagnose gemeldet statt die ganze Aufloesung zu kippen. Maven wird durch {@link FakeMaven} ersetzt.
 *
 * @author Janusch Rentenatus
 */
class MavenMultiModuleResolveTest {

    private static final Set<String> GOALS = Set.of("help:effective-pom", "dependency:build-classpath");

    @TempDir
    Path tempDir;

    private Path project;
    private String previousLimit;

    @BeforeEach
    void setUp() throws Exception {
        project = Files.createDirectories(tempDir.resolve("proj"));
        previousLimit = System.getProperty("build.maxModules");
    }

    @AfterEach
    void tearDown() {
        if (previousLimit == null) System.clearProperty("build.maxModules");
        else System.setProperty("build.maxModules", previousLimit);
    }

    private static String pom(String... modules) {
        StringBuilder sb = new StringBuilder("<project><modelVersion>4.0.0</modelVersion>");
        if (modules.length > 0) {
            sb.append("<modules>");
            for (String m : modules) sb.append("<module>").append(m).append("</module>");
            sb.append("</modules>");
        }
        return sb.append("</project>").toString();
    }

    private void module(String relative, String... children) throws Exception {
        Path dir = Files.createDirectories(project.resolve(relative));
        Files.writeString(dir.resolve("pom.xml"), pom(children));
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.createDirectories(dir.resolve("src/test/java"));
    }

    private BuildContext resolve(String failingDirs) throws Exception {
        String mvn = FakeMaven.install(tempDir.resolve("bin"), failingDirs);
        return new MavenBuildResolver(mvn, GOALS).resolve(project, new ResolveOptions(false, 120, 1_000_000, false));
    }

    @Test
    void declaredModulesAreResolvedRecursivelyWithOwnClasspathAndSeparatedRoots() throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("module-a", "module-b"));
        module("module-a", "module-c");
        module("module-a/module-c");
        module("module-b");

        BuildContext ctx = resolve("");

        List<String> ids = ctx.modules().stream().map(ModuleContext::moduleId).toList();
        assertEquals(List.of("proj", "module-a", "module-a/module-c", "module-b"), ids);
        ModuleContext a = ctx.modules().get(1);
        assertEquals(List.of("libs/module-a.jar"), a.compileClasspath());
        assertEquals(Set.of(project.resolve("module-a/src/main/java")), a.sourceRoots());
        assertEquals(Set.of(project.resolve("module-a/src/test/java")), a.testSourceRoots());
        assertEquals("21", a.languageLevel());
        assertEquals(List.of("libs/module-c.jar"), ctx.modules().get(2).compileClasspath());
        assertTrue(ctx.diagnostics().isEmpty(), ctx.diagnostics().toString());
    }

    @Test
    void aModuleWhoseClasspathCannotBeResolvedStaysInTheContextWithADiagnostic() throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("module-a", "module-b"));
        module("module-a");
        module("module-b");

        BuildContext ctx = resolve("\"module-b\"");

        ModuleContext b = ctx.modules().stream().filter(m -> m.moduleId().equals("module-b")).findFirst().orElseThrow();
        assertTrue(b.compileClasspath().isEmpty());
        assertEquals(List.of("libs/module-a.jar"),
                ctx.modules().stream().filter(m -> m.moduleId().equals("module-a")).findFirst().orElseThrow().compileClasspath());
        assertTrue(ctx.diagnostics().stream().anyMatch(d -> d.code() == BuildDiagnosticCode.MODULE_CLASSPATH_FAILED
                && "module-b".equals(d.module())), ctx.diagnostics().toString());
        assertTrue(ctx.diagnostics().stream().anyMatch(d -> d.message().contains("-Dbuild.networkAccess=true")),
                "an offline failure names the way out: " + ctx.diagnostics());
    }

    @Test
    void missingAndOutsideModulesAreReportedNotResolved() throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("missing", "../outside", "module-a"));
        Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(tempDir.resolve("outside/pom.xml"), pom());
        module("module-a");

        BuildContext ctx = resolve("");

        assertEquals(List.of("proj", "module-a"), ctx.modules().stream().map(ModuleContext::moduleId).toList());
        long incomplete = ctx.diagnostics().stream().filter(d -> d.code() == BuildDiagnosticCode.MODULES_INCOMPLETE).count();
        assertEquals(2, incomplete, ctx.diagnostics().toString());
    }

    @Test
    void theModuleLimitStopsFurtherResolution() throws Exception {
        Files.writeString(project.resolve("pom.xml"), pom("m1", "m2", "m3", "m4"));
        for (String m : List.of("m1", "m2", "m3", "m4")) module(m);
        System.setProperty("build.maxModules", "2");

        BuildContext ctx = resolve("");

        assertTrue(ctx.modules().size() <= 3, ctx.modules().toString()); // Root plus hoechstens 2 Module
        assertTrue(ctx.diagnostics().stream().anyMatch(d -> d.code() == BuildDiagnosticCode.MODULES_INCOMPLETE
                && d.message().contains("Module limit")), ctx.diagnostics().toString());
    }
}

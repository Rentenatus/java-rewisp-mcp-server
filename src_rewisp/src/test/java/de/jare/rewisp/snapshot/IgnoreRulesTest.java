/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Tests für {@link IgnoreRules} (AP-2.1). */
class IgnoreRulesTest {

    private final IgnoreRules rules = IgnoreRules.defaults();
    private final Path root = Path.of("work/builds/my-target-app").toAbsolutePath();

    @Test
    void ignoresBuildOutputAboveSrc() {
        assertTrue(rules.isIgnored(root, root.resolve("target/classes/A.java")));
        assertTrue(rules.isIgnored(root, root.resolve("module-a/build/gen/B.java")));
        assertTrue(rules.isIgnored(root, root.resolve(".git/hooks/x")));
    }

    @Test
    void doesNotLookAtPartsOutsideTheRoot() {
        // "builds" und "my-target-app" im Elternpfad duerfen nichts ausloesen
        assertFalse(rules.isIgnored(root, root.resolve("src/main/java/A.java")));
    }

    @Test
    void packageNamedBuildUnderSrcIsSource() {
        assertFalse(rules.isIgnored(root, root.resolve("src/main/java/com/example/build/Util.java")));
        assertFalse(rules.isIgnored(root, root.resolve("module-a/src/main/java/out/Printer.java")));
    }

    /** Review H1: Root liegt selbst im Quellbaum -> Pakete 'build'/'out'/'target' sind Quellcode. */
    @Test
    void rootInsideSourceTree_packageNamedBuildIsSource() {
        Path javaRoot = Path.of("work/app/src/main/java").toAbsolutePath();
        assertFalse(rules.isIgnored(javaRoot, javaRoot.resolve("com/acme/build/Foo.java")));
        Path plainJava = Path.of("work/app/main/java").toAbsolutePath();
        assertFalse(rules.isIgnored(plainJava, plainJava.resolve("org/x/target/Bar.java")));
        // .git bleibt auch dort ignoriert
        assertTrue(rules.isIgnored(javaRoot, javaRoot.resolve(".git/x")));
    }

    /** Ein 'src' weit oberhalb des Projekts macht das Projekt nicht zum Quellbaum. */
    @Test
    void farAwaySrcDirectoryDoesNotDisableBuildOutputIgnore() {
        Path project = Path.of("work/src/team/deep/er/my-project").toAbsolutePath();
        assertTrue(rules.isIgnored(project, project.resolve("target/classes/A.java")));
    }

    @Test
    void matchesWholeSegmentsOnly() {
        assertFalse(rules.isIgnored(root, root.resolve("rebuild/A.java")));
        assertFalse(rules.isIgnored(root, root.resolve(".github/workflows/x.yml")));
        assertFalse(rules.isIgnored(root, root.resolve("targets/A.java")));
    }

    @Test
    void customListIsUsedAndNullIsSafe() {
        IgnoreRules custom = new IgnoreRules(List.of("generated"));
        assertTrue(custom.isIgnored(root, root.resolve("generated/A.java")));
        assertFalse(custom.isIgnored(root, root.resolve("target/A.java")));
        assertFalse(new IgnoreRules(null).isIgnored(root, root.resolve("target/A.java")));
        assertFalse(rules.isIgnored(null, root));
    }

    @Test
    void relativePathWorksOnWindowsSeparators() {
        assertTrue(rules.isIgnoredRelative(Path.of("target").resolve("classes").resolve("A.java")));
        assertFalse(rules.isIgnoredRelative(Path.of("src").resolve("build").resolve("A.java")));
    }

    /** N37: Vergleich ohne Beachtung der Gross-/Kleinschreibung (Windows). */
    @Test
    void matchesDirectoryNamesCaseInsensitively() {
        assertTrue(rules.isIgnored(root, root.resolve("Target/generated-sources/G.java")));
        assertTrue(rules.isIgnored(root, root.resolve(".GIT/x")));
        assertFalse(rules.isIgnored(root, root.resolve("src/main/java/Build/A.java")), "Build unter src bleibt Quellcode");
    }
}

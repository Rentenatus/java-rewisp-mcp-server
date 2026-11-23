/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-5.26: Parent-POM nur bei vorhandenem {@code <parent>}, leeres {@code <relativePath/>},
 * Verzeichnis als relativePath, nur echte Dateien.
 *
 * @author Janusch Rentenatus
 */
class PomUtilsTest {

    @TempDir
    Path tempDir;

    private Path child(String childPom) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><packaging>pom</packaging></project>");
        Path child = tempDir.resolve("child");
        Files.createDirectories(child);
        Files.writeString(child.resolve("pom.xml"), childPom);
        return child;
    }

    @Test
    void noParentElement_meansNoParent_evenIfParentDirectoryHasPom() throws Exception {
        assertNull(PomUtils.resolveParentPomPath(child("<project><artifactId>c</artifactId></project>")));
    }

    @Test
    void emptyRelativePath_meansParentIsNotLocal() throws Exception {
        assertNull(PomUtils.resolveParentPomPath(
                child("<project><parent><artifactId>p</artifactId><relativePath/></parent></project>")));
        assertNull(PomUtils.resolveParentPomPath(
                child("<project><parent><artifactId>p</artifactId><relativePath></relativePath></parent></project>")));
    }

    @Test
    void relativePathPointingToDirectory_usesPomXmlInside() throws Exception {
        Path child = child("<project><parent><relativePath>..</relativePath></parent></project>");
        assertEquals(tempDir.resolve("pom.xml"), PomUtils.resolveParentPomPath(child));
    }

    @Test
    void parentInComment_isIgnored() throws Exception {
        assertNull(PomUtils.resolveParentPomPath(
                child("<project><!-- <parent><artifactId>p</artifactId></parent> --></project>")));
    }

    @Test
    void relativePathToMissingFile_returnsNull() throws Exception {
        assertNull(PomUtils.resolveParentPomPath(
                child("<project><parent><relativePath>../nope/pom.xml</relativePath></parent></project>")));
    }
}

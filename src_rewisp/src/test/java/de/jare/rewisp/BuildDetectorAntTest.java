/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-5.13: Ant und Ant+Ivy werden unterschieden; ein einzelnes {@code ivy.xml} ist kein Ant-Projekt.
 *
 * @author Janusch Rentenatus
 */
class BuildDetectorAntTest {

    @TempDir
    Path dir;

    @Test
    void buildXmlOnly_isAnt() throws Exception {
        Files.writeString(dir.resolve("build.xml"), "<project/>");

        var info = new BuildDetector().detect(dir);

        assertEquals(BuildDetector.BuildType.ANT, info.type());
        assertEquals("Ant", info.detail());
        assertEquals(List.of("build.xml"), info.detectedMarkers());
    }

    @Test
    void buildXmlAndIvyXml_isAntPlusIvy() throws Exception {
        Files.writeString(dir.resolve("build.xml"), "<project/>");
        Files.writeString(dir.resolve("ivy.xml"), "<ivy-module/>");

        var info = new BuildDetector().detect(dir);

        assertEquals(BuildDetector.BuildType.ANT, info.type());
        assertEquals("Ant + Ivy", info.detail());
        assertEquals(List.of("build.xml", "ivy.xml"), info.detectedMarkers());
    }

    @Test
    void ivyXmlOnly_isNotAnt() throws Exception {
        Files.writeString(dir.resolve("ivy.xml"), "<ivy-module/>");

        assertEquals(BuildDetector.BuildType.RAW, new BuildDetector().detect(dir).type());
    }
}

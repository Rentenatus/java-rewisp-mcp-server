/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-5.11: Lombok wird nur an echten {@code lombok}-Imports/Annotationen erkannt, und
 * {@code ${...}}-Versionen in der POM werden aufgeloest.
 *
 * @author Janusch Rentenatus
 */
class LombokDetectorFixesTest {

    @TempDir
    Path tempDir;

    private Path sourceFile(String content) throws Exception {
        Path dir = tempDir.resolve("src/main/java/p");
        Files.createDirectories(dir);
        Path f = dir.resolve("A.java");
        Files.writeString(f, content);
        return f;
    }

    @Test
    void springValueAnnotation_isNotLombok() throws Exception {
        sourceFile("package p;\nimport org.springframework.beans.factory.annotation.Value;\n"
                + "class A { @Value(\"${x}\") String x; @NonNull String y; }\n");

        assertFalse(new LombokDetector().hasLombokInSources(tempDir));
    }

    @Test
    void lombokImportOrQualifiedAnnotation_isDetected() throws Exception {
        sourceFile("package p;\nimport lombok.Value;\n@Value class A { int x; }\n");
        assertTrue(new LombokDetector().hasLombokInSources(tempDir));

        sourceFile("package p;\n@lombok.Data class A { int x; }\n");
        assertTrue(new LombokDetector().hasLombokInSources(tempDir));
    }

    @Test
    void pomVersionProperty_isResolved() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project><properties><lombokVersion>1.18.30</lombokVersion></properties>"
              + "<dependencies><dependency><groupId>org.projectlombok</groupId>"
              + "<artifactId>lombok</artifactId><version>${lombokVersion}</version>"
              + "</dependency></dependencies></project>");

        var info = new LombokDetector().detect(tempDir);

        assertTrue(info.present());
        assertEquals("1.18.30", info.version());
    }

    @Test
    void pomVersionPropertyUnresolvable_yieldsNullVersionNotPlaceholder() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"),
                "<project><dependencies><dependency><groupId>org.projectlombok</groupId>"
              + "<artifactId>lombok</artifactId><version>${managed}</version>"
              + "</dependency></dependencies></project>");

        var info = new LombokDetector().detect(tempDir);

        assertTrue(info.present());
        assertEquals(null, info.version());
    }
}

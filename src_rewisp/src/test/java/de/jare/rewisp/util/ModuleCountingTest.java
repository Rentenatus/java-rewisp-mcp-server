/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * AP-5.15: Modulzaehlung kennt Gradle-include-Varianten und ignoriert Maven-Kommentare.
 *
 * @author Janusch Rentenatus
 */
class ModuleCountingTest {

    @Test
    void mavenCommentedModulesAreIgnoredAndDuplicatesCountOnce() {
        String pom = "<project><modules><module>a</module><!-- <module>old</module> -->"
                + "<module> b </module></modules>"
                + "<profiles><profile><modules><module>a</module><module>c</module></modules></profile></profiles></project>";

        assertEquals(Set.of("a", "b", "c"), ProjectBuildHelpers.mavenModuleNames(pom));
    }

    @Test
    void gradleIncludeVariants() {
        String settings = "rootProject.name = 'demo'\n"
                + "include 'a', 'b'\n"
                + "include(\":c\", \":d:e\")\n"
                + "include \"f\"\n"
                + "// include 'commented'\n"
                + "/* include 'blockcommented' */\n"
                + "include(\n  \"g\",\n  \"h\"\n)\n";

        assertEquals(Set.of("a", "b", "c", "d:e", "f", "g", "h"), ProjectBuildHelpers.gradleModuleNames(settings));
    }
}

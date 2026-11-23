/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 5.27 (BatBadBut): Argumente mit cmd.exe-Metazeichen werden nicht an .bat/.cmd-Skripte uebergeben.
 *
 * @author Janusch Rentenatus
 */
class ProcessRunnerBatchArgsTest {

    @Test
    void batchFilesRejectCmdMetacharactersInArguments() {
        for (String bad : List.of("a&calc", "a|b", "a>b", "a<b", "a^b", "%PATH%", "x\"y", "hi!", "a\nb")) {
            assertEquals(bad, ProcessRunner.unsafeBatchArgument(List.of("mvn.cmd", "-q", bad)), bad);
            assertEquals(bad, ProcessRunner.unsafeBatchArgument(List.of("C:\\p\\gradlew.BAT", bad)), bad);
        }
    }

    @Test
    void normalArgumentsAndNonBatchProgramsAreAccepted() {
        assertNull(ProcessRunner.unsafeBatchArgument(List.of("mvn.cmd", "help:effective-pom",
                "-Doutput=C:\\Users\\me\\AppData\\Local\\Temp\\rewisp-maven1\\effective-pom.xml", "-o", "-q")));
        assertNull(ProcessRunner.unsafeBatchArgument(List.of("java", "-version", "a&b"))); // kein cmd.exe im Spiel
        assertNull(ProcessRunner.unsafeBatchArgument(List.of("gradle.bat")));
        assertNull(ProcessRunner.unsafeBatchArgument(List.of()));
    }

    @Test
    void runRefusesToStartAnUnsafeBatchCall() {
        assertThrows(IOException.class, () -> ProcessRunner.run(List.of("evil.cmd", "a&calc"), null, 5, 1000, List.of()));
    }
}

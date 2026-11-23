/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * WK-14/WK-17: Hygiene-Pruefungen am Quelltext. Der Server spricht MCP ueber stdout - dorthin darf
 * kein Code schreiben - und alle Java-Quellen haben einheitliche Zeilenenden (CRLF).
 *
 * @author Janusch Rentenatus
 */
class SourceHygieneTest {

    private static List<Path> javaFiles(String root) throws Exception {
        try (Stream<Path> s = Files.walk(Path.of(root))) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void mainCodeNeverWritesToStdout() throws Exception {
        for (String root : new String[] {"src/main/java", "../src_mcp/src/main/java"}) {
            for (Path p : javaFiles(root)) {
                String text = Files.readString(p);
                assertTrue(!text.contains("System" + ".out") && !text.contains("printStack" + "Trace"),
                        p + " schreibt auf stdout oder nutzt printStackTrace; stdout gehoert dem MCP-Protokoll");
            }
        }
    }

    /** Modulgrenze: das Backend importiert weder das MCP-SDK noch Jackson 3 noch die Tool-Schicht. */
    @Test
    void backendDoesNotReferenceTheMcpLayer() throws Exception {
        for (Path p : javaFiles("src/main/java")) {
            String text = Files.readString(p);
            assertTrue(!text.contains("io.modelcontext" + "protocol") && !text.contains("tools.jack" + "son")
                            && !text.contains("de.jare.rewisp." + "tools."),
                    p + " verletzt die Modulgrenze src_rewisp -> src_mcp");
        }
    }

    @Test
    void javaSourcesUseCrlfConsistently() throws Exception {
        for (String root : new String[] {"src/main/java", "src/test/java", "../src_mcp/src/main/java", "../src_mcp/src/test/java"}) {
            for (Path p : javaFiles(root)) {
                String text = Files.readString(p);
                String withoutCrlf = text.replace("\r\n", "");
                assertTrue(!withoutCrlf.contains("\n"), p + " enthaelt Zeilen mit LF statt CRLF");
            }
        }
    }
}
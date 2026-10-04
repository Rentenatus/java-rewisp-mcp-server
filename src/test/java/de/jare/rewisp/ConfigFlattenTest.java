/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressionstest für das Config-Flattening: application.yaml wird beim
 * Server-Start in System-Properties gestreckt. Listen wurden dabei früher
 * per String.valueOf() zu Strings wie "[]" — woraufhin die WorkspaceRegistry
 * eine Allowlist mit dem literalen Eintrag "[]" hatte und JEDE Projekt-Load
 * als "Path traversal detected" ablehnte (gefunden am 2026-10-03 beim
 * ersten Live-Start des Servers).
 */
class ConfigFlattenTest {

    @Test
    void emptyYamlListFlattensToBlankProperty() {
        Map<String, Object> config = Map.of(
                "workspace", Map.of("allowedRoots", List.of(), "debounceMillis", 200));
        JavaMcpServer.flatten("", config);
        String flattened = System.getProperty("workspace.allowedRoots");
        assertTrue(flattened == null || flattened.isBlank(),
                "Leere YAML-Liste muss leer/blank ergeben, war: '" + flattened + "'");
    }

    @Test
    void scalarListFlattensToCommaJoinedProperty() {
        Map<String, Object> config = Map.of(
                "workspace", Map.of("ignoredDirectories", List.of("target", "build", ".git")));
        JavaMcpServer.flatten("", config);
        assertEquals("target,build,.git", System.getProperty("workspace.ignoredDirectories"));
    }

    @Test
    void realApplicationYamlYieldsUsableAllowedRoots() throws Exception {
        try (InputStream in = JavaMcpServer.class.getResourceAsStream("/application.yaml")) {
            assertNotNull(in, "application.yaml muss auf dem Classpath liegen");
            Map<String, Object> config = new Yaml().load(in);
            JavaMcpServer.flatten("", config);

            // Die echte Config deklariert allowedRoots als leere Liste:
            // "keine Allowlist konfiguriert" -> alles erlaubt.
            String allowedRoots = System.getProperty("workspace.allowedRoots");
            assertTrue(allowedRoots == null || allowedRoots.isBlank(),
                    "workspace.allowedRoots muss blank sein, war: '" + allowedRoots + "'");

            // Eine daraus gebaute Allowlist bleibt leer -> isAllowed() erlaubt alles
            List<String> parsed = new java.util.ArrayList<>();
            if (allowedRoots != null && !allowedRoots.isBlank()) {
                for (String root : allowedRoots.split(",")) parsed.add(root.trim());
            }
            assertTrue(parsed.isEmpty(), "Allowlist muss leer bleiben");
        }
    }
}

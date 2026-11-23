/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * WK-18: keine ungenutzten Importe in Main- und Testcode. {@code -Xlint} meldet sie nicht, deshalb prueft dieser Test
 * jeden Einzel-Import: sein einfacher Name muss ausserhalb der Import-Zeilen vorkommen (Javadoc-Verweise zaehlen als
 * Nutzung). Wildcard-Importe werden nicht geprueft.
 *
 * @author Janusch Rentenatus
 */
class UnusedImportsTest {

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+)\\.(\\w+)\\s*;\\s*$");

    @Test
    void noUnusedImportsInSources() throws Exception {
        List<String> unused = new ArrayList<>();
        for (String root : new String[] {"src/main/java", "src/test/java", "../src_mcp/src/main/java", "../src_mcp/src/test/java"}) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    collectUnused(p, unused);
                }
            }
        }
        assertTrue(unused.isEmpty(), "Ungenutzte Importe:\n" + String.join("\n", unused));
    }

    private static void collectUnused(Path file, List<String> unused) throws Exception {
        List<String> lines = Files.readAllLines(file);
        StringBuilder body = new StringBuilder();
        List<String[]> imports = new ArrayList<>();
        for (String line : lines) {
            Matcher m = IMPORT.matcher(line.trim());
            if (m.matches()) {
                imports.add(new String[] {m.group(3), line.trim()});
            } else if (!line.trim().startsWith("import ")) {
                body.append(line).append('\n');
            }
        }
        String text = body.toString();
        for (String[] imp : imports) {
            if (!Pattern.compile("\\b" + Pattern.quote(imp[0]) + "\\b").matcher(text).find()) {
                unused.add(file + ": " + imp[1]);
            }
        }
    }
}
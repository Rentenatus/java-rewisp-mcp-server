/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaParser;
import org.openrewrite.tree.ParseError;

/**
 * AP-0.2 (RT:C-01/C-06): Das Projekt kompiliert und laeuft auf Java 21; der OpenRewrite-Parser
 * muss Java-21-Syntax (Record-Patterns, Pattern-Matching-Switch) verstehen, sonst scheitern Edits
 * an modernen Quelldateien.
 *
 * @author Janusch Rentenatus
 */
class Java21ParsingTest {

    private static final String JAVA_21_SOURCE = """
            package demo;

            sealed interface Shape permits Circle, Square {}
            record Circle(double r) implements Shape {}
            record Square(double side) implements Shape {}

            class Areas {
                static double area(Shape shape) {
                    return switch (shape) {
                        case Circle(double r) -> Math.PI * r * r;
                        case Square(double s) when s > 0 -> s * s;
                        case Square sq -> 0;
                    };
                }
            }
            """;

    @Test
    void parsesJava21RecordPatternsAndPatternSwitch() {
        List<SourceFile> parsed = JavaParser.fromJavaVersion().build()
                .parse(JAVA_21_SOURCE).collect(Collectors.toList());

        assertEquals(1, parsed.size());
        assertTrue(!(parsed.get(0) instanceof ParseError),
                "Java-21-Syntax nicht parsebar: " + parsed.get(0));
    }
}

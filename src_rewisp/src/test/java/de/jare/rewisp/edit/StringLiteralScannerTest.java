/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;



import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.support.compiler.VirtualFile;

/**
 * 8.6: Der String-Literal-Scan findet nur ganze Woerter, deckelt die Trefferzahl und kennt Positionen.
 *
 * @author Janusch Rentenatus
 */
class StringLiteralScannerTest {

    private static CtModel model(String source) {
        Launcher l = new Launcher();
        l.getEnvironment().setNoClasspath(true);
        l.addInputResource(new VirtualFile(source, "A.java"));
        return l.buildModel();
    }

    @Test
    void matchesWholeWordsOnly() {
        CtModel m = model("class A {\n  String a = \"valid identity\";\n  String b = \"the id field\";\n"
                + "  String c = \"getId()\";\n}\n");
        StringLiteralScanner.Result r = StringLiteralScanner.scan(m, "id");
        assertEquals(1, r.total(), r.toString());
        assertEquals("\"the id field\"", r.hits().get(0).text());
        assertEquals(3, r.hits().get(0).line());
    }

    @Test
    void capsTheNumberOfHitsButCountsAll() {
        StringBuilder src = new StringBuilder("class A {\n");
        for (int i = 0; i < 25; i++) src.append("  String s").append(i).append(" = \"id ").append(i).append("\";\n");
        src.append("}\n");
        StringLiteralScanner.Result r = StringLiteralScanner.scan(model(src.toString()), "id");
        assertEquals(25, r.total());
        assertEquals(StringLiteralScanner.MAX_HITS, r.hits().size());
        assertEquals(5, r.omitted());
    }

    @Test
    void nullAndEmptyInputsGiveNoHits() {
        assertEquals(0, StringLiteralScanner.scan(null, "x").total());
        assertEquals(0, StringLiteralScanner.scan(model("class A {}\n"), "").total());
        assertTrue(StringLiteralScanner.scan(model("class A {}\n"), "x").hits().isEmpty());
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.edit;

import de.jare.rewisp.edit.SourceCodeUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * AP-4.18: {@code insertImports} setzt Imports nach dem Package bzw. hinter einen Header-Kommentar,
 * nie vor Lizenz-Kommentar oder Package-Zeile.
 *
 * @author Janusch Rentenatus
 */
class SourceCodeUtilsInsertImportsTest {

    @Test
    void withLicenseHeaderAndPackage_importGoesAfterPackage() {
        String src = "/*\n * License\n */\npackage a.b;\n\npublic class X {}\n";

        String out = SourceCodeUtils.insertImports(src, List.of("java.util.List"));

        assertEquals("/*\n * License\n */\npackage a.b;\n\nimport java.util.List;\n\npublic class X {}\n", out);
    }

    @Test
    void headerCommentWithoutPackage_importGoesBelowHeader() {
        String src = "/*\n * License\n */\npublic class X {}\n";

        String out = SourceCodeUtils.insertImports(src, List.of("java.util.List"));

        assertTrue(out.startsWith("/*\n * License\n */\n"), out);
        assertTrue(out.indexOf("import java.util.List;") > out.indexOf("*/"), out);
        assertTrue(out.indexOf("import java.util.List;") < out.indexOf("public class X"), out);
    }

    @Test
    void lineCommentHeaderAndExistingImports_appendsAfterLastImport() {
        String src = "// header\npackage p;\n\nimport java.util.Map;\n\nclass X {}\n";

        String out = SourceCodeUtils.insertImports(src, List.of("java.util.List"));

        assertTrue(out.indexOf("import java.util.List;") > out.indexOf("import java.util.Map;"), out);
        assertTrue(out.indexOf("import java.util.List;") < out.indexOf("class X"), out);
        assertTrue(out.startsWith("// header\npackage p;"), out);
    }
}

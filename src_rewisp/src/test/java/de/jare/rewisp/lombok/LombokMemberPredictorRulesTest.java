/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.lombok;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import spoon.Launcher;
import spoon.reflect.declaration.CtType;
import spoon.support.compiler.VirtualFile;

/**
 * AP-5.12: Lombok-Vorhersage folgt den echten Lombok-Regeln (nur Lombok-Annotationen,
 * Konstruktor-Regeln, Builder-Name, Boolean-Getter/Setter).
 *
 * @author Janusch Rentenatus
 */
class LombokMemberPredictorRulesTest {

    private static CtType<?> type(String source, String fqn) {
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.addInputResource(new VirtualFile(source, "T.java"));
        launcher.buildModel();
        return launcher.getFactory().Type().get(fqn);
    }

    private static List<String> sigs(CtType<?> t) {
        return LombokMemberPredictor.predict(t).stream()
                .map(LombokMemberPredictor.PredictedMember::signature).toList();
    }

    @Test
    void nonLombokAnnotationsWithSameNames_areIgnored() {
        CtType<?> t = type("package p;\nimport org.springframework.beans.factory.annotation.Value;\n"
                + "class T { @Value(\"x\") String a; }\n", "p.T");

        assertFalse(LombokMemberPredictor.hasLombokAnnotations(t));
        assertTrue(LombokMemberPredictor.predict(t).isEmpty());
    }

    @Test
    void data_generatesOnlyTheRequiredArgsConstructor() {
        CtType<?> t = type("package p;\nimport lombok.Data;\nimport lombok.NonNull;\n"
                + "@Data class T { @NonNull String name; final int id = 1; int age; static int COUNT; }\n", "p.T");

        List<String> s = sigs(t);

        assertEquals(1, s.stream().filter(x -> x.startsWith("T(")).count(), s.toString());
        assertTrue(s.contains("T(java.lang.String name)"), s.toString());
        assertTrue(s.stream().noneMatch(x -> x.contains("builder")), s.toString());
        assertTrue(s.stream().anyMatch(x -> x.endsWith("getAge()")), s.toString());
        assertTrue(s.stream().anyMatch(x -> x.contains("setAge(")), s.toString());
        assertTrue(s.stream().noneMatch(x -> x.contains("setId(")), "final field has no setter: " + s);
        assertTrue(s.stream().noneMatch(x -> x.contains("COUNT")), "static fields are skipped: " + s);
    }

    @Test
    void explicitConstructor_suppressesDataConstructor() {
        CtType<?> t = type("package p;\nimport lombok.Data;\n@Data class T { int a; T(int a) { this.a = a; } }\n", "p.T");

        assertTrue(sigs(t).stream().noneMatch(x -> x.startsWith("T(")), sigs(t).toString());
    }

    @Test
    void value_hasAllArgsConstructor_noSetters() {
        CtType<?> t = type("package p;\nimport lombok.Value;\n@Value class T { int a; String b; }\n", "p.T");

        List<String> s = sigs(t);

        assertTrue(s.contains("T(int a, java.lang.String b)"), s.toString());
        assertTrue(s.stream().noneMatch(x -> x.contains("set")), s.toString());
        assertTrue(s.stream().anyMatch(x -> x.endsWith("getA()")), s.toString());
    }

    @Test
    void builder_isNamedTypeBuilder() {
        CtType<?> t = type("package p;\nimport lombok.Builder;\n@Builder class T { int a; }\n", "p.T");

        List<String> s = sigs(t);

        assertTrue(s.contains("static TBuilder builder()"), s.toString());
        assertTrue(s.stream().anyMatch(x -> x.startsWith("T build()")), s.toString());
    }

    @Test
    void booleanGetterAndSetterNames() {
        CtType<?> t = type("package p;\nimport lombok.Data;\n@Data class T { boolean isActive; boolean done;"
                + " Boolean flag; String isName; }\n", "p.T");

        List<String> s = sigs(t);

        assertTrue(s.contains("boolean isActive()"), s.toString());
        assertTrue(s.stream().anyMatch(x -> x.contains("setActive(")), s.toString());
        assertTrue(s.contains("boolean isDone()"), s.toString());
        assertTrue(s.stream().anyMatch(x -> x.endsWith("getFlag()")), s.toString());
        assertTrue(s.stream().anyMatch(x -> x.endsWith("getIsName()")), s.toString());
    }

    @Test
    void shortIsFieldDoesNotOverflow() {
        CtType<?> t = type("package p;\nimport lombok.Getter;\n@Getter class T { boolean is; int x; }\n", "p.T");

        assertTrue(sigs(t).stream().anyMatch(x -> x.contains("isIs()")), sigs(t).toString());
    }

    @Test
    void wildcardLombokImport_isRecognized() {
        CtType<?> t = type("package p;\nimport lombok.*;\n@Getter class T { int a; }\n", "p.T");

        assertTrue(LombokMemberPredictor.hasLombokAnnotations(t));
        assertTrue(sigs(t).stream().anyMatch(x -> x.endsWith("getA()")), sigs(t).toString());
    }
}

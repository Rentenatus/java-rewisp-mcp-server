/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jare.rewisp.lombok;

import java.util.HashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;

/**
 * Heuristic predictor of Lombok-generated members, used as a safety net when
 * a project is loaded with delombok disabled (or delombok failed). It inspects
 * Lombok annotations on a type and reports which members Lombok would have
 * generated, even though they are NOT present in the AST.
 *
 * <p>Only real Lombok annotations count (qualified {@code lombok.*}, or the simple name when the
 * file has {@code import lombok.*}); a Spring {@code @Value} is not Lombok's {@code @Value}.</p>
 *
 * @author Alejandro Ferreira
 */
public final class LombokMemberPredictor {

    private static final Set<String> TYPE_LEVEL_GETTER = Set.of("Getter", "Data", "Value");
    private static final Set<String> TYPE_LEVEL_SETTER = Set.of("Setter", "Data");

    public record PredictedMember(String kind, String signature) {}

/**
 * Private constructor to prevent instantiation.
 */
    private LombokMemberPredictor() {}

/**
 * Predicts which members Lombok would generate for the given type based on its annotations.
 */
    public static List<PredictedMember> predict(CtType<?> type) {
        if (type == null) return List.of();
        List<PredictedMember> out = new ArrayList<>();
        Set<String> typeAnnots = lombokAnnotationNames(type, type);
        String name = type.getSimpleName();

        boolean data = typeAnnots.contains("Data");
        boolean value = typeAnnots.contains("Value");
        boolean builder = typeAnnots.contains("Builder");
        boolean explicitCtor = hasExplicitConstructor(type);
        boolean ctorAnnotation = typeAnnots.contains("NoArgsConstructor")
                || typeAnnots.contains("RequiredArgsConstructor") || typeAnnots.contains("AllArgsConstructor");
        // @Data/@Value/@Builder erzeugen ihren Konstruktor nur, wenn weder ein expliziter noch ein
        // annotierter Konstruktor existiert
        boolean implicitCtorAllowed = !explicitCtor && !ctorAnnotation;

        if (typeAnnots.contains("ToString") || data || value) {
            out.add(new PredictedMember("method", "String toString()"));
        }
        if (typeAnnots.contains("EqualsAndHashCode") || data || value) {
            out.add(new PredictedMember("method", "boolean equals(Object obj)"));
            out.add(new PredictedMember("method", "int hashCode()"));
        }
        if (typeAnnots.contains("NoArgsConstructor")) {
            out.add(new PredictedMember("constructor", name + "()"));
        }
        if (typeAnnots.contains("RequiredArgsConstructor") || (data && implicitCtorAllowed)) {
            out.add(new PredictedMember("constructor", name + "(" + params(requiredArgsFields(type)) + ")"));
        }
        if (typeAnnots.contains("AllArgsConstructor") || ((value || builder) && implicitCtorAllowed)) {
            out.add(new PredictedMember("constructor", name + "(" + params(allInstanceFields(type)) + ")"));
        }
        if (builder) {
            // Lombok benennt die Builder-Klasse <Typ>Builder
            out.add(new PredictedMember("method", "static " + name + "Builder builder()"));
            out.add(new PredictedMember("method", name + " build()  (on inner " + name + "Builder)"));
        }

        boolean hasTypeGetter = typeAnnots.stream().anyMatch(TYPE_LEVEL_GETTER::contains);
        boolean hasTypeSetter = typeAnnots.stream().anyMatch(TYPE_LEVEL_SETTER::contains);

        for (CtField<?> field : allInstanceFields(type)) {
            Set<String> fieldAnnots = lombokAnnotationNames(field, type);
            String fieldName = field.getSimpleName();
            String typeName = field.getType().getQualifiedName();
            // @Value macht alle Felder implizit final
            boolean isFinal = value || field.hasModifier(ModifierKind.FINAL);

            if (fieldAnnots.contains("Getter") || hasTypeGetter) {
                out.add(new PredictedMember("method", typeName + " " + getterName(field) + "()"));
            }
            if (!isFinal && (fieldAnnots.contains("Setter") || hasTypeSetter)) {
                out.add(new PredictedMember("method",
                        "void " + setterName(field) + "(" + typeName + " " + fieldName + ")"));
            }
        }

        return out;
    }

/**
 * Checks whether the given type or any of its fields has Lombok annotations.
 */
    public static boolean hasLombokAnnotations(CtType<?> type) {
        if (type == null) return false;
        if (!lombokAnnotationNames(type, type).isEmpty()) return true;
        for (CtField<?> f : type.getFields()) {
            if (!lombokAnnotationNames(f, type).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Simple names of the LOMBOK annotations on {@code element}: qualified {@code lombok.*}, or an
     * unqualified name when the enclosing file imports {@code lombok.*}.
     */
    private static Set<String> lombokAnnotationNames(CtElement element, CtType<?> context) {
        boolean wildcard = importsLombokWildcard(context);
        Set<String> names = new HashSet<>();
        for (var annotation : element.getAnnotations()) {
            String qualified = annotation.getAnnotationType().getQualifiedName();
            if (qualified == null) continue;
            if (qualified.startsWith("lombok.") || (wildcard && qualified.indexOf('.') < 0)) {
                names.add(annotation.getAnnotationType().getSimpleName());
            }
        }
        return names;
    }

    private static boolean importsLombokWildcard(CtType<?> type) {
        try {
            var cu = type.getFactory().CompilationUnit().getOrCreate(type);
            for (var imp : cu.getImports()) {
                if (imp.toString().contains("lombok.*")) return true;
            }
        } catch (RuntimeException e) {
            // ohne Compilation-Unit gilt nur die qualifizierte Form
        }
        return false;
    }

    private static boolean hasExplicitConstructor(CtType<?> type) {
        return type instanceof CtClass<?> c && c.getConstructors().stream().anyMatch(k -> !k.isImplicit());
    }

    private static String params(List<CtField<?>> fields) {
        return fields.stream()
                .map(f -> f.getType().getQualifiedName() + " " + f.getSimpleName())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    /** All non-static instance fields. */
    private static List<CtField<?>> allInstanceFields(CtType<?> type) {
        return type.getFields().stream()
                .filter(f -> !f.hasModifier(ModifierKind.STATIC))
                .toList();
    }

    /** Fields of the RequiredArgsConstructor: uninitialized final fields and {@code @NonNull} fields. */
    private static List<CtField<?>> requiredArgsFields(CtType<?> type) {
        return allInstanceFields(type).stream()
                .filter(f -> f.getDefaultExpression() == null)
                .filter(f -> f.hasModifier(ModifierKind.FINAL)
                        || lombokAnnotationNames(f, type).contains("NonNull"))
                .toList();
    }

    private static boolean isPrimitiveBoolean(CtField<?> field) {
        return field.getType() != null && field.getType().isPrimitive()
                && "boolean".equals(field.getType().getSimpleName());
    }

    /** Lombok: boolean {@code isActive} has the getter {@code isActive()}, everything else {@code getXxx()}. */
    private static String getterName(CtField<?> field) {
        String n = field.getSimpleName();
        if (isPrimitiveBoolean(field)) {
            return hasIsPrefix(n) ? n : "is" + capitalize(n);
        }
        return "get" + capitalize(n);
    }

    /** Lombok: boolean {@code isActive} has the setter {@code setActive(...)}. */
    private static String setterName(CtField<?> field) {
        String n = field.getSimpleName();
        if (isPrimitiveBoolean(field) && hasIsPrefix(n)) {
            return "set" + n.substring(2);
        }
        return "set" + capitalize(n);
    }

    /** {@code isXxx} with a capital letter after the prefix (i.e. a real boolean prefix). */
    private static boolean hasIsPrefix(String name) {
        return name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
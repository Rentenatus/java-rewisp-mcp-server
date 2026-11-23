/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.analysis;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.ModifierKind;

/**
 * Complete signature rendering for the inspect tools: modifiers (incl. {@code default},
 * {@code final}, {@code synchronized}), type parameters, parameters (varargs), {@code throws}.
 *
 * @author Janusch Rentenatus
 */
public final class SignatureFormatter {

    private static final List<ModifierKind> ORDER = List.of(
            ModifierKind.PUBLIC, ModifierKind.PROTECTED, ModifierKind.PRIVATE, ModifierKind.ABSTRACT,
            ModifierKind.STATIC, ModifierKind.FINAL, ModifierKind.SYNCHRONIZED, ModifierKind.NATIVE,
            ModifierKind.STRICTFP);

    private SignatureFormatter() {}

    /** z.B. {@code public static <T extends Comparable<T>> T max(T a, T b) throws IOException}. */
    public static String method(CtMethod<?> m) {
        StringBuilder sb = new StringBuilder(modifiers(m.getModifiers()));
        if (m.isDefaultMethod()) sb.append("default ");
        if (!m.getFormalCtTypeParameters().isEmpty()) {
            sb.append('<').append(m.getFormalCtTypeParameters().stream()
                    .map(Object::toString).collect(Collectors.joining(", "))).append("> ");
        }
        sb.append(m.getType()).append(' ').append(m.getSimpleName());
        return sb.append(parametersAndThrows(m)).toString();
    }

    /** z.B. {@code public Foo(int x)}. */
    public static String constructor(CtConstructor<?> c) {
        StringBuilder sb = new StringBuilder(modifiers(c.getModifiers()));
        if (!c.getFormalCtTypeParameters().isEmpty()) {
            sb.append('<').append(c.getFormalCtTypeParameters().stream()
                    .map(Object::toString).collect(Collectors.joining(", "))).append("> ");
        }
        sb.append(c.getDeclaringType().getSimpleName());
        return sb.append(parametersAndThrows(c)).toString();
    }

    private static String modifiers(Set<ModifierKind> modifiers) {
        StringBuilder sb = new StringBuilder();
        for (ModifierKind k : ORDER) {
            if (modifiers.contains(k)) sb.append(k.toString()).append(' ');
        }
        return sb.toString();
    }

    private static String parametersAndThrows(CtExecutable<?> e) {
        StringBuilder sb = new StringBuilder("(");
        List<CtParameter<?>> params = e.getParameters();
        for (int i = 0; i < params.size(); i++) {
            CtParameter<?> p = params.get(i);
            if (i > 0) sb.append(", ");
            String type = String.valueOf(p.getType());
            if (p.isVarArgs() && type.endsWith("[]")) type = type.substring(0, type.length() - 2) + "...";
            sb.append(type).append(' ').append(p.getSimpleName());
        }
        sb.append(')');
        if (!e.getThrownTypes().isEmpty()) {
            sb.append(" throws ").append(e.getThrownTypes().stream()
                    .map(Object::toString).collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }
}

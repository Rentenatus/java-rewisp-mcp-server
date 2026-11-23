/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.analysis;

import java.util.WeakHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;

/**
 * Central type lookup in the Spoon model. {@code model.getAllTypes()} only returns top-level types;
 * nested types are found via their outer type. Accepts {@code a.Outer.Inner} and
 * {@code a.Outer$Inner}.
 *
 * @author Janusch Rentenatus
 */
public final class TypeLookup {

    private TypeLookup() {}

    /** The type for the (possibly nested) name, or {@code null}. */
    public static CtType<?> find(CtModel model, String qualifiedName) {
        if (model == null || qualifiedName == null || qualifiedName.isBlank()) return null;
        String name = qualifiedName.trim().replace('$', '.');
        // Am laengsten passendes Top-Level-Praefix suchen, der Rest sind geschachtelte Typen
        int end = name.length();
        while (end > 0) {
            String prefix = name.substring(0, end);
            CtType<?> top = topLevel(model, prefix);
            if (top != null) {
                return nested(top, end == name.length() ? "" : name.substring(end + 1));
            }
            end = name.lastIndexOf('.', end - 1);
        }
        return null;
    }

    /** Like {@link #find}, but throws {@link IllegalArgumentException} with {@code messagePrefix + name}. */
    public static CtType<?> require(CtModel model, String qualifiedName, String messagePrefix) {
        CtType<?> type = find(model, qualifiedName);
        if (type == null) {
            throw new TypeNotFoundException(messagePrefix + qualifiedName, qualifiedName);
        }
        return type;
    }

    /** All named types of the model: top-level and nested (without anonymous and local classes). */
    public static List<CtType<?>> allTypes(CtModel model) {
        List<CtType<?>> out = new ArrayList<>();
        for (CtType<?> top : model.getAllTypes()) {
            out.add(top);
            for (CtType<?> inner : top.getElements(new spoon.reflect.visitor.filter.TypeFilter<>(CtType.class))) {
                if (inner != top && !inner.isAnonymous() && !inner.isLocalType()) out.add(inner);
            }
        }
        return out;
    }

    /** Index top-level name -> type per model (a model is not changed after construction; a new model is a new instance). */
    private static final Map<CtModel, Map<String, CtType<?>>> INDEXES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<CtModel, Map<String, List<String>>> SIMPLE_INDEXES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static CtType<?> topLevel(CtModel model, String qualifiedName) {
        return INDEXES.computeIfAbsent(model, m -> {
            Map<String, CtType<?>> index = new HashMap<>();
            for (CtType<?> t : m.getAllTypes()) index.put(t.getQualifiedName(), t);
            return index;
        }).get(qualifiedName);
    }

    /** Qualified names of all top-level types (from the model index, immutable). */
    public static Set<String> topLevelNames(CtModel model) {
        if (model == null) return Set.of();
        topLevel(model, "");
        return Collections.unmodifiableSet(INDEXES.get(model).keySet());
    }

    /** Qualified names of the top-level types with this simple name (from the model index). */
    public static List<String> topLevelNamesBySimpleName(CtModel model, String simpleName) {
        if (model == null) return List.of();
        topLevel(model, "");
        return Collections.unmodifiableList(SIMPLE_INDEXES.computeIfAbsent(model, m -> {
            Map<String, List<String>> bySimple = new HashMap<>();
            for (CtType<?> t : INDEXES.get(m).values()) {
                bySimple.computeIfAbsent(t.getSimpleName(), k -> new ArrayList<>()).add(t.getQualifiedName());
            }
            return bySimple;
        }).getOrDefault(simpleName, List.of()));
    }

    private static CtType<?> nested(CtType<?> outer, String path) {
        CtType<?> current = outer;
        if (path.isEmpty()) return current;
        for (String segment : path.split("\\.")) {
            current = current.getNestedType(segment);
            if (current == null) return null;
        }
        return current;
    }
}

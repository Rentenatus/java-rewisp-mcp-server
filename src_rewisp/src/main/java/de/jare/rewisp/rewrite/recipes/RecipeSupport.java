/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite.recipes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.openrewrite.Cursor;
import org.openrewrite.java.tree.J;

/**
 * Shared helper methods of the recipes (signature parsing), so they are not
 * copied slightly differently in every recipe.
 *
 * @author Janusch Rentenatus
 */
public final class RecipeSupport {

    private RecipeSupport() {
    }

    /**
     * The directly enclosing class declaration of the cursor. If the cursor is inside the body of an
     * anonymous class ({@code new X() { ... }}), there is no such declaration: {@code null},
     * so that the outer class is not mistakenly treated as the target.
     */
    public static J.ClassDeclaration enclosingClass(Cursor cursor) {
        for (Iterator<Object> it = cursor.getPath(); it.hasNext(); ) {
            Object value = it.next();
            if (value instanceof J.ClassDeclaration c) {
                return c;
            }
            if (value instanceof J.NewClass nc && nc.getBody() != null) {
                return null;
            }
        }
        return null;
    }

    /**
     * Splits a parameter list at top-level commas; commas inside
     * {@code <...>} (e.g. {@code Map<String, Integer>}) do not separate.
     */
    public static List<String> splitTopLevel(String list) {
        List<String> result = new ArrayList<>();
        if (list == null || list.isBlank()) {
            return result;
        }
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>' && depth > 0) {
                depth--;
            }
            if (c == ',' && depth == 0) {
                addIfNotBlank(result, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addIfNotBlank(result, current);
        return result;
    }

    private static void addIfNotBlank(List<String> target, StringBuilder part) {
        String trimmed = part.toString().trim();
        if (!trimmed.isEmpty()) {
            target.add(trimmed);
        }
    }

    /**
     * Reduces a type name to its simple name: removes generics (also for arrays,
     * {@code List<String>[]} becomes {@code List[]}), the package and normalizes varargs
     * ({@code int...} becomes {@code int[]}).
     */
    public static String eraseType(String type) {
        if (type == null) {
            return "";
        }
        String t = type.trim();
        if (t.endsWith("...")) {
            t = t.substring(0, t.length() - 3).trim() + "[]";
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>' && depth > 0) {
                depth--;
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        String erased = sb.toString().replace(" ", "");
        int lastDot = erased.lastIndexOf('.');
        return lastDot >= 0 ? erased.substring(lastDot + 1) : erased;
    }

    /**
     * Does the class declaration belong to the target {@code className}? If the type is resolved, the
     * qualified name decides; otherwise (or if it does not match) the simple name of the target.
     */
    public static boolean matchesClass(String className, J.ClassDeclaration c) {
        if (className == null) {
            return false;
        }
        if (c.getType() != null && className.equals(c.getType().getFullyQualifiedName())) {
            return true;
        }
        return simpleName(className).equals(c.getSimpleName());
    }

    /**
     * Like {@link #matchesClass}, but for a resolved type only by qualified name: same-named classes
     * in other packages are not matched.
     */
    public static boolean matchesClassStrict(String className, J.ClassDeclaration c) {
        if (className == null) {
            return false;
        }
        if (c.getType() != null) {
            return className.equals(c.getType().getFullyQualifiedName());
        }
        return simpleName(className).equals(c.getSimpleName());
    }

    /** Last segment of a qualified name. */
    public static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /** Splits the comma-separated import list ({@code "java.util.List, java.util.Map"}); empty for {@code null}. */
    public static List<String> splitImports(String imports) {
        List<String> result = new ArrayList<>();
        if (imports != null && !imports.isBlank()) {
            for (String part : imports.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }}

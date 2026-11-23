/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.util;

import java.util.ArrayDeque;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import java.util.Set;

/**
 * Validation of Java identifiers. Used by tools that build file paths from package and
 * class names (protection against path tricks like {@code ..} or {@code /}).
 *
 * @author Janusch Rentenatus
 */
public final class JavaNames {

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null");

    private JavaNames() {
    }

    /** Valid simple identifier (no keyword, no special characters). */
    public static boolean isValidIdentifier(String s) {
        if (s == null || s.isEmpty() || KEYWORDS.contains(s)) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(s.codePointAt(0))) {
            return false;
        }
        for (int i = s.offsetByCodePoints(0, 1); i < s.length(); i = s.offsetByCodePoints(i, 1)) {
            if (!Character.isJavaIdentifierPart(s.codePointAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Valid package name: dot-separated valid identifiers (no empty segments). */
    public static boolean isValidPackageName(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (String part : s.split("\\.", -1)) {
            if (!isValidIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    private static final Set<String> FIELD_MODIFIERS = Set.of(
            "public", "protected", "private", "static", "final", "volatile", "transient");

    /** Allowed field modifiers, separated by spaces or commas, without repetition. */
    public static boolean isValidFieldModifiers(String s) {
        if (s == null || s.isBlank()) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        for (String m : s.trim().split("[\\s,]+")) {
            if (!FIELD_MODIFIERS.contains(m) || !seen.add(m)) {
                return false;
            }
        }
        return true;
    }

    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double");

    /**
     * Syntactically valid type expression: (qualified) name or primitive type, optional type arguments
     * ({@code ?}, {@code ? extends T}, {@code ? super T}) and array brackets. No semantic check.
     */
    public static boolean isValidTypeExpression(String s) {
        if (s == null) {
            return false;
        }
        TypeParser p = new TypeParser(s);
        return p.parseType() && p.atEnd();
    }

    private static final Set<String> METHOD_MODIFIERS = Set.of(
            "public", "protected", "private", "static", "final", "abstract", "synchronized", "native", "strictfp",
            "default");

    /**
     * Allowed method modifiers (separated by spaces/commas): no repetition, at most one
     * visibility, {@code abstract} not together with private/static/final/synchronized/native/strictfp.
     */
    public static boolean isValidMethodModifiers(String s) {
        if (s == null || s.isBlank()) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        for (String m : s.trim().split("[\\s,]+")) {
            if (!METHOD_MODIFIERS.contains(m) || !seen.add(m)) {
                return false;
            }
        }
        int visibility = (seen.contains("public") ? 1 : 0) + (seen.contains("protected") ? 1 : 0)
                + (seen.contains("private") ? 1 : 0);
        if (visibility > 1) {
            return false;
        }
        if (seen.contains("abstract")) {
            for (String bad : new String[] {"private", "static", "final", "synchronized", "native", "strictfp"}) {
                if (seen.contains(bad)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Syntactically valid parameter list {@code [final] Type name, ...} (empty allowed, varargs {@code Type... name}).
     * Generic commas ({@code Map<String, Integer> m}) do not separate parameters.
     */
    public static boolean isValidParameterList(String s) {
        if (s == null || s.isBlank()) {
            return true;
        }
        for (String p : splitTopLevelCommas(s)) {
            if (!new TypeParser(p).parseParameter()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> splitTopLevelCommas(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /** Annotation name: (qualified) identifier, optionally with a leading {@code @}. */
    public static boolean isValidAnnotationName(String s) {
        if (s == null) {
            return false;
        }
        String n = s.startsWith("@") ? s.substring(1) : s;
        return isValidPackageName(n);
    }

    /**
     * Checks that a code fragment (method body, initializer, annotation attributes) is self-contained:
     * brackets {@code () [] {}} are balanced and never negative, strings/characters/comments are terminated.
     * Protects against a fragment injecting further code beyond its boundary ({@code } ... void x() {}).
     */
    public static boolean isBalancedSnippet(String s) {
        return scanSnippet(s, false);
    }

    /** Like {@link #isBalancedSnippet}, additionally without {@code ;} at the top level (a single expression). */
    public static boolean isExpressionSnippet(String s) {
        return scanSnippet(s, true);
    }

    private static boolean scanSnippet(String s, boolean forbidTopLevelSemicolon) {
        if (s == null) {
            return true;
        }
        ArrayDeque<Character> stack = new ArrayDeque<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) {
                    return false;
                }
                i = end + 2;
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                int end = findTextBlockEnd(s, i + 3);
                if (end < 0) {
                    return false;
                }
                i = end + 3;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && s.charAt(i) != c) {
                    if (s.charAt(i) == '\\') {
                        i++;
                    }
                    if (i < n && s.charAt(i) == '\n') {
                        return false;
                    }
                    i++;
                }
                if (i >= n) {
                    return false;
                }
                i++;
            } else {
                if (c == ';' && forbidTopLevelSemicolon && stack.isEmpty()) {
                    return false;
                }
                if (c == '(' || c == '[' || c == '{') {
                    stack.push(c);
                } else if (c == ')' || c == ']' || c == '}') {
                    if (stack.isEmpty()) {
                        return false;
                    }
                    char open = stack.pop();
                    if ((c == ')' && open != '(') || (c == ']' && open != '[') || (c == '}' && open != '{')) {
                        return false;
                    }
                }
                i++;
            }
        }
        return stack.isEmpty();
    }

    private static int findTextBlockEnd(String s, int from) {
        int i = from;
        while (i < s.length()) {
            if (s.charAt(i) == '\\') {
                i += 2;
            } else if (s.startsWith("\"\"\"", i)) {
                return i;
            } else {
                i++;
            }
        }
        return -1;
    }
    private static final class TypeParser {
        private final String s;
        private int i;

        TypeParser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            skipBlanks();
            return i == s.length();
        }

        private void skipBlanks() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private boolean eat(char c) {
            skipBlanks();
            if (i < s.length() && s.charAt(i) == c) {
                i++;
                return true;
            }
            return false;
        }

        private String word() {
            skipBlanks();
            int start = i;
            while (i < s.length() && Character.isJavaIdentifierPart(s.charAt(i))) {
                i++;
            }
            return s.substring(start, i);
        }

        /** {@code [final] Type[...] name} - the entire text must be consumed. */
        boolean parseParameter() {
            int save = i;
            if (word().equals("final")) {
                skipBlanks();
            } else {
                i = save;
            }
            if (!parseType()) {
                return false;
            }
            skipBlanks();
            if (s.startsWith("...", i)) {
                i += 3;
            }
            String paramName = word();
            return isValidIdentifier(paramName) && atEnd();
        }
        boolean parseType() {
            String first = word();
            boolean primitive = PRIMITIVES.contains(first);
            if (!primitive && !isValidIdentifier(first)) {
                return false;
            }
            if (!primitive) {
                while (true) {
                    if (eat('<')) {
                        if (!parseTypeArguments()) {
                            return false;
                        }
                    }
                    int save = i;
                    if (!eat('.')) {
                        break;
                    }
                    if (!isValidIdentifier(word())) {
                        i = save;
                        return false;
                    }
                }
            }
            while (eat('[')) {
                if (!eat(']')) {
                    return false;
                }
            }
            return true;
        }

        private boolean parseTypeArguments() {
            do {
                skipBlanks();
                if (eat('?')) {
                    int save = i;
                    String kw = word();
                    if (kw.equals("extends") || kw.equals("super")) {
                        if (!parseType()) {
                            return false;
                        }
                    } else {
                        i = save;
                    }
                } else if (!parseType()) {
                    return false;
                }
            } while (eat(','));
            return eat('>');
        }
    }}

/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
 */
package de.jare.rewisp.tools;

/**
 * Utility class for source code analysis operations.
 * Provides text-based parsing and navigation of Java source code.
 * 
 * <p><strong>Architecture Note:</strong> This class is part of the REWRITE layer.
 * All methods are pure text/string operations and do NOT use SPOON.
 * These utilities can be used by any layer (SPOON, REWRITE, SPEICHER).
 * 
 * @author Mistral Vibe
 */
public final class SourceCodeUtils {

    private SourceCodeUtils() {}

    /**
     * Finds the offset of the class closing brace by scanning backward from the end of the
     * file, skipping {@code '}'} characters inside comments and string/char literals.
     * The classEndLine hint from the model is used as a starting hint.
     * 
     * @param source the source code
     * @param classEndLine the line number where the class ends (hint for starting position)
     * @return the character offset of the closing brace, or -1 if not found
     */
    public static int findClassClosingBrace(String source, int classEndLine) {
        // P49: scan backward from end, skip } in comments/strings
        int i = source.length() - 1;
        boolean inBlock = false;
        while (i >= 0) {
            char c = source.charAt(i);
            // Check for block comment end */ (scanning backward, we see */ before /*)
            if (inBlock) {
                if (c == '*' && i > 0 && source.charAt(i - 1) == '/') { inBlock = false; i -= 2; continue; }
                i--; continue;
            }
            // Check for block comment start (from backward perspective: */ ... /*)
            if (c == '/' && i > 0 && source.charAt(i - 1) == '*') { inBlock = true; i -= 2; continue; }
            // Check for line comment // — skip to beginning of line
            if (c == '/' && i > 0 && source.charAt(i - 1) == '/') {
                int nl = source.lastIndexOf('\n', i);
                i = (nl < 0) ? -1 : nl;
                continue;
            }
            // Skip string/char literals (scan backward to find the opening quote)
            if (c == '"') {
                i = skipStringBackward(source, i);
                continue;
            }
            if (c == '\'') {
                i = skipCharBackward(source, i);
                continue;
            }
            if (c == '}') return i;
            i--;
        }
        return -1;
    }

    /** Scans backward from a closing '"' to just before the opening '"'. */
    public static int skipStringBackward(String s, int close) {
        int i = close - 1;
        while (i >= 0) {
            char c = s.charAt(i);
            if (c == '\\') { i--; continue; } // escaped char
            if (c == '"') return i - 1;
            i--;
        }
        return -1;
    }

    /** Scans backward from a closing '\'' to just before the opening '\''. */
    public static int skipCharBackward(String s, int close) {
        int i = close - 1;
        while (i >= 0) {
            char c = s.charAt(i);
            if (c == '\\') { i--; continue; }
            if (c == '\'') return i - 1;
            i--;
        }
        return -1;
    }

    /**
     * Maps an offset in the raw (on-disk, possibly CR-containing) source to the
     * corresponding offset in the normalized (CR-stripped) source, or returns -1
     * if the offset is out of range. Used by the text-based edit tools to translate
     * Spoon source positions (which are based on the on-disk file) into the
     * CR-stripped buffer they edit.
     * 
     * @param raw the raw source with CR characters
     * @param normalized the normalized source without CR characters
     * @param diskOffset the offset in the raw source
     * @return the corresponding offset in the normalized source, or -1 if out of range
     */
    public static int mapDiskOffsetToNormalized(String raw, String normalized, int diskOffset) {
        if (diskOffset < 0) return -1;
        int crBefore = 0;
        for (int k = 0; k < diskOffset && k < raw.length(); k++) {
            if (raw.charAt(k) == '\r') crBefore++;
        }
        int candidate = diskOffset - crBefore;
        if (candidate >= 0 && candidate <= normalized.length()) return candidate;
        return -1;
    }

    /**
     * Returns the char offset of the start of the given 1-indexed line.
     * 
     * @param source the source code
     * @param line1 the 1-indexed line number
     * @return the character offset of the line start
     */
    public static int lineStartOffset(String source, int line1) {
        int idx = 0;
        for (int l = 1; l < line1; l++) {
            int nl = source.indexOf('\n', idx);
            if (nl < 0) return source.length();
            idx = nl + 1;
        }
        return idx;
    }

    /**
     * Skips backward from a closing ')' to the matching '('.
     * Used for method/constructor parameter lists.
     */
    public static int skipParenBackward(String s, int close) {
        int i = close - 1;
        int depth = 1;
        while (i >= 0 && depth > 0) {
            char c = s.charAt(i);
            // Skip string literals
            if (c == '"') {
                i = skipStringBackward(s, i);
                continue;
            }
            // Skip char literals
            if (c == '\'') {
                i = skipCharBackward(s, i);
                continue;
            }
            // Skip block comments
            if (c == '*' && i > 0 && s.charAt(i - 1) == '/') {
                i = skipBlockCommentBackward(s, i - 1);
                continue;
            }
            // Skip line comments (scanning backward: see // before newline)
            if (c == '/' && i > 0 && s.charAt(i - 1) == '/') {
                int nl = s.lastIndexOf('\n', i - 2);
                i = (nl < 0) ? -1 : nl;
                continue;
            }
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
            }
            if (depth == 0) return i;
            i--;
        }
        return -1;
    }

    /**
     * Skips backward from a closing '*' to the matching '/*' start.
     */
    private static int skipBlockCommentBackward(String s, int closeStar) {
        int i = closeStar - 1;
        while (i >= 0) {
            if (s.charAt(i) == '/' && i > 0 && s.charAt(i - 1) == '*') {
                // Found /* but we're scanning backward, so this is the start
                return i - 1;
            }
            i--;
        }
        return -1;
    }
}

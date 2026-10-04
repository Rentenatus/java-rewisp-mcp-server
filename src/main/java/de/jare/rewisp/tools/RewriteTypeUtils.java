/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
 */
package de.jare.rewisp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for REWRITE-layer type operations.
 * Provides text-based type analysis without using SPOON.
 * 
 * <p><strong>Architecture Note:</strong> This class is part of the REWRITE layer.
 * It performs text-based analysis of Java source files without using SPOON.
 * All methods operate on raw source code strings, not on CtModel elements.
 * 
 * @author Mistral Vibe
 */
public final class RewriteTypeUtils {

    private RewriteTypeUtils() {}

    /**
     * Pattern to match class/interface/enum declarations.
     * Matches: class, interface, enum, @interface (annotations), record
     */
    private static final Pattern TYPE_DECLARATION_PATTERN = Pattern.compile(
        "(?:(?:public|protected|private|static|final|abstract|strictfp|native|transient|volatile|synchronized|\s)+)" +
        "(class|interface|enum|@interface|record)" +
        "\\s+(\\w+)" +
        "(?:\\s*<[^>]*>)?" +  // Generic parameters
        "(?:\\s+extends\\s+[^{]+)?" +
        "(?:\\s+implements\\s+[^{]+)?" +
        "\\s*\\{"
    );

    /**
     * Pattern to match nested class/interface declarations.
     */
    private static final Pattern NESTED_TYPE_PATTERN = Pattern.compile(
        "(?:(?:public|protected|private|static|final|abstract|\\s)+)" +
        "(class|interface|enum|@interface|record)" +
        "\\s+(\\w+)"
    );

    /**
     * Pattern to match the closing brace of a type.
     * This is a simplified version that counts braces.
     */

    /**
     * Locates a type declaration in a source file by its simple name.
     * This is a text-based search that looks for the type declaration pattern.
     * 
     * <p>Note: This is a simplified implementation that may not handle all edge cases
     * (complex generics, comments within declarations, etc.). For full accuracy,
     * a proper Java parser should be used.
     * 
     * @param file the path to the Java source file
     * @param className the fully qualified name of the type to find
     * @return the start and end positions (line, column) of the type declaration, or null if not found
     */
    public static TypeLocation findTypeLocation(Path file, String className) {
        if (file == null || className == null) {
            return null;
        }

        try {
            String source = Files.readString(file);
            return findTypeLocationInSource(source, className);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Locates a type declaration in a source string by its simple name.
     * 
     * @param source the Java source code
     * @param className the fully qualified name of the type
     * @return the start and end positions of the type declaration, or null if not found
     */
    public static TypeLocation findTypeLocationInSource(String source, String className) {
        if (source == null || className == null) {
            return null;
        }

        String simpleName = extractSimpleName(className);
        if (simpleName == null) {
            return null;
        }

        Matcher matcher = TYPE_DECLARATION_PATTERN.matcher(source);
        while (matcher.find()) {
            String typeKeyword = matcher.group(1);
            String foundName = matcher.group(2);
            
            if (foundName.equals(simpleName)) {
                int startPos = matcher.start();
                int endPos = findMatchingCloseBrace(source, startPos);
                
                if (endPos >= 0) {
                    return new TypeLocation(startPos, endPos);
                }
            }
        }
        
        return null;
    }

    /**
     * Finds the position of the closing brace that matches the opening brace at startPos.
     * 
     * @param source the source code
     * @param startPos the position of the opening brace
     * @return the position of the closing brace, or -1 if not found
     */
    public static int findMatchingCloseBrace(String source, int startPos) {
        if (source == null || startPos < 0 || startPos >= source.length()) {
            return -1;
        }

        int braceCount = 0;
        boolean foundOpening = false;
        
        for (int i = startPos; i < source.length(); i++) {
            char c = source.charAt(i);
            
            if (c == '{') {
                braceCount++;
                foundOpening = true;
            } else if (c == '}') {
                braceCount--;
                if (foundOpening && braceCount == 0) {
                    return i;
                }
            }
            // Skip string literals
            else if (c == '"') {
                i = skipStringForward(source, i);
            }
            // Skip character literals
            else if (c == '\'') {
                i = skipCharForward(source, i);
            }
            // Skip block comments
            else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i = skipBlockComment(source, i);
            }
            // Skip line comments
            else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i = skipLineComment(source, i);
            }
        }
        
        return -1;
    }

    /**
     * Skips forward from an opening quote to the closing quote.
     */
    private static int skipStringForward(String s, int openQuote) {
        int i = openQuote + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i;
            }
            i++;
        }
        return s.length() - 1;
    }

    /**
     * Skips forward from an opening single quote to the closing quote.
     */
    private static int skipCharForward(String s, int openQuote) {
        int i = openQuote + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '\'') {
                return i;
            }
            i++;
        }
        return s.length() - 1;
    }

    /**
     * Skips forward from / * to * /.
     */
    private static int skipBlockComment(String s, int start) {
        int i = start + 2;
        while (i < s.length() - 1) {
            if (s.charAt(i) == '*' && s.charAt(i + 1) == '/') {
                return i + 1;
            }
            i++;
        }
        return s.length() - 1;
    }

    /**
     * Skips forward to end of line.
     */
    private static int skipLineComment(String s, int start) {
        int i = start + 2;
        while (i < s.length()) {
            if (s.charAt(i) == '\n') {
                return i;
            }
            i++;
        }
        return s.length() - 1;
    }

    /**
     * Extracts the simple name from a fully qualified name.
     */
    private static String extractSimpleName(String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return null;
        }
        int lastDot = qualifiedName.lastIndexOf('.');
        if (lastDot < 0) {
            return qualifiedName;
        }
        return qualifiedName.substring(lastDot + 1);
    }

    /**
     * Represents the location of a type declaration in source code.
     */
    public static record TypeLocation(int startPos, int endPos) {
        public int length() {
            return endPos - startPos + 1;
        }
    }

    /**
     * Locates a type in a source file and returns comprehensive type information.
     * This is the REWRITE-layer equivalent of SPOON's CtType for position information.
     * 
     * <p>Note: This method does NOT parse the type structure (methods, fields, etc.).
     * It only provides position information needed for edit operations.
     * 
     * @param file the path to the Java source file
     * @param className the fully qualified name of the type to find
     * @return TypeInfo with position information, or null if not found
     */
    public static TypeInfo locateType(Path file, String className) {
        if (file == null || className == null) {
            return null;
        }

        try {
            String source = Files.readString(file);
            TypeLocation location = findTypeLocationInSource(source, className);
            
            if (location != null) {
                String simpleName = extractSimpleName(className);
                int declarationLine = countLinesBefore(source, location.startPos());
                int declarationColumn = location.startPos() - findLineStart(source, declarationLine);
                
                return new TypeInfo(
                    className,
                    simpleName,
                    file,
                    declarationLine,
                    declarationColumn,
                    location.startPos(),
                    location.endPos(),
                    source
                );
            }
        } catch (IOException e) {
            // Fall through to null
        }
        return null;
    }

    /**
     * Counts the number of newlines before a given position.
     * Returns the 1-indexed line number.
     */
    private static int countLinesBefore(String source, int pos) {
        if (pos < 0) return 0;
        int lines = 1; // 1-indexed
        for (int i = 0; i < pos && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * Finds the start position of a given line (0-indexed).
     */
    private static int findLineStart(String source, int line1) {
        int idx = 0;
        for (int l = 1; l < line1; l++) {
            int nl = source.indexOf('\n', idx);
            if (nl < 0) return source.length();
            idx = nl + 1;
        }
        return idx;
    }
}

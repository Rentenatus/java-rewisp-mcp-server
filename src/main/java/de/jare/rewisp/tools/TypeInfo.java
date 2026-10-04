/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
 */
package de.jare.rewisp.tools;

import java.nio.file.Path;

/**
 * Information about a type declaration in source code.
 * Used as a REWRITE-layer alternative to SPOON's CtType for position information.
 * 
 * <p><strong>Architecture Note:</strong> This class is part of the REWRITE layer.
 * It contains only source code position information, not a parsed AST.
 * This allows Edit Tools to work without accessing the SPOON layer.
 * 
 * @author Mistral Vibe
 */
public final class TypeInfo {

    private final String qualifiedName;
    private final String simpleName;
    private final Path filePath;
    private final int declarationLine;
    private final int declarationColumn;
    private final int startPos;
    private final int endPos;
    private final String source;

    /**
     * Creates a new TypeInfo with all position information.
     * 
     * @param qualifiedName the fully qualified name of the type
     * @param simpleName the simple name of the type
     * @param filePath the path to the source file
     * @param declarationLine the 1-indexed line number of the declaration
     * @param declarationColumn the 1-indexed column number of the declaration
     * @param startPos the character offset of the type declaration start
     * @param endPos the character offset of the closing brace
     * @param source the source code of the file
     */
    public TypeInfo(String qualifiedName, String simpleName, Path filePath,
                   int declarationLine, int declarationColumn,
                   int startPos, int endPos, String source) {
        this.qualifiedName = qualifiedName;
        this.simpleName = simpleName;
        this.filePath = filePath;
        this.declarationLine = declarationLine;
        this.declarationColumn = declarationColumn;
        this.startPos = startPos;
        this.endPos = endPos;
        this.source = source;
    }

    /**
     * Creates a minimal TypeInfo with essential information.
     */
    public TypeInfo(String qualifiedName, String simpleName, Path filePath,
                   int declarationLine, int startPos, int endPos, String source) {
        this(qualifiedName, simpleName, filePath, declarationLine, 0, startPos, endPos, source);
    }

    public String qualifiedName() {
        return qualifiedName;
    }

    public String simpleName() {
        return simpleName;
    }

    public Path filePath() {
        return filePath;
    }

    /**
     * Returns the 1-indexed line number of the type declaration.
     */
    public int declarationLine() {
        return declarationLine;
    }

    /**
     * Returns the 1-indexed column number of the type declaration.
     */
    public int declarationColumn() {
        return declarationColumn;
    }

    /**
     * Returns the character offset of the type declaration start.
     */
    public int startPos() {
        return startPos;
    }

    /**
     * Returns the character offset of the closing brace.
     */
    public int endPos() {
        return endPos;
    }

    /**
     * Returns the source code of the file.
     */
    public String source() {
        return source;
    }

    /**
     * Returns true if this TypeInfo has valid position information.
     */
    public boolean isValid() {
        return startPos >= 0 && endPos > startPos && declarationLine >= 1;
    }

    @Override
    public String toString() {
        return String.format("TypeInfo{%s at %s:%d [%d-%d]}", 
                qualifiedName, filePath, declarationLine, startPos, endPos);
    }
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
 */
package de.jare.rewisp.edit;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for source code analysis operations.
 * Provides text-based parsing and navigation of Java source code.
 * 
 * <p><strong>Architecture Note:</strong> This class is part of the REWRITE layer.
 * All methods are pure text/string operations and do NOT use SPOON.
 * These utilities can be used by any layer (SPOON, REWRITE, SPEICHER).
 * 
 * @author Janusch Rentenatus
 */
public final class SourceCodeUtils {

    private SourceCodeUtils() {}

    /**
     * Inserts import statements before the first class/interface/enum declaration in the source.
     * This is a utility method for code generation in REWRITE tools.
     *
     * @param source the source code
     * @param importsToAdd list of fully qualified names to import
     * @return source with imports inserted
     */
    public static String insertImports(String source, List<String> importsToAdd) {
        if (importsToAdd == null || importsToAdd.isEmpty()) return source;
        // P51: deduplicate — skip imports already present in the source
        List<String> filtered = new ArrayList<>();
        for (String fqn : importsToAdd) {
            String importLine = "import " + fqn + ";";
            if (!source.contains(importLine)) {
                filtered.add(fqn);
            }
        }
        if (filtered.isEmpty()) return source;
        StringBuilder importBlock = new StringBuilder();
        for (String fqn : filtered) {
            importBlock.append("import ").append(fqn).append(";\n");
        }
        // Find insertion point: after package declaration and existing imports, before first type
        int insertIdx = 0;
        String[] lines = source.split("\n", -1);
        boolean foundPackage = false;
        boolean seenImport = false;
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            // Header (Lizenz-Kommentar, Leerzeilen) vor Package/Imports ueberspringen
            if (inBlockComment) {
                if (t.contains("*/")) inBlockComment = false;
                insertIdx = i + 1;
                continue;
            }
            if (!foundPackage && !seenImport) {
                if (t.startsWith("/*")) {
                    if (!t.contains("*/")) inBlockComment = true;
                    insertIdx = i + 1;
                    continue;
                }
                if (t.startsWith("//") || t.isEmpty()) { insertIdx = i + 1; continue; }
            }
            if (t.startsWith("package ")) { foundPackage = true; insertIdx = i + 1; continue; }
            if (t.startsWith("import ")) { seenImport = true; insertIdx = i + 1; continue; }
            if (foundPackage || i > 0) {
                // Skip blank lines after package/imports
                if (t.isEmpty() && i == insertIdx) { insertIdx = i + 1; continue; }
                break;
            }
        }
        // Insert import block at insertIdx
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == insertIdx) {
                result.append(importBlock);
                result.append("\n");
            }
            result.append(lines[i]);
            if (i < lines.length - 1) result.append("\n");
        }
        return result.toString();
    }

    /**
     * Splits a comma-separated list on top-level commas only, keeping commas
     * inside generic angle brackets together. For example {@code "Map<K, V>, int"}
     * yields {@code ["Map<K, V>", "int"]}. Each fragment is trimmed. Shared by
     * tools that parse method signatures or parameter lists.
     */
    public static List<String> splitTopLevelCommas(String list) {
        List<String> params = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth = Math.max(0, depth - 1);
            if (c == ',' && depth == 0) {
                params.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) params.add(cur.toString().trim());
        return params;
    }
}

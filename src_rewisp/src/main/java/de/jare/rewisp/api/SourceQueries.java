/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.api.RewispDriver.ElementPosition;
import de.jare.rewisp.api.RewispDriver.FileContent;
import de.jare.rewisp.api.RewispDriver.SearchHit;
import de.jare.rewisp.api.RewispDriver.SearchResult;
import de.jare.rewisp.edit.FingerprintValidator;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.FileSnapshot;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.util.PathUtils;

import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

/**
 * Source-text queries: text search, file content and element positions. They read the effective
 * Rewrite state (snapshot, dirty files, transaction overlay), never the disk directly.
 *
 * @author Janusch Rentenatus
 */
final class SourceQueries {

    private SourceQueries() {
    }

    /** Element kinds of the position search. */
    private enum ElementKind { AUTO, CLASS, METHOD, FIELD, CONSTRUCTOR }

    // ==================== Snapshot ====================

    private static ProjectSnapshot snapshot(ProjectManager manager, ProjectEntry entry, String name) {
        ProjectSnapshot snapshot;
        try {
            snapshot = manager.effectiveSnapshot(entry);
        } catch (IOException e) {
            throw new RewispException(ErrorType.SNAPSHOT_NOT_AVAILABLE,
                    "Could not determine the current state of project " + name + ": " + e.getMessage());
        }
        if (snapshot == null) {
            throw new RewispException(ErrorType.SNAPSHOT_NOT_AVAILABLE,
                    "No snapshot available for project: " + name + ". Call check_project_dirty first.");
        }
        return snapshot;
    }

    // ==================== search_source ====================

    static SearchResult searchSource(ProjectManager manager, ProjectEntry entry, String project, String query,
            boolean caseSensitive, int maxResults) {
        if (query == null || query.isBlank()) {
            throw new RewispException(ErrorType.DOMAIN_ERROR, "query cannot be empty");
        }
        ProjectSnapshot snapshot = snapshot(manager, entry, project);
        String needle = caseSensitive ? query : query.toLowerCase();
        int limit = Math.max(1, maxResults);
        int occurrences = 0;
        int matchingLines = 0;
        int files = 0;
        List<SearchHit> hits = new ArrayList<>();

        var sorted = snapshot.files().values().stream()
                .filter(f -> f.content() != null && !f.deleted())
                .sorted(Comparator.comparing(f -> f.relativePath().toString()))
                .toList();
        for (FileSnapshot file : sorted) {
            String[] fileLines = file.content().split("\r?\n", -1);
            boolean fileHit = false;
            for (int i = 0; i < fileLines.length; i++) {
                int count = countOccurrences(caseSensitive ? fileLines[i] : fileLines[i].toLowerCase(), needle);
                if (count == 0) continue;
                occurrences += count;
                matchingLines++;
                fileHit = true;
                if (hits.size() < limit) {
                    hits.add(new SearchHit(file.relativePath().toString().replace('\\', '/'), i + 1,
                            fileLines[i].strip()));
                }
            }
            if (fileHit) files++;
        }
        return new SearchResult(entry.name(), hits, occurrences, matchingLines, files);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int idx = text.indexOf(needle); idx >= 0; idx = text.indexOf(needle, idx + needle.length())) {
            count++;
        }
        return count;
    }

    // ==================== get_file_content ====================

    static FileContent fileContent(ProjectManager manager, ProjectEntry entry, String project, CtType<?> type,
            String className, Integer startLine, Integer endLine) {
        var top = type.getTopLevelType();
        ProjectSnapshot snapshot = snapshot(manager, entry, project);

        // Basis ist das urspruengliche Projektverzeichnis (der Snapshot ist relativ dazu indiziert)
        Path root = PathUtils.canonical(entry.originalProjectDir());
        Path absolute = null;
        FileSnapshot file = null;
        if (top.getPosition() != null && top.getPosition().getFile() != null) {
            absolute = PathUtils.canonical(top.getPosition().getFile().toPath());
            if (absolute.startsWith(root)) {
                file = snapshot.files().get(root.relativize(absolute).normalize());
            }
        }
        if (file == null) {
            Path byName = null;
            for (var e : snapshot.files().entrySet()) {
                if (e.getKey().endsWith(top.getQualifiedName().replace('.', '/') + ".java")) {
                    byName = e.getKey();
                    file = e.getValue();
                    break;
                }
            }
            if (byName != null) absolute = root.resolve(byName);
        }
        if (file == null || file.content() == null) {
            throw new RewispException(ErrorType.DOMAIN_ERROR,
                    "Could not locate source file for " + className + " in snapshot");
        }

        String content = file.content();
        String[] lines = content.split("\r?\n", -1);
        int total = content.endsWith("\n") ? lines.length - 1 : lines.length; // kein Phantom nach dem letzten \n
        int from = startLine == null ? 1 : Math.min(startLine, Math.max(total, 1));
        int to = endLine == null ? total : Math.min(endLine, total);

        String fingerprint = absolute == null ? null : FingerprintValidator.currentFingerprint(entry, absolute);
        List<String> shown = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            shown.add(lines[i - 1]);
        }
        return new FileContent(entry.name(), file.relativePath().toString().replace('\\', '/'),
                absolute == null ? null : absolute.toString(), fingerprint, total, from, to, shown);
    }

    // ==================== get_element_position ====================

    static ElementPosition elementPosition(ProjectEntry entry, CtType<?> type, String className, String elementName,
            String elementTypeText) {
        ElementKind kind = ElementKind.AUTO;
        if (elementTypeText != null && !elementTypeText.isBlank()) {
            try {
                kind = ElementKind.valueOf(elementTypeText.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid elementType '" + elementTypeText
                        + "'. Allowed: CLASS, METHOD, FIELD, CONSTRUCTOR (or omit for auto-detect)");
            }
        }
        Map<String, Object> ctx = Map.of("className", className);
        List<CtElement> candidates = candidates(type, elementName, kind);
        if (candidates.isEmpty()) {
            throw new RewispException(ErrorType.ELEMENT_NOT_FOUND,
                    "Element '" + elementName + "' not found in class '" + className + "'.", ctx);
        }
        if (candidates.size() > 1) {
            StringBuilder amb = new StringBuilder("Element '").append(elementName).append("' in '").append(className)
                    .append("' is ambiguous. Candidates (use 'name(paramTypes)' or elementType to select one):\n");
            for (CtElement c : candidates) {
                amb.append("  - ").append(describe(c));
                if (c.getPosition() != null && c.getPosition().isValidPosition()) {
                    amb.append(" (line ").append(c.getPosition().getLine()).append(")");
                }
                amb.append("\n");
            }
            throw new RewispException(ErrorType.AMBIGUOUS_ELEMENT, amb.toString(), ctx);
        }
        CtElement element = candidates.get(0);
        if (element.getPosition() == null) {
            throw new RewispException(ErrorType.NO_POSITION,
                    "No position information available for element '" + elementName + "'.", ctx);
        }
        String filePath = element.getPosition().getFile() != null
                ? element.getPosition().getFile().getAbsolutePath() : "unknown";
        String fingerprint = element.getPosition().getFile() != null
                ? FingerprintValidator.currentFingerprint(entry, element.getPosition().getFile().toPath()) : null;
        return new ElementPosition(entry.name(), element.getClass().getSimpleName(), filePath,
                element.getPosition().getLine(), element.getPosition().getColumn(), fingerprint);
    }

    /** All elements matching {@code elementName} (exact name resp. exact signature). */
    private static List<CtElement> candidates(CtType<?> type, String elementName, ElementKind kind) {
        List<CtElement> out = new ArrayList<>();
        boolean all = kind == ElementKind.AUTO;
        if (all || kind == ElementKind.METHOD) {
            for (CtMethod<?> m : type.getMethods()) if (matches(m.getSimpleName(), m.getParameters(), elementName)) out.add(m);
        }
        if ((all || kind == ElementKind.CONSTRUCTOR) && type instanceof CtClass<?> cls) {
            for (var c : cls.getConstructors()) {
                // Der blanke Klassenname bezeichnet die Klasse; Konstruktoren per <init> oder Name(Typen)
                if (matches("<init>", c.getParameters(), elementName)
                        || (elementName.contains("(") && matches(type.getSimpleName(), c.getParameters(), elementName))) out.add(c);
            }
        }
        if (all || kind == ElementKind.FIELD) {
            for (CtField<?> f : type.getFields()) if (f.getSimpleName().equals(elementName)) out.add(f);
        }
        if ((all || kind == ElementKind.CLASS)
                && (type.getSimpleName().equals(elementName) || type.getQualifiedName().equals(elementName))) {
            out.add(type);
        }
        return out;
    }

    /** {@code name} or {@code name(T1, T2)} against name and parameter types (simple or qualified names). */
    private static boolean matches(String simpleName, List<? extends spoon.reflect.declaration.CtParameter<?>> params,
            String wanted) {
        int open = wanted.indexOf('(');
        if (open < 0) return wanted.equals(simpleName);
        if (!wanted.endsWith(")") || !wanted.substring(0, open).trim().equals(simpleName)) return false;
        String inner = wanted.substring(open + 1, wanted.length() - 1).trim();
        List<String> want = inner.isEmpty() ? List.of() : Arrays.stream(inner.split(",")).map(String::trim).toList();
        if (want.size() != params.size()) return false;
        for (int i = 0; i < want.size(); i++) {
            String w = want.get(i);
            int lt = w.indexOf('<');
            if (lt >= 0) w = w.substring(0, lt).trim();
            var type = params.get(i).getType();
            if (type == null || !(w.equals(type.getSimpleName()) || w.equals(type.getQualifiedName()))) return false;
        }
        return true;
    }

    private static String describe(CtElement element) {
        if (element instanceof CtMethod<?> m) return "method " + m.getSignature();
        if (element instanceof spoon.reflect.declaration.CtConstructor<?> c) return "constructor " + c.getSignature();
        if (element instanceof CtField<?> f) return "field " + f.getSimpleName();
        if (element instanceof CtType<?> t) return "class " + t.getQualifiedName();
        return element.getClass().getSimpleName();
    }
}

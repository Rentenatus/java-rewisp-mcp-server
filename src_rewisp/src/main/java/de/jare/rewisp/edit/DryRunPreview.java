/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.jare.rewisp.rewrite.RewriteResult;
import de.jare.rewisp.rewrite.RewrittenFile;
import de.jare.rewisp.snapshot.FileSnapshot;
import de.jare.rewisp.snapshot.ProjectSnapshot;

/**
 * Common dry-run output of the rewrite tools: file list plus diff (context lines, {@code -}/{@code +})
 * between the snapshot state and the rewrite result. Nothing is written.
 *
 * @author Janusch Rentenatus
 */
public final class DryRunPreview {

    private static final int CONTEXT = 2;
    /** Upper limit for the LCS table (lines x lines in the changed middle part). */
    private static final long MAX_LCS_CELLS = 4_000_000L;
    private static final int MAX_DIFF_LINES = 400;

    private DryRunPreview() {
    }

    /** Appends file list and diffs of the result to {@code sb}. */
    public static void append(StringBuilder sb, RewriteResult result, ProjectSnapshot base) {
        for (Map.Entry<Path, RewrittenFile> e : result.changedFiles().entrySet()) {
            sb.append("  modify ").append(e.getKey()).append('\n');
            FileSnapshot old = base != null ? base.files().get(e.getKey()) : null;
            String before = old != null ? old.content() : "";
            appendDiff(sb, before, e.getValue().content());
        }
        for (Path rel : result.deletedFiles()) {
            sb.append("  delete ").append(rel).append('\n');
        }
        for (Path rel : result.createdFiles()) {
            sb.append("  create ").append(rel).append('\n');
        }
    }

    /**
     * Appends the diff and returns the number of added and removed lines.
     *
     * @return {@code {added, removed}}
     */
    public static int[] appendDiff(StringBuilder sb, String before, String after) {
        String[] a = before.replace("\r", "").split("\n", -1);
        String[] b = after.replace("\r", "").split("\n", -1);
        int prefix = 0;
        while (prefix < a.length && prefix < b.length && a[prefix].equals(b[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.length - prefix && suffix < b.length - prefix
                && a[a.length - 1 - suffix].equals(b[b.length - 1 - suffix])) {
            suffix++;
        }
        List<String> midA = List.of(a).subList(prefix, a.length - suffix);
        List<String> midB = List.of(b).subList(prefix, b.length - suffix);
        if (midA.isEmpty() && midB.isEmpty()) {
            sb.append("    (no textual difference)\n");
            return new int[] {0, 0};
        }
        List<String> ops = diffOps(midA, midB);
        int added = 0;
        int removed = 0;
        for (String op : ops) {
            if (op.startsWith("    + ")) added++;
            else if (op.startsWith("    - ")) removed++;
        }

        int from = Math.max(0, prefix - CONTEXT);
        sb.append("    @@ line ").append(from + 1).append(" @@\n");
        List<String> out = new ArrayList<>();
        for (int i = from; i < prefix; i++) {
            out.add("      " + a[i]);
        }
        out.addAll(ops);
        int tail = Math.min(CONTEXT, suffix);
        for (int i = 0; i < tail; i++) {
            out.add("      " + a[a.length - suffix + i]);
        }
        int shown = Math.min(out.size(), MAX_DIFF_LINES);
        for (int i = 0; i < shown; i++) {
            sb.append(out.get(i)).append('\n');
        }
        if (out.size() > shown) {
            sb.append("    ... (").append(out.size() - shown).append(" more diff lines omitted)\n");
        }
        return new int[] {added, removed};
    }

    /** Diff of the changed middle part as lines {@code "    - ..."} / {@code "    + ..."} / context. */
    private static List<String> diffOps(List<String> a, List<String> b) {
        List<String> ops = new ArrayList<>();
        int n = a.size();
        int m = b.size();
        if ((long) n * m > MAX_LCS_CELLS) {
            for (String s : a) ops.add("    - " + s);
            for (String s : b) ops.add("    + " + s);
            return ops;
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                ops.add("      " + a.get(i));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add("    - " + a.get(i++));
            } else {
                ops.add("    + " + b.get(j++));
            }
        }
        while (i < n) ops.add("    - " + a.get(i++));
        while (j < m) ops.add("    + " + b.get(j++));
        return ops;
    }
}

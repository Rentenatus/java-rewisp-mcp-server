/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Describes a rewrite change for {@link EditPipeline#preview} and {@link EditPipeline#commit}.
 *
 * @param recipeId              recipe id
 * @param params                recipe parameters
 * @param targetFiles           files (relative to the project) the recipe is limited to
 * @param broadScope            true: rebuild the snapshot completely (caller updates, moves)
 * @param precheckFingerprints  true: check the fingerprints of all target files before the first write
 * @param errorContext          context of the error responses
 * @param noChangesMessage      message when the recipe changes nothing
 * @param noChangesHint         hint for that (may be {@code null})
 * @param logEntry              entry in the edit log
 * @param checkCompiles         compile check before writing (D2)
 * @param fileMoves             files the edit moves after writing (old -> new path)
 * @author Janusch Rentenatus
 */
public record RewriteEdit(String recipeId, Map<String, String> params, Set<Path> targetFiles,
        boolean broadScope, boolean precheckFingerprints,
        Map<String, Object> errorContext, String noChangesMessage,
        String noChangesHint, String logEntry,
        boolean checkCompiles, Map<Path, Path> fileMoves) {

    /** Default: compile check on, no file move. */
    public RewriteEdit(String recipeId, Map<String, String> params, Set<Path> targetFiles,
            boolean broadScope, boolean precheckFingerprints,
            Map<String, Object> errorContext, String noChangesMessage,
            String noChangesHint, String logEntry) {
        this(recipeId, params, targetFiles, broadScope, precheckFingerprints, errorContext, noChangesMessage,
                noChangesHint, logEntry, true, Map.of());
    }

    /**
     * Without compile check before writing: for edits the caller explicitly requests as (for now) not
     * compilable (staged refactoring, {@code remove_member} in {@code hard} mode).
     */
    public RewriteEdit withoutCompileCheck() {
        return new RewriteEdit(recipeId, params, targetFiles, broadScope, precheckFingerprints, errorContext,
                noChangesMessage, noChangesHint, logEntry, false, fileMoves);
    }

    /**
     * Files the edit moves after writing (old -> new path, relative to the project, e.g. the file
     * of a renamed class). The compile check simulates the move.
     */
    public RewriteEdit withFileMoves(Map<Path, Path> moves) {
        return new RewriteEdit(recipeId, params, targetFiles, broadScope, precheckFingerprints, errorContext,
                noChangesMessage, noChangesHint, logEntry, checkCompiles, Map.copyOf(moves));
    }
}

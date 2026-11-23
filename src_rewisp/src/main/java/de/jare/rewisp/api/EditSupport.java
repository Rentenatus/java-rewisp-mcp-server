/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.jare.rewisp.analysis.TypeLookup;
import de.jare.rewisp.analysis.TypeNotFoundException;
import de.jare.rewisp.api.RewispDriver.EditResult;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ErrorType;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.util.PathUtils;

import spoon.reflect.declaration.CtType;

/**
 * Common helpers of the edit operations ({@code XxxOperations}): error context, type lookup, source file and the
 * standard preview/write flow via the {@link EditPipeline}.
 *
 * @author Janusch Rentenatus
 */
final class EditSupport {

    /** Standard hint at the end of the rejection by the compile check. */
    static final String DEFAULT_COMPILE_HINT = "(Disable this check with -Dedit.validate=false.)";

    private EditSupport() {
    }

    /** Builds a (null-tolerant, ordered) context map from key-value pairs. */
    static Map<String, Object> ctx(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }

    /**
     * True when the file (after resolving {@code ..} and symlinks) lies under the project directory.
     * Containment check for all write paths built from user input (ED:E-51).
     */
    static boolean isInsideProject(ProjectEntry entry, Path file) {
        if (file == null) {
            return false;
        }
        Path real = PathUtils.canonical(file);
        for (Path root : new Path[] {entry.originalProjectDir(), entry.projectDir()}) {
            if (root != null && real.startsWith(PathUtils.canonical(root))) {
                return true;
            }
        }
        return false;
    }

    /** Looks up the type; an unknown type is reported as {@code TYPE_NOT_FOUND}. */
    static CtType<?> requireType(ProjectEntry entry, String className) {
        try {
            return TypeLookup.require(entry.model(), className, "Type not found: ");
        } catch (TypeNotFoundException e) {
            throw new RewispException(ErrorType.TYPE_NOT_FOUND, e.getMessage(), ctx("className", e.typeName()));
        }
    }

    /** Source file of the type or {@code null} when it is not known. */
    static Path sourceFile(CtType<?> type) {
        return type.getPosition().getFile() != null ? type.getPosition().getFile().toPath() : null;
    }

    /** Reports a missing rewrite engine as {@code OPENREWRITE_REQUIRED}. */
    static void requireEngine(RewriteEngine engine, String tool, Map<String, Object> context) {
        if (engine == null) {
            throw new RewispException(ErrorType.OPENREWRITE_REQUIRED,
                    tool + " requires the OpenRewrite engine. "
                            + "Per architecture decision there is no Spoon fallback for edits.",
                    context);
        }
    }

    /** Preview; on dry run the result without writing, otherwise writing via the EditManager. */
    static EditResult apply(EditPipeline pipeline, EditManager editManager, RewriteEngine engine,
            ProjectEntry entry, RewriteEdit edit, boolean dryRun, String compileHint, List<String> importsAdded)
            throws IOException {
        var outcome = pipeline.preview(entry, entry.name(), engine, edit, dryRun, compileHint);
        if (outcome.dryRun()) {
            return new EditResult(entry.name(), outcome.preview(), outcome.dryRunSnapshot(), true,
                    entry.modulesDetected(), entry.modulesLoaded(), importsAdded);
        }
        ProjectEntry written = pipeline.commit(entry, editManager, outcome.preview(), edit);
        return new EditResult(written.name(), outcome.preview(), null, false, written.modulesDetected(),
                written.modulesLoaded(), importsAdded);
    }
}

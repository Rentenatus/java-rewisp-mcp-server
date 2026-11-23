/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jare.rewisp.util.ProjectLayout;

/**
 * Atomic file operations of the edit layer (temp file + rename).
 *
 * @author Janusch Rentenatus
 */
final class AtomicFiles {

    private static final Logger LOG = LoggerFactory.getLogger(AtomicFiles.class);

    private AtomicFiles() {
    }

    /** Path of the temp file next to {@code file}. */
    static Path tempSibling(Path file) {
        return file.resolveSibling(file.getFileName() + ProjectLayout.TEMP_FILE_SUFFIX);
    }

    /**
     * Atomic rename with replace. On Windows the target can briefly be held open by another
     * process/thread (antivirus, indexer, reader) and the move can fail
     * with {@code AccessDeniedException} - therefore a few retries
     * with backoff before the error is propagated.
     */
    static void moveReplacing(Path temp, Path target) throws IOException {
        int attempts = 6;
        long backoffMillis = 20;
        for (int i = 1; ; i++) {
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AccessDeniedException e) {
                if (i >= attempts) throw e;
                LOG.debug("move {} -> {} denied (attempt {}/{}), retrying", temp, target, i, attempts);
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoffMillis *= 2;
            }
        }
    }
}

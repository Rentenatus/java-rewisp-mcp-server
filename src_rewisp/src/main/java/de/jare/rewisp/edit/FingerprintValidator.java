/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import de.jare.rewisp.model.Fingerprint;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.util.PathUtils;

/**
 * Optimistic locking for files: compares the {@link Fingerprint} recorded at load or after the last own
 * write with the disk and with the value expected by the caller. Previously duplicated in
 * {@code BaseEditTool} and {@code BaseReadTool}; placing it in {@code edit} breaks the cycle
 * {@code edit -> tools}.
 *
 * @author Janusch Rentenatus
 */
public final class FingerprintValidator {

    private FingerprintValidator() {
    }

    /**
     * Checks that the file has neither been changed nor deleted since the recorded state
     * and (if given) matches the fingerprint expected by the caller.
     * Files without a recorded fingerprint (newly created) are not checked.
     *
     * <p>The fingerprint is updated after every own write ({@code EditManager.writeFile},
     * commit) - therefore the check against disk also applies to already edited files.</p>
     *
     * @param expectedFingerprint optional, format see {@link #toString(Fingerprint)}
     * @throws IllegalArgumentException on external change, deletion or deviating expected value
     */
    public static void validateAgainstDisk(ProjectEntry entry, Path file, String expectedFingerprint) {
        Fingerprint stored = stored(entry, file);
        if (stored == null) return; // kein Fingerprint: nichts zu pruefen

        if (!Files.exists(file)) {
            throw new IllegalArgumentException(
                    "File '" + file.getFileName() + "' has been deleted since project load. "
                    + "Reload the project before editing.");
        }
        Fingerprint onDisk;
        try {
            onDisk = new Fingerprint(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read file '" + file + "': " + e.getMessage());
        }
        if (!stored.equals(onDisk)) {
            throw new IllegalArgumentException(
                    "File '" + file.getFileName() + "' has been modified since project load (fingerprint mismatch). "
                    + "Another session or external process changed this file. Reload the project before editing.");
        }
        checkExpected(file, stored, expectedFingerprint);
    }

    /** Serializes a fingerprint for the parameter {@code expectedFingerprint}. */
    public static String toString(Fingerprint fp) {
        return fp.lastModified() + "|" + fp.fileSize();
    }

    /**
     * The recorded fingerprint of a file in the format for {@code expectedFingerprint}, or {@code null}
     * (e.g. for newly created files). Feeds the optimistic lock of {@code edit_line}.
     */
    public static String currentFingerprint(ProjectEntry entry, Path file) {
        Fingerprint stored = stored(entry, file);
        return stored == null ? null : toString(stored);
    }
    private static Fingerprint stored(ProjectEntry entry, Path file) {
        Fingerprint stored = entry.sourceFingerprints().get(PathUtils.canonical(file));
        return stored != null ? stored : entry.sourceFingerprints().get(file.normalize());
    }

    private static void checkExpected(Path file, Fingerprint stored, String expectedFingerprint) {
        if (expectedFingerprint != null && !expectedFingerprint.isBlank()
                && !expectedFingerprint.equals(toString(stored))) {
            throw new IllegalArgumentException(
                    "File '" + file.getFileName() + "' has been modified since the caller's last observation "
                    + "(expected fingerprint mismatch; current fingerprint: " + toString(stored) + "). "
                    + "Read the file again (get_file_content) to get the current fingerprint; no reload is needed.");
        }
    }
}

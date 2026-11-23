/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.snapshot;

import java.nio.file.Path;

/**
 * Receiver of filesystem changes of the {@link WorkspaceWatcher}.
 * Decouples the watcher from the {@link ChangeTracker} (and makes it testable).
 *
 * @author Janusch Rentenatus
 */
@FunctionalInterface
public interface FileChangeListener {

    /**
     * @param projectName project name
     * @param absolutePath absolute path of the changed file or directory
     * @param eventKind {@code ENTRY_CREATE}, {@code ENTRY_MODIFY} or {@code ENTRY_DELETE}
     */
    void onFileChanged(String projectName, Path absolutePath, String eventKind);
}

/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-5.17: Ein Git-Clone laedt nur den aktuellen Stand ({@code depth=1}), nicht die Historie.
 *
 * @author Janusch Rentenatus
 */
class ProjectLoaderCloneTest {

    @TempDir
    Path tempDir;

    @Test
    void clone_isShallow() throws Exception {
        Path origin = tempDir.resolve("origin");
        Files.createDirectories(origin);
        try (Git git = Git.init().setDirectory(origin.toFile()).call()) {
            for (int i = 1; i <= 3; i++) {
                Files.writeString(origin.resolve("F" + i + ".java"), "class F" + i + " {}\n");
                git.add().addFilepattern(".").call();
                git.commit().setMessage("commit " + i).setAuthor("t", "t@example.invalid")
                        .setCommitter("t", "t@example.invalid").setSign(false).call();
            }
        }

        ProjectLoader loader = new ProjectLoader();
        Path clone = loader.gitClone(origin.toUri().toString());
        try {
            assertTrue(Files.exists(clone.resolve("F3.java")));
            try (Git git = Git.open(clone.toFile())) {
                int commits = 0;
                for (var ignored : git.log().call()) commits++;
                assertEquals(1, commits, "shallow clone must contain only the latest commit");
            }
        } finally {
            loader.cleanup(clone);
        }
    }
}

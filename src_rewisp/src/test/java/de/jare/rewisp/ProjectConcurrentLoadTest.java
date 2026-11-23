/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AP-5.5/5.20: Gleichzeitige Loads desselben Projekts enden mit genau einem konsistenten Eintrag.
 *
 * @author Janusch Rentenatus
 */
class ProjectConcurrentLoadTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentLoadsOfTheSameProjectLeaveOneConsistentEntry() throws Exception {
        Path src = Files.createDirectories(tempDir.resolve("src/p"));
        Files.writeString(src.resolve("A.java"), "package p;\npublic class A {}\n");
        ProjectManager mgr = new ProjectManager();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Object>> jobs = java.util.stream.IntStream.range(0, 4)
                    .<Callable<Object>>mapToObj(i -> () -> mgr.load(tempDir.resolve("src").toString(), "concurrent", null, false, true))
                    .toList();
            for (Future<Object> f : pool.invokeAll(jobs)) {
                f.get(); // keine Exception
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, mgr.list().stream().filter(e -> e.alias().equals("concurrent")).count());
        var entry = mgr.find("concurrent");
        assertNotNull(entry.model());
        assertEquals(1, entry.model().getAllTypes().size());
        mgr.remove("concurrent");
    }
}

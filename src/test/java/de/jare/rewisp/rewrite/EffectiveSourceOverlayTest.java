/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import de.jare.rewisp.snapshot.FileSnapshot;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.SnapshotId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EffectiveSourceOverlayTest {

    private final EffectiveSourceOverlay overlay = new EffectiveSourceOverlay();

    @Test
    void apply_withNoRewrite_returnsOriginal() {
        ProjectSnapshot original = createSnapshot("test", Map.of(
                Path.of("A.java"), "class A {}"
        ));
        RewriteResult emptyResult = new RewriteResult(
                Map.of(), Set.of(), Set.of(), List.of(), false
        );

        Map<Path, String> result = overlay.apply(original, emptyResult);

        assertEquals(1, result.size());
        assertEquals("class A {}", result.get(Path.of("A.java")));
    }

    @Test
    void apply_withModify_overwritesOriginal() {
        ProjectSnapshot original = createSnapshot("test", Map.of(
                Path.of("A.java"), "class A {}"
        ));
        RewriteResult result = new RewriteResult(
                Map.of(Path.of("A.java"), new RewrittenFile("class A { int x; }", "hash", "MODIFY")),
                Set.of(), Set.of(), List.of(), false
        );

        Map<Path, String> overlaid = overlay.apply(original, result);

        assertEquals(1, overlaid.size());
        assertEquals("class A { int x; }", overlaid.get(Path.of("A.java")));
    }

    @Test
    void apply_withDelete_removesFile() {
        ProjectSnapshot original = createSnapshot("test", Map.of(
                Path.of("A.java"), "class A {}",
                Path.of("B.java"), "class B {}"
        ));
        RewriteResult result = new RewriteResult(
                Map.of(),
                Set.of(Path.of("A.java")),
                Set.of(),
                List.of(),
                false
        );

        Map<Path, String> overlaid = overlay.apply(original, result);

        assertEquals(1, overlaid.size());
        assertFalse(overlaid.containsKey(Path.of("A.java")));
        assertEquals("class B {}", overlaid.get(Path.of("B.java")));
    }

    @Test
    void apply_withCreate_addsFile() {
        ProjectSnapshot original = createSnapshot("test", Map.of(
                Path.of("A.java"), "class A {}"
        ));
        RewriteResult result = new RewriteResult(
                Map.of(),
                Set.of(),
                Set.of(),
                List.of(),
                false
        );
        // Create: file in changedFiles with CREATE type
        result = new RewriteResult(
                Map.of(Path.of("B.java"), new RewrittenFile("class B {}", "hash", "CREATE")),
                Set.of(),
                Set.of(),
                List.of(),
                false
        );

        Map<Path, String> overlaid = overlay.apply(original, result);

        assertEquals(2, overlaid.size());
        assertEquals("class A {}", overlaid.get(Path.of("A.java")));
        assertEquals("class B {}", overlaid.get(Path.of("B.java")));
    }

    @Test
    void apply_withModifyAndDelete_handlesMultipleFiles() {
        ProjectSnapshot original = createSnapshot("test", Map.of(
                Path.of("A.java"), "class A {}",
                Path.of("B.java"), "class B {}",
                Path.of("C.java"), "class C {}"
        ));
        RewriteResult result = new RewriteResult(
                Map.of(
                        Path.of("A.java"), new RewrittenFile("class A { int x; }", "hash1", "MODIFY"),
                        Path.of("C.java"), new RewrittenFile("class C { int y; }", "hash3", "MODIFY")
                ),
                Set.of(Path.of("B.java")),
                Set.of(),
                List.of(),
                false
        );

        Map<Path, String> overlaid = overlay.apply(original, result);

        assertEquals(2, overlaid.size());
        assertEquals("class A { int x; }", overlaid.get(Path.of("A.java")));
        assertFalse(overlaid.containsKey(Path.of("B.java")));
        assertEquals("class C { int y; }", overlaid.get(Path.of("C.java")));
    }

    @Test
    void apply_withDeleteNonExistentFile_doesNotFail() {
        ProjectSnapshot original = createSnapshot("test", Map.of(
                Path.of("A.java"), "class A {}"
        ));
        RewriteResult result = new RewriteResult(
                Map.of(),
                Set.of(Path.of("Nonexistent.java")),
                Set.of(),
                List.of(),
                false
        );

        // Sollte nicht fehlschlagen, wenn eine nicht existierende Datei gelöscht wird
        Map<Path, String> overlaid = overlay.apply(original, result);
        assertEquals(1, overlaid.size());
    }

    private ProjectSnapshot createSnapshot(String projectId, Map<Path, String> files) {
        Map<Path, FileSnapshot> fileSnapshots = new HashMap<>();
        for (Map.Entry<Path, String> entry : files.entrySet()) {
            fileSnapshots.put(entry.getKey(), new FileSnapshot(
                    entry.getKey(),
                    entry.getValue(),
                    "hash-" + entry.getKey(),
                    entry.getValue().length(),
                    System.currentTimeMillis(),
                    false,
                    false
            ));
        }
        return new ProjectSnapshot(
                new SnapshotId(projectId, 1, "test-hash"),
                projectId,
                fileSnapshots,
                Instant.now()
        );
    }
}

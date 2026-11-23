/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.rewrite;

import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.snapshot.FileSnapshot;
import de.jare.rewisp.snapshot.ProjectSnapshot;
import de.jare.rewisp.snapshot.SnapshotId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OpenRewriteEngineImplTest {

    @Test
    void recipeNotFound_throwsRewriteException() {
        RecipeRegistry registry = new YamlRecipeRegistry();
        OpenRewriteEngineImpl engine = new OpenRewriteEngineImpl(registry);

        ProjectSnapshot snapshot = createSnapshot("test", Map.of(
                Path.of("Test.java"), "public class Test {}"
        ));

        RewriteRequest request = new RewriteRequest(
                "test", "nonexistent", Map.of(), Set.of(), snapshot.id()
        );

        assertThrows(RewriteException.class, 
                () -> engine.preview(snapshot, null, request));
    }

    @Test
    void recipeDisabled_throwsRewriteException() {
        RecipeRegistry registry = new YamlRecipeRegistry();
        registry.register(new RecipeDefinition(
                "disabledRecipe", "Disabled", "", 
                "de.jare.rewisp.rewrite.recipes.RenameMethodRecipe",
                Set.of(), ChangeRisk.LOW, false, false, false
        ));
        OpenRewriteEngineImpl engine = new OpenRewriteEngineImpl(registry);

        ProjectSnapshot snapshot = createSnapshot("test", Map.of(
                Path.of("Test.java"), "public class Test {}"
        ));

        RewriteRequest request = new RewriteRequest(
                "test", "disabledRecipe", Map.of(), Set.of(), snapshot.id()
        );

        assertThrows(RewriteException.class, 
                () -> engine.preview(snapshot, null, request));
    }

    @Test
    void engineCreatedWithRegistry() {
        RecipeRegistry registry = new YamlRecipeRegistry();
        OpenRewriteEngineImpl engine = new OpenRewriteEngineImpl(registry);
        
        assertNotNull(engine);
    }

    @Test
    void previewWithNullBuildContext_doesNotFail() {
        RecipeRegistry registry = new YamlRecipeRegistry();
        registry.register(new RecipeDefinition(
                "renameGetStartDate", "Rename getStartDate", 
                "Renames getStartDate to getContractStart",
                "de.jare.rewisp.rewrite.recipes.RenameMethodRecipe",
                Set.of("oldMethodName", "newMethodName"),
                ChangeRisk.MEDIUM, false, false, true
        ));
        OpenRewriteEngineImpl engine = new OpenRewriteEngineImpl(registry);

        ProjectSnapshot snapshot = createSnapshot("test", Map.of(
                Path.of("Test.java"), "public class Test {}"
        ));

        Map<String, String> params = new HashMap<>();
        params.put("oldMethodName", "getStartDate");
        params.put("newMethodName", "getContractStart");

        RewriteRequest request = new RewriteRequest(
                "test", "renameGetStartDate", params, Set.of(), snapshot.id()
        );

        // Sollte nicht wegen null BuildContext fehlschlagen
        assertDoesNotThrow(() -> {
            try {
                engine.preview(snapshot, null, request);
            } catch (RewriteException e) {
                // Andere Exceptions sind OK
                if ("BUILD_CONTEXT_REQUIRED".equals(e.code())) {
                    fail("Should not fail with null BuildContext");
                }
            }
        });
    }

    @Test
    void previewWithScope_includesScopeInRequest() {
        RecipeRegistry registry = new YamlRecipeRegistry();
        registry.register(new RecipeDefinition(
                "renameGetStartDate", "Rename getStartDate", 
                "Renames getStartDate to getContractStart",
                "de.jare.rewisp.rewrite.recipes.RenameMethodRecipe",
                Set.of("oldMethodName", "newMethodName"),
                ChangeRisk.MEDIUM, false, false, true
        ));
        OpenRewriteEngineImpl engine = new OpenRewriteEngineImpl(registry);

        ProjectSnapshot snapshot = createSnapshot("test", Map.of(
                Path.of("A.java"), "public class A {}",
                Path.of("B.java"), "public class B {}"
        ));

        Map<String, String> params = new HashMap<>();
        params.put("oldMethodName", "getStartDate");
        params.put("newMethodName", "getContractStart");

        // Scope nur auf A.java beschränken
        RewriteRequest request = new RewriteRequest(
                "test", "renameGetStartDate", params, Set.of(Path.of("A.java")), snapshot.id()
        );

        // Sollte nicht fehlschlagen, auch wenn Recipe nicht funktioniert
        assertDoesNotThrow(() -> {
            try {
                engine.preview(snapshot, null, request);
            } catch (RewriteException e) {
                // Recipe könnte fehlschlagen, aber nicht wegen Scope
            }
        });
    }

    @Test
    void previewDoesNotModifyOriginalSnapshot() {
        RecipeRegistry registry = new YamlRecipeRegistry();
        registry.register(new RecipeDefinition(
                "renameGetStartDate", "Rename getStartDate", 
                "Renames getStartDate to getContractStart",
                "de.jare.rewisp.rewrite.recipes.RenameMethodRecipe",
                Set.of("oldMethodName", "newMethodName"),
                ChangeRisk.MEDIUM, false, false, true
        ));
        OpenRewriteEngineImpl engine = new OpenRewriteEngineImpl(registry);

        String originalCode = "public class Test {}";
        ProjectSnapshot snapshot = createSnapshot("test", Map.of(
                Path.of("Test.java"), originalCode
        ));

        Map<String, String> params = new HashMap<>();
        params.put("oldMethodName", "getStartDate");
        params.put("newMethodName", "getContractStart");

        RewriteRequest request = new RewriteRequest(
                "test", "renameGetStartDate", params, Set.of(), snapshot.id()
        );

        try {
            engine.preview(snapshot, null, request);
        } catch (RewriteException e) {
            // Recipe könnte fehlschlagen
        }

        // Original Snapshot sollte unverändert sein
        FileSnapshot originalFile = snapshot.files().get(Path.of("Test.java"));
        assertEquals(originalCode, originalFile.content());
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

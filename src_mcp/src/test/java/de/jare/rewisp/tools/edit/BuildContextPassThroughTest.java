/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package de.jare.rewisp.tools.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.rewrite.RewriteRequest;
import de.jare.rewisp.rewrite.RewriteResult;
import de.jare.rewisp.snapshot.ProjectSnapshot;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Tests that the edit tools resolve a BuildContext via
 * {@link ProjectManager#ensureBuildContext} and pass it through to the
 * RewriteEngine instead of null. RAW projects without a supported build
 * system fall back to null (BuildResolutionException is swallowed) — the
 * tool must still work end to end.
 *
 * @author Janusch Rentenatus
 */
class BuildContextPassThroughTest {

    /** Records the buildContext argument of every preview() call. */
    private static final class RecordingEngine implements RewriteEngine {
        int calls;
        BuildContext lastBuildContext;

        @Override
        public RewriteResult preview(ProjectSnapshot snapshot, BuildContext buildContext, RewriteRequest request) {
            calls++;
            lastBuildContext = buildContext;
            return new RewriteResult(Map.of(), Set.of(), Set.of(), List.of());
        }
    }

    @TempDir
    Path tempDir;
    private ProjectManager mgr;
    private RecordingEngine engine;
    private AddAnnotationTool addAnno;
    private Path srcDir;

    @BeforeEach
    void setup() throws Exception {
        mgr = new ProjectManager();
        engine = new RecordingEngine();
        addAnno = new AddAnnotationTool(mgr, new EditManager(tempDir.resolve("backups")),
                engine);
        srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
    }

    @Test
    void rawProjectFallsBackToNullBuildContext() throws Exception {
        Path file = srcDir.resolve("Ctx.java");
        Files.writeString(file, "class Ctx {\n  void handle() {}\n}\n");
        ProjectEntry entry = mgr.load(srcDir.toString(), null, null, true, true);

        CallToolResult result = addAnno.handle(null, req(entry.name(), "class", "Ctx", "Deprecated"));

        // The tool must call the engine (no exception leaked from
        // ensureBuildContext) and still produce a valid response.
        assertEquals(1, engine.calls);
        // RAW project without pom.xml/build.gradle: BuildResolutionException
        // is swallowed and null is passed through (documented fallback).
        assertNull(engine.lastBuildContext);
        // RecordingEngine returns no changed files → domain error with isError=true
        assertTrue(result.isError());
        assertTrue(result.content().toString().contains("NO_CHANGES"));
        mgr.remove(entry.name());
    }

    private static CallToolRequest req(String name, String targetType, String className, String annotation) {
        Map<String, Object> args = new HashMap<>();
        args.put("name", name);
        args.put("targetType", targetType);
        args.put("className", className);
        args.put("annotation", annotation);
        return new CallToolRequest("add_annotation", args);
    }
}

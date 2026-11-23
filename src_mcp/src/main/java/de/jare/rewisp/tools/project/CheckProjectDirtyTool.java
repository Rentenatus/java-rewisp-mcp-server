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

package de.jare.rewisp.tools.project;


import de.jare.rewisp.tools.base.BaseProjectTool;
import de.jare.rewisp.tools.base.ToolCallGuard;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.VersionLoader;
import de.jare.rewisp.snapshot.SnapshotService;

import java.util.List;
import java.util.Map;


/**
 * Compares a fresh snapshot of the project sources with the state the server last synced to and
 * reports external changes, the files edited through rewisp since load, and changed build files.
 * Architecture: Spoon -> REWRITE (SnapshotService, BuildFileHasher) -> Storage
 *
 * @author Janusch Rentenatus
 */
public class CheckProjectDirtyTool extends BaseProjectTool {

    public CheckProjectDirtyTool(ProjectManager manager) {
        super(manager);
    }

    @Override
    protected String toolName() {
        return "check_project_dirty";
    }

    @Override
    protected String toolDescription() {
        return "[SPOON] Scans the project sources and compares them with the state the server last synced to. "
            + "Reports external changes (changed, deleted, new files), files edited through this server since load "
            + "(not an error), and changed build files (pom.xml, Gradle files, parent POM). "
            + "This is the central status tool - use it to check if a reload is needed.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return propsWithDescription("name", "string", "Project name or alias");
    }

    @Override
    protected List<String> toolRequired() {
        return req("name");
    }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        var report = driver.checkDirty(name);
        ToolCallGuard.noteProject(report.project());

        StringBuilder sb = new StringBuilder();
        sb.append("# Dirty Check: `").append(report.project()).append("`\n\n");
        sb.append("**MCP Server Version:** ").append(VersionLoader.getVersion()).append("\n\n");
        if (report.previousSnapshotId() != null) {
            sb.append("**Previous Snapshot:** `").append(report.previousSnapshotId()).append("`\n");
        }
        sb.append("**Current Snapshot:** `").append(report.currentSnapshotId()).append("`\n\n");

        if (report.baselineMissing()) {
            sb.append("> ❓ **Dirty state unknown** — there is no usable baseline snapshot for this project ")
              .append("(initial snapshot failed or the project had no source files at load). ")
              .append("Call `reload_java_project` with name `").append(report.project()).append("`.\n");
            appendBuildFiles(sb, report.changedBuildFiles());
            return ok(sb);
        }

        boolean external = report.external();
        if (!external) {
            sb.append("> ✅ **No external changes** — ").append(report.filesChecked())
              .append(" source files checked against the last synced state.\n");
        } else {
            sb.append("> ⚠️ **External changes detected** — ").append(report.filesChecked())
              .append(" source files on disk: ").append(report.changed().size()).append(" changed, ")
              .append(report.deleted().size()).append(" deleted, ").append(report.added().size()).append(" new.\n\n");
            sb.append("| Status | File | Reason | Origin |\n");
            sb.append("|--------|------|--------|--------|\n");
            for (String f : report.changed()) sb.append("| Changed | ").append(f).append(" | CONTENT_CHANGED | SNAPSHOT |\n");
            for (String f : report.deleted()) sb.append("| Deleted | ").append(f).append(" | SOURCE_DELETED | SNAPSHOT |\n");
            for (String f : report.added()) sb.append("| New | ").append(f).append(" | SOURCE_NEW | SNAPSHOT |\n");
        }

        if (report.editedFiles() > 0) {
            sb.append("\n**Edited through rewisp since load:** ").append(report.editedFiles()).append(" file(s)")
              .append(" (kept in sync by the server, not an error)\n");
        }
        appendBuildFiles(sb, report.changedBuildFiles());

        var state = report.state();
        if (state != null) {
            sb.append("\n| Flag | Value |\n|------|-------|\n");
            sb.append("| buildModelDirty | ").append(state.buildModelDirty()).append(" |\n");
            sb.append("| classpathDirty | ").append(state.classpathDirty()).append(" |\n");
            sb.append("| buildFilesChanged | ").append(state.buildFilesChanged()).append(" |\n");
            sb.append("| requiresFullSemanticRefresh | ").append(state.requiresFullSemanticRefresh()).append(" |\n");
        }

        if (external) {
            sb.append("\nCall `reload_java_project` with name `").append(report.project())
              .append("` to refresh the model.\n");
        }
        return ok(sb);
    }

    private static void appendBuildFiles(StringBuilder sb, List<String> changedBuildFiles) {
        if (changedBuildFiles.isEmpty()) return;
        sb.append("\n> ⚠️ **Build files changed:** ").append(String.join(", ", changedBuildFiles))
          .append(". The build context is invalidated; call `project_refresh_build` or `reload_java_project`.\n");
    }}
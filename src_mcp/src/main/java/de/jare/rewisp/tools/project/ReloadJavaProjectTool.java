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
import de.jare.rewisp.tools.base.BaseReadTool;


import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;

import java.util.List;
import java.util.Map;

/**
 * The {@code ReloadJavaProjectTool} class.
 *
 * @author Janusch Rentenatus
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class ReloadJavaProjectTool extends BaseProjectTool {

    public ReloadJavaProjectTool(ProjectManager manager) {
        super(manager);
    }

    @Override protected boolean requiresProjectLock() { return true; }
    @Override protected String toolName() { return "reload_java_project"; }
    @Override protected String toolDescription() {
        return "[SPOON] Reloads previously loaded Java project(s) from their original source, replacing "
            + "the in-memory model with a fresh parse. Use when check_project_dirty or a scoped "
            + "dirty warning indicates stale source files, or when a tool warns about expired projects. "
            + "Parameters: name (project to reload, required unless expired=true), expired (if true, "
            + "reloads ALL expired projects; default false). Projects are reloaded with the same "
            + "parameters as the original load. The expiry timer is reset on reload. "
            + "Note: New files created after the initial load are not visible in the model and cannot "
            + "be detected by scoped dirty checks. Only check_project_dirty performs a full scan. "
            + "After reload, all new, changed, and deleted files are reflected in the model. "
            + "CAUTION: If the on-disk source is unparseable (e.g. a previous edit produced invalid "
            + "Java), reload will FAIL because the parser cannot build a model. In that case, fix "
            + "the source on disk first, or call reboot_spoon to discard all projects and re-load "
            + "from scratch.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias to reload (required unless expired=true)"),
            "expired", Map.of("type", "boolean", "description", "If true, reloads ALL expired projects (default false)")
        );
    }
    @Override protected List<String> toolRequired() { return List.of(); }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        Boolean expiredArg = request.arguments().get("expired") == null ? null : toBoolean(request.arguments().get("expired"));
        boolean expired = expiredArg != null && expiredArg;

        var reloaded = driver.reloadProjects(name, expired);
        if (reloaded.isEmpty()) {
            return ok("No expired projects to reload.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Project Reload Complete\n\n");
        sb.append("**Reloaded:** ").append(reloaded.size()).append(" project(s)\n\n");
        for (var p : reloaded) {
            sb.append("- **").append(p.name()).append("** — ")
              .append(p.types()).append(" types, build: ")
              .append(p.build());
            if (p.delomboked()) sb.append(" (delomboked)");
            sb.append("\n");
        }
        sb.append("\nExpiry timer reset for all reloaded projects.\n");
        return ok(sb);
    }}

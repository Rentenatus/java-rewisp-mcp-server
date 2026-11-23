/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
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
 * The {@code ListLoadedProjectsTool} class.
 *
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class ListLoadedProjectsTool extends BaseProjectTool {

/**
 * Constructs the {@code ListLoadedProjectsTool} with the given project manager.
 */
    public ListLoadedProjectsTool(ProjectManager manager) {
        super(manager);
    }

/**
 * Returns the name of this tool.
 */
    @Override protected String toolName() { return "list_loaded_projects"; }
/**
 * Returns the description of this tool.
 */
    @Override protected String toolDescription() { return "[SPOON] Lists all Java projects currently loaded in memory."; }
/**
 * Returns the input schema properties for this tool.
 */
    @Override protected Map<String, Object> toolProperties() { return Map.of(); }
/**
 * Returns the list of required argument keys for this tool.
 */
    @Override protected List<String> toolRequired() { return List.of(); }

/**
 * Handles the {@code list_loaded_projects} tool invocation and returns the result.
 */
    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        var projects = driver.listProjects();
        if (projects.isEmpty()) {
            return ok("# Loaded Projects\n\n_No projects loaded._");
        }

        StringBuilder sb = new StringBuilder("# Loaded Projects\n\n");
        for (var p : projects) {
            String types = p.modelReleased() ? "model released (rebuilt on access)" : String.valueOf(p.types());
            sb.append("- **").append(p.name()).append("**");
            if (!p.alias().equals(p.name())) {
                sb.append(" (alias: `").append(p.alias()).append("`)");
            }
            sb.append(" — ").append(types).append(p.modelReleased() ? ", build: " : " types, build: ").append(p.build());
            sb.append(", editable: ").append(p.editable());
            if (p.expiry() != null) {
                sb.append(" [expires: ").append(p.expiry()).append("]");
                if (p.expired()) {
                    sb.append(" **[EXPIRED]**");
                }
            }
            sb.append("\n");
        }
        return ok(sb);
    }}

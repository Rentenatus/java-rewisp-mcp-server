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

package de.jare.rewisp.tools.read;

import de.jare.rewisp.tools.base.BaseReadTool;
import de.jare.rewisp.tools.base.ToolCallGuard;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;

import java.util.*;


/**
 * The {@code GetTypeHierarchyTool} class.
 *
 * @author Alejandro Ferreira
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class GetTypeHierarchyTool extends BaseReadTool {

/**
 * Constructs the {@code GetTypeHierarchyTool} with the given project manager.
 */
    public GetTypeHierarchyTool(ProjectManager manager) {
        super(manager);
    }

/**
 * Returns the name of this tool.
 */
    @Override protected String toolName() { return "get_type_hierarchy"; }
/**
 * Returns the description of this tool.
 */
    @Override protected String toolDescription() { return "[SPOON] Shows the type hierarchy: superclass chain, all (also inherited and super-) interfaces, and all subtypes recursively (via superclass or interface), including nested types."; }
/**
 * Returns the input schema properties for this tool.
 */
    @Override protected Map<String, Object> toolProperties() {
        return propsWithDescription("name", "string", "Project name or alias",
                "className", "string", "Fully qualified class name");
    }
/**
 * Returns the list of required argument keys for this tool.
 */
    @Override protected List<String> toolRequired() { return req("name", "className"); }

/**
 * Handles the {@code get_type_hierarchy} tool invocation and returns the result.
 */
    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String className = arg(request, "className");
        var h = driver.typeHierarchy(name, className);
        ToolCallGuard.noteProject(h.project());

        StringBuilder sb = new StringBuilder();
        sb.append("# Type Hierarchy: `").append(className).append("`\n\n");

        sb.append("### Inheritance\n\n- **").append(h.qualifiedName()).append("**\n");
        for (String superClass : h.superclasses()) {
            sb.append("- ↑ ").append(superClass).append("\n");
        }

        if (!h.interfaces().isEmpty()) {
            sb.append("\n### Interfaces (including inherited)\n\n");
            for (var i : h.interfaces()) {
                sb.append("  ".repeat(i.depth())).append("- `").append(i.name()).append("`\n");
            }
        }
        if (!h.subtypes().isEmpty()) {
            sb.append("\n### Subtypes\n\n");
            for (var s : h.subtypes()) {
                sb.append("  ".repeat(s.depth())).append("- `").append(s.name()).append("` (").append(s.kind()).append(")\n");
            }
        }

        return ok(sb);
    }}
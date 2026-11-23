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
 * The {@code FindImplementationsTool} class.
 *
 * @author Alejandro Ferreira
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class FindImplementationsTool extends BaseReadTool {

/**
 * Constructs the {@code FindImplementationsTool} with the given project manager.
 */
    public FindImplementationsTool(ProjectManager manager) {
        super(manager);
    }

/**
 * Returns the name of this tool.
 */
    @Override protected String toolName() { return "find_implementations"; }
/**
 * Returns the description of this tool.
 */
    @Override protected String toolDescription() { return "[SPOON] Finds the implementations of an interface or abstract class (transitively, including nested types): concrete classes first, then abstract intermediate classes and sub-interfaces in separate sections."; }
/**
 * Returns the input schema properties for this tool.
 */
    @Override protected Map<String, Object> toolProperties() {
        return propsWithDescription("name", "string", "Project name or alias",
                "className", "string", "Fully qualified interface or abstract class name");
    }
/**
 * Returns the list of required argument keys for this tool.
 */
    @Override protected List<String> toolRequired() { return req("name", "className"); }

/**
 * Handles the {@code find_implementations} tool invocation and returns the result.
 */
    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String className = arg(request, "className");
        var result = driver.findImplementations(name, className);
        ToolCallGuard.noteProject(result.project());

        StringBuilder sb = new StringBuilder();
        sb.append("# Implementations of `").append(className).append("`\n\n");

        if (result.isEmpty()) {
            sb.append("_No implementations found._");
        } else {
            sb.append("**Concrete implementations:** ").append(result.concrete().size()).append("\n\n");
            for (String impl : result.concrete()) {
                sb.append("- `").append(impl).append("`\n");
            }
            appendSection(sb, "Abstract classes (not instantiable)", result.abstractTypes());
            appendSection(sb, "Sub-interfaces", result.subInterfaces());
        }
        return ok(sb);
    }

    private static void appendSection(StringBuilder sb, String title, List<String> names) {
        if (names.isEmpty()) return;
        sb.append("\n### ").append(title).append(" (").append(names.size()).append(")\n\n");
        for (String n : names) sb.append("- `").append(n).append("`\n");
    }}

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

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;

import java.util.List;
import java.util.Map;

/**
 * The {@code ListClassesTool} class.
 *
 * @author Alejandro Ferreira
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class ListClassesTool extends BaseReadTool {

/**
 * Constructs the {@code ListClassesTool} with the given project manager.
 */
    public ListClassesTool(ProjectManager manager) {
        super(manager);
    }

/**
 * Returns the name of this tool.
 */
    @Override protected String toolName() { return "list_classes"; }
/**
 * Returns the description of this tool.
 */
    @Override protected String toolDescription() { return "[SPOON] Lists classes, interfaces, enums and annotation types of a loaded Java project, optionally filtered by package (exact, or including sub-packages with recursive=true). Nested (inner) types are included with includeNested=true."; }
/**
 * Returns the input schema properties for this tool.
 */
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
                "name", Map.of("type", "string", "description", "Project name or alias"),
                "package", Map.of("type", "string", "description", "Optional: filter by package qualified name"),
                "recursive", Map.of("type", "boolean", "description", "Optional: include sub-packages of the package filter (default false)"),
                "includeNested", Map.of("type", "boolean", "description", "Optional: also list nested (inner) types (default false)"));
    }
/**
 * Returns the list of required argument keys for this tool.
 */
    @Override protected List<String> toolRequired() { return req("name"); }

/**
 * Handles the {@code list_classes} tool invocation and returns the result.
 */
    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String pkgFilter = arg(request, "package");
        var types = driver.listClasses(name, pkgFilter, boolArg(request, "recursive"), boolArg(request, "includeNested"));
        var entry = findEntry(name);

        StringBuilder sb = new StringBuilder("# Classes");
        if (pkgFilter != null && !pkgFilter.isBlank()) {
            sb.append(" in `").append(pkgFilter).append("`");
        }
        sb.append(" (").append(entry.name()).append(")\n\n");
        sb.append("**Total:** ").append(types.size()).append(" types\n\n");

        for (var type : types) {
            sb.append("- **").append(type.qualifiedName()).append("**");
            sb.append(" (").append(type.kind()).append(", ").append(type.methods()).append(" methods)");
            if (type.isPublic()) sb.append(" `public`");
            sb.append("\n");
        }

        return ok(sb);
    }
}

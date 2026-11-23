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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 *
 * <p>Scans the whole type (methods, constructors, initializers, fields, nested and anonymous classes)
 * and reports {@code file:line} for each reference; overloads are told apart by their signature and
 * can be filtered with {@code parameterTypes}.
 *
 * @author Alejandro Ferreira
 */
public class FindReferencesTool extends BaseReadTool {

    private static final Set<String> KINDS = Set.of("method", "field", "type", "constructor");

/**
 * Constructs the {@code FindReferencesTool} with the given project manager.
 */
    public FindReferencesTool(ProjectManager manager) {
        super(manager);
    }

/**
 * Returns the name of this tool.
 */
    @Override protected String toolName() { return "find_references"; }
/**
 * Returns the description of this tool.
 */
    @Override protected String toolDescription() { return "[SPOON] Finds all references to a type, method, field, or constructor across the project, with file:line and the enclosing member. Nested types are supported (a.Outer.Inner); use memberName='<init>' (or memberKind='constructor') for constructors and parameterTypes to select one overload."; }
/**
 * Returns the input schema properties for this tool.
 */
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name of the target (nested: a.Outer.Inner)"),
            "memberName", Map.of("type", "string", "description", "Optional: method, field name, or '<init>' for constructor"),
            "memberKind", Map.of("type", "string", "description", "Optional: 'method', 'field', 'type', or 'constructor' (default: all)"),
            "parameterTypes", Map.of("type", "string", "description", "Optional: comma-separated parameter types to select one overload, e.g. 'String,int' (empty string = no parameters)")
        );
    }
/**
 * Returns the list of required argument keys for this tool.
 */
    @Override protected List<String> toolRequired() { return req("name", "className"); }

/**
 * Handles the {@code find_references} tool invocation and returns the result.
 */
    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String memberName = arg(request, "memberName");
        String memberKind = arg(request, "memberKind");
        Object rawParams = request.arguments() == null ? null : request.arguments().get("parameterTypes");
        if (memberKind != null && !KINDS.contains(memberKind)) {
            throw new IllegalArgumentException("Invalid memberKind '" + memberKind + "'. Allowed: method, field, type, constructor");
        }
        List<String> parameterTypes = null;
        if (rawParams != null) {
            String joined = String.valueOf(rawParams).trim();
            parameterTypes = joined.isEmpty() ? List.of() : Arrays.stream(joined.split(",")).map(String::trim).toList();
        }
        var result = driver.findReferences(name, className, memberName, memberKind, parameterTypes);
        ToolCallGuard.noteProject(result.project());
        var refs = result.references();

        StringBuilder sb = new StringBuilder();
        String title = memberName != null ? className + "." + memberName : className;
        sb.append("# References to `").append(title).append("`\n\n");

        if (refs.isEmpty()) {
            sb.append("_No references found._");
            return ok(sb);
        }
        String currentFile = null;
        for (var r : refs) {
            String file = r.file() == null ? "(no position)" : r.file();
            if (!file.equals(currentFile)) {
                if (currentFile != null) sb.append("\n");
                sb.append("## ").append(file).append("\n");
                currentFile = file;
            }
            sb.append("- `").append(r.location()).append("` in `").append(r.owner()).append("` — ")
                    .append(r.description()).append("\n");
        }
        sb.append("\n**Total references:** ").append(refs.size())
                .append(" in ").append(result.fileCount()).append(" file(s)");
        return ok(sb);
    }}
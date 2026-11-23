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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * The {@code InspectClassTool} class.
 *
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class InspectClassTool extends BaseReadTool {

/**
 * Constructs the {@code InspectClassTool} with the given project manager.
 */
    public InspectClassTool(ProjectManager manager) {
        super(manager);
    }

/**
 * Returns the name of this tool.
 */
    @Override protected String toolName() { return "inspect_class"; }
/**
 * Returns the description of this tool.
 */
    @Override protected String toolDescription() { return "[SPOON] Retrieves detailed information about a class: fields, methods, superclass, interfaces, and annotations. When the project was loaded with delombok=true, Lombok-generated members are present in the AST. Otherwise, a 'Lombok-predicted' section is appended listing what Lombok would generate."; }
/**
 * Returns the input schema properties for this tool.
 */
    @Override protected Map<String, Object> toolProperties() {
        return propsWithDescription("name", "string", "Project name or alias",
                "className", "string", "Fully qualified class name",
                "withJavadoc", "boolean", "If true (default), includes the Javadoc comment in the output");
    }
/**
 * Returns the list of required argument keys for this tool.
 */
    @Override protected List<String> toolRequired() { return req("name", "className"); }

/**
 * Handles the {@code inspect_class} tool invocation and returns the result.
 */
    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String className = arg(request, "className");
        Boolean wj = request.arguments().get("withJavadoc") == null ? null : toBoolean(request.arguments().get("withJavadoc"));
        boolean withJavadoc = (wj == null) || wj;
        var type = driver.inspectClass(name, className, withJavadoc);
        ToolCallGuard.noteProject(type.project());

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(type.qualifiedName()).append("\n\n");

        if (type.delomboked()) {
            sb.append("> ✅ **Lombok delombok applied** (version ")
                    .append(type.lombokVersion() == null ? "?" : type.lombokVersion())
                    .append("). Lombok-generated members are visible below.\n\n");
        } else if (type.lombokDetected()) {
            sb.append("> ⚠️ **Lombok detected but not delomboked.** Members that Lombok would generate are listed in the `## Lombok-predicted members` section below. Re-load the project with `delombok: true` to see them as real AST members.\n\n");
        }

        if (withJavadoc && type.javadoc() != null) {
            sb.append("## Javadoc\n\n");
            sb.append("```\n");
            sb.append(type.javadoc());
            sb.append("\n```\n\n");
        }
        sb.append("| Property | Value |\n");
        sb.append("|----------|-------|\n");
        sb.append("| **Kind** | **").append(type.kind()).append("** |\n");
        sb.append("| **Visibility** | `").append(type.visibility()).append("` |\n");
        if (type.isAbstract()) sb.append("| **Abstract** | ✅ |\n");
        if (type.isFinal()) sb.append("| **Final** | ✅ |\n");
        if (type.isStatic()) sb.append("| **Static** | ✅ |\n");

        if (type.superclass() != null) {
            sb.append("| **Superclass** | `").append(type.superclass()).append("` |\n");
        }
        if (!type.interfaces().isEmpty()) {
            String ifaces = type.interfaces().stream()
                    .map(i -> "`" + i + "`")
                    .collect(Collectors.joining(", "));
            sb.append("| **Interfaces** | ").append(ifaces).append(" |\n");
        }

        sb.append("\n## Fields (").append(type.fields().size()).append(")\n\n");
        for (var field : type.fields()) {
            sb.append("- `").append(field.visibility()).append(" ").append(field.type()).append(" ").append(field.name()).append("`");
            if (field.isStatic()) sb.append(" `static`");
            if (field.isFinal()) sb.append(" `final`");
            sb.append("\n");
        }

        if (!type.constructors().isEmpty()) {
            sb.append("\n## Constructors (").append(type.constructors().size()).append(")\n\n");
            for (var c : type.constructors()) {
                sb.append("- `").append(c).append("`\n");
            }
        }

        sb.append("\n## Methods (").append(type.methods().size()).append(")\n\n");
        for (var method : type.methods()) {
            sb.append("- `").append(method).append("`\n");
        }

        if (!type.nestedTypes().isEmpty()) {
            sb.append("\n## Nested types (").append(type.nestedTypes().size()).append(")\n\n");
            for (var nested : type.nestedTypes()) {
                sb.append("- `").append(nested).append("`\n");
            }
        }

        if (!type.annotations().isEmpty()) {
            sb.append("\n## Annotations\n\n");
            for (var ann : type.annotations()) {
                sb.append("- `").append(ann).append("`\n");
            }
        }

        if (!type.predictedMembers().isEmpty()) {
            sb.append("\n## Lombok-predicted members (").append(type.predictedMembers().size()).append(")\n\n");
            sb.append("_These members are NOT in the AST. They would be generated by Lombok at compile time._\n\n");
            for (var m : type.predictedMembers()) {
                sb.append("- `[").append(m.kind()).append("]` `").append(m.signature()).append("`\n");
            }
        }

        return ok(sb);
    }}

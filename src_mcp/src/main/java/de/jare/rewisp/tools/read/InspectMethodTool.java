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


/**
 * The {@code InspectMethodTool} class.
 *
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class InspectMethodTool extends BaseReadTool {

/**
 * Constructs the {@code InspectMethodTool} with the given project manager.
 */
    public InspectMethodTool(ProjectManager manager) {
        super(manager);
    }

/**
 * Returns the name of this tool.
 */
    @Override protected String toolName() { return "inspect_method"; }
/**
 * Returns the description of this tool.
 */
    @Override protected String toolDescription() { return "[SPOON] Retrieves detailed information about a specific method: signature, parameters, return type, annotations, and source body."; }
/**
 * Returns the input schema properties for this tool.
 */
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "methodName", Map.of("type", "string", "description", "Method name"),
            "withJavadoc", Map.of("type", "boolean", "description", "If true (default), includes the Javadoc comment in the output")
        );
    }
/**
 * Returns the list of required argument keys for this tool.
 */
    @Override protected List<String> toolRequired() { return req("name", "className", "methodName"); }

/**
 * Handles the {@code inspect_method} tool invocation and returns the result.
 */
    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String methodName = arg(request, "methodName");
        Boolean wj = request.arguments().get("withJavadoc") == null ? null : toBoolean(request.arguments().get("withJavadoc"));
        boolean withJavadoc = (wj == null) || wj;
        var result = driver.inspectMethod(name, className, methodName, withJavadoc);
        ToolCallGuard.noteProject(result.project());

        StringBuilder sb = new StringBuilder();
        sb.append("# Method: `").append(methodName).append("` in `").append(className).append("`\n\n");

        for (var method : result.overloads()) {
            sb.append("## Signature\n\n");
            sb.append("```java\n").append(method.signature()).append("\n```\n\n");

            if (withJavadoc && method.javadoc() != null) {
                sb.append("## Javadoc\n\n");
                sb.append("```\n");
                sb.append(method.javadoc());
                sb.append("\n```\n\n");
            }

            sb.append("| Property | Value |\n");
            sb.append("|----------|-------|\n");
            sb.append("| **Return type** | `").append(method.returnType()).append("` |\n");
            sb.append("| **Parameters** | ").append(method.parameterCount()).append(" |\n");
            sb.append("| **Visibility** | ").append(method.visibility()).append(" |\n");

            if (method.isStatic()) sb.append("| **Static** | ✅ |\n");
            if (method.isAbstract()) sb.append("| **Abstract** | ✅ |\n");
            if (method.isFinal()) sb.append("| **Final** | ✅ |\n");

            if (!method.annotations().isEmpty()) {
                sb.append("\n## Annotations\n\n");
                for (var ann : method.annotations()) {
                    sb.append("- `").append(ann).append("`\n");
                }
            }

            if (method.body() != null) {
                sb.append("\n## Body\n\n```java\n");
                sb.append(method.body());
                sb.append("\n```\n");
            } else {
                sb.append("\n_No method body (abstract or native)._");
            }
        }

        return ok(sb);
    }}

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
import de.jare.rewisp.tools.base.ToolCallGuard;
import de.jare.rewisp.tools.base.BaseReadTool;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;

import java.util.List;
import java.util.Map;

/**
 * The {@code ProjectMetadataTool} class.
 *
 * @author Alejandro Ferreira
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class ProjectMetadataTool extends BaseProjectTool {

/**
 * Constructs the {@code ProjectMetadataTool} with the given project manager.
 */
    public ProjectMetadataTool(ProjectManager manager) {
        super(manager);
    }

/**
 * Returns the name of this tool.
 */
    @Override protected String toolName() { return "project_metadata"; }
/**
 * Returns the description of this tool.
 */
    @Override protected String toolDescription() { return "[SPOON] Retrieves metadata about a loaded Java project."; }
/**
 * Returns the input schema properties for this tool.
 */
    @Override protected Map<String, Object> toolProperties() { return props("name", "string"); }
/**
 * Returns the list of required argument keys for this tool.
 */
    @Override protected List<String> toolRequired() { return req("name"); }

/**
 * Handles the {@code project_metadata} tool invocation and returns the result.
 */
    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        var m = driver.projectMetadata(name);
        ToolCallGuard.noteProject(m.name());

        StringBuilder sb = new StringBuilder();
        sb.append("# Project Metadata: ").append(m.name()).append("\n\n");
        sb.append("| Field | Value |\n");
        sb.append("|-------|-------|\n");
        sb.append("| **Name** | ").append(m.name()).append(" |\n");
        if (!m.alias().equals(m.name())) {
            sb.append("| **Alias** | ").append(m.alias()).append(" |\n");
        }
        sb.append("| **Build** | ").append(m.build()).append(" |\n");
        sb.append("| **Directory** | ").append(m.directory()).append(" |\n");
        if (m.analysedDirectory() != null) {
            sb.append("| **Analysed (delomboked) directory** | ").append(m.analysedDirectory()).append(" |\n");
        }
        sb.append("| **Modules** | ").append(m.modulesLoaded()).append(" loaded / ")
          .append(m.modulesDetected()).append(" detected |\n");
        sb.append("| **Packages** | ").append(m.packages()).append(" |\n");
        sb.append("| **Classes** | ").append(m.classes()).append(" |\n");
        sb.append("| **Interfaces** | ").append(m.interfaces()).append(" |\n");
        sb.append("| **Enums** | ").append(m.enums()).append(" |\n");
        sb.append("| **Methods** | ").append(m.methods()).append(" |\n");
        sb.append("| **Fields** | ").append(m.fields()).append(" |\n");

        return ok(sb);
    }}

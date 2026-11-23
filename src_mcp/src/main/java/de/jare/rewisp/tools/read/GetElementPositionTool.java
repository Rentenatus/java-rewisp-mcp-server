/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.read;

import java.util.LinkedHashMap;

import de.jare.rewisp.tools.base.BaseReadTool;
import de.jare.rewisp.tools.base.ToolCallGuard;


import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;


import java.util.List;
import java.util.Map;


/**
 * [SPOON] Tool to get the exact position (file, line, column) of a given element (class, method, field).
 * This allows AI agents to determine the precise location of code elements for edit operations.
 *
 * @author Janusch Rentenatus
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class GetElementPositionTool extends BaseReadTool {


    /**
     * Element types for position lookup.
     */
    private enum ElementType {
        AUTO, CLASS, METHOD, FIELD, CONSTRUCTOR
    }

    public GetElementPositionTool(ProjectManager manager) {
        super(manager);
    }

    @Override
    protected String toolName() {
        return "get_element_position";
    }

    @Override
    protected String toolDescription() {
        return "[SPOON] Gets the exact position (file, line, column) of a class, method, or field. "
                + "Allows AI agents to determine precise locations for edit operations like edit_line.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of("type", "string", "description", "Project name or alias"));
        props.put("className", Map.of("type", "string", "description", "Fully qualified class name containing the element"));
        props.put("element", Map.of("type", "string", "description", "Element name; for methods/constructors optionally with parameter types, e.g. 'run(String,int)'; constructor: '<init>'"));
        props.put("elementType", Map.of("type", "string", "description", "Optional: 'CLASS', 'METHOD', 'FIELD' or 'CONSTRUCTOR' (default: auto-detect; ambiguous matches are reported as an error with candidates)"));
        return props;
    }

    @Override
    protected List<String> toolRequired() {
        return List.of("name", "className", "element");
    }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String elementName = arg(request, "element").trim();
        var pos = driver.elementPosition(name, className, elementName, arg(request, "elementType"));
        ToolCallGuard.noteProject(pos.project());

        StringBuilder sb = new StringBuilder();
        sb.append("# Element Position\n\n");
        sb.append("**Element:** `").append(elementName).append("`\n");
        sb.append("**Type:** `").append(pos.kind()).append("`\n");
        sb.append("**Containing Class:** `").append(className).append("`\n\n");
        sb.append("## Location\n\n");
        sb.append("| Property | Value |\n");
        sb.append("|----------|-------|\n");
        sb.append("| **File** | `").append(pos.file()).append("` |\n");
        sb.append("| **Line** | `").append(pos.line()).append("` |\n");
        sb.append("| **Column** | `").append(pos.column()).append("` |\n\n");

        sb.append("## Usage for edit_line\n\n");
        sb.append("```\n");
        sb.append("edit_line {name: \"").append(name).append("\", file: \"").append(pos.file())
          .append("\", lineNumber: ").append(pos.line()).append(", newContent: \"...\"");
        if (pos.fingerprint() != null) sb.append(", expectedFingerprint: \"").append(pos.fingerprint()).append("\"");
        sb.append("}\n");
        sb.append("```\n");

        return ok(sb);
    }}
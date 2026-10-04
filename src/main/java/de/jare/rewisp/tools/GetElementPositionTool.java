/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;

import spoon.reflect.declaration.CtType;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.reference.CtTypeReference;

/**
 * [SPOON] Tool to get the exact position (file, line, column) of a given element (class, method, field).
 * This allows agents to determine the precise location of code elements for edit operations.
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
        AUTO, CLASS, METHOD, FIELD
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
                + "Allows agents to determine precise locations for edit operations like edit_line.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("name", Map.of("type", "string", "description", "Project name or alias"));
        props.put("className", Map.of("type", "string", "description", "Fully qualified class name containing the element"));
        props.put("element", Map.of("type", "string", "description", "Element name (class, method, or field)"));
        props.put("elementType", Map.of("type", "string", "description", "Optional: 'CLASS', 'METHOD', or 'FIELD' (default: auto-detect)"));
        return props;
    }

    @Override
    protected List<String> toolRequired() {
        return List.of("name", "className", "element");
    }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String className = arg(request, "className");
        String elementName = arg(request, "element");
        String elementTypeStr = arg(request, "elementType");

        reloadIfSpoonStale(name);

        ElementType elementType = ElementType.AUTO;
        if (elementTypeStr != null && !elementTypeStr.isBlank()) {
            try {
                elementType = ElementType.valueOf(elementTypeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Invalid elementType, fall back to AUTO
            }
        }

        var entry = findEntry(name);

        // Find the type containing the element
        CtType<?> type = entry.model().getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals(className))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Class not found: " + className));

        CtElement element = null;

        switch (elementType) {
            case CLASS:
                element = type;
                break;
            case METHOD:
                element = findMethodByName(type, elementName);
                break;
            case FIELD:
                element = type.getFields().stream()
                        .filter(f -> f.getSimpleName().equals(elementName))
                        .findFirst()
                        .orElse(null);
                break;
            case AUTO:
            default:
                // Try method first, then field, then class itself
                element = findMethodByName(type, elementName);
                if (element == null) {
                    element = type.getFields().stream()
                            .filter(f -> f.getSimpleName().equals(elementName))
                            .findFirst()
                            .orElse(null);
                }
                if (element == null) {
                    // Check if elementName matches the class name
                    if (type.getSimpleName().equals(elementName) || type.getQualifiedName().equals(elementName)) {
                        element = type;
                    }
                }
                break;
        }

        if (element == null) {
            return domainError("ELEMENT_NOT_FOUND",
                    "Element '" + elementName + "' not found in class '" + className + "'.",
                    className);
        }

        // Get position information
        if (element.getPosition() == null) {
            return domainError("NO_POSITION",
                    "No position information available for element '" + elementName + "'.",
                    className);
        }

        String filePath = element.getPosition().getFile() != null
                ? element.getPosition().getFile().getAbsolutePath()
                : "unknown";
        int line = element.getPosition().getLine();
        int column = element.getPosition().getColumn();

        StringBuilder sb = new StringBuilder();
        sb.append("# Element Position\n\n");
        sb.append("**Element:** `").append(elementName).append("`\n");
        sb.append("**Type:** `").append(element.getClass().getSimpleName()).append("`\n");
        sb.append("**Containing Class:** `").append(className).append("`\n\n");
        sb.append("## Location\n\n");
        sb.append("| Property | Value |\n");
        sb.append("|----------|-------|\n");
        sb.append("| **File** | `").append(filePath).append("` |\n");
        sb.append("| **Line** | `").append(line).append("` |\n");
        sb.append("| **Column** | `").append(column).append("` |\n\n");

        sb.append("## Usage for edit_line\n\n");
        sb.append("To edit this element's line, use:\n");
        sb.append("```\n");
        sb.append("edit_line(\"" + name + "\", \"" + filePath + "\", " + line + ", \"new content\")\n");
        sb.append("```\n");

        return ok(sb);
    }

    /**
     * Finds a method by name in the given type.
     * Tries exact match first, then partial match.
     */
    private CtMethod<?> findMethodByName(CtType<?> type, String methodName) {
        // Try exact match by simple name
        CtMethod<?> exactMatch = type.getMethods().stream()
                .filter(m -> methodName.equals(m.getSimpleName()))
                .findFirst()
                .orElse(null);
        
        if (exactMatch != null) {
            return exactMatch;
        }
        
        // Try by signature (methodName might include parameters)
        return type.getMethods().stream()
                .filter(m -> m.getSignature().contains(methodName))
                .findFirst()
                .orElse(null);
    }
}

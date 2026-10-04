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

package de.jare.rewisp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;

import java.util.*;

import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * SPOON-based tool: Finds unused private methods and fields.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 *
 * <p>This tool is the SPOON equivalent of javalens_find_unused_code.
 * It identifies private methods and fields that are never referenced within the project.</p>
 *
 * <p>Note: This analysis is limited to the loaded CtModel and may not detect
 * all usages (e.g., through reflection, dependency injection, or external libraries).</p>
 */
public class FindUnusedCodeTool extends BaseReadTool {

    /**
     * Constructs the tool with the given project manager.
     */
    public FindUnusedCodeTool(ProjectManager manager) {
        super(manager);
    }

    /**
     * Returns the name of this tool.
     */
    @Override
    protected String toolName() {
        return "find_unused_code";
    }

    /**
     * Returns the description of this tool.
     */
    @Override
    protected String toolDescription() {
        return "[SPOON] Finds unused private methods and fields in the project.";
    }

    /**
     * Returns the input schema properties for this tool.
     */
    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "scope", Map.of("type", "string", "description", "Optional: package or type name to restrict the search")
        );
    }

    /**
     * Returns the list of required argument keys for this tool.
     */
    @Override
    protected List<String> toolRequired() {
        return req("name");
    }

    /**
     * Handles the tool invocation and returns the result.
     */
    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String scope = arg(request, "scope");
        var entry = findEntry(name);

        // Collect all private elements
        Map<String, PrivateElementInfo> privateElements = new LinkedHashMap<>();
        
        for (CtType<?> type : entry.model().getAllTypes()) {
            if (scope != null && !type.getQualifiedName().startsWith(scope)) {
                continue;
            }
            collectPrivateElements(type, privateElements);
        }

        // Find usages of private elements
        Map<String, List<UsageInfo>> usages = new LinkedHashMap<>();
        for (CtType<?> type : entry.model().getAllTypes()) {
            if (scope != null && !type.getQualifiedName().startsWith(scope)) {
                continue;
            }
            findPrivateUsages(type, privateElements, usages);
        }

        // Identify unused elements
        List<PrivateElementInfo> unused = new ArrayList<>();
        for (Map.Entry<String, PrivateElementInfo> el : privateElements.entrySet()) {
            if (!usages.containsKey(el.getKey())) {
                unused.add(el.getValue());
            }
        }

        if (unused.isEmpty()) {
            return ok("No unused private methods or fields found in project '" + name + "'");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Unused Private Elements in `").append(name).append("`\n\n");
        sb.append("## Summary\n\n");
        sb.append("- **Total unused**: ").append(unused.size()).append("\n\n");
        sb.append("## Details\n\n");
        
        for (PrivateElementInfo element : unused) {
            sb.append("- ").append(element.kind).append(" ").append(element.name).append(" at ").append(element.filePath).append("\n");
        }

        return ok(sb.toString());
    }

    /**
     * Collects private methods and fields from a type.
     */
    private void collectPrivateElements(CtType<?> type, Map<String, PrivateElementInfo> privateElements) {
        // Collect private methods
        for (CtMethod<?> method : type.getMethods()) {
            if (hasPrivateModifier(method)) {
                String key = method.getSignature();
                privateElements.put(key, new PrivateElementInfo(
                    "method",
                    method.getDeclaringType().getQualifiedName() + "." + method.getSimpleName() + method.getSignature(),
                    getFilePath(method)
                ));
            }
        }

        // Collect private fields
        for (CtField<?> field : type.getFields()) {
            if (hasPrivateModifier(field)) {
                String key = field.getDeclaringType().getQualifiedName() + "." + field.getSimpleName();
                privateElements.put(key, new PrivateElementInfo(
                    "field",
                    key,
                    getFilePath(field)
                ));
            }
        }

        // Collect from nested types
        for (CtType<?> nested : type.getNestedTypes()) {
            collectPrivateElements(nested, privateElements);
        }
    }

    /**
     * Finds usages of private elements in a type.
     */
    private void findPrivateUsages(CtType<?> type, Map<String, PrivateElementInfo> privateElements, 
                                   Map<String, List<UsageInfo>> usages) {
        for (CtMethod<?> method : type.getMethods()) {
            if (method.getBody() != null) {
                scanMethodForPrivateUsages(method, privateElements, usages);
            }
        }
    }

    /**
     * Scans a method for usages of private elements.
     */
    private void scanMethodForPrivateUsages(CtMethod<?> method, Map<String, PrivateElementInfo> privateElements,
                                         Map<String, List<UsageInfo>> usages) {
        CtBlock<?> body = method.getBody();
        if (body == null) return;

        // Find method invocations
        List<CtInvocation<?>> invocations = body.getElements(new TypeFilter<>(CtInvocation.class));
        for (CtInvocation<?> inv : invocations) {
            if (inv.getExecutable() != null) {
                String executableSig = inv.getExecutable().getSignature();
                if (privateElements.containsKey(executableSig)) {
                    addUsage(usages, executableSig, new UsageInfo(getFilePath(method), method.getSimpleName()));
                }
            }
        }

        // Find field accesses
        List<CtFieldAccess<?>> fieldAccesses = body.getElements(new TypeFilter<>(CtFieldAccess.class));
        for (CtFieldAccess<?> fa : fieldAccesses) {
            if (fa.getVariable() != null && fa.getVariable().getDeclaringType() != null) {
                String fieldKey = fa.getVariable().getDeclaringType().getQualifiedName() + "." + fa.getVariable().getSimpleName();
                if (privateElements.containsKey(fieldKey)) {
                    addUsage(usages, fieldKey, new UsageInfo(getFilePath(method), method.getSimpleName()));
                }
            }
        }
    }

    /**
     * Adds a usage entry.
     */
    private void addUsage(Map<String, List<UsageInfo>> usages, String elementKey, UsageInfo usage) {
        usages.computeIfAbsent(elementKey, k -> new ArrayList<>()).add(usage);
    }

    /**
     * Checks if an element is private.
     */
    private boolean hasPrivateModifier(CtElement element) {
        if (element instanceof CtMethod) {
            return ((CtMethod<?>) element).isPrivate();
        } else if (element instanceof CtField) {
            return ((CtField<?>) element).isPrivate();
        }
        return false;
    }

    /**
     * Gets the file path for an element.
     */
    private String getFilePath(CtElement element) {
        if (element.getPosition() != null && element.getPosition().getFile() != null) {
            return element.getPosition().getFile().toString();
        }
        return "<unknown>";
    }

    /**
     * Information about a private element.
     */
    private static class PrivateElementInfo {
        final String kind; // "method" or "field"
        final String name;
        final String filePath;

        PrivateElementInfo(String kind, String name, String filePath) {
            this.kind = kind;
            this.name = name;
            this.filePath = filePath;
        }
    }

    /**
     * Information about a usage of a private element.
     */
    private static class UsageInfo {
        final String filePath;
        final String contextMethod;

        UsageInfo(String filePath, String contextMethod) {
            this.filePath = filePath;
            this.contextMethod = contextMethod;
        }
    }
}

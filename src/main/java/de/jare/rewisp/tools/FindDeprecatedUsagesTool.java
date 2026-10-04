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
import spoon.reflect.reference.*;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * SPOON-based tool: Finds usages of deprecated elements.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 *
 * <p>This tool is the SPOON equivalent of javalens_find_deprecated_usages.
 * It identifies all usages of elements marked with @Deprecated annotation.</p>
 */
public class FindDeprecatedUsagesTool extends BaseReadTool {

    /**
     * Constructs the tool with the given project manager.
     */
    public FindDeprecatedUsagesTool(ProjectManager manager) {
        super(manager);
    }

    /**
     * Returns the name of this tool.
     */
    @Override
    protected String toolName() {
        return "find_deprecated_usages";
    }

    /**
     * Returns the description of this tool.
     */
    @Override
    protected String toolDescription() {
        return "[SPOON] Finds all usages of deprecated types, methods, and fields in the project.";
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
        ProjectEntry projectEntry = findEntry(name);

        // Find all deprecated elements
        List<DeprecatedElement> deprecatedElements = new ArrayList<>();
        
        for (CtType<?> type : projectEntry.model().getAllTypes()) {
            if (scope != null && !type.getQualifiedName().startsWith(scope)) {
                continue;
            }
            findDeprecatedElements(type, deprecatedElements);
        }

        // Find usages of deprecated elements
        List<UsageInfo> usages = new ArrayList<>();
        for (CtType<?> type : projectEntry.model().getAllTypes()) {
            if (scope != null && !type.getQualifiedName().startsWith(scope)) {
                continue;
            }
            findDeprecatedElementUsages(type, deprecatedElements, usages);
        }

        if (usages.isEmpty()) {
            return ok("No usages of deprecated elements found in project '" + name + "'");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Deprecated Element Usages in `").append(name).append("`\n\n");
        sb.append("## Summary\n\n");
        sb.append("- **Total usages**: ").append(usages.size()).append("\n\n");
        
        // Group by deprecated element
        Map<String, List<UsageInfo>> byElement = new LinkedHashMap<>();
        for (UsageInfo usage : usages) {
            byElement.computeIfAbsent(usage.deprecatedElementName, k -> new ArrayList<>()).add(usage);
        }
        
        for (Map.Entry<String, List<UsageInfo>> entrySet : byElement.entrySet()) {
            String deprecatedName = entrySet.getKey();
            List<UsageInfo> elementUsages = entrySet.getValue();
            
            sb.append("## ").append(deprecatedName).append("\n\n");
            sb.append("- **Usage count**: ").append(elementUsages.size()).append("\n\n");
            sb.append("### Locations:\n\n");
            for (UsageInfo usage : elementUsages) {
                sb.append("- ").append(usage.contextMethod).append(" at ").append(usage.filePath).append("\n");
            }
            sb.append("\n");
        }

        return ok(sb.toString());
    }

    /**
     * Finds deprecated elements in a type and its nested types.
     */
    private void findDeprecatedElements(CtType<?> type, List<DeprecatedElement> deprecatedElements) {
        // Check if type itself is deprecated
        if (isDeprecated(type)) {
            deprecatedElements.add(new DeprecatedElement(
                "type",
                type.getQualifiedName(),
                getFilePath(type)
            ));
        }

        // Check methods
        for (CtMethod<?> method : type.getMethods()) {
            if (isDeprecated(method)) {
                deprecatedElements.add(new DeprecatedElement(
                    "method",
                    method.getDeclaringType().getQualifiedName() + "." + method.getSimpleName() + method.getSignature(),
                    getFilePath(method)
                ));
            }
        }

        // Check fields
        for (CtField<?> field : type.getFields()) {
            if (isDeprecated(field)) {
                deprecatedElements.add(new DeprecatedElement(
                    "field",
                    field.getDeclaringType().getQualifiedName() + "." + field.getSimpleName(),
                    getFilePath(field)
                ));
            }
        }

        // Check nested types
        for (CtType<?> nested : type.getNestedTypes()) {
            findDeprecatedElements(nested, deprecatedElements);
        }
    }

    /**
     * Finds usages of deprecated elements in a type.
     */
    private void findDeprecatedElementUsages(CtType<?> type, List<DeprecatedElement> deprecatedElements, List<UsageInfo> usages) {
        for (CtMethod<?> method : type.getMethods()) {
            if (method.getBody() != null) {
                scanMethodForDeprecatedUsages(method, deprecatedElements, usages);
            }
        }
    }

    /**
     * Scans a method for usages of deprecated elements.
     */
    private void scanMethodForDeprecatedUsages(CtMethod<?> method, List<DeprecatedElement> deprecatedElements, List<UsageInfo> usages) {
        CtBlock<?> body = method.getBody();
        if (body == null) return;

        String contextMethod = method.getDeclaringType().getQualifiedName() + "." + method.getSimpleName();

        // Find type references (instantiation, casts, variable declarations)
        List<CtTypeAccess<?>> typeAccesses = body.getElements(new TypeFilter<>(CtTypeAccess.class));
        for (CtTypeAccess<?> typeAccess : typeAccesses) {
            if (typeAccess.getAccessedType() != null) {
                String accessedType = typeAccess.getAccessedType().getQualifiedName();
                for (DeprecatedElement deprecated : deprecatedElements) {
                    if (deprecated.kind.equals("type") && deprecated.name.equals(accessedType)) {
                        usages.add(new UsageInfo(deprecated.name, getFilePath(method), contextMethod));
                    }
                }
            }
        }

        // Find method invocations
        List<CtInvocation<?>> invocations = body.getElements(new TypeFilter<>(CtInvocation.class));
        for (CtInvocation<?> inv : invocations) {
            if (inv.getExecutable() != null && inv.getExecutable().getDeclaringType() != null) {
                String methodKey = inv.getExecutable().getDeclaringType().getQualifiedName() + "." + 
                                  inv.getExecutable().getSimpleName() + inv.getExecutable().getSignature();
                for (DeprecatedElement deprecated : deprecatedElements) {
                    if (deprecated.kind.equals("method") && deprecated.name.equals(methodKey)) {
                        usages.add(new UsageInfo(deprecated.name, getFilePath(method), contextMethod));
                    }
                }
            }
        }

        // Find field accesses
        List<CtFieldAccess<?>> fieldAccesses = body.getElements(new TypeFilter<>(CtFieldAccess.class));
        for (CtFieldAccess<?> fa : fieldAccesses) {
            if (fa.getVariable() != null && fa.getVariable().getDeclaringType() != null) {
                String fieldKey = fa.getVariable().getDeclaringType().getQualifiedName() + "." + fa.getVariable().getSimpleName();
                for (DeprecatedElement deprecated : deprecatedElements) {
                    if (deprecated.kind.equals("field") && deprecated.name.equals(fieldKey)) {
                        usages.add(new UsageInfo(deprecated.name, getFilePath(method), contextMethod));
                    }
                }
            }
        }
    }

    /**
     * Checks if an element is deprecated.
     */
    private boolean isDeprecated(CtElement element) {
        for (CtAnnotation<?> annotation : element.getAnnotations()) {
            if ("java.lang.Deprecated".equals(annotation.getType().getQualifiedName())) {
                return true;
            }
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
     * Information about a deprecated element.
     */
    private static class DeprecatedElement {
        final String kind; // "type", "method", "field"
        final String name;
        final String filePath;

        DeprecatedElement(String kind, String name, String filePath) {
            this.kind = kind;
            this.name = name;
            this.filePath = filePath;
        }
    }

    /**
     * Information about a usage of a deprecated element.
     */
    private static class UsageInfo {
        final String deprecatedElementName;
        final String filePath;
        final String contextMethod;

        UsageInfo(String deprecatedElementName, String filePath, String contextMethod) {
            this.deprecatedElementName = deprecatedElementName;
            this.filePath = filePath;
            this.contextMethod = contextMethod;
        }
    }
}

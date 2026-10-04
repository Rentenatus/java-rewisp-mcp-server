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
 * SPOON-based tool: Finds unreachable code.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 *
 * <p>This tool is the SPOON equivalent of javalens_find_unreachable_code.
 * It identifies methods, fields, and types that are never referenced from entry points
 * (main methods, test methods, etc.).</p>
 */
public class FindUnreachableCodeTool extends BaseReadTool {

    /**
     * Constructs the tool with the given project manager.
     */
    public FindUnreachableCodeTool(ProjectManager manager) {
        super(manager);
    }

    /**
     * Returns the name of this tool.
     */
    @Override
    protected String toolName() {
        return "find_unreachable_code";
    }

    /**
     * Returns the description of this tool.
     */
    @Override
    protected String toolDescription() {
        return "[SPOON] Finds unreachable methods, fields, and types in the project.";
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

        // Find all entry points (main methods, test methods)
        Set<String> entryPoints = findEntryPoints(projectEntry);
        
        // Find all reachable elements from entry points
        Set<String> reachable = findReachableElements(projectEntry, entryPoints);
        
        // Find all elements in the project
        Set<String> allElements = collectAllElements(projectEntry);
        
        // Identify unreachable elements
        Set<String> unreachable = new HashSet<>(allElements);
        unreachable.removeAll(reachable);
        unreachable.removeAll(entryPoints); // Entry points themselves are reachable

        if (unreachable.isEmpty()) {
            return ok("No unreachable code found in project '" + name + "'");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Unreachable Code in `").append(name).append("`\n\n");
        sb.append("## Summary\n\n");
        sb.append("- **Total unreachable**: ").append(unreachable.size()).append("\n\n");
        sb.append("## Details\n\n");
        
        // Group by kind
        Map<String, List<String>> byKind = new LinkedHashMap<>();
        for (String element : unreachable) {
            String kind = getElementKind(element);
            byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(element);
        }
        
        for (Map.Entry<String, List<String>> kindEntry : byKind.entrySet()) {
            sb.append("### ").append(kindEntry.getKey()).append("s\n\n");
            for (String element : kindEntry.getValue()) {
                sb.append("- ").append(element).append("\n");
            }
            sb.append("\n");
        }

        return ok(sb.toString());
    }

    /**
     * Finds all entry points (main methods, test methods) in the project.
     */
    private Set<String> findEntryPoints(ProjectEntry entry) {
        Set<String> entryPoints = new HashSet<>();
        
        for (CtType<?> type : entry.model().getAllTypes()) {
            for (CtMethod<?> method : type.getMethods()) {
                if (isMainMethod(method)) {
                    entryPoints.add(method.getSignature());
                } else if (isTestMethod(method)) {
                    entryPoints.add(method.getSignature());
                }
            }
        }
        
        return entryPoints;
    }

    /**
     * Checks if a method is a main method.
     */
    private boolean isMainMethod(CtMethod<?> method) {
        return "main".equals(method.getSimpleName()) && 
               method.getParameters().size() == 1 &&
               "java.lang.String[]".equals(method.getParameters().get(0).getType().getQualifiedName());
    }

    /**
     * Checks if a method is a test method.
     */
    private boolean isTestMethod(CtMethod<?> method) {
        for (CtAnnotation<?> annotation : method.getAnnotations()) {
            String annotationType = annotation.getType().getQualifiedName();
            if ("org.junit.Test".equals(annotationType) ||
                "org.junit.jupiter.api.Test".equals(annotationType) ||
                "org.testng.annotations.Test".equals(annotationType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds all reachable elements from entry points.
     */
    private Set<String> findReachableElements(ProjectEntry entry, Set<String> entryPointSignatures) {
        Set<String> reachable = new HashSet<>(entryPointSignatures);
        Set<String> toProcess = new HashSet<>(entryPointSignatures);
        
        while (!toProcess.isEmpty()) {
            Set<String> newlyReachable = new HashSet<>();
            
            for (CtType<?> type : entry.model().getAllTypes()) {
                for (CtMethod<?> method : type.getMethods()) {
                    if (toProcess.contains(method.getSignature())) {
                        // This method is reachable, find all elements it references
                        if (method.getBody() != null) {
                            newlyReachable.addAll(findReferencedElements(method));
                        }
                    }
                }
            }
            
            reachable.addAll(newlyReachable);
            toProcess = newlyReachable;
        }
        
        return reachable;
    }

    /**
     * Finds all elements referenced by a method.
     */
    private Set<String> findReferencedElements(CtMethod<?> method) {
        Set<String> referenced = new HashSet<>();
        
        CtBlock<?> body = method.getBody();
        if (body == null) return referenced;

        // Find method invocations
        List<CtInvocation<?>> invocations = body.getElements(new TypeFilter<>(CtInvocation.class));
        for (CtInvocation<?> inv : invocations) {
            if (inv.getExecutable() != null) {
                CtExecutable<?> executable = inv.getExecutable().getDeclaration();
                if (executable != null) {
                    referenced.add(executable.getSignature());
                }
            }
        }

        // Find field accesses
        List<CtFieldAccess<?>> fieldAccesses = body.getElements(new TypeFilter<>(CtFieldAccess.class));
        for (CtFieldAccess<?> fa : fieldAccesses) {
            if (fa.getVariable() != null && fa.getVariable().getDeclaringType() != null) {
                CtField<?> field = fa.getVariable().getDeclaration();
                if (field != null) {
                    referenced.add(field.getDeclaringType().getQualifiedName() + "." + field.getSimpleName());
                }
            }
        }

        // Find type references
        List<CtTypeAccess<?>> typeAccesses = body.getElements(new TypeFilter<>(CtTypeAccess.class));
        for (CtTypeAccess<?> ta : typeAccesses) {
            if (ta.getAccessedType() != null) {
                referenced.add(ta.getAccessedType().getQualifiedName());
            }
        }

        return referenced;
    }

    /**
     * Collects all elements (methods, fields, types) in the project.
     */
    private Set<String> collectAllElements(ProjectEntry entry) {
        Set<String> allElements = new HashSet<>();
        
        for (CtType<?> type : entry.model().getAllTypes()) {
            // Add type itself
            allElements.add(type.getQualifiedName());
            
            // Add methods
            for (CtMethod<?> method : type.getMethods()) {
                allElements.add(method.getSignature());
            }
            
            // Add fields
            for (CtField<?> field : type.getFields()) {
                allElements.add(field.getDeclaringType().getQualifiedName() + "." + field.getSimpleName());
            }
        }
        
        return allElements;
    }

    /**
     * Gets the kind of an element based on its signature/name.
     */
    private String getElementKind(String element) {
        if (element.contains("(") && element.contains(")")) {
            return "method";
        } else if (element.contains(".")) {
            return "field";
        } else {
            return "type";
        }
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
}

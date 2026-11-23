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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reads the source of the file containing a type from the effective Rewrite state (snapshot, dirty
 * files and transaction overlay), numbered line by line, with the fingerprint that feeds
 * {@code edit_line}'s optimistic lock.
 *
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
public class GetFileContentTool extends BaseReadTool {

    /**
     * Constructs the {@code GetFileContentTool} with the given project manager.
     */
    public GetFileContentTool(ProjectManager manager) {
        super(manager);
    }

    @Override
    protected String toolName() {
        return "get_file_content";
    }

    @Override
    protected String toolDescription() {
        return "[SPOON] Reads the source of the file containing a given type (nested types resolve to their file) "
                + "from the effective Rewrite state, with line numbers and the file fingerprint. "
                + "Use the file path and fingerprint with edit_line (file, lineNumber, newContent, expectedFingerprint).";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
                "name", Map.of("type", "string", "description", "Project name or alias"),
                "className", Map.of("type", "string", "description", "Fully qualified class name (nested: a.Outer.Inner)"),
                "startLine", Map.of("type", "integer", "description", "Optional: first line to show (1-based)"),
                "endLine", Map.of("type", "integer", "description", "Optional: last line to show (inclusive)"));
    }

    @Override
    protected List<String> toolRequired() {
        return req("name", "className");
    }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String className = arg(request, "className");
        Integer startLine = intArg(request, "startLine");
        Integer endLine = intArg(request, "endLine");
        var file = driver.fileContent(name, className, startLine, endLine);
        ToolCallGuard.noteProject(file.project());

        StringBuilder sb = new StringBuilder();
        sb.append("### File: ").append(className).append("\n\n");
        sb.append("- **Path:** `").append(file.path()).append("`\n");
        if (file.absolutePath() != null) sb.append("- **Absolute path:** `").append(file.absolutePath()).append("`\n");
        sb.append("- **Fingerprint:** `").append(file.fingerprint() == null ? "n/a" : file.fingerprint()).append("`\n");
        sb.append("- **Lines:** ").append(file.totalLines());
        if (file.from() != 1 || file.to() != file.totalLines()) {
            sb.append(" (showing ").append(file.from()).append("-").append(file.to()).append(")");
        }
        sb.append("\n\n```java\n");
        int width = String.valueOf(Math.max(file.to(), 1)).length();
        for (int i = file.from(); i <= file.to(); i++) {
            sb.append(String.format("%" + width + "d | %s%n", i, file.lines().get(i - file.from())).replace("\r\n", "\n"));
        }
        sb.append("```");
        return ok(sb);
    }

    private static Integer intArg(CallToolRequest request, String key) {
        Object v = request.arguments() == null ? null : request.arguments().get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }}
/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
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

package de.jare.rewisp.tools.edit;

import de.jare.rewisp.tools.base.BaseEditTool;
import de.jare.rewisp.model.ErrorType;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import de.jare.rewisp.api.DefaultRewispDriver;
import de.jare.rewisp.api.RewispDriver;
import de.jare.rewisp.tools.base.ToolCallGuard;

/**
 * The {@code EditLineTool} — replaces a single line in a source file.
 *
 * @author Janusch Rentenatus
 */
/**
 * REWRITE-based tool: Edit tool with filesystem access.
 * Extends BaseEditTool for 3-layer architecture compliance.
 */
public class EditLineTool extends BaseEditTool {

    /**
     * Constructs the {@code EditLineTool} with the given project manager and edit manager.
     */
    public EditLineTool(ProjectManager manager, EditManager editManager) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager);
    }

    @Override protected String toolName() { return "edit_line"; }
    @Override protected String toolDescription() {
        return "[SPOON] Replace a single line in a source file. CR characters are stripped from both "
                + "the file content and newContent before matching, so the edit works regardless "
                + "of CRLF or LF. The written line adopts the file's existing line ending.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "file", Map.of("type", "string", "description", "Path to the source file (relative to project source dir, or absolute)"),
            "lineNumber", Map.of("type", "integer", "description", "1-indexed line number to replace"),
            "newContent", Map.of("type", "string", "description", "New content for the line (without line ending)"),
            "expectedFingerprint", Map.of("type", "string", "description", "Optional: fingerprint for optimistic locking"),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }
    @Override protected List<String> toolRequired() { return req("name", "file", "lineNumber", "newContent"); }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        int lineNumber;
        try {
            lineNumber = Integer.parseInt(String.valueOf(request.arguments().get("lineNumber")));
        } catch (NumberFormatException e) {
            return domainError(ErrorType.DOMAIN_ERROR, "lineNumber must be an integer");
        }

        var result = driver.editLine(new RewispDriver.EditLineRequest(arg(request, "name"), arg(request, "file"),
                lineNumber, arg(request, "newContent"), arg(request, "expectedFingerprint"),
                boolArg(request, "dryRun")));
        ToolCallGuard.noteProject(result.project());

        // DRY RUN: nichts schreiben, nur Vorschau der Zeilenänderung
        if (result.dryRun()) {
            StringBuilder sb = new StringBuilder();
            sb.append("DRY RUN - edit_line: no files written. Would replace line ")
              .append(lineNumber).append(" in ").append(result.file()).append(":\n");
            sb.append("  - ").append(result.oldLine()).append('\n');
            sb.append("  + ").append(result.newLine()).append('\n');
            return ok(sb);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Line ").append(lineNumber).append(" replaced in ").append(result.file().getFileName()).append(".\n");
        sb.append(formatMultiModuleWarning(result.modulesDetected(), result.modulesLoaded()));
        return ok(sb);
    }
}

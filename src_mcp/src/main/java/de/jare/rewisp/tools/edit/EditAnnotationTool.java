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

import java.util.List;
import de.jare.rewisp.tools.base.BaseEditTool;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.rewrite.RewriteEngine;
import java.util.Map;
import de.jare.rewisp.api.DefaultRewispDriver;
import de.jare.rewisp.api.RewispDriver;
import de.jare.rewisp.tools.base.ToolCallGuard;

/**
 * The {@code EditAnnotationTool} — edits the attributes of an existing annotation
 * on a class, method, or field.
 *
 * <p>Architecture (since 1.1.1-REWRITE): Spoon is used ONLY for pre-edit analysis
 * (target validation, overload check, annotation existence). The actual change happens
 * EXCLUSIVELY via OpenRewrite (EditAnnotationRecipe). There is no Spoon fallback for
 * edit operations anymore — the line-based annotation-window search is gone.</p>
 *
 * @author Janusch Rentenatus
 */
public class EditAnnotationTool extends BaseEditTool {

    /**
     * Constructor with full OpenRewrite support.
     * OpenRewrite is mandatory — there is no Spoon fallback anymore.
     */
    public EditAnnotationTool(ProjectManager manager, EditManager editManager,
                           RewriteEngine rewriteEngine) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager, rewriteEngine);
    }

    @Override protected String toolName() { return "edit_annotation"; }
    @Override protected String toolDescription() {
        return "[REWRITE] Edit the attributes of an existing annotation on a class, method, or field. "
                + "The annotation must already exist (use add_annotation first). "
                + "Content via OpenRewrite; Spoon only for pre-edit analysis.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "targetType", Map.of("type", "string", "description", "'class', 'method', or 'field'"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "targetName", Map.of("type", "string", "description", "Method or field name (omit for class)"),
            "annotation", Map.of("type", "string", "description", "Annotation name (simple or qualified)"),
            "newAttributes", Map.of("type", "string", "description", "New attribute pairs (e.g. 'value=\"/new\"')"),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }
    @Override protected List<String> toolRequired() {
        return req("name", "targetType", "className", "annotation", "newAttributes");
    }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String targetType = arg(request, "targetType");
        String targetName = arg(request, "targetName");
        String annotation = arg(request, "annotation");
        var result = driver.editAnnotation(new RewispDriver.EditAnnotationRequest(arg(request, "name"), targetType,
                arg(request, "className"), targetName, annotation, arg(request, "newAttributes"),
                boolArg(request, "dryRun")));
        ToolCallGuard.noteProject(result.project());
        if (result.dryRun()) {
            return dryRunResult(result.preview(), result.dryRunSnapshot());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Annotation edited: @").append(annotation).append(" on ").append(targetType != null ? targetType : "class");
        if (targetName != null) sb.append(" ").append(targetName);
        sb.append(" [OpenRewrite]\n");
        sb.append(formatMultiModuleWarning(result.modulesDetected(), result.modulesLoaded()));
        sb.append(formatDiagnostics(result.preview()));
        return ok(sb);
    }
}

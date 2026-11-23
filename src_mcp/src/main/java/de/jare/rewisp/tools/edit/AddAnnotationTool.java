/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the software and associated documentation files in the same way.
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
 * The {@code AddAnnotationTool} — adds an annotation to a class, method, or field.
 *
 * <p>Architecture (since 1.1.1-REWRITE): Spoon is used ONLY for pre-edit analysis
 * (target validation, duplicate check, overload check). The actual change happens
 * EXCLUSIVELY via OpenRewrite (AddAnnotationRecipe). There is no Spoon fallback for
 * edit operations anymore.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddAnnotationTool extends BaseEditTool {

    /**
     * Constructor with full OpenRewrite support.
     * OpenRewrite is mandatory — there is no Spoon fallback anymore.
     */
    public AddAnnotationTool(ProjectManager manager, EditManager editManager,
                           RewriteEngine rewriteEngine) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager, rewriteEngine);
    }

    @Override protected String toolName() { return "add_annotation"; }
    @Override protected String toolDescription() {
        return "[REWRITE] Add an annotation to a class, method, or field. "
                + "Checks for duplicates. "
                + "Edit via OpenRewrite; Spoon only for pre-edit analysis.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "targetType", Map.of("type", "string", "description", "'class', 'method', or 'field'"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "targetName", Map.of("type", "string", "description", "Method or field name (omit for class)"),
            "annotation", Map.of("type", "string", "description", "Annotation name (simple or qualified)"),
            "attributes", Map.of("type", "string", "description", "Optional: key=value pairs (e.g. 'value=\"/new\"')"),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }
    @Override protected List<String> toolRequired() {
        return req("name", "targetType", "className", "annotation");
    }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String targetType = arg(request, "targetType");
        String targetName = arg(request, "targetName");
        String annotation = arg(request, "annotation");
        var result = driver.addAnnotation(new RewispDriver.AddAnnotationRequest(arg(request, "name"), targetType,
                arg(request, "className"), targetName, annotation, arg(request, "attributes"),
                boolArg(request, "dryRun")));
        ToolCallGuard.noteProject(result.project());
        if (result.dryRun()) {
            return dryRunResult(result.preview(), result.dryRunSnapshot());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Annotation added: @").append(annotation).append(" on ").append(targetType);
        if (targetName != null) sb.append(" ").append(targetName);
        sb.append(" [OpenRewrite]\n");
        sb.append(formatMultiModuleWarning(result.modulesDetected(), result.modulesLoaded()));
        sb.append(formatDiagnostics(result.preview()));
        return ok(sb);
    }
}

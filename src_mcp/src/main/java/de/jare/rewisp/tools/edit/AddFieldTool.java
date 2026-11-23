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
 * The {@code AddFieldTool} — adds a new field to an existing class.
 *
 * <p>Architecture (since 1.1.1-REWRITE): Spoon is used ONLY for pre-edit analysis
 * (field duplicate check, auto-import resolution, unresolved-type detection).
 * The actual change happens EXCLUSIVELY via OpenRewrite
 * (AddFieldRecipe). There is no Spoon fallback for edit operations anymore.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddFieldTool extends BaseEditTool {

    /**
     * Constructor with full OpenRewrite support.
     * OpenRewrite is mandatory — there is no Spoon fallback anymore.
     */
    public AddFieldTool(ProjectManager manager, EditManager editManager,
                       RewriteEngine rewriteEngine) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager, rewriteEngine);
    }

    @Override
    protected String toolName() {
        return "add_field";
    }

    @Override
    protected String toolDescription() {
        return "[REWRITE] Add a new field to an existing class. Automatic import resolution applies "
                + "to the field type and initializer expression. "
                + "Edit via OpenRewrite; Spoon only for pre-edit analysis.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "fieldName", Map.of("type", "string", "description", "Field name"),
            "type", Map.of("type", "string", "description", "Field type (e.g. 'String', 'int')"),
            "modifiers", Map.of("type", "string", "description", "Optional: 'private', 'public', 'static', 'final'"),
            "initializer", Map.of("type", "string", "description", "Optional: initializer expression (e.g. '\"hello\"' or '42')"),
            "imports", Map.of("type", "array", "description",
                "Optional: list of fully-qualified import names (e.g. ['java.util.List']) "
                + "to disambiguate types with the same simple name in different packages. "
                + "Each FQN must be a project type or a loadable JDK class; invalid FQNs are reported as unresolved."),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }

    @Override
    protected List<String> toolRequired() {
        return req("name", "className", "fieldName", "type");
    }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String className = arg(request, "className");
        String fieldName = arg(request, "fieldName");
        var result = driver.addField(new RewispDriver.AddFieldRequest(arg(request, "name"), className, fieldName,
                arg(request, "type"), arg(request, "modifiers"), arg(request, "initializer"),
                stringListArg(request, "imports"), boolArg(request, "dryRun")));
        ToolCallGuard.noteProject(result.project());
        if (result.dryRun()) {
            return dryRunResult(result.preview(), result.dryRunSnapshot());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Field added: ").append(className).append(".").append(fieldName)
                .append(" [OpenRewrite]\n");
        sb.append(formatMultiModuleWarning(result.modulesDetected(), result.modulesLoaded()));
        sb.append(formatDiagnostics(result.preview()));
        return ok(sb);
    }
}

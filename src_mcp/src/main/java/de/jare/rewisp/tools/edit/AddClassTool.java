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

import de.jare.rewisp.edit.DryRunPreview;
import de.jare.rewisp.tools.base.BaseEditTool;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.rewrite.RewriteEngine;
import java.util.List;
import java.util.Map;
import de.jare.rewisp.api.DefaultRewispDriver;
import de.jare.rewisp.api.RewispDriver;
import de.jare.rewisp.tools.base.ToolCallGuard;

/**
 * The {@code AddClassTool} — creates a new class, enum, interface, or abstract class.
 *
 * <p>Architecture (since 1.1.1-REWRITE): Spoon is used ONLY for pre-edit analysis
 * (auto-import resolution for the body). The generated source text goes through the
 * OpenRewrite pipeline: it is parsed with the OpenRewrite JavaParser (validation)
 * and written in normalized form. Broken body code is rejected as PARSE_ERROR
 * before any file is touched. There is no Spoon fallback for edit operations anymore.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddClassTool extends BaseEditTool {

    /**
     * Constructor with full OpenRewrite support.
     * OpenRewrite is mandatory — there is no Spoon fallback anymore.
     */
    public AddClassTool(ProjectManager manager, EditManager editManager,
                       RewriteEngine rewriteEngine) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager, rewriteEngine);
    }

    @Override
    protected String toolName() {
        return "add_class";
    }

    @Override
    protected String toolDescription() {
        return "[REWRITE] Create a new class, enum, interface, or abstract class in a package. "
                + "The generated source is validated and normalized through the OpenRewrite parser; "
                + "Spoon only for pre-edit analysis (import resolution).";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "packageName", Map.of("type", "string", "description", "Target package"),
            "className", Map.of("type", "string", "description", "New class name"),
            "type", Map.of("type", "string", "description", "'class', 'enum', 'interface', or 'abstract' (default: class)"),
            "visibility", Map.of("type", "string", "description",
                "Optional: 'public' (default) or 'package' (package-private)"),
            "body", Map.of("type", "string", "description", "Optional: class body (members, one per line)"),
            "imports", Map.of("type", "array", "description",
                "Optional: list of fully-qualified import names (e.g. ['java.util.List']) "
                + "to disambiguate types with the same simple name in different packages."),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }

    @Override
    protected List<String> toolRequired() {
        return req("name", "packageName", "className");
    }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String packageName = arg(request, "packageName");
        String className = arg(request, "className");
        var result = driver.addClass(new RewispDriver.AddClassRequest(arg(request, "name"), packageName, className,
                arg(request, "type"), arg(request, "visibility"), arg(request, "body"),
                stringListArg(request, "imports"), boolArg(request, "dryRun")));
        ToolCallGuard.noteProject(result.project());
        if (result.dryRun()) {
            StringBuilder sb = new StringBuilder();
            sb.append("DRY RUN - add_class: no files written. Would create:\n");
            sb.append("  create ").append(result.file()).append('\n');
            DryRunPreview.appendDiff(sb, "", result.content());
            return ok(sb);
        }
        return ok("Class created: " + packageName + "." + className + " at " + result.file()
                + " [OpenRewrite]\n" + formatMultiModuleWarning(result.modulesDetected(), result.modulesLoaded()));
    }
}

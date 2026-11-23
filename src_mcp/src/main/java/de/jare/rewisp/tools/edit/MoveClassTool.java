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
 * The {@code MoveClassTool} — moves a class to a different package.
 *
 * <p>Architecture (since 1.1.1-REWRITE): Spoon is used ONLY for pre-edit analysis
 * (class existence, package determination, source file location). The content change
 * (package declaration, import updates, fully qualified references,
 * same-package imports) happens EXCLUSIVELY via OpenRewrite (MoveClassRecipe
 * with ChangeType sub-recipe). The physical file move goes through the EditManager.
 * There is no Spoon fallback for edit operations anymore.</p>
 *
 * @author Janusch Rentenatus
 */
public class MoveClassTool extends BaseEditTool {

    /**
     * Constructor with full OpenRewrite support.
     * OpenRewrite is mandatory — there is no Spoon fallback anymore.
     */
    public MoveClassTool(ProjectManager manager, EditManager editManager,
                       RewriteEngine rewriteEngine) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager, rewriteEngine);
    }

    @Override
    protected String toolName() {
        return "move_class";
    }

    @Override
    protected String toolDescription() {
        return "[REWRITE] Move a class to a different package. Rewrites package declaration and updates "
                + "imports across all loaded projects. "
                + "Content via OpenRewrite; Spoon only for pre-edit analysis.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "newPackage", Map.of("type", "string", "description", "Target package name"),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }

    @Override
    protected List<String> toolRequired() {
        return req("name", "className", "newPackage");
    }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String className = arg(request, "className");
        String newPackage = arg(request, "newPackage");
        var result = driver.moveClass(new RewispDriver.MoveClassRequest(arg(request, "name"), className,
                newPackage, boolArg(request, "dryRun")));
        ToolCallGuard.noteProject(result.project());
        if (result.alreadyThere()) {
            return ok("Class " + className + " is already in package " + newPackage + ". No changes needed.");
        }
        var edit = result.edit();
        if (edit.dryRun()) {
            return dryRunResult(edit.preview(), edit.dryRunSnapshot());
        }

        String newQualified = result.movedTo();
        StringBuilder sb = new StringBuilder();
        sb.append("Moved: ").append(className).append(" -> ").append(newQualified).append(" [OpenRewrite]\n");
        sb.append("Imports updated: ").append(result.importsUpdated()).append("\n");
        if (result.deletionDeferred()) {
            sb.append("NOTE: old file deletion deferred to commit_transaction.\n");
        }
        sb.append(formatMultiModuleWarning(edit.modulesDetected(), edit.modulesLoaded()));
        sb.append(formatDiagnostics(edit.preview()));
        return ok(sb);
    }
}

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
 * The {@code RewriteSignatureTool} — changes a method's signature (return type
 * and/or parameters).
 *
 * <p>Architecture (since 1.1.1-REWRITE): Spoon is used ONLY for pre-edit analysis
 * (method existence, overload exclusion, auto-import resolution, caller counting).
 * The actual change happens EXCLUSIVELY via OpenRewrite
 * (RewriteSignatureRecipe): AST-based declaration change and TODO markers
 * at real call sites. There is no Spoon fallback for edit operations anymore —
 * the line-based signature replacement is gone.</p>
 *
 * @author Janusch Rentenatus
 */
public class RewriteSignatureTool extends BaseEditTool {

    /**
     * Constructor with full OpenRewrite support.
     * OpenRewrite is mandatory — there is no Spoon fallback anymore.
     */
    public RewriteSignatureTool(ProjectManager manager, EditManager editManager,
                              RewriteEngine rewriteEngine) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager, rewriteEngine);
    }

    @Override protected String toolName() { return "rewrite_signature"; }
    @Override protected String toolDescription() {
        return "[REWRITE] Change a method's signature (return type and/or parameters). Two modes: "
                + "signature_only (change just the declaration, no caller updates — useful for "
                + "interface methods or staged refactoring) or signature_and_callers (default: "
                + "inserts TODO markers at call sites so arguments can be reviewed). "
                + "Content via OpenRewrite; Spoon only for pre-edit analysis.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "methodName", Map.of("type", "string", "description", "Method name"),
            "newReturnType", Map.of("type", "string", "description", "New return type (omit to keep current)"),
            "newParameters", Map.of("type", "string", "description", "New parameter list (e.g. 'int count, String label')"),
            "mode", Map.of("type", "string", "description", "'signature_only' or 'signature_and_callers' (default)"),
            "imports", Map.of("type", "array", "description",
                "Optional: list of fully-qualified import names (e.g. ['java.util.List']) "
                + "to disambiguate types with the same simple name in different packages."),
            "allowBreakingChanges", Map.of("type", "boolean", "description",
                "If true, apply the signature change even though callers no longer compile afterwards "
                + "(staged refactoring; default false: such an edit is rejected)"),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }
    @Override protected List<String> toolRequired() { return req("name", "className", "methodName"); }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String methodName = arg(request, "methodName");
        var result = driver.rewriteSignature(new RewispDriver.SignatureRequest(arg(request, "name"),
                arg(request, "className"), methodName, arg(request, "newReturnType"),
                arg(request, "newParameters"), arg(request, "mode"), stringListArg(request, "imports"),
                boolArg(request, "allowBreakingChanges"), boolArg(request, "dryRun")));
        ToolCallGuard.noteProject(result.project());
        if (result.unchanged()) {
            return ok("Signature unchanged: " + methodName + "(" + result.oldParameters() + ") — no edits made.");
        }
        var edit = result.edit();
        if (edit.dryRun()) {
            return dryRunResult(edit.preview(), edit.dryRunSnapshot());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Signature changed: ").append(methodName).append("(").append(result.oldParameters())
                .append(") -> ").append(result.newDeclaration()).append(" [OpenRewrite]\n");
        sb.append("Mode: ").append(result.mode()).append("\n");
        if ("signature_and_callers".equals(result.mode())) {
            sb.append("Callers updated: ").append(result.callersUpdated()).append("\n");
        }
        sb.append(formatMultiModuleWarning(edit.modulesDetected(), edit.modulesLoaded()));
        sb.append(formatDiagnostics(edit.preview()));
        return ok(sb);
    }
}

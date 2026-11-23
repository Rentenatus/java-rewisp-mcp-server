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
 * The {@code RemoveMemberTool} — removes a method or field from a class.
 *
 * <p>Architecture (since 1.1.1-REWRITE): Spoon is used ONLY for pre-edit analysis
 * (reference search for safe mode, signature disambiguation, string literal scan).
 * The actual change happens EXCLUSIVELY via OpenRewrite
 * (RemoveMemberRecipe). There is no Spoon fallback for edit operations anymore.</p>
 *
 * @author Janusch Rentenatus
 */
public class RemoveMemberTool extends BaseEditTool {

    /**
     * Constructor with full OpenRewrite support.
     * OpenRewrite is mandatory — there is no Spoon fallback anymore.
     */
    public RemoveMemberTool(ProjectManager manager, EditManager editManager,
                           RewriteEngine rewriteEngine) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager, rewriteEngine);
    }

    @Override protected String toolName() { return "remove_member"; }
    @Override protected String toolDescription() {
        return "[REWRITE] Remove a method or field. Safe mode (default): refuses if references exist. "
                + "Hard mode: removes regardless (also skips the compile check) and lists dangling references. "
                + "For overloaded methods, provide the optional 'signature' parameter "
                + "(comma-separated parameter types, e.g. 'int, String') to disambiguate. "
                + "Edit via OpenRewrite; Spoon only for pre-edit analysis.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "memberName", Map.of("type", "string", "description", "Method or field name to remove"),
            "scope", Map.of("type", "string", "description", "'method' or 'field' (default: method)"),
            "mode", Map.of("type", "string", "description", "'safe' (default) or 'hard'"),
            "signature", Map.of("type", "string", "description", "Comma-separated parameter types (e.g. 'int, String') to disambiguate overloaded methods"),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }
    @Override protected List<String> toolRequired() { return req("name", "className", "memberName"); }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String className = arg(request, "className");
        String memberName = arg(request, "memberName");
        var result = driver.removeMember(new RewispDriver.RemoveMemberRequest(arg(request, "name"), className,
                memberName, arg(request, "scope"), arg(request, "mode"), arg(request, "signature"),
                boolArg(request, "dryRun")));
        var edit = result.edit();
        ToolCallGuard.noteProject(edit.project());
        if (edit.dryRun()) {
            return dryRunResult(edit.preview(), edit.dryRunSnapshot());
        }
        boolean hard = result.hard();
        StringBuilder sb = new StringBuilder();
        sb.append("Removed: ").append(className).append(".").append(memberName)
                .append(" (").append(hard ? "hard" : "safe").append(" mode) [OpenRewrite]\n");
        if (hard && !result.danglingReferences().isEmpty()) {
            sb.append("WARNING: Dangling references that will not compile:\n");
            for (String ref : result.danglingReferences()) sb.append("  ").append(ref).append("\n");
        }
        // Unresolved string-literal references
        if (!result.stringReferences().isEmpty()) {
            sb.append("Unresolved references (string literals):\n");
            for (String ref : result.stringReferences()) sb.append("  ").append(ref).append("\n");
        }
        sb.append(formatMultiModuleWarning(edit.modulesDetected(), edit.modulesLoaded()));
        sb.append(formatDiagnostics(edit.preview()));
        return ok(sb);
    }
}

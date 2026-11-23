/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */

package de.jare.rewisp.tools.edit;

import de.jare.rewisp.edit.StringLiteralScanner;

import java.nio.file.Files;


import de.jare.rewisp.tools.base.BaseEditTool;
import de.jare.rewisp.tools.base.ToolCallGuard;
import de.jare.rewisp.api.DefaultRewispDriver;
import de.jare.rewisp.api.RewispDriver;



import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.rewrite.RewriteEngine;

import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * The {@code RenameSymbolTool} — renames a method, class, or field across
 * every caller, backed by OpenRewrite.
 *
 * <p>Architecture (since 1.1.1-REWRITE): Spoon is used ONLY for pre-edit analysis
 * (symbol validation, overload check, occurrence counting, string literal scan).
 * The actual change happens EXCLUSIVELY via OpenRewrite
 * (RenameSymbolRecipe) — declaration AND call sites type-safely via the AST,
 * no longer via regex over source text. There is no Spoon fallback for
 * edit operations anymore.</p>
 *
 * @author Janusch Rentenatus
 */
public class RenameSymbolTool extends BaseEditTool {

    /** Recipe-ID in application.yaml. */
    private static final String RECIPE_ID = "rename-symbol";

    /**
     * Constructor with full OpenRewrite support.
     * OpenRewrite is mandatory — there is no Spoon fallback anymore.
     */
    public RenameSymbolTool(ProjectManager manager, EditManager editManager,
                           RewriteEngine rewriteEngine) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager, rewriteEngine);
    }
    @Override
    protected String toolName() {
        return "rename_symbol";
    }

    @Override
    protected String toolDescription() {
        return "[REWRITE] Rename a class, method, or field. By default updates every caller (global). "
                + "Set updateCallers=false to rename only the declaration without touching call sites. "
                + "Edit via OpenRewrite; Spoon only for pre-edit analysis.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name of the target"),
            "oldName", Map.of("type", "string", "description", "Current name of the symbol"),
            "newName", Map.of("type", "string", "description", "New name for the symbol"),
            "scope", Map.of("type", "string", "description", "Optional: 'method', 'field', or 'class' (default: method)"),
            "updateCallers", Map.of("type", "boolean", "description", "If true (default), update all call sites. If false, rename only the declaration."),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }

    @Override
    protected List<String> toolRequired() {
        return req("name", "className", "oldName", "newName");
    }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        Object rawCallers = request.arguments().get("updateCallers");
        var result = driver.renameSymbol(new RewispDriver.RenameRequest(name, arg(request, "className"),
                arg(request, "oldName"), arg(request, "newName"), arg(request, "scope"),
                rawCallers == null ? null : toBoolean(rawCallers), boolArg(request, "dryRun")));
        ToolCallGuard.noteProject(result.project());
        if (result.dryRun()) {
            return dryRunResult(result.preview(), result.dryRunSnapshot());
        }

        String oldName = arg(request, "oldName");
        StringBuilder sb = new StringBuilder();
        sb.append("Renamed: ").append(oldName).append(" -> ").append(arg(request, "newName")).append(" [OpenRewrite]\n");
        sb.append("Declarations renamed: ").append(result.declarationsRenamed()).append("\n");
        sb.append("Callers updated: ").append(result.callersUpdated()).append("\n");
        sb.append("Files changed: ").append(result.changedFiles()).append("\n");
        if (!result.updateCallers()) {
            sb.append("Mode: declaration only (updateCallers=false). Call sites were NOT modified.\n");
        }
        var unresolved = result.unresolved();
        if (unresolved.total() > 0) {
            sb.append("Unresolved references (string literals):\n");
            for (StringLiteralScanner.Hit ref : unresolved.hits()) {
                sb.append("  ").append(ref.file()).append(":").append(ref.line() > 0 ? String.valueOf(ref.line()) : "?")
                        .append(" - ").append(ref.text()).append("\n");
            }
            if (unresolved.omitted() > 0) {
                sb.append("  ... and ").append(unresolved.omitted()).append(" more\n");
            }
        }
        sb.append(formatMultiModuleWarning(result.modulesDetected(), result.modulesLoaded()));
        sb.append(formatDiagnostics(result.preview()));
        return ok(sb);
    }}

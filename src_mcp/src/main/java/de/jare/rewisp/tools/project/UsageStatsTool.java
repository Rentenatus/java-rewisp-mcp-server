/*
 * MIT License
 *
 * Copyright (c) 2026 Janusch Rentenatus
 */
package de.jare.rewisp.tools.project;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.analysis.SpoonModelService;
import de.jare.rewisp.build.ResolveOptions;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.tools.base.BaseProjectTool;
import de.jare.rewisp.tools.base.UsageStats;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * {@code get_usage_stats}: usage statistics per tool since server start with a flat token estimate, number of
 * loaded projects and files as well as the effective time limits (project lifetime, eviction grace, build resolution).
 *
 * @author Janusch Rentenatus
 */
public class UsageStatsTool extends BaseProjectTool {

    public UsageStatsTool(ProjectManager manager) {
        super(manager);
    }

    @Override protected String toolName() { return "get_usage_stats"; }

    @Override protected String toolDescription() {
        return "[SPOON] Usage statistics since server start: per tool the calls, errors, run time and a rough token "
                + "estimate (about 4 characters per token), plus the number of loaded projects and source files and "
                + "the effective timeouts (project lifetime, eviction grace, build resolution).";
    }

    @Override protected Map<String, Object> toolProperties() { return Map.of(); }

    @Override protected List<String> toolRequired() { return List.of(); }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        StringBuilder sb = new StringBuilder("# Usage statistics\n\n");
        Instant now = Instant.now();
        Duration uptime = Duration.between(UsageStats.startedAt(), now);
        sb.append("**Counting since:** ").append(UsageStats.startedAt()).append(" (").append(formatDuration(uptime))
                .append(")\n\n");

        appendProjects(sb, now);
        appendTimeouts(sb);
        appendUsage(sb);
        return ok(sb);
    }

    private void appendProjects(StringBuilder sb, Instant now) {
        var entries = manager.list();
        long files = 0;
        long expired = 0;
        StringBuilder rows = new StringBuilder();
        for (ProjectEntry e : entries) {
            var state = manager.getProjectState(e.name());
            int fileCount = state != null && state.snapshot() != null ? state.snapshot().files().size() : 0;
            files += fileCount;
            if (e.expired()) expired++;
            String expires = e.expiryDate() == null ? "-" : e.expiryDate() + " ("
                    + (e.expiryDate().isBefore(now) ? "expired " : "in ")
                    + formatDuration(Duration.between(now, e.expiryDate()).abs()) + ")";
            rows.append("| ").append(e.name()).append(" | ").append(e.buildType()).append(" | ").append(fileCount)
                    .append(" | ").append(e.model() == null ? "released" : "loaded").append(" | ").append(expires)
                    .append(" |\n");
        }
        sb.append("## Projects\n\n");
        sb.append("**Loaded projects:** ").append(entries.size()).append(" (").append(expired).append(" expired), ")
                .append("**source files in snapshots:** ").append(files).append("\n\n");
        if (!entries.isEmpty()) {
            sb.append("| Project | Build | Files | Spoon model | Expires |\n|---|---|---|---|---|\n").append(rows).append('\n');
        }
    }

    private void appendTimeouts(StringBuilder sb) {
        ResolveOptions resolve = ResolveOptions.defaults();
        sb.append("## Timeouts and limits\n\n");
        sb.append("| Setting | Value | System property |\n|---|---|---|\n");
        sb.append("| Default project lifetime | ")
                .append(Long.getLong(ProjectManager.DEFAULT_EXPIRY_PROPERTY, ProjectManager.DEFAULT_EXPIRY_MINUTES))
                .append(" min | `").append(ProjectManager.DEFAULT_EXPIRY_PROPERTY).append("` (or `expiryDate` per load) |\n");
        sb.append("| Grace before the Spoon model of an expired project is released | ")
                .append(manager.evictionGrace().toMinutes()).append(" min | `project.evictionGraceMinutes` |\n");
        sb.append("| Build resolution timeout (mvn/gradle) | ").append(resolve.timeoutSeconds()).append(" s | - |\n");
        sb.append("| Build resolution output limit | ").append(resolve.maxOutputBytes() / 1024).append(" KiB | - |\n");
        sb.append("| Build resolution network access | ").append(resolve.networkAccess() ? "allowed" : "offline")
                .append(" | `").append(ResolveOptions.NETWORK_PROPERTY).append("` |\n");
        sb.append("| Compile check before writing | ")
                .append(Boolean.parseBoolean(System.getProperty(SpoonModelService.VALIDATE_PROPERTY, "true")) ? "on" : "off")
                .append(", skipped above ")
                .append(Integer.getInteger(SpoonModelService.VALIDATE_MAX_SOURCES_PROPERTY, 3000))
                .append(" source files | `").append(SpoonModelService.VALIDATE_PROPERTY).append("`, `")
                .append(SpoonModelService.VALIDATE_MAX_SOURCES_PROPERTY).append("` |\n\n");
    }

    private void appendUsage(StringBuilder sb) {
        List<UsageStats.Row> rows = UsageStats.snapshot();
        long calls = 0;
        long errors = 0;
        long tokensIn = 0;
        long tokensOut = 0;
        long tokensDefinition = 0;
        sb.append("## Tool usage\n\n");
        sb.append("| Tool | Calls | Errors | Avg ms | Max ms | Tokens in | Tokens out | Tokens total | Definition tokens |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (UsageStats.Row r : rows) {
            calls += r.calls();
            errors += r.errors();
            tokensIn += r.tokensIn();
            tokensOut += r.tokensOut();
            tokensDefinition += r.tokensDefinition();
            sb.append("| `").append(r.tool()).append("` | ").append(r.calls()).append(" | ").append(r.errors())
                    .append(" | ").append(r.avgMillis()).append(" | ").append(r.maxMillis()).append(" | ")
                    .append(r.tokensIn()).append(" | ").append(r.tokensOut()).append(" | ")
                    .append(r.tokensIn() + r.tokensOut()).append(" | ").append(r.tokensDefinition()).append(" |\n");
        }
        sb.append("| **Total** | **").append(calls).append("** | **").append(errors).append("** | | | **")
                .append(tokensIn).append("** | **").append(tokensOut).append("** | **").append(tokensIn + tokensOut)
                .append("** | **").append(tokensDefinition).append("** |\n\n");
        sb.append("_Token estimate: about ").append(UsageStats.CHARS_PER_TOKEN)
                .append(" characters per token over the arguments (in), the response text (out) and the tool definition "
                        + "(description plus schema, which the client loads once per session). It is a flat rule of thumb, "
                        + "not a measurement; it does not include the model's own reasoning or the client's formatting._\n");
    }

    private static String formatDuration(Duration d) {
        long s = d.getSeconds();
        if (s < 120) return s + " s";
        if (s < 7200) return (s / 60) + " min";
        return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
    }
}

package de.jare.rewisp.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.VersionLoader;
import de.jare.rewisp.build.BuildContext;
import de.jare.rewisp.build.BuildFingerprint;
import de.jare.rewisp.build.BuildResolutionException;
import de.jare.rewisp.build.ModuleContext;

import java.util.List;
import java.util.Map;

/**
 * The {@code ProjectRefreshBuildTool} class.
 *
 * <p>Loest den Build-Kontext fuer ein Projekt on-demand auf ueber
 * {@link ProjectManager#ensureBuildContext}. Gibt Module, Classpath-Groesse
 * und Fingerprint als strukturiertes Markdown zurueck.
 *
 * @author Janusch Rentenatus
 */
/**
 * SPOON-based tool: Read-only analysis tool.
 * Extends BaseReadTool to ensure no filesystem access (3-layer architecture compliance).
 */
public class ProjectRefreshBuildTool extends BaseReadTool {

    public ProjectRefreshBuildTool(ProjectManager manager) {
        super(manager);
    }

    @Override protected String toolName() { return "project_refresh_build"; }
    @Override protected String toolDescription() {
        return "[SPOON] Resolves the build context (classpath, module structure, source roots) for a project "
            + "on demand. Uses the Maven build resolver to run effective-pom and dependency:build-classpath. "
            + "Returns modules, classpath size, and build fingerprint. The resolved classpath "
            + "supplements the Spoon launcher on the next reload.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return propsWithDescription("name", "string", "Project name or alias");
    }
    @Override protected List<String> toolRequired() { return req("name"); }

    @Override
    protected CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String name = arg(request, "name");
        findEntry(name); // validates project exists

        BuildContext ctx;
        try {
            ctx = manager.ensureBuildContext(name);
        } catch (BuildResolutionException e) {
            return domainError("BUILD_RESOLUTION_ERROR", e.getMessage());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Build Context: `").append(name).append("`\\n\\n");
        sb.append("**MCP Server Version:** ").append(VersionLoader.getVersion()).append("\\n\\n");
        sb.append("**Build System:** ").append(ctx.buildSystem()).append("\\n\\n");

        // Fingerprint
        BuildFingerprint fp = ctx.fingerprint();
        sb.append("## Fingerprint\\n\\n");
        sb.append("| Component | Hash |\\n");
        sb.append("|-----------|------|\\n");
        sb.append("| buildFiles | `").append(abbreviate(fp.buildFilesHash())).append("` |\\n");
        sb.append("| classpath | `").append(abbreviate(fp.classpathHash())).append("` |\\n");
        sb.append("| sourceRoots | `").append(abbreviate(fp.sourceRootsHash())).append("` |\\n");
        sb.append("| toolchain | `").append(fp.toolchainHash()).append("` |\\n");
        sb.append("| composite | `").append(abbreviate(fp.compositeHash())).append("` |\\n\\n");

        // Modules
        sb.append("## Modules (").append(ctx.modules().size()).append(")\\n\\n");
        for (ModuleContext mod : ctx.modules()) {
            sb.append("### `").append(mod.moduleId()).append("`\\n");
            sb.append("- **Root:** `").append(mod.moduleRoot()).append("`\\n");
            sb.append("- **Language Level:** ").append(mod.languageLevel()).append("\\n");
            sb.append("- **Source Roots:** ").append(mod.sourceRoots().size()).append("\\n");
            sb.append("- **Test Source Roots:** ").append(mod.testSourceRoots().size()).append("\\n");
            sb.append("- **Generated Source Roots:** ").append(mod.generatedSourceRoots().size()).append("\\n");
            sb.append("- **Compile Classpath Entries:** ").append(mod.compileClasspath().size()).append("\\n");
            if (!mod.compileClasspath().isEmpty()) {
                sb.append("<details><summary>Classpath</summary>\\n\\n```\n");
                for (int i = 0; i < mod.compileClasspath().size(); i++) {
                    sb.append(mod.compileClasspath().get(i)).append("\\n");
                }
                sb.append("```\\n\\n</details>\\n");
            }
            sb.append("\\n");
        }

        // Diagnostics
        if (!ctx.diagnostics().isEmpty()) {
            sb.append("## Diagnostics\\n\\n");
            sb.append("| Code | Severity | Module | Message |\\n");
            sb.append("|------|----------|--------|---------|\\n");
            for (var diag : ctx.diagnostics()) {
                sb.append("| ").append(diag.code()).append(" | ").append(diag.severity())
                  .append(" | ").append(diag.module()).append(" | ").append(diag.message()).append(" |\\n");
            }
        }

        return ok(sb);
    }

    private static String abbreviate(String hash) {
        if (hash == null || hash.length() <= 16) return hash;
        return hash.substring(0, 16) + "...";
    }
}

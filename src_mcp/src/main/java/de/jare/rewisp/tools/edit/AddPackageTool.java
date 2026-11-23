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
import de.jare.rewisp.edit.DryRunPreview;
import de.jare.rewisp.tools.base.BaseEditTool;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import java.util.Map;
import de.jare.rewisp.api.DefaultRewispDriver;
import de.jare.rewisp.api.RewispDriver;
import de.jare.rewisp.tools.base.ToolCallGuard;

/**
 * The {@code AddPackageTool} — creates a new package directory.
 *
 * @author Janusch Rentenatus
 */
/**
 * REWRITE-based tool: Edit operation tool.
 * Extends BaseEditTool to ensure filesystem access goes through REWRITE layer (3-layer architecture compliance).
 */
public class AddPackageTool extends BaseEditTool {

    public AddPackageTool(ProjectManager manager, EditManager editManager) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager);
    }

    @Override protected String toolName() { return "add_package"; }
    @Override protected String toolDescription() {
        return "[SPOON] Create a new package. Creates the directory structure and optionally generates package-info.java.";
    }
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "packageName", Map.of("type", "string", "description", "Fully qualified package name (e.g. 'com.example.service')"),
            "generatePackageInfo", Map.of("type", "boolean", "description", "If true, generates package-info.java"),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }
    @Override protected List<String> toolRequired() { return req("name", "packageName"); }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String packageName = arg(request, "packageName");
        boolean genInfo = boolArg(request, "generatePackageInfo");
        var result = driver.addPackage(new RewispDriver.AddPackageRequest(arg(request, "name"), packageName,
                genInfo, boolArg(request, "dryRun")));
        ToolCallGuard.noteProject(result.project());
        var srcDir = result.sourceRoot();
        var packageDir = result.packageDir();

        if (result.dryRun()) {
            StringBuilder sb = new StringBuilder("DRY RUN - add_package: no files written. Would change ")
                    .append(genInfo ? 2 : 1).append(" item(s):\n");
            sb.append("  mkdir ").append(srcDir.relativize(packageDir)).append('\n');
            if (genInfo) {
                sb.append("  create ").append(srcDir.relativize(packageDir.resolve("package-info.java"))).append('\n');
                DryRunPreview.appendDiff(sb, "", "package " + packageName + ";\n\n");
            }
            return ok(sb);
        }

        return ok("Package created: " + packageName + " at " + packageDir
                + (genInfo ? " (with package-info.java)" : "")
                + "\n" + formatMultiModuleWarning(result.modulesDetected(), result.modulesLoaded()));
    }
}

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

package de.jare.rewisp.tools.project;

import de.jare.rewisp.tools.base.BaseProjectTool;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code RebootSpoonTool} — discards all loaded projects and resets the
 * edit-manager state so the server can recover from parse errors, corrupted
 * model state, or stuck dirty flags without restarting the JVM.
 *
 * <p>After reboot, no projects are loaded; call {@code load_java_project} to
 * re-load the ones you need. This is the recovery tool for situations where
 * {@code reload_java_project} fails (e.g. after an edit produced unparseable
 * source, or a MavenLauncher classpath error put the server in a bad state).
 *
 * @author Janusch Rentenatus
 */
public class RebootSpoonTool extends BaseProjectTool {

    private static final Logger LOG = LoggerFactory.getLogger(RebootSpoonTool.class);

    public RebootSpoonTool(ProjectManager manager, EditManager editManager) {
        super(manager);
        this.driver = new de.jare.rewisp.api.DefaultRewispDriver(manager, editManager);
    }

    @Override protected boolean requiresProjectLock() { return true; }
    @Override protected String toolName() { return "reboot_spoon"; }
    @Override protected String toolDescription() {
        return "[SPOON] Discard ALL loaded projects and reset edit-manager state (transactions, "
                + "backups, edit log) to recover from "
                + "parse errors or a corrupted model — without restarting the JVM. "
                + "After reboot, no projects are loaded; call load_java_project to re-load. "
                + "Use this when reload_java_project fails or the server is in a bad state.";
    }
    @Override protected Map<String, Object> toolProperties() { return Map.of(); }
    @Override protected List<String> toolRequired() { return List.of(); }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        var result = driver.reboot();
        var removed = result.removedProjects();

        StringBuilder sb = new StringBuilder();
        sb.append("Spoon rebooted successfully.\n");
        sb.append("Removed ").append(removed.size()).append(" project(s): ");
        if (removed.isEmpty()) {
            sb.append("(none were loaded)");
        } else {
            sb.append(String.join(", ", removed));
        }
        sb.append("\n");
        if (result.discardedChanges() > 0) {
            sb.append("Discarded pending transaction with ").append(result.discardedChanges())
              .append(" uncommitted change(s).\n");
        }
        sb.append("All edit-manager state cleared (transactions, backups, edit log).\n");
        sb.append("No projects are loaded. Call load_java_project to re-load.");
        return ok(sb);
    }}

/*
 * MIT License
 *
 * Copyright (c) 2026 Alejandro Ferreira
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

package de.jare.rewisp.tools.read;

import de.jare.rewisp.tools.base.BaseReadTool;
import de.jare.rewisp.tools.base.ToolCallGuard;


import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import de.jare.rewisp.ProjectManager;

import java.util.List;
import java.util.Map;

/**
 * Text search over the effective Rewrite state (snapshot, dirty files, transaction overlay): the
 * original source text of every file - methods, constructors, fields, annotations, comments, nested
 * types - with {@code file:line}. Counts every occurrence, not just one per method.
 *
 * @author Alejandro Ferreira
 */
public class SearchSourceTool extends BaseReadTool {

    private static final int DEFAULT_MAX_RESULTS = 200;

/**
 * Constructs the {@code SearchSourceTool} with the given project manager.
 */
    public SearchSourceTool(ProjectManager manager) {
        super(manager);
    }

/**
 * Returns the name of this tool.
 */
    @Override protected String toolName() { return "search_source"; }
/**
 * Returns the description of this tool.
 */
    @Override protected String toolDescription() { return "[SPOON] Searches the source text of all files of a loaded Java project (code, comments, annotations, fields, constructors, nested types) and reports file:line for each matching line plus the total number of occurrences."; }
/**
 * Returns the input schema properties for this tool.
 */
    @Override protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "query", Map.of("type", "string", "description", "Text to search for (plain text, not a regex)"),
            "caseSensitive", Map.of("type", "boolean", "description", "Optional: case-sensitive search (default false)"),
            "maxResults", Map.of("type", "integer", "description", "Optional: maximum number of matching lines to list (default " + DEFAULT_MAX_RESULTS + "); counts stay exact")
        );
    }
/**
 * Returns the list of required argument keys for this tool.
 */
    @Override protected List<String> toolRequired() { return req("name", "query"); }

/**
 * Handles the {@code search_source} tool invocation and returns the result.
 */
    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) {
        String name = arg(request, "name");
        String query = arg(request, "query");
        boolean caseSensitive = boolArg(request, "caseSensitive");
        int maxResults = DEFAULT_MAX_RESULTS;
        Object rawMax = request.arguments() == null ? null : request.arguments().get("maxResults");
        if (rawMax != null && !String.valueOf(rawMax).isBlank()) {
            try {
                maxResults = Math.max(1, Integer.parseInt(String.valueOf(rawMax).trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("maxResults must be an integer");
            }
        }
        var result = driver.searchSource(name, query, caseSensitive, maxResults);
        ToolCallGuard.noteProject(result.project());

        StringBuilder sb = new StringBuilder();
        sb.append("# Search Results for `").append(query).append("` in ").append(result.project()).append("\n\n");
        for (var hit : result.hits()) {
            sb.append("- `").append(hit.file()).append(":").append(hit.line()).append("`  ").append(hit.text()).append("\n");
        }

        if (result.occurrences() == 0) {
            sb.append("_No results found._");
        } else {
            if (result.matchingLines() > result.hits().size()) {
                sb.append("\n_Output limited to ").append(result.hits().size()).append(" of ").append(result.matchingLines())
                  .append(" matching lines (maxResults)._\n");
            }
            sb.append("\n**Total matches:** ").append(result.occurrences())
              .append(" in ").append(result.matchingLines()).append(" line(s) of ").append(result.files()).append(" file(s)");
        }

        return ok(sb);
    }}
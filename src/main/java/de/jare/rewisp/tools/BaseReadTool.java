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

package de.jare.rewisp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.ProjectEntry;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all SPOON-based tools (Read-only operations).
 * 
 * <p><strong>Architecture Rule:</strong> This class is part of the SPOON layer.
 * It must NOT access the filesystem directly. All data must be obtained through
 * the REWRITE layer (SnapshotService, ProjectManager, etc.).
 * 
 * <p><strong>SPOON Layer Responsibilities:</strong>
 * <ul>
 *   <li>Code analysis (inspecting types, methods, fields)</li>
 *   <li>Symbol search (finding references, implementations)</li>
 *   <li>Validation (pre-checks before edits)</li>
 *   <li>AST navigation</li>
 * </ul>
 * 
 * <p><strong>Forbidden Operations:</strong>
 * <ul>
 *   <li>Direct filesystem access (Files.exists, Files.readString, Files.walk, etc.)</li>
 *   <li>Direct file I/O operations</li>
 *   <li>Access to SPEICHER layer</li>
 * </ul>
 * 
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
public abstract class BaseReadTool implements McpTool {

    protected final ProjectManager manager;
    protected final Logger log = LoggerFactory.getLogger(getClass());
    private List<String> expiredNames = List.of();

    /**
     * Constructs the tool with the given project manager.
     */
    protected BaseReadTool(ProjectManager manager) {
        this.manager = manager;
    }

    /**
     * Returns the name of the tool.
     */
    protected abstract String toolName();

    /**
     * Returns the human-readable description of the tool.
     */
    protected abstract String toolDescription();

    /**
     * Returns the input schema properties for the tool.
     */
    protected abstract Map<String, Object> toolProperties();

    /**
     * Returns the list of required argument keys.
     */
    protected abstract List<String> toolRequired();

    /**
     * Builds the MCP tool specification with the tool schema and handler.
     */
    @Override
    public McpServerFeatures.SyncToolSpecification build() {
        McpSchema.Tool toolDef = McpSchema.Tool.builder(toolName())
                .description(toolDescription())
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", toolProperties(),
                        "required", toolRequired()
                ))
                .build();

        return new McpServerFeatures.SyncToolSpecification(toolDef, (exchange, request) -> {
            ToolCallGuard.CallTrace trace = ToolCallGuard.begin(toolName(), request.arguments());
            try {
                expiredNames = manager.markExpired();
                CallToolResult result = handle(exchange, request);
                ToolCallGuard.end(trace, result.isError(), null);
                return result;
            } catch (IllegalArgumentException e) {
                CallToolResult err = domainError("DOMAIN_ERROR", e.getMessage(), (Map<String, Object>) null);
                ToolCallGuard.end(trace, true, e.getMessage());
                return err;
            } catch (Exception e) {
                // Auch interne Fehler müssen auf die Log-Trace verweisen —
                // sonst ist der konkrete Stacktrace im Log für den Agenten
                // nicht auffindbar (Lücke gefunden im Dogfooding-Review).
                String hint = trace != null ? " | " + ToolCallGuard.hint(trace.traceId()) : "";
                CallToolResult err = error("internal error: " + e.getMessage() + hint);
                ToolCallGuard.end(trace, true, e.getMessage(), e);
                return err;
            }
        });
    }

    /**
     * Handles the tool invocation and returns the result.
     */
    protected abstract CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception;

    // ==================== Common Argument Extractors ====================

    /**
     * Extracts a string argument from the request.
     */
    protected static String arg(CallToolRequest request, String key) {
        return (String) request.arguments().get(key);
    }

    /**
     * Extracts a boolean argument from the request, defaulting to {@code false}.
     */
    protected static boolean boolArg(CallToolRequest request, String key) {
        Boolean val = (Boolean) request.arguments().get(key);
        return val != null && val;
    }

    /**
     * Extracts a list-of-strings argument from the request, returning an
     * empty list if absent. Accepts both {@code List<String>} and arrays of
     * String; non-String entries are coerced via {@code String.valueOf}.
     * Returns an empty list (never null) when the key is missing or null.
     */
    @SuppressWarnings("unchecked")
    protected static java.util.List<String> stringListArg(CallToolRequest request, String key) {
        Object val = request.arguments().get(key);
        if (val == null) return java.util.List.of();
        java.util.List<String> result = new java.util.ArrayList<>();
        if (val instanceof java.util.List<?> list) {
            for (Object o : list) result.add(o == null ? null : String.valueOf(o));
        } else if (val instanceof Object[] arr) {
            for (Object o : arr) result.add(o == null ? null : String.valueOf(o));
        } else {
            // Single string — treat as a one-element list for convenience.
            result.add(String.valueOf(val));
        }
        return result;
    }

    // ==================== Project Entry Management ====================

    /**
     * Finds a loaded project entry by name, throwing if not found.
     */
    protected ProjectEntry findEntry(String name) {
        var entry = manager.find(name);
        if (entry == null) {
            throw new IllegalArgumentException(
                    "no Java project found with name or alias '" + name + "'. Call load_java_project first.");
        }
        return entry;
    }

    // ==================== SPOON Model Synchronization ====================

    /**
     * Checks if the Spoon model is stale (files changed by REWRITE).
     * Call this at the beginning of Spoon-based tools to ensure model consistency.
     * 
     * @param projectName the project name
     * @return true if a reload was performed, false if model was already synced
     */
    protected boolean reloadIfSpoonStale(String projectName) {
        var syncManager = manager.rewriteSyncManager();
        if (syncManager == null) {
            return false; // MVP: fallback to old behavior
        }
        
        if (!syncManager.needsRebuild()) {
            return false;
        }
        
        log.info("Spoon model stale for project {}, reloading...", projectName);
        
        // Find the project entry
        var entry = manager.find(projectName);
        if (entry != null) {
            try {
                // Reload the project - this rebuilds Spoon model from REWRITE layer
                manager.reload(entry.name());
                // Mark as synced after reload
                if (syncManager != null) {
                    syncManager.markSynced();
                }
                log.info("Spoon model reloaded and synced for project {}", projectName);
                return true;
            } catch (Exception e) {
                log.warn("Failed to reload project {} after stale detection: {}", projectName, e.getMessage());
                // Continue with stale model - the tool will work but may have inconsistent data
            }
        }
        return false;
    }

    /**
     * Checks if a specific file is stale in the Spoon model.
     * 
     * @param projectName the project name
     * @param filePath the file path to check
     * @return true if file is stale, false otherwise
     */
    protected boolean isFileStaleInSpoon(String projectName, java.nio.file.Path filePath) {
        var syncManager = manager.rewriteSyncManager();
        if (syncManager == null) {
            return false; // MVP: fallback to old behavior
        }
        return syncManager.needsRebuild(filePath);
    }

    /**
     * Checks if there are any stale files in the Spoon model.
     * 
     * @return true if there are stale files, false otherwise
     */
    protected boolean hasStaleFilesInSpoon() {
        var syncManager = manager.rewriteSyncManager();
        if (syncManager == null) {
            return false; // MVP: fallback to old behavior
        }
        return syncManager.needsRebuild();
    }

    // ==================== Fingerprint Validation =====================

    /**
     * Validates the optimistic lock for a file before reading.
     * This is a SPOON-layer check that uses fingerprints from the ProjectEntry
     * (which are maintained by the REWRITE layer).
     * 
     * <p>Note: This method does NOT access the filesystem directly. It uses
     * fingerprints stored in the ProjectEntry by the REWRITE layer.
     * 
     * @param entry the loaded project entry (in-memory fingerprints)
     * @param file the target file path
     * @param expectedFingerprint optional fingerprint the caller believes the file has
     * @throws IllegalArgumentException if the file fingerprint does not match
     */
    protected void validateFingerprint(ProjectEntry entry, java.nio.file.Path file, String expectedFingerprint) {
        // Skip check for files this session has edited
        if (entry.isFileEdited(file)) return;

        java.nio.file.Path normalized = file.normalize();
        de.jare.rewisp.model.Fingerprint stored = entry.sourceFingerprints().get(normalized);
        if (stored == null) return; // no fingerprint for this file — cannot check

        // Conditions 1 & 3: if expectedFingerprint provided, it must match in-memory
        if (expectedFingerprint != null && !expectedFingerprint.isBlank()) {
            String storedStr = fingerprintToString(stored);
            if (!expectedFingerprint.equals(storedStr)) {
                throw new IllegalArgumentException(
                        "File '" + file.getFileName() + "' has been modified since the caller's last observation "
                        + "(expected fingerprint mismatch). Reload the project.");
            }
        }
    }

    /**
     * Serializes a fingerprint to a string for use as expectedFingerprint parameter.
     */
    protected static String fingerprintToString(de.jare.rewisp.model.Fingerprint fp) {
        return fp.lastModified() + "|" + fp.fileSize();
    }

    // ==================== Result Builders ====================

    private static final tools.jackson.databind.ObjectMapper DOMAIN_ERROR_MAPPER = new tools.jackson.databind.ObjectMapper();

    /**
     * Builds a <strong>domain error</strong> result — a structured, agent-readable
     * answer for a <em>fachlichen</em> Fehler (type not found, duplicate annotation,
     * invalid {@code @Override}, fingerprint mismatch, etc.).
     *
     * @param errorType a stable, uppercase error category
     * @param message human-readable description of the problem
     * @param context additional attributes (className, methodName, parameters,
     *                details, suggestion, etc.); may be {@code null}
     * @return a {@code CallToolResult} with {@code isError=true} and JSON content
     */
    protected CallToolResult domainError(String errorType, String message, Map<String, Object> context) {
        try {
            tools.jackson.databind.node.ObjectNode json = DOMAIN_ERROR_MAPPER.createObjectNode();
            json.put("error", true);
            json.put("isDomainError", true);
            json.put("errorType", errorType != null ? errorType : "UNKNOWN");
            json.put("message", message != null ? message : "");
            json.put("tool", toolName());
            if (context != null) {
                for (var entry : context.entrySet()) {
                    String k = entry.getKey();
                    Object v = entry.getValue();
                    if (v == null) json.putNull(k);
                    else if (v instanceof String s) json.put(k, s);
                    else if (v instanceof Boolean b) json.put(k, b);
                    else json.put(k, v.toString());
                }
            }
            String content = DOMAIN_ERROR_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            ToolCallGuard.CallTrace trace = ToolCallGuard.current();
            if (trace != null) {
                json.put("traceId", trace.traceId());
                String logFile = ToolCallGuard.logFile();
                if (logFile != null) json.put("logFile", logFile);
                json.put("logHint", ToolCallGuard.hint(trace.traceId()));
                content = DOMAIN_ERROR_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            }
            log.warn("Domain error [{}] {} | context={} | trace={}", errorType, message, context,
                    trace != null ? trace.traceId() : "none");
            return McpSchema.CallToolResult.builder()
                    .addTextContent(content)
                    .isError(true)
                    .build();
        } catch (Exception e) {
            log.warn("Domain error fallback [{}]: {}", errorType, message);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Domain error [" + errorType + "]: " + message)
                    .isError(true)
                    .build();
        }
    }

    /**
     * Convenience overload: domain error with errorType and message, no context.
     */
    protected CallToolResult domainError(String errorType, String message) {
        return domainError(errorType, message, (Map<String, Object>) null);
    }

    /**
     * Convenience overload for a domain error with a single className context.
     */
    protected CallToolResult domainError(String errorType, String message, String className) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        if (className != null) ctx.put("className", className);
        return domainError(errorType, message, ctx);
    }

    /**
     * Convenience overload: builds a context map from key-value pairs.
     * Usage: {@code domainError("TYPE_NOT_FOUND", "...", ctx("className", cls, "methodName", m))}
     */
    protected static Map<String, Object> ctx(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }

    /**
     * Builds an error result with the given message.
     */
    protected static CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent("Error: " + message)
                .isError(true)
                .build();
    }

    /**
     * Builds a successful result with the given text content.
     */
    protected CallToolResult ok(String content) {
        String warning = formatExpiredWarning(expiredNames);
        warning += formatModelDirtyWarning(manager);
        return McpSchema.CallToolResult.builder()
                .addTextContent(warning + content)
                .isError(false)
                .build();
    }

    /**
     * Builds a successful result from a StringBuilder.
     */
    protected CallToolResult ok(StringBuilder sb) {
        return ok(sb.toString());
    }

    /**
     * Formats an expiry warning for newly expired projects.
     */
    protected static String formatExpiredWarning(List<String> expiredNames) {
        if (expiredNames == null || expiredNames.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("> ⚠️ **Expired projects:** ");
        for (int i = 0; i < expiredNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("`").append(expiredNames.get(i)).append("`");
        }
        sb.append("\n");
        sb.append("> These projects have passed their expiry date. Data may be stale.\n");
        sb.append("> Call `reload_java_project` with `expired=true` to reload all expired projects.\n\n");
        return sb.toString();
    }

    /**
     * Formats a model-dirty warning for projects with stale models after edits.
     */
    protected static String formatModelDirtyWarning(ProjectManager manager) {
        java.util.List<String> dirty = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<String>> dirtyFilesByProject = new java.util.LinkedHashMap<>();
        for (var entry : manager.list()) {
            if (entry.modelDirty()) {
                dirty.add(entry.name());
                java.util.List<String> names = new java.util.ArrayList<>();
                for (java.nio.file.Path p : entry.dirtyFiles()) {
                    java.nio.file.Path fn = p.getFileName();
                    names.add(fn != null ? fn.toString() : p.toString());
                }
                dirtyFilesByProject.put(entry.name(), names);
            }
        }
        if (dirty.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("> ⚠️ **Model is dirty:** ");
        for (int i = 0; i < dirty.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("`").append(dirty.get(i)).append("`");
        }
        sb.append("\n> Edits have been made since load. Data may be stale.\n");
        for (var proj : dirtyFilesByProject.entrySet()) {
            java.util.List<String> files = proj.getValue();
            sb.append("> `").append(proj.getKey()).append("` — ");
            if (files.size() <= 5) {
                sb.append(String.join(", ", files));
            } else {
                sb.append(files.size()).append(" files (");
                sb.append(String.join(", ", files.subList(0, 5)));
                sb.append(", ...)");
            }
            sb.append("\n");
        }
        sb.append("> Call `reload_java_project` to refresh the model.\n\n");
        return sb.toString();
    }

    // ==================== Schema Helpers ====================

    /**
     * Builds a single-property schema map.
     */
    protected static Map<String, Object> props(String k1, String t1) {
        return Map.of(k1, Map.of("type", t1));
    }

    /**
     * Builds a two-property schema map.
     */
    protected static Map<String, Object> props(String k1, String t1, String k2, String t2) {
        return Map.of(k1, Map.of("type", t1), k2, Map.of("type", t2));
    }

    /**
     * Builds a three-property schema map.
     */
    protected static Map<String, Object> props(String k1, String t1, String k2, String t2, String k3, String t3) {
        return Map.of(
            k1, Map.of("type", t1),
            k2, Map.of("type", t2),
            k3, Map.of("type", t3)
        );
    }

    /**
     * Builds a single-property schema map with descriptions.
     */
    protected static Map<String, Object> propsWithDescription(String k1, String t1, String d1) {
        return Map.of(k1, Map.of("type", t1, "description", d1));
    }

    /**
     * Builds a two-property schema map with descriptions.
     */
    protected static Map<String, Object> propsWithDescription(String k1, String t1, String d1,
                                                                String k2, String t2, String d2) {
        return Map.of(
            k1, Map.of("type", t1, "description", d1),
            k2, Map.of("type", t2, "description", d2)
        );
    }

    /**
     * Builds a three-property schema map with descriptions.
     */
    protected static Map<String, Object> propsWithDescription(String k1, String t1, String d1,
                                                                String k2, String t2, String d2,
                                                                String k3, String t3, String d3) {
        return Map.of(
            k1, Map.of("type", t1, "description", d1),
            k2, Map.of("type", t2, "description", d2),
            k3, Map.of("type", t3, "description", d3)
        );
    }

    /**
     * Builds a list of required argument keys.
     */
    protected static List<String> req(String... keys) {
        return List.of(keys);
    }
}

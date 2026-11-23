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

package de.jare.rewisp.tools.base;

import de.jare.rewisp.project.ProjectLock;

import java.util.ArrayList;

import de.jare.rewisp.model.ErrorType;

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


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common base of all MCP tools: schema/argument helpers, error and result builders,
 * warning formatting and the uniform call wrapper ({@link #build()}).
 *
 * <p>The layer base classes {@link BaseReadTool} (SPOON, read-only) and {@link BaseEditTool}
 * (REWRITE, writing) extend it and only add their layer-specific rules
 * (edit lock, fingerprint check, snapshot refresh).</p>
 *
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
public abstract class BaseTool implements McpTool {

    protected final ProjectManager manager;
    /** Backend interface: the tools parse arguments, call the driver and render the result. */
    protected de.jare.rewisp.api.RewispDriver driver;
    protected final Logger log = LoggerFactory.getLogger(getClass());
    /** Newly expired projects of the current call (per thread; one tool object serves parallel calls). */
    private final ThreadLocal<List<String>> expiredNames = ThreadLocal.withInitial(List::of);

    /**
     * Constructs the tool with the given project manager.
     */
    protected BaseTool(ProjectManager manager) {
        this.manager = manager;
        this.driver = new de.jare.rewisp.api.DefaultRewispDriver(manager);
    }

    /**
     * true if the call changes project, snapshot or transaction state and therefore must run under
     * the {@link ProjectLock process-wide lock}. Default: no (pure reads).
     */
    protected boolean requiresProjectLock() {
        return false;
    }

    /** Hook before the call; default: take the lock if needed. */
    protected void beforeCall() {
        if (requiresProjectLock()) ProjectLock.lock();
    }

    /** Hook after the call, also on error; default: release the lock if it was taken. */
    protected void afterCall() {
        if (requiresProjectLock()) ProjectLock.unlock();
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

        UsageStats.registerDefinition(toolName(),
                (toolDef.description() == null ? 0 : toolDef.description().length())
                        + String.valueOf(toolDef.inputSchema()).length() + toolName().length());

        return new McpServerFeatures.SyncToolSpecification(toolDef, (exchange, request) -> {
            long started = System.nanoTime();
            CallToolResult result = execute(exchange, request);
            UsageStats.record(toolName(), System.nanoTime() - started, result.isError(),
                    UsageStats.argumentChars(request.arguments()), UsageStats.resultChars(result));
            return result;
        });
    }

    /** Executes the call with trace, flow check and uniform error handling (the MCP server's path). */
    public CallToolResult execute(McpSyncServerExchange exchange, CallToolRequest request) {
        beforeCall();
        ToolCallGuard.CallTrace trace = ToolCallGuard.begin(toolName(), request.arguments());
        try {
            expiredNames.set(manager.markExpired());
            CallToolResult result = doHandle(exchange, request);
            ToolCallGuard.end(trace, result.isError(), null);
            return result;
        } catch (de.jare.rewisp.analysis.TypeNotFoundException e) {
            CallToolResult err = domainError(ErrorType.TYPE_NOT_FOUND, e.getMessage(), ctx("className", e.typeName()));
            ToolCallGuard.end(trace, true, e.getMessage());
            return err;
        } catch (de.jare.rewisp.api.RewispException e) {
            CallToolResult err = domainError(e.code(), e.getMessage(), e.context());
            ToolCallGuard.end(trace, true, e.getMessage());
            return err;
        } catch (IllegalArgumentException e) {
            CallToolResult err = domainError(ErrorType.DOMAIN_ERROR, e.getMessage(), (Map<String, Object>) null);
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
        } finally {
            expiredNames.remove();
            afterCall();
        }
    }

    /**
     * Handles the tool invocation and returns the result. Domain errors of the backend
     * ({@link de.jare.rewisp.api.RewispException}) become the domain-error response; everything else
     * is rethrown and handled by {@link #execute}.
     */
    public final CallToolResult handle(McpSyncServerExchange exchange, CallToolRequest request) {
        try {
            return doHandle(exchange, request);
        } catch (de.jare.rewisp.api.RewispException e) {
            return domainError(e.code(), e.getMessage(), e.context());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw BaseTool.<RuntimeException>sneaky(e); // wie bisher: geprueft weitergereicht
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }

    /** The actual tool logic: parse arguments, call the driver, render the result. */
    protected abstract CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception;

    // ==================== Common Argument Extractors ====================

    /**
     * Extracts a string argument from the request.
     */
    protected static String arg(CallToolRequest request, String key) {
        Object val = request.arguments().get(key);
        return val == null ? null : val.toString(); // tolerant: auch Zahlen und Booleans als Text
    }

    /**
     * Extracts a boolean argument from the request, defaulting to {@code false}.
     */
    protected static boolean boolArg(CallToolRequest request, String key) {
        return toBoolean(request.arguments().get(key));
    }

    /**
     * Tolerant boolean conversion: {@code Boolean}, the texts "true"/"false" (case-insensitive)
     * and numbers (non-zero = true); anything else is {@code false}.
     */
    protected static boolean toBoolean(Object val) {
        if (val instanceof Boolean b) return b;
        if (val instanceof Number n) return n.doubleValue() != 0;
        return val != null && Boolean.parseBoolean(val.toString().trim());
    }

    /** Tolerant integer conversion (number or text); {@code defaultValue} when missing or invalid. */
    protected static int intArg(CallToolRequest request, String key, int defaultValue) {
        Object val = request.arguments().get(key);
        if (val instanceof Number n) return n.intValue();
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Extracts a list-of-strings argument from the request, returning an
     * empty list if absent. Accepts both {@code List<String>} and arrays of
     * String; non-String entries are coerced via {@code String.valueOf}.
     * Returns an empty list (never null) when the key is missing or null.
     */
    @SuppressWarnings("unchecked")
    protected static List<String> stringListArg(CallToolRequest request, String key) {
        Object val = request.arguments().get(key);
        if (val == null) return List.of();
        List<String> result = new ArrayList<>();
        if (val instanceof List<?> list) {
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
        ToolCallGuard.noteProject(entry.name());
        return entry;
    }

    // ==================== SPOON Model Synchronization ====================




    // ==================== Fingerprint Validation =====================



    // ==================== Result Builders ====================

    private static final tools.jackson.databind.ObjectMapper DOMAIN_ERROR_MAPPER = new tools.jackson.databind.ObjectMapper();

    /**
     * Builds a <strong>domain error</strong> result — a structured, AI-agent-readable
     * answer for a <em>domain</em> error (type not found, duplicate annotation,
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

    /** Typsichere Variante von {@link #domainError(String, String, Map)}. */
    protected CallToolResult domainError(ErrorType errorType, String message, Map<String, Object> context) {
        return domainError(errorType.name(), message, context);
    }

    /** Typsichere Variante von {@link #domainError(String, String)}. */
    protected CallToolResult domainError(ErrorType errorType, String message) {
        return domainError(errorType.name(), message);
    }

    /** Typsichere Variante von {@link #domainError(String, String, String)}. */
    protected CallToolResult domainError(ErrorType errorType, String message, String className) {
        return domainError(errorType.name(), message, className);
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
     * Usage: {@code domainError(ErrorType.TYPE_NOT_FOUND, "...", ctx("className", cls, "methodName", m))}
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
        String warning = formatExpiredWarning(expiredNames.get());
        warning += formatStaleModelWarning(manager);
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
        sb.append("> These projects have passed their expiry date. The sources are still current; the structure model "
                + "is freed after a few idle minutes and rebuilt on the next access.\n");
        sb.append("> Call `reload_java_project` with `expired=true` to renew all expired projects.\n\n");
        return sb.toString();
    }

    /**
     * Warns when a project's Spoon model could not be rebuilt from the current sources
     * (e.g. after an edit that does not compile): structure queries then show the last valid state.
     */
    protected static String formatStaleModelWarning(ProjectManager manager) {
        String callProject = ToolCallGuard.callProject();
        StringBuilder sb = new StringBuilder();
        for (var entry : manager.list()) {
            // Aufruf mit Projektbezug: nur dessen Warnung (andere Projekte stoeren hier nicht)
            if (callProject != null && !callProject.equals(entry.name())) continue;
            var state = manager.getProjectState(entry.name());
            String error = state == null ? null : state.spoonRebuildError();
            if (error == null) continue;
            String shortError = error.length() > 300 ? error.substring(0, 300) + "..." : error;
            sb.append("> ⚠️ **Stale structure model:** `").append(entry.name()).append("` - the current sources do not compile (")
              .append(shortError.replace("\n", " ")).append("). inspect/find/list results reflect the last compilable state; ")
              .append("get_file_content and search_source show the current text. Fix the source (e.g. edit_line) to refresh.\n");
        }
        return sb.length() == 0 ? "" : sb.append("\n").toString();
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

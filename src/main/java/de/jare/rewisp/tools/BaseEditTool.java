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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Stream;

import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.model.Fingerprint;
import de.jare.rewisp.model.ProjectEntry;
import de.jare.rewisp.snapshot.DirtyCheckResult;

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
 * Base class for all REWRITE-based tools (Edit operations).
 * 
 * <p><strong>Architecture Rule:</strong> This class is part of the REWRITE layer.
 * It may access the filesystem (SPEICHER layer) directly through the FileAccessService.
 * It must NOT access the SPOON layer.
 * 
 * <p><strong>REWRITE Layer Responsibilities:</strong>
 * <ul>
 *   <li>Code editing (adding, removing, modifying methods, fields, classes)</li>
 *   <li>File operations (create, delete, move files)</li>
 *   <li>Transaction management (begin, commit, rollback)</li>
 *   <li>Snapshot management (capturing file states)</li>
 * </ul>
 * 
 * <p><strong>Allowed Operations:</strong>
 * <ul>
 *   <li>Filesystem access through FileAccessService</li>
 *   <li>Direct file I/O operations (for edit tools)</li>
 *   <li>Access to SPEICHER layer</li>
 * </ul>
 * 
 * <p><strong>Forbidden Operations:</strong>
 * <ul>
 *   <li>Direct access to SPOON layer (CtModel, Launcher, etc.)</li>
 *   <li>Using Spoon for edit operations</li>
 * </ul>
 * 
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
public abstract class BaseEditTool implements McpTool {

    protected final ProjectManager manager;
    protected final Logger log = LoggerFactory.getLogger(getClass());
    private List<String> expiredNames = List.of();

    /**
     * Constructs the tool with the given project manager.
     */
    protected BaseEditTool(ProjectManager manager) {
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
            // MCP-Clients koennen Tool-Calls parallel absetzen. Edit-Tools
            // teilen sich Snapshot-State, den Transaktions-Puffer und die
            // EditManager-Pipeline — ohne Serialisierung wuerden überlappende
            // Edits desselben Projekts sich gegenseitig die gepufferten
            // Vorgänger überschreiben (letzter Schreiber gewinnt, gefunden
            // am 2026-10-03 im Live-Betrieb). Ein globales Lock ist bewusst:
            // Transaktionen sind prozessglobal, und Edit-Durchsatz ist
            // sequenziell in der Praxis.
            EDIT_LOCK.lock();
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
            } finally {
                EDIT_LOCK.unlock();
            }
        });
    }

    /**
     * Serialisiert alle Edit- und Transaktions-Tool-Calls prozessglobal
     * (siehe Kommentar im build()-Wrapper).
     */
    private static final java.util.concurrent.locks.ReentrantLock EDIT_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

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

    /**
     * Checks that the project is editable; throws if not.
     */
    protected ProjectEntry requireEditable(ProjectEntry entry) {
        if (!entry.editable()) {
            throw new IllegalArgumentException(
                    "Project '" + entry.name() + "' is read-only (loaded from JAR or with editable=false). "
                    + "Edit tools are not available.");
        }
        return entry;
    }

    // ==================== SPOON Model Synchronization ====================

    /**
     * Checks if the Spoon model is stale (files changed by REWRITE).
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
                // Reload the project - this rebuilds Spoon model from disk
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
    protected boolean isFileStaleInSpoon(String projectName, Path filePath) {
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

    /**
     * Marks all files as synchronized with Spoon.
     */
    protected void markSpoonSynced() {
        var syncManager = manager.rewriteSyncManager();
        if (syncManager != null) {
            syncManager.markSynced();
        }
    }

    /**
     * Reloads the project if a specific file is stale in Spoon.
     * More efficient for single-file tools.
     * 
     * @param projectName the project name
     * @param filePath the file path to check
     * @return true if a reload was performed, false if file was already synced
     */
    protected boolean reloadIfFileStale(String projectName, Path filePath) {
        if (!isFileStaleInSpoon(projectName, filePath)) {
            return false;
        }
        
        return reloadIfSpoonStale(projectName);
    }

    // ==================== Filesystem Access (REWRITE Layer) ====================

    /**
     * Checks if a file exists on the filesystem.
     * REWRITE layer is allowed to access SPEICHER layer.
     * 
     * @param file the path to check
     * @return true if the file exists, false otherwise
     */
    protected static boolean fileExists(Path file) {
        return Files.exists(file);
    }

    /**
     * Checks if a directory exists on the filesystem.
     * 
     * @param directory the path to check
     * @return true if the directory exists, false otherwise
     */
    protected boolean directoryExists(Path directory) {
        return Files.isDirectory(directory);
    }

    /**
     * Aktualisiert den Projekt-Snapshot vor einem Edit, wenn das Projekt
     * dreckige Dateien hat (vorherige Edits dieser Session).
     *
     * <p>Schmaler Scope ({@code broadScope=false}, Edit zielt auf eine
     * Datei): nur die dreckigen Dateien werden neu gelesen und im
     * bestehenden Snapshot ersetzt (Stale-Map-Prinzip). Breite Scopes
     * ({@code broadScope=true}, z.B. rename mit Caller-Updates,
     * move_class oder Signatur-Aenderung mit Caller-Updates) brauchen
     * weiterhin einen kompletten Neuaufbau ueber captureStable.</p>
     *
     * @param entry der geladene Projekt-Eintrag
     * @param broadScope true = kompletter Neuaufbau, false = inkrementell
     * @return null bei Erfolg, sonst die Fehlermeldung
     */
    protected String refreshSnapshotForEdit(ProjectEntry entry, boolean broadScope) {
        // In einer aktiven Transaktion liegen gepufferte Aenderungen nur im
        // Speicher — dirtyFiles bleibt bis zum Commit leer. Der Refresh muss
        // sie daher zusaetzlich zur Platte beruecksichtigen (Overlay),
        // sonst wuerde jeder folgende Edit seine Vorgänger mit dem
        // Platten-Zustand ueberschreiben (letzter Schreiber gewinnt).
        Map<Path, String> pending = pendingTransactionChanges(entry);
        if (entry.dirtyFiles().isEmpty() && pending.isEmpty()) return null;
        try {
            var state = manager.getProjectState(entry.name());
            if (state == null || state.snapshot() == null) {
                return "no snapshot available for project " + entry.name();
            }
            var snapshotService = manager.getSnapshotService();
            var fresh = broadScope
                    ? snapshotService.captureStable(entry, manager.getSnapshotSequence().incrementAndGet())
                    : snapshotService.refreshFiles(entry, state.snapshot(),
                            dirtyRelativePaths(entry), manager.getSnapshotSequence().incrementAndGet());
            if (!pending.isEmpty()) {
                fresh = snapshotService.applyContents(entry, fresh, pending,
                        manager.getSnapshotSequence().incrementAndGet());
            }
            state.setSnapshot(fresh);
            state.setSnapshotId(fresh.id());
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /**
     * Liefert die in einer aktiven Transaktion gepufferten Inhalte als
     * Map relativ zum Projekt-Root; außerhalb einer Transaktion leer.
     */
    private Map<Path, String> pendingTransactionChanges(ProjectEntry entry) {
        var editMgr = manager.editManager();
        if (editMgr == null || !editMgr.isInTransaction()) return Map.of();
        Map<Path, String> pending = editMgr.getPendingChanges();
        if (pending.isEmpty()) return Map.of();
        Map<Path, String> relative = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : pending.entrySet()) {
            relative.put(de.jare.rewisp.util.PathUtils.relativizeFromRoot(entry.originalProjectDir(), e.getKey()),
                    e.getValue());
        }
        return relative;
    }

    /**
     * Wandelt die absoluten dreckigen Dateien eines Projekts in Pfade um,
     * die relativ zum Projekt-Root sind (das Schluessel-Format der
     * Snapshots). Nutzt PathUtils wegen der Windows-8.3-Kurz-/Langnamen-
     * Problematik.
     */
    private Set<Path> dirtyRelativePaths(ProjectEntry entry) {
        Set<Path> relative = new HashSet<>();
        for (Path dirty : entry.dirtyFiles()) {
            relative.add(de.jare.rewisp.util.PathUtils.relativizeFromRoot(
                    entry.originalProjectDir(), dirty));
        }
        return relative;
    }

    /**
     * Reads the content of a file as a string.
     * 
     * @param file the path to the file
     * @return the file content as string
     * @throws IOException if the file cannot be read
     */
    protected String readFileContent(Path file) throws IOException {
        return Files.readString(file);
    }

    /**
     * Gets the last modified time of a file.
     * 
     * @param file the path to the file
     * @return the last modified time in milliseconds
     * @throws IOException if the file cannot be accessed
     */
    protected long getLastModifiedTime(Path file) throws IOException {
        return Files.getLastModifiedTime(file).toMillis();
    }

    /**
     * Gets the size of a file in bytes.
     * 
     * @param file the path to the file
     * @return the file size in bytes
     * @throws IOException if the file cannot be accessed
     */
    protected long getFileSize(Path file) throws IOException {
        return Files.size(file);
    }

    /**
     * Creates a fingerprint for a file (last modified time + size).
     * 
     * @param file the path to the file
     * @return a Fingerprint object, or null if file doesn't exist
     * @throws IOException if the file cannot be accessed
     */
    protected Fingerprint createFingerprint(Path file) throws IOException {
        if (!fileExists(file)) {
            return null;
        }
        return new Fingerprint(
            getLastModifiedTime(file),
            getFileSize(file)
        );
    }

    /**
     * Walks the file tree from a root directory and returns all Java source files.
     * 
     * @param root the root directory to walk
     * @return set of all Java source file paths
     * @throws IOException if the walk operation fails
     */
    protected Set<Path> findJavaSourceFiles(Path root) throws IOException {
        Set<Path> javaFiles = new HashSet<>();
        if (!directoryExists(root)) {
            return javaFiles;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".java"))
                  .forEach(javaFiles::add);
        }
        return javaFiles;
    }

    // ==================== Dirty Checking (Filesystem-based) ====================

    /**
     * Phase 10: REWRITE-Spoon Synchronisation
     * Checks if a file has been modified since project load.
     * Uses filesystem access (REWRITE layer is allowed to access SPEICHER).
     * 
     * @param entry the project entry
     * @param file the file to check
     * @param expectedFingerprint optional fingerprint the caller believes the file has
     * @throws IllegalArgumentException if the file was changed externally or the
     *                                  expected fingerprint does not match
     */
    public static void validateFingerprint(ProjectEntry entry, Path file, String expectedFingerprint) {
        // Skip disk-vs-fingerprint comparison only for files this session has edited.
        // For unedited files, the fingerprint must still match disk (external change detection).
        if (entry.isFileEdited(file)) return;

        Path normalized = file.normalize();
        Fingerprint stored = entry.sourceFingerprints().get(normalized);
        if (stored == null) return; // no fingerprint for this file — cannot check

        // Condition 4: in-memory fingerprint must match disk (external change detection)
        if (!fileExists(file)) {
            throw new IllegalArgumentException(
                    "File '" + file.getFileName() + "' has been deleted since project load. "
                    + "Reload the project before editing.");
        }
        Fingerprint onDisk;
        try {
            onDisk = new Fingerprint(
                    Files.getLastModifiedTime(file).toMillis(),
                    Files.size(file));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read file '" + file + "': " + e.getMessage());
        }
        if (!stored.equals(onDisk)) {
            throw new IllegalArgumentException(
                    "File '" + file.getFileName() + "' has been modified since project load (fingerprint mismatch). "
                    + "Another session or external process changed this file. Reload the project before editing.");
        }

        // Conditions 1 & 3: if expectedFingerprint provided, it must match in-memory
        if (expectedFingerprint != null && !expectedFingerprint.isBlank()) {
            String storedStr = fingerprintToString(stored);
            if (!expectedFingerprint.equals(storedStr)) {
                throw new IllegalArgumentException(
                        "File '" + file.getFileName() + "' has been modified since the caller's last observation "
                        + "(expected fingerprint mismatch). Reload the project before editing.");
            }
        }
        // Condition 2: expectedFingerprint not provided, disk matches in-memory — proceed
    }

    /**
     * Serializes a fingerprint to a string for use as expectedFingerprint parameter.
     */
    protected static String fingerprintToString(Fingerprint fp) {
        return fp.lastModified() + "|" + fp.fileSize();
    }

    /**
     * Phase 10: REWRITE-Spoon Synchronisation
     * Checks that the project is editable and validates file fingerprint.
     * 
     * @param entry the project entry
     * @param file the file to check
     * @param expectedFingerprint optional fingerprint the caller believes the file has
     * @return the validated entry
     * @throws IllegalArgumentException if the file was changed externally or the
     *                                  expected fingerprint does not match
     */
    protected ProjectEntry validateEditableAndFingerprint(ProjectEntry entry, Path file, String expectedFingerprint) {
        requireEditable(entry);
        validateFingerprint(entry, file, expectedFingerprint);
        return entry;
    }

    // ==================== Result Builders ====================

    /**
     * Legacy wrapper for DirtyChecker.checkDirtyFullScan - kept for test compatibility.
     * This method accesses the filesystem directly.
     * 
     * @param entry the project entry
     * @return a DirtyCheckResult with information about changed files
     * @deprecated Use {@link de.jare.rewisp.snapshot.DirtyChecker#checkDirtyAgainstSnapshot} instead
     */
    @Deprecated
    public static DirtyCheckResult checkDirtyFullScan(ProjectEntry entry) {
        return de.jare.rewisp.snapshot.DirtyChecker.checkDirtyFullScan(entry);
    }

    private static final tools.jackson.databind.ObjectMapper DOMAIN_ERROR_MAPPER = new tools.jackson.databind.ObjectMapper();

    /**
     * Maximum number of stale (dirty) source files that reference-needing
     * edit tools (global rename, signature+callers) will re-parse on demand
     * for caller detection before requiring a full project reload.
     */
    protected static final int STALE_FILE_THRESHOLD = 20;

    /**
     * Guards reference-needing operations against an excessively stale model.
     * Returns a domain-error result the caller should return immediately when
     * too many source files have changed since load, or {@code null} to proceed.
     * Position-only edits (add_method, add_field, edit_line, ...) are NOT
     * affected — they re-parse their single target file on demand and do not
     * need the model's cross-file reference graph.
     */
    protected CallToolResult guardStaleThreshold(ProjectEntry entry) {
        if (entry.dirtyFiles().size() > STALE_FILE_THRESHOLD) {
            return domainError("MODEL_STALE",
                    entry.dirtyFiles().size() + " source files have changed since load — too many to re-parse "
                    + "individually for caller detection. The in-memory reference graph is stale.",
                    ctx("staleFiles", String.valueOf(entry.dirtyFiles().size()),
                        "threshold", String.valueOf(STALE_FILE_THRESHOLD),
                        "suggestion", "Call reload_java_project to refresh the model, then retry the edit."));
        }
        return null;
    }

    /**
     * Builds a <strong>domain error</strong> result — a structured, agent-readable
     * answer for a <em>fachlichen</em> Fehler (Spoon parse error, type not found,
     * duplicate annotation, invalid {@code @Override}, fingerprint mismatch, etc.).
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
        List<String> dirty = new ArrayList<>();
        Map<String, List<String>> dirtyFilesByProject = new LinkedHashMap<>();
        for (var entry : manager.list()) {
            if (entry.modelDirty()) {
                dirty.add(entry.name());
                List<String> names = new ArrayList<>();
                for (Path p : entry.dirtyFiles()) {
                    Path fn = p.getFileName();
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
            List<String> files = proj.getValue();
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

    /**
     * Formats a multi-module warning when detected modules exceed loaded modules.
     */
    protected static String formatMultiModuleWarning(ProjectEntry entry) {
        if (entry.modulesDetected() <= entry.modulesLoaded()) return "";
        int unloaded = entry.modulesDetected() - entry.modulesLoaded();
        return "> WARNING: " + entry.modulesDetected() + " modules detected, "
                + entry.modulesLoaded() + " loaded. Edits may miss references in "
                + unloaded + " unloaded module" + (unloaded > 1 ? "s" : "") + ". "
                + "Load all modules before editing for full coverage.\n\n";
    }

    /**
     * Formats a scoped dirty warning (no clean confirmation — token efficiency).
     */
    protected static String formatDirtyWarning(DirtyCheckResult dirty) {
        if (!dirty.isDirty()) return "";
        StringBuilder sb = new StringBuilder();
        int total = dirty.changedFiles().size() + dirty.deletedFiles().size() + dirty.newFiles().size();
        sb.append("> WARNING: ").append(total).append(" source file(s) changed since load:\n");
        List<String> all = new ArrayList<>();
        all.addAll(dirty.changedFiles());
        all.addAll(dirty.deletedFiles());
        all.addAll(dirty.newFiles());
        int cap = Math.min(all.size(), 10);
        for (int i = 0; i < cap; i++) {
            sb.append("> ").append(all.get(i)).append("\n");
        }
        if (all.size() > 10) sb.append("> ...and ").append(all.size() - 10).append(" more\n");
        sb.append("> Call `reload_java_project` to refresh the model.\n\n");
        return sb.toString();
    }

    // ==================== Code Generation Utilities ====================

    /**
     * Inserts import statements before the first class/interface/enum declaration in the source.
     * This is a utility method for code generation in REWRITE tools.
     *
     * @param source the source code
     * @param importsToAdd list of fully qualified names to import
     * @return source with imports inserted
     */
    protected static String insertImports(String source, java.util.List<String> importsToAdd) {
        if (importsToAdd == null || importsToAdd.isEmpty()) return source;
        // P51: deduplicate — skip imports already present in the source
        java.util.List<String> filtered = new java.util.ArrayList<>();
        for (String fqn : importsToAdd) {
            String importLine = "import " + fqn + ";";
            if (!source.contains(importLine)) {
                filtered.add(fqn);
            }
        }
        if (filtered.isEmpty()) return source;
        StringBuilder importBlock = new StringBuilder();
        for (String fqn : filtered) {
            importBlock.append("import ").append(fqn).append(";\n");
        }
        // Find insertion point: after package declaration and existing imports, before first type
        int insertIdx = 0;
        String[] lines = source.split("\n", -1);
        boolean foundPackage = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.startsWith("package ")) { foundPackage = true; insertIdx = i + 1; continue; }
            if (t.startsWith("import ")) { insertIdx = i + 1; continue; }
            if (foundPackage || i > 0) {
                // Skip blank lines after package/imports
                if (t.isEmpty() && i == insertIdx) { insertIdx = i + 1; continue; }
                break;
            }
        }
        // Insert import block at insertIdx
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == insertIdx) {
                result.append(importBlock);
                result.append("\n");
            }
            result.append(lines[i]);
            if (i < lines.length - 1) result.append("\n");
        }
        return result.toString();
    }

    /**
     * Splits a comma-separated list on top-level commas only, keeping commas
     * inside generic angle brackets together. For example {@code "Map<K, V>, int"}
     * yields {@code ["Map<K, V>", "int"]}. Each fragment is trimmed. Shared by
     * tools that parse method signatures or parameter lists.
     */
    protected static java.util.List<String> splitTopLevelCommas(String list) {
        java.util.List<String> params = new java.util.ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth = Math.max(0, depth - 1);
            if (c == ',' && depth == 0) {
                params.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) params.add(cur.toString().trim());
        return params;
    }

    // ==================== SPOON-based Code Analysis Utilities (Legacy) ====================
    // NOTE: These methods use SPOON for code analysis, which technically violates
    // the REWRITE layer rule "no SPOON access". However, they are kept here to
    // allow migration of legacy edit tools. These should be replaced with
    // pure REWRITE-layer implementations using SnapshotService in the future.

    /**
     * Locates a type in the source file and returns TypeInfo (REWRITE-layer compatible).
     * This is the architecture-compliant alternative to locateFreshType().
     * 
     * <p><strong>Architecture Note:</strong> This method is part of the REWRITE layer
     * and does NOT use SPOON. It provides text-based position information.
     * 
     * @param file the path to the Java source file
     * @param className the fully qualified name of the type to find
     * @return TypeInfo with position information, or null if not found
     */
    protected static TypeInfo locateTypeInfo(java.nio.file.Path file, String className) {
        return RewriteTypeUtils.locateType(file, className);
    }

    /**
     * Re-parses the current on-disk file and returns the CtType matching the
     * given class name. Returns {@code null} if parsing fails or the type is
     * not found; callers fall back to the loaded model's type.
     * 
     * <p><strong>Architecture Warning:</strong> This method uses SPOON and violates
     * the 3-layer architecture. Use {@link #locateTypeInfo} instead for REWRITE-layer compliance.
     * 
     * @deprecated This method uses SPOON and should be replaced with REWRITE-layer implementation.
     *            Use locateTypeInfo() which returns TypeInfo instead of CtType.
     */
    @Deprecated
    protected static spoon.reflect.declaration.CtType<?> locateFreshType(java.nio.file.Path file, String className) {
        try {
            spoon.Launcher fresh = new spoon.Launcher();
            fresh.addInputResource(file.toAbsolutePath().toString());
            fresh.getEnvironment().setNoClasspath(true);
            fresh.getEnvironment().setCommentEnabled(true);
            fresh.getEnvironment().setAutoImports(false);
            fresh.buildModel();
            return fresh.getModel().getAllTypes().stream()
                    .filter(t -> t.getQualifiedName().equals(className))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the offset of the type's closing {@code '}'}.
     * 
     * @deprecated This method uses SPOON and should be replaced with REWRITE-layer implementation
     */
    @Deprecated
    protected static int classClosingBraceOffset(java.nio.file.Path file, String source,
            spoon.reflect.declaration.CtType<?> freshType, spoon.reflect.declaration.CtType<?> targetType) {
        spoon.reflect.declaration.CtType<?> posType = (freshType != null) ? freshType : targetType;
        if (posType != null && posType.getPosition() != null) {
            int diskEnd = posType.getPosition().getSourceEnd();
            if (diskEnd >= 0) {
                String raw;
                try {
                    raw = java.nio.file.Files.readString(file);
                } catch (java.io.IOException e) {
                    raw = source;
                }
                int crBefore = 0;
                for (int k = 0; k < diskEnd && k < raw.length(); k++) {
                    if (raw.charAt(k) == '\r') crBefore++;
                }
                int candidate = diskEnd - crBefore;
                if (candidate >= 0 && candidate < source.length() && source.charAt(candidate) == '}') {
                    return candidate;
                }
            }
        }
        int endLine = (posType != null && posType.getPosition() != null) ? posType.getPosition().getEndLine() : 0;
        return SourceCodeUtils.findClassClosingBrace(source, endLine);
    }

    // ==================== String/Source Code Utilities ====================
    // These methods are now in SourceCodeUtils (REWRITE layer compatible)
    // Kept as delegates for backward compatibility

    /**
     * Finds the offset of the class closing brace by scanning backward from the end of the
     * file, skipping {@code '}'} characters inside comments and string/char literals.
     * The classEndLine hint from the model is used as a starting hint.
     * 
     * @param source the source code
     * @param classEndLine the line number where the class ends (hint for starting position)
     * @return the character offset of the closing brace, or -1 if not found
     */
    protected static int findClassClosingBrace(String source, int classEndLine) {
        return SourceCodeUtils.findClassClosingBrace(source, classEndLine);
    }

    /**
     * Maps an offset in the raw (on-disk, possibly CR-containing) source to the
     * corresponding offset in the normalized (CR-stripped) source, or returns -1
     * if the offset is out of range. Used by the text-based edit tools to translate
     * Spoon source positions (which are based on the on-disk file) into the
     * CR-stripped buffer they edit.
     * 
     * @param raw the raw source with CR characters
     * @param normalized the normalized source without CR characters
     * @param diskOffset the offset in the raw source
     * @return the corresponding offset in the normalized source, or -1 if out of range
     */
    protected static int mapDiskOffsetToNormalized(String raw, String normalized, int diskOffset) {
        return SourceCodeUtils.mapDiskOffsetToNormalized(raw, normalized, diskOffset);
    }

    /**
     * Returns the char offset of the start of the given 1-indexed line.
     * 
     * @param source the source code
     * @param line1 the 1-indexed line number
     * @return the character offset of the line start
     */
    protected static int lineStartOffset(String source, int line1) {
        return SourceCodeUtils.lineStartOffset(source, line1);
    }

    /**
     * Re-parses all dirty files in the project and returns a map from file path to fresh CtType.
     * Returns an empty map when no files are dirty. Callers should first pass
     * guardStaleThreshold(ProjectEntry) to avoid re-parsing too many.
     * 
     * @deprecated This method uses SPOON and should be replaced with REWRITE-layer implementation
     */
    @Deprecated
    protected static java.util.Map<java.nio.file.Path, spoon.reflect.declaration.CtType<?>> freshTypesForDirtyFiles(de.jare.rewisp.model.ProjectEntry entry) {
        java.util.Map<java.nio.file.Path, spoon.reflect.declaration.CtType<?>> fresh = new java.util.HashMap<>();
        if (entry.dirtyFiles().isEmpty()) return fresh;
        // Canonicalize dirty paths so matching tolerates path-form differences
        // (Windows 8.3 short names, symlinks) between the dirty set (populated
        // from writeFile, which may receive an agent-supplied path) and the
        // model's reported file paths (resolved by Spoon).
        java.util.Set<String> dirtyCanonical = new java.util.HashSet<>();
        for (java.nio.file.Path dirty : entry.dirtyFiles()) {
            dirtyCanonical.add(canonicalizePath(dirty));
        }
        for (spoon.reflect.declaration.CtType<?> t : entry.model().getAllTypes()) {
            if (t.getPosition() == null || t.getPosition().getFile() == null) continue;
            java.nio.file.Path modelFile = t.getPosition().getFile().toPath().normalize();
            if (!dirtyCanonical.contains(canonicalizePath(modelFile))) continue;
            spoon.reflect.declaration.CtType<?> ft = locateFreshType(modelFile, t.getQualifiedName());
            if (ft != null) {
                // Key by the model's normalized file path: the caller loops look
                // up via type.getPosition().getFile().toPath().normalize(), so
                // this key form matches their lookup.
                fresh.put(modelFile, ft);
            }
        }
        return fresh;
    }

    /**
     * Canonicalizes a path for comparison, handling path-form differences.
     */
    private static String canonicalizePath(java.nio.file.Path path) {
        try {
            return path.toRealPath().toString();
        } catch (java.io.IOException e) {
            return path.normalize().toString();
        }
    }

    /**
     * Validates an annotation target specification shared by the add, edit,
     * and remove annotation tools.
     *
     * @param targetType {@code "class"}, {@code "method"}, or {@code "field"}
     * @param targetName method or field name (null/blank for class)
     * @return an error message if invalid, or {@code null} if valid
     */
    public static String validateAnnotationTarget(String targetType, String targetName) {
        if (!"class".equals(targetType) && !"method".equals(targetType) && !"field".equals(targetType)) {
            return "targetType must be 'class', 'method', or 'field'";
        }
        if (("method".equals(targetType) || "field".equals(targetType))
                && (targetName == null || targetName.isBlank())) {
            return "targetName required for method and field annotations";
        }
        return null;
    }

    /**
     * Formats a warning for partially refreshed files.
     * 
     * @deprecated This method uses SPOON and should be replaced with REWRITE-layer implementation
     */
    @Deprecated
    protected static String formatFreshParsedWarning(java.util.Map<java.nio.file.Path, spoon.reflect.declaration.CtType<?>> freshTypes) {
        if (freshTypes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("> ⚠️ ").append(freshTypes.size()).append(" stale file(s) re-parsed on demand for caller detection: ");
        int i = 0;
        for (var e : freshTypes.entrySet()) {
            if (i > 0) sb.append(", ");
            java.nio.file.Path fn = e.getKey().getFileName();
            sb.append(fn != null ? fn : e.getKey());
            if (++i >= 5) { sb.append(", …"); break; }
        }
        sb.append(".\n> Model is partially stale — call `reload_java_project` for a full refresh.\n\n");
        return sb.toString();
    }

    /**
     * Counts net parentheses (closing minus opening) in a line, skipping
     * string and char literals. Positive = more closing parens than opening.
     */
    static int countNetParens(String line) {
        int net = 0;
        boolean inStr = false, inChar = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') { i++; continue; }
                if (c == '\'') inChar = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '(') net--;
            else if (c == ')') net++;
        }
        return net;
    }

    /**
     * Returns the 1-indexed start line of the preceding annotation block.
     */
    protected static int annotationSearchStart(String[] lines, int declLine) {
        int start = declLine;
        int parenDepth = 0; // >0 means we are inside a multi-line annotation
        for (int i = declLine - 2; i >= 0; i--) {
            String t = lines[i].trim();
            if (parenDepth <= 0 && t.isEmpty()) break;
            boolean isPattern = t.startsWith("@") || t.startsWith("//") || t.startsWith("*")
                    || t.startsWith("/*") || t.startsWith("*/");
            if (!isPattern && parenDepth <= 0) {
                // Check if this could be the closing ')' of a multi-line annotation
                int net = countNetParens(lines[i]);
                if (net > 0) {
                    parenDepth = net;
                    start = i + 1;
                    continue;
                }
                break;
            }
            start = i + 1;
            parenDepth += countNetParens(lines[i]);
            if (parenDepth < 0) parenDepth = 0;
        }
        return start;
    }

    /**
     * Computes the character offset range {@code [startOffset, endOffset)} for
     * the annotation block preceding the declaration of the given target. The
     * window includes the annotation block (which sits above the declaration)
     * up to and including the declaration line itself.
     *
     * @deprecated This method uses SPOON and should be replaced with REWRITE-layer implementation
     */
    @Deprecated
    protected static int[] annotationWindow(String source, spoon.reflect.declaration.CtType<?> type,
                                              String targetType, String targetName) {
        int declLine = -1;
        if ("method".equals(targetType) && targetName != null) {
            spoon.reflect.declaration.CtMethod<?> method = type.getMethods().stream()
                    .filter(m -> m.getSimpleName().equals(targetName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Method '" + targetName + "' not found in " + type.getQualifiedName()));
            declLine = method.getPosition().getLine();
        } else if ("field".equals(targetType) && targetName != null) {
            spoon.reflect.declaration.CtField<?> field = type.getFields().stream()
                    .filter(f -> f.getSimpleName().equals(targetName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Field '" + targetName + "' not found in " + type.getQualifiedName()));
            declLine = field.getPosition().getLine();
        } else if ("class".equals(targetType)) {
            declLine = type.getPosition().getLine();
        }
        String[] lines = source.split("\n", -1);
        int searchStartLine, searchEndLine;
        if (declLine > 0) {
            searchStartLine = annotationSearchStart(lines, declLine);
            searchEndLine = declLine;
        } else {
            searchStartLine = 1;
            searchEndLine = Integer.MAX_VALUE;
        }
        int startOffset = 0;
        for (int i = 0; i < searchStartLine - 1 && i < lines.length; i++) {
            startOffset += lines[i].length() + 1;
        }
        int endOffset = startOffset;
        for (int i = searchStartLine - 1; i < searchEndLine && i < lines.length; i++) {
            endOffset += lines[i].length() + 1;
        }
        endOffset = Math.min(endOffset, source.length());
        return new int[]{startOffset, endOffset};
    }

    /** Returns the index of the ')' matching the '(' at {@code open}, or -1. */
    private static int findMatchingParen(String s, int open, int limit) {
        int depth = 0;
        int i = open;
        boolean inStr = false, inChar = false;
        while (i < limit) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i += 2; continue; }
                if (c == '"') inStr = false;
                i++; continue;
            }
            if (inChar) {
                if (c == '\\') { i += 2; continue; }
                if (c == '\'') inChar = false;
                i++; continue;
            }
            if (c == '"') { inStr = true; i++; continue; }
            if (c == '\'') { inChar = true; i++; continue; }
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) return i; }
            i++;
        }
        return -1;
    }

    /**
     * Returns the index of the ')' matching the '(' at {@code open} within a
     * single line, skipping string and char literals, or -1. Single-line
     * wrapper around the offset-based scanner, shared by tools that match a
     * parameter list on one declaration line.
     */
    protected static int findMatchingParen(String line, int open) {
        return findMatchingParen(line, open, line.length());
    }

    /**
     * Replaces an annotation in a source window.
     * 
     * @deprecated This method uses SPOON and should be replaced with REWRITE-layer implementation
     */
    @Deprecated
    protected static String replaceAnnotationInWindow(String source, int startOffset, int endOffset,
                                                       String annotation, String replacement) {
        String search = "@" + annotation;
        int windowLen = endOffset - startOffset;
        int atIdx = -1;
        for (int i = startOffset; i <= endOffset - search.length(); i++) {
            if (source.regionMatches(i, search, 0, search.length())) {
                // Whole-word check: preceding char must not be an identifier part
                if (i > startOffset && Character.isJavaIdentifierPart(source.charAt(i - 1))) continue;
                // Following char must not be an identifier part (avoids @Override matching @Overrides)
                int after = i + search.length();
                if (after < endOffset && Character.isJavaIdentifierPart(source.charAt(after))) continue;
                atIdx = i;
                break;
            }
        }
        if (atIdx < 0) return null;

        int afterName = atIdx + search.length();
        // Skip whitespace
        int j = afterName;
        while (j < endOffset && Character.isWhitespace(source.charAt(j))) j++;

        int replaceEnd;
        if (j < endOffset && source.charAt(j) == '(') {
            // Find matching ')' with depth tracking, skipping strings/chars
            int close = findMatchingParen(source, j, endOffset);
            if (close < 0) return null; // unmatched — abort
            replaceEnd = close + 1;
        } else {
            replaceEnd = afterName;
        }
        return source.substring(0, atIdx) + replacement + source.substring(replaceEnd);
    }

    /**
     * Compares a method's parameter simple names against a signature list.
     */
    private static boolean freshParamsMatch(spoon.reflect.declaration.CtMethod<?> method, java.util.List<String> sigParams) {
        if (method.getParameters().size() != sigParams.size()) return false;
        for (int i = 0; i < sigParams.size(); i++) {
            String expected = eraseSigType(sigParams.get(i));
            String actual = method.getParameters().get(i).getType() != null
                    ? method.getParameters().get(i).getType().getSimpleName() : "";
            if (!actual.equals(expected)) return false;
        }
        return true;
    }

    private static String eraseSigType(String type) {
        String t = type.trim();
        // Normalize varargs: "int..." -> "int[]" (Spoon stores varargs as array)
        if (t.endsWith("...")) {
            t = t.substring(0, t.length() - 3) + "[]";
        }
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt).trim();
        int lastDot = t.lastIndexOf('.');
        if (lastDot >= 0) t = t.substring(lastDot + 1);
        return t;
    }

    /**
     * Re-parses the current on-disk file and returns the CtMethod matching
     * the given method name (and optional signature). Returns {@code null}
     * if not found. Shared by ReplaceMethodBodyTool and other tools that need
     * accurate method body positions after edits.
     * 
     * @deprecated This method uses SPOON and should be replaced with REWRITE-layer implementation
     */
    @Deprecated
    protected static spoon.reflect.declaration.CtMethod<?> locateFreshMethod(java.nio.file.Path file, String className,
                                                    String methodName, String signature) {
        spoon.reflect.declaration.CtType<?> t = locateFreshType(file, className);
        if (t == null) return null;
        java.util.List<spoon.reflect.declaration.CtMethod<?>> cands = t.getMethods().stream()
                .filter(m -> m.getSimpleName().equals(methodName))
                .collect(java.util.stream.Collectors.toList());
        if (signature == null || signature.isBlank()) {
            return cands.size() == 1 ? cands.get(0) : null;
        }
        java.util.List<String> sigParams = splitTopLevelCommas(signature);
        for (spoon.reflect.declaration.CtMethod<?> m : cands) {
            if (freshParamsMatch(m, sigParams)) return m;
        }
        return null;
    }

    // --- Shared code-safe text replacement (migrated from BaseJavaTool) ---

    /**
     * Replaces all word-boundary matches of {@code namePattern} with
     * {@code newName} in code regions of the source, skipping string literals,
     * char literals, and comments (line, block, Javadoc). This prevents
     * corrupting string content or comment text that happens to contain the
     * searched name.
     */
    protected static String replaceInCodeOnly(String source, java.util.regex.Pattern namePattern, String newName) {
        String[] lines = source.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (inBlockComment) {
                int closeIdx = line.indexOf("*/");
                if (closeIdx >= 0) {
                    // Block comment closes on this line — code may follow after */
                    inBlockComment = false;
                    result.append(line, 0, closeIdx + 2);
                    String rest = line.substring(closeIdx + 2);
                    result.append(replaceOutsideStrings(rest, namePattern, newName));
                    if (hasUnclosedBlockComment(rest)) inBlockComment = true;
                } else {
                    result.append(line);
                }
            } else if (trimmed.startsWith("//") || trimmed.startsWith("*/")) {
                result.append(line);
            } else if (trimmed.startsWith("/*")) {
                if (trimmed.contains("*/")) {
                    // Block comment opens and closes on same line — code may follow after */
                    result.append(replaceOutsideStrings(line, namePattern, newName));
                } else {
                    result.append(line);
                    inBlockComment = true;
                }
            } else {
                result.append(replaceOutsideStrings(line, namePattern, newName));
                if (hasUnclosedBlockComment(line)) inBlockComment = true;
            }
            if (i < lines.length - 1) result.append("\n");
        }
        return result.toString();
    }

    /** Returns true if the line contains an unclosed block comment opener. */
    private static boolean hasUnclosedBlockComment(String line) {
        int idx = 0;
        boolean inString = false;
        boolean inChar = false;
        while (idx < line.length()) {
            char c = line.charAt(idx);
            if (inString) {
                if (c == '\\') { idx += 2; continue; }
                if (c == '"') inString = false;
                idx++; continue;
            }
            if (inChar) {
                if (c == '\\') { idx += 2; continue; }
                if (c == '\'') inChar = false;
                idx++; continue;
            }
            if (c == '"') { inString = true; idx++; continue; }
            if (c == '\'') { inChar = true; idx++; continue; }
            // Line comment — rest of the line is a comment, not a block opener.
            if (c == '/' && idx + 1 < line.length() && line.charAt(idx + 1) == '/') {
                return false;
            }
            if (c == '/' && idx + 1 < line.length() && line.charAt(idx + 1) == '*') {
                int close = line.indexOf("*/", idx + 2);
                return close < 0;
            }
            idx++;
        }
        return false;
    }

    private static String replaceOutsideStrings(String line, java.util.regex.Pattern namePattern, String newName) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"') {
                result.append(c);
                i++;
                while (i < line.length()) {
                    char sc = line.charAt(i);
                    result.append(sc);
                    i++;
                    if (sc == '\\' && i < line.length()) {
                        result.append(line.charAt(i));
                        i++;
                    } else if (sc == '"') break;
                }
            } else if (c == '\'') {
                result.append(c);
                i++;
                while (i < line.length()) {
                    char cc = line.charAt(i);
                    result.append(cc);
                    i++;
                    if (cc == '\\' && i < line.length()) {
                        result.append(line.charAt(i));
                        i++;
                    } else if (cc == '\'') break;
                }
            } else {
                int start = i;
                while (i < line.length()) {
                    char ch = line.charAt(i);
                    if (ch == '"' || ch == '\'') break;
                    if (ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
                    if (ch == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') break;
                    i++;
                }
                String codeSegment = line.substring(start, i);
                result.append(namePattern.matcher(codeSegment).replaceAll(newName));
                if (i < line.length() && line.charAt(i) == '/' && i + 1 < line.length()
                        && line.charAt(i + 1) == '/') {
                    result.append(line.substring(i));
                    i = line.length();
                } else if (i < line.length() && line.charAt(i) == '/' && i + 1 < line.length()
                        && line.charAt(i + 1) == '*') {
                    int end = line.indexOf("*/", i + 2);
                    if (end < 0) { result.append(line.substring(i)); i = line.length(); }
                    else { result.append(line, i, end + 2); i = end + 2; }
                }
            }
        }
        return result.toString();
    }
}

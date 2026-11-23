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

package de.jare.rewisp;

import java.nio.file.StandardCopyOption;
import java.util.stream.Collectors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import de.jare.rewisp.tools.base.ToolCallGuard;
import de.jare.rewisp.tools.edit.AddAnnotationTool;
import de.jare.rewisp.tools.edit.AddClassTool;
import de.jare.rewisp.tools.edit.AddFieldTool;
import de.jare.rewisp.tools.edit.AddMethodTool;
import de.jare.rewisp.tools.edit.AddPackageTool;
import de.jare.rewisp.tools.edit.EditAnnotationTool;
import de.jare.rewisp.tools.edit.EditLineTool;
import de.jare.rewisp.tools.edit.MoveClassTool;
import de.jare.rewisp.tools.edit.RemoveAnnotationTool;
import de.jare.rewisp.tools.edit.RemoveMemberTool;
import de.jare.rewisp.tools.edit.RenameSymbolTool;
import de.jare.rewisp.tools.edit.ReplaceMethodBodyTool;
import de.jare.rewisp.tools.edit.RewriteSignatureTool;
import de.jare.rewisp.tools.project.CheckProjectDirtyTool;
import de.jare.rewisp.tools.project.ListLoadedProjectsTool;
import de.jare.rewisp.tools.project.LoadJavaProjectTool;
import de.jare.rewisp.tools.project.ProjectMetadataTool;
import de.jare.rewisp.tools.project.ProjectRefreshBuildTool;
import de.jare.rewisp.tools.project.RebootSpoonTool;
import de.jare.rewisp.tools.project.ReloadJavaProjectTool;
import de.jare.rewisp.tools.project.UnloadJavaProjectTool;
import de.jare.rewisp.tools.read.FindImplementationsTool;
import de.jare.rewisp.tools.read.FindReferencesTool;
import de.jare.rewisp.tools.read.GetElementPositionTool;
import de.jare.rewisp.tools.read.GetFileContentTool;
import de.jare.rewisp.tools.read.GetTypeHierarchyTool;
import de.jare.rewisp.tools.read.InspectClassTool;
import de.jare.rewisp.tools.read.InspectFieldTool;
import de.jare.rewisp.tools.read.InspectMethodTool;
import de.jare.rewisp.tools.read.ListClassesTool;
import de.jare.rewisp.tools.read.SearchSourceTool;
import de.jare.rewisp.tools.txn.TransactionTools;

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * The {@code JavaMcpServer} class.
 *
 * @author Alejandro Ferreira
 * @author Janusch Rentenatus
 */
public class JavaMcpServer {

    private final ProjectManager projectManager = new ProjectManager();
    private final de.jare.rewisp.edit.EditManager editManager = new de.jare.rewisp.edit.EditManager();
    
    private final de.jare.rewisp.rewrite.RecipeRegistry recipeRegistry = new de.jare.rewisp.rewrite.YamlRecipeRegistry();
    private final de.jare.rewisp.rewrite.RewriteEngine rewriteEngine = new de.jare.rewisp.rewrite.OpenRewriteEngineImpl(recipeRegistry);

    // Phase 8: Hardening und Observability
    private final de.jare.rewisp.snapshot.WorkspaceRegistry workspaceRegistry;
    private final de.jare.rewisp.snapshot.ChangeTracker changeTracker = new de.jare.rewisp.snapshot.ChangeTracker();
    private final de.jare.rewisp.snapshot.WorkspaceWatcher workspaceWatcher;

    {
        projectManager.setEditManager(editManager);

        // Lade Recipes aus Classpath
        recipeRegistry.loadFromClasspath(JavaMcpServer.class.getClassLoader());

        // OpenRewrite/Spoon im Hintergrund vorwaermen: der erste Edit soll keine Anlaufzeit mehr tragen
        Warmup.start(rewriteEngine);

        // Phase 8: Hardening und Observability initialisieren
        // WorkspaceRegistry mit erlaubten Roots aus Konfiguration
        List<String> allowedRoots = new ArrayList<>();
        String workspaceAllowedRoots = System.getProperty("workspace.allowedRoots");
        if (workspaceAllowedRoots != null && !workspaceAllowedRoots.isBlank()) {
            for (String root : workspaceAllowedRoots.split(",")) {
                allowedRoots.add(root.trim());
            }
        }
        this.workspaceRegistry = new de.jare.rewisp.snapshot.WorkspaceRegistry(allowedRoots);
        
        // DebounceMillis aus Konfiguration
        long debounceMillis = 200; // Default
        String debounceStr = System.getProperty("workspace.debounceMillis");
        if (debounceStr != null && !debounceStr.isBlank()) {
            try {
                debounceMillis = Long.parseLong(debounceStr);
            } catch (NumberFormatException e) {
                // Verwende Default
                // Logger erst hier holen: ein statisches Feld wuerde slf4j vor configureLogFile() initialisieren
                LoggerFactory.getLogger(JavaMcpServer.class).warn("Ignoring invalid workspace debounce value '{}': {}", debounceStr, e.getMessage());
            }
        }
        
        // IgnoredDirectories aus Konfiguration
        List<String> ignoredDirs = new ArrayList<>();
        String ignoredDirsStr = System.getProperty("workspace.ignoredDirectories");
        if (ignoredDirsStr != null && !ignoredDirsStr.isBlank()) {
            // Beispiel: "target,build,.git,.gradle" -> ["target", "build", ".git", ".gradle"]
            for (String dir : ignoredDirsStr.split(",")) {
                ignoredDirs.add(dir.trim());
            }
        } else {
            // Default-IgnoredDirectories
            ignoredDirs = List.of("target", "build", ".git", ".gradle", ".idea", "out");
        }
        
        this.workspaceWatcher = new de.jare.rewisp.snapshot.WorkspaceWatcher(
                changeTracker, debounceMillis, ignoredDirs);
        
        // WorkspaceWatcher Integration in ProjectManager
        projectManager.setWorkspaceRegistry(workspaceRegistry);
        projectManager.setChangeTracker(changeTracker);
        projectManager.setWorkspaceWatcher(workspaceWatcher);
        
        // Shutdown-Hook für WorkspaceWatcher
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (workspaceWatcher != null) {
                workspaceWatcher.shutdown();
            }
        }));
    }

/**
 * Entry point that loads logging configuration and starts the MCP server.
 */
    public static void main(String[] args) {
        configureLogFile();
        loadLoggingConfig();
        JavaMcpServer server = new JavaMcpServer();
        server.start();
    }

/**
 * Configures the server log file (slf4j-simple) before the first logger is
 * initialized. Default: ~/.rewisp/logs/mcp-server.log;
 * overridable via the system property mcp.server.logFile. Above 10 MB the
 * previous file is moved to .old once.
 *
 * <p>Basis for the ToolCallGuard: every tool call writes a CALL/DONE line with a
 * trace ID; domain errors reference file and trace ID in the body (logHint) so the
 * details stay findable even when the MCP client only shows isError results
 * generically.</p>
 */
    private static void configureLogFile() {
        try {
            String configured = System.getProperty("mcp.server.logFile");
            Path logFile = (configured != null && !configured.isBlank())
                    ? Path.of(configured)
                    : Path.of(System.getProperty("user.home"),
                            ".rewisp", "logs", "mcp-server.log");
            Files.createDirectories(logFile.getParent());
            // Einmalige Rotation bei >10 MB (slf4j-simple kann nicht rollen)
            if (Files.exists(logFile)
                    && Files.size(logFile) > 10L * 1024 * 1024) {
                Files.move(logFile,
                        logFile.resolveSibling(logFile.getFileName() + ".old"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            System.setProperty("org.slf4j.simpleLogger.logFile", logFile.toString());
            System.setProperty("org.slf4j.simpleLogger.cacheOutputStream", "true");
            // stdout gehoert dem JSON-RPC-Protokoll (stdio-Transport) - nur stderr verwenden
            System.err.println("rewisp logging to " + logFile);
        } catch (Exception e) {
            System.err.println("Warning: could not set up log file, logging to stderr: " + e.getMessage());
        }
    }

/**
 * Loads the YAML logging configuration from the classpath and sets system properties.
 */
    private static void loadLoggingConfig() {
        try (InputStream in = JavaMcpServer.class.getResourceAsStream("/application.yaml")) {
            if (in != null) {
                Yaml yaml = new Yaml();
                Map<String, Object> config = yaml.load(in);
                flatten("", config);
            }
        } catch (Exception e) {
            System.err.println("Warning: could not load application.yaml: " + e.getMessage());
        }
    }

/**
 * Flattens a nested map into dot-separated system property keys.
 *
 * <p>Scalar lists become comma-joined values (e.g. workspace.allowedRoots:
 * [a, b] -> "a,b"); an empty list becomes "" so that consumers treating a
 * blank value as "not configured" work correctly. Lists containing maps
 * (e.g. rewrite.recipes) are skipped — their readers load the original
 * YAML directly.</p>
 */
    @SuppressWarnings("unchecked")
    static void flatten(String prefix, Map<String, Object> map) {
        map.forEach((key, value) -> {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map) {
                flatten(fullKey, (Map<String, Object>) value);
            } else if (value instanceof List<?> list) {
                boolean scalarsOnly = list.stream().allMatch(
                        v -> v == null || v instanceof String || v instanceof Number || v instanceof Boolean);
                if (scalarsOnly) {
                    String joined = list.stream()
                            .map(v -> v == null ? "" : String.valueOf(v))
                            .collect(Collectors.joining(","));
                    setDefault(fullKey, joined);
                }
            } else {
                setDefault(fullKey, String.valueOf(value));
            }
        });
    }

    /** Sets the property only if it is not already set (e.g. via -D). */
    private static void setDefault(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

/**
 * Builds and starts the MCP server with all registered tools over stdio transport.
 */
    private void start() {
        String serverVersion = VersionLoader.getVersion();
        Logger log = LoggerFactory.getLogger(JavaMcpServer.class);

        List<McpServerFeatures.SyncToolSpecification> toolSpecs = List.of(
                // --- Projekt-Lifecycle ---
                new LoadJavaProjectTool(projectManager).build(),
                new ListLoadedProjectsTool(projectManager).build(),
                new de.jare.rewisp.tools.project.UsageStatsTool(projectManager).build(),
                new UnloadJavaProjectTool(projectManager).build(),
                new ReloadJavaProjectTool(projectManager).build(),
                new ProjectMetadataTool(projectManager).build(),
                new ProjectRefreshBuildTool(projectManager).build(),
                new CheckProjectDirtyTool(projectManager).build(),
                new RebootSpoonTool(projectManager, editManager).build(),
                // --- SPOON-Analyse (read-only) ---
                new ListClassesTool(projectManager).build(),
                new InspectClassTool(projectManager).build(),
                new InspectMethodTool(projectManager).build(),
                new InspectFieldTool(projectManager).build(),
                new GetFileContentTool(projectManager).build(),
                new GetElementPositionTool(projectManager).build(),
                new SearchSourceTool(projectManager).build(),
                new FindReferencesTool(projectManager).build(),
                new FindImplementationsTool(projectManager).build(),
                new GetTypeHierarchyTool(projectManager).build(),
                // --- REWRITE-Edits (alle über OpenRewrite + EditManager) ---
                new EditLineTool(projectManager, editManager).build(),
                new RenameSymbolTool(projectManager, editManager, rewriteEngine).build(),
                new ReplaceMethodBodyTool(projectManager, editManager, rewriteEngine).build(),
                new AddMethodTool(projectManager, editManager, rewriteEngine).build(),
                new AddFieldTool(projectManager, editManager, rewriteEngine).build(),
                new RemoveMemberTool(projectManager, editManager, rewriteEngine).build(),
                new AddAnnotationTool(projectManager, editManager, rewriteEngine).build(),
                new RemoveAnnotationTool(projectManager, editManager, rewriteEngine).build(),
                new EditAnnotationTool(projectManager, editManager, rewriteEngine).build(),
                new RewriteSignatureTool(projectManager, editManager, rewriteEngine).build(),
                new AddPackageTool(projectManager, editManager).build(),
                new AddClassTool(projectManager, editManager, rewriteEngine).build(),
                new MoveClassTool(projectManager, editManager, rewriteEngine).build(),
                // --- Transaktionen ---
                new TransactionTools.Begin(projectManager, editManager).build(),
                new TransactionTools.Commit(projectManager, editManager).build(),
                new TransactionTools.Rollback(projectManager, editManager).build(),
                new TransactionTools.GetEditSummary(projectManager, editManager).build(),
                new TransactionTools.GetDiff(projectManager, editManager).build()
        );

        // stdin-EOF (Client trennt) beendet den Server: der Transport meldet das nicht selbst nach aussen
        CountDownLatch latch = new CountDownLatch(1);
        System.setIn(new de.jare.rewisp.util.EofSignalingInputStream(System.in, latch::countDown));

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapperSupplier().get();
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(jsonMapper);

        var server = McpServer.sync(transportProvider)
                .serverInfo("rewisp", serverVersion)
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tools(toolSpecs)
                .build();

        log.info("MCP server started (rewisp v{}).", serverVersion);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down rewisp...");
            server.closeGracefully();
            latch.countDown();
            log.info("rewisp shutdown complete");
        }));

        try {
            latch.await();
        } catch (InterruptedException e) {
            log.warn("Main thread interrupted", e);
            Thread.currentThread().interrupt();
        }
        // Erreicht, wenn stdin geschlossen wurde: Shutdown-Hook (closeGracefully) laeuft beim Beenden
        log.info("stdin closed - exiting.");
        System.exit(0);
    }
}

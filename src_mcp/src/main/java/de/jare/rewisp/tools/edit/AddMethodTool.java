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

import java.util.Arrays;
import de.jare.rewisp.tools.base.BaseEditTool;
import de.jare.rewisp.model.ErrorType;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import de.jare.rewisp.ProjectManager;
import de.jare.rewisp.edit.EditManager;
import de.jare.rewisp.rewrite.RewriteEngine;
import de.jare.rewisp.util.JavaNames;
import java.util.List;
import java.util.Map;
import de.jare.rewisp.api.DefaultRewispDriver;
import de.jare.rewisp.api.RewispDriver;
import de.jare.rewisp.tools.base.ToolCallGuard;

/**
 * The {@code AddMethodTool} — adds a new method to an existing class.
 *
 * <p>Architecture (since 1.1.1-REWRITE): Spoon is used ONLY for pre-edit analysis
 * (erasure collision check, auto-import resolution, unresolved-type detection).
 * The actual change happens EXCLUSIVELY via OpenRewrite
 * (AddMethodRecipe). There is no Spoon fallback for edit operations anymore.</p>
 *
 * @author Janusch Rentenatus
 */
public class AddMethodTool extends BaseEditTool {

    /**
     * Constructor with full OpenRewrite support.
     * OpenRewrite is mandatory — there is no Spoon fallback anymore.
     */
    public AddMethodTool(ProjectManager manager, EditManager editManager,
                       RewriteEngine rewriteEngine) {
        super(manager);
        this.driver = new DefaultRewispDriver(manager, editManager, rewriteEngine);
    }

    @Override
    protected String toolName() {
        return "add_method";
    }

    @Override
    protected String toolDescription() {
        return "[REWRITE] Add a new method to an existing class or interface. Checks for erasure collisions. "
                + "Automatic import resolution applies to types in the signature and body. "
                + "On an interface: with no body, emits an abstract method (semicolon); "
                + "with a body, emits a 'default' method (Java 8+). On a class: with no body, "
                + "emits an empty-bodied method; with a body, emits the full method. "
                + "Edit via OpenRewrite; Spoon only for pre-edit analysis.";
    }

    @Override
    protected Map<String, Object> toolProperties() {
        return Map.of(
            "name", Map.of("type", "string", "description", "Project name or alias"),
            "className", Map.of("type", "string", "description", "Fully qualified class name"),
            "methodName", Map.of("type", "string", "description", "Method name"),
            "returnType", Map.of("type", "string", "description", "Return type (e.g. 'void', 'String')"),
            "parameters", Map.of("type", "string", "description", "Comma-separated 'type name' pairs (e.g. 'int count, String label')"),
            "modifiers", Map.of("type", "string", "description", "Optional: 'public', 'private', 'protected', 'static'"),
            "body", Map.of("type", "string", "description", "Optional: method body (without braces)"),
            "imports", Map.of("type", "array", "description",
                "Optional: list of fully-qualified import names (e.g. ['java.util.List']) "
                + "to disambiguate types with the same simple name in different packages. "
                + "Each FQN must be a project type or a loadable JDK class; invalid FQNs are reported as unresolved."),
            "dryRun", Map.of("type", "boolean", "description", "If true, only preview the changes - no files are written")
        );
    }

    @Override
    protected List<String> toolRequired() {
        return req("name", "className", "methodName", "returnType");
    }

    /** Syntax check of the free text before anything is analyzed or written (injection protection). */
    private CallToolResult validateInputs(String className, String methodName, String returnType,
                                          String parameters, String modifiers, String body) {
        String bad = null;
        if (!JavaNames.isValidIdentifier(methodName)) {
            bad = "Not a valid Java method name: '" + methodName + "'";
        } else if (!"void".equals(returnType == null ? null : returnType.trim()) && !JavaNames.isValidTypeExpression(returnType)) {
            bad = "Not a valid Java return type: '" + returnType + "' (expected e.g. void, int, String, List<String>)";
        } else if (!JavaNames.isValidParameterList(parameters)) {
            bad = "Not a valid parameter list: '" + parameters + "' (expected 'Type name, Type name', e.g. 'int count, String label')";
        } else if (!JavaNames.isValidMethodModifiers(modifiers)) {
            bad = "Invalid modifiers: '" + modifiers + "' (no repeats, one visibility, abstract excludes private/static/final/synchronized/native/strictfp)";
        } else if (!JavaNames.isBalancedSnippet(body)) {
            bad = "The body has unbalanced braces/parentheses or an unterminated string/comment; it must be a self-contained block body (without enclosing braces)";
        } else if (body != null && !body.isBlank() && modifiers != null
                && Arrays.asList(modifiers.trim().split("[\\s,]+")).contains("abstract")) {
            bad = "An abstract method cannot have a body";
        }
        if (bad == null) return null;
        return domainError(ErrorType.INVALID_PARAMS, bad,
                ctx("className", String.valueOf(className), "methodName", String.valueOf(methodName)));
    }

    @Override
    public CallToolResult doHandle(McpSyncServerExchange exchange, CallToolRequest request) throws Exception {
        String className = arg(request, "className");
        String methodName = arg(request, "methodName");
        var result = driver.addMethod(new RewispDriver.AddMethodRequest(arg(request, "name"), className, methodName,
                arg(request, "returnType"), arg(request, "parameters"), arg(request, "modifiers"),
                arg(request, "body"), stringListArg(request, "imports"), boolArg(request, "dryRun")));
        ToolCallGuard.noteProject(result.project());
        if (result.dryRun()) {
            return dryRunResult(result.preview(), result.dryRunSnapshot());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Method added: ").append(className).append(".").append(methodName)
                .append(" [OpenRewrite]\n");
        sb.append(formatMultiModuleWarning(result.modulesDetected(), result.modulesLoaded()));
        sb.append(formatDiagnostics(result.preview()));
        return ok(sb);
    }
}

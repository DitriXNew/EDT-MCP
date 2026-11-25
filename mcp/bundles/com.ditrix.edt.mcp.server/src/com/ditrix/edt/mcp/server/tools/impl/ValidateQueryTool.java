/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.xtext.util.CancelIndicator;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.ActiveToolCall;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.ModuleQueryExtractor;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.QueryValidationService;
import com.ditrix.edt.mcp.server.utils.QueryValidationJobs;

/**
 * Tool to validate 1C:Enterprise query language (QL) text in the context of a project.
 * Uses Xtext QL infrastructure to parse and validate query text, returning syntax and
 * semantic errors with line numbers and messages.
 */
public class ValidateQueryTool implements IMcpTool
{
    public static final String NAME = QueryValidationJobs.TOOL;
    
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);
    private static final String PROJECT_NAME = "projectName"; //$NON-NLS-1$
    private static final String QUERY_TEXT = "queryText"; //$NON-NLS-1$
    private static final String DCS_MODE = "dcsMode"; //$NON-NLS-1$
    private static final String METHOD = "method";
    private final QueryValidationService validator;

    public ValidateQueryTool()
    {
        this(new QueryValidationService());
    }

    ValidateQueryTool(QueryValidationService validator)
    {
        this.validator = validator;
    }

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Check a 1C query or stored BSL query literals for syntax and metadata-reference errors. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('validate_query')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(PROJECT_NAME,
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty(QUERY_TEXT,
                "Full 1C query text, e.g. 'SELECT Ref FROM Catalog.Products'. Pass exactly one of queryText or modulePath.")
            .stringProperty(McpKeys.MODULE_PATH,
                "Stored BSL path from src/, e.g. 'CommonModules/MyModule/Module.bsl'. Exclusive with queryText; omit method to scan the whole module.")
            .stringProperty(METHOD,
                "Procedure/function name (case-insensitive); only with modulePath.")
            .booleanProperty(DCS_MODE,
                "true for Data Composition System queries (allows DCS-specific syntax). Default: false.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("valid", "true only for complete validation without errors") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("validationState", "VALID, INVALID, or PARTIAL")
            .booleanProperty("validationComplete", "Whether all sources and native checks resolved")
            .objectArrayProperty("unresolvedSources", "Unresolved source names and reasons")
            .objectArrayProperty("sources", "Native source resolution and bilingual available fields")
            .booleanProperty(DCS_MODE, "Whether DCS validation mode was used") //$NON-NLS-1$
            .stringProperty("validationScope", "Project and metadata scope used for validation") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("errorCount", "Number of ERROR-severity issues") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("warningCount", "Number of WARNING-severity issues") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("infoCount", "Number of INFO-severity issues") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("issues", //$NON-NLS-1$
                "Validation issues; each has severity, message, optional line/column/offset") //$NON-NLS-1$
            .stringProperty(McpKeys.MODULE_PATH, "Stored module scanned in modulePath mode")
            .objectArrayProperty("queries", "Validated literals: method, module line/column, shared verdict and module-mapped issues")
            .objectArrayProperty("skipped", "Keyword-leading literals refused by extraction: method, line/column, reason")
            .integerProperty("foundCount", "Number of standalone query literals validated")
            .integerProperty("skippedCount", "Number of keyword-leading literals skipped")
            .stringProperty("message", "Empty-result explanation and queryText fallback")
            .build();
    }
    
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, PROJECT_NAME);
        String queryText = JsonUtils.extractStringArgument(params, QUERY_TEXT);
        String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
        String method = JsonUtils.extractStringArgument(params, METHOD);
        boolean dcsMode = JsonUtils.extractBooleanArgument(params, DCS_MODE, false);

        String err = JsonUtils.requireArguments(params, PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        if (method != null && (modulePath == null || modulePath.isBlank()))
            return ToolResult.error("method requires modulePath. Pass a stored modulePath from list_modules, "
                + "or remove method to validate queryText.").toJson();
        boolean hasQuery = queryText != null && !queryText.isEmpty();
        boolean hasModule = modulePath != null && !modulePath.isBlank();
        if (queryText != null && modulePath != null || hasQuery == hasModule)
            return ToolResult.error("Pass exactly one of queryText or modulePath. Use queryText for a complete query "
                + "or list_modules to choose a stored modulePath.").toJson();
        if (method != null && method.isBlank())
            return ToolResult.error("method must be a non-blank procedure/function name. "
                + "Use get_module_structure to choose a name or omit method to scan the module.").toJson();
        
        // Find the project
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        if (!ctx.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
        }

        var activator = Activator.getDefault();
        var server = activator == null ? null : activator.getMcpServer();
        ActiveToolCall call = server == null ? null : server.getExecutingToolCall();
        var progress = hasModule ? new ModuleProgress(modulePath, dcsMode) : null;
        return validateWithDeadline(QueryValidationJobs.shared(), TIMEOUT_MS, ctx.project().getName(),
            () -> call != null && call.isResponseDelivered(), cancel -> hasModule
                ? doValidateModule(ctx.project(), modulePath, method, dcsMode, cancel, progress)
                : doValidateQuery(ctx.project(), queryText, dcsMode, cancel), progress);
    }

    /** Waits for the whole tool, not for each literal separately. Writer preflight stays synchronous. */
    static String validateWithDeadline(QueryValidationJobs jobs, long timeoutMs, String projectName,
        BooleanSupplier responseDelivered, Function<CancelIndicator, String> work)
    {
        return validateWithDeadline(jobs, timeoutMs, projectName, responseDelivered, work, null);
    }

    static String validateWithDeadline(QueryValidationJobs jobs, long timeoutMs, String projectName,
        BooleanSupplier responseDelivered, Function<CancelIndicator, String> work, ModuleProgress progress)
    {
        try
        {
            QueryValidationJobs.Result result = jobs.run(projectName, timeoutMs, responseDelivered, work);
            if (result.response() != null)
            {
                return result.response();
            }
            if (result.jobId() != null)
            {
                return incomplete(progress, "Query validation did not finish in time or was interrupted. " //$NON-NLS-1$
                    + "Cancellation was requested, but EDT may still hold a read lock until the worker exits. " //$NON-NLS-1$
                    + "Check get_job_status for jobId '" + result.jobId() + "' before retrying."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return incomplete(progress, "Query validation did not return a result before timeout or cancellation."); //$NON-NLS-1$
        }
        catch (QueryValidationJobs.CapacityException e)
        {
            return incomplete(progress, "No query validation slot is available. Detached validation still occupies "
                + "capacity. Check get_job_status for job IDs: " + String.join(", ", e.blockingJobIds()) + ".");
        }
        catch (RejectedExecutionException e)
        {
            return incomplete(progress, "Query validation could not start: " + e.getMessage());
        }
        catch (RuntimeException e)
        {
            return ToolResult.error("Native query validation failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private static String incomplete(ModuleProgress progress, String message)
    {
        ToolResult result = ToolResult.error(message);
        return (progress == null ? result : progress.populate(result).put("validationComplete", false)).toJson();
    }

    /** This is a same-project candidate, not verified ownership of the Configuration lock. */
    static String activeValidationHint(QueryValidationJobs jobs, String projectName)
    {
        var job = jobs.runningForProject(projectName);
        return job == null ? "" : "\nA validate_query job for this project is still active (started " //$NON-NLS-1$ //$NON-NLS-2$
            + Instant.ofEpochMilli(job.getStartedAtMs()) + "). It may be holding a read lock. " //$NON-NLS-1$
            + "Check get_job_status for jobId '" + job.getId() + "' before retrying."; //$NON-NLS-1$
    }

    String doValidateQuery(IProject project, String queryText, boolean dcsMode, CancelIndicator cancel)
    {
        return validator.validate(project, queryText, dcsMode, cancel).toJson();
    }

    String doValidateModule(IProject project, String modulePath, String method, boolean dcsMode,
        CancelIndicator cancel)
    {
        return doValidateModule(project, modulePath, method, dcsMode, cancel, new ModuleProgress(modulePath, dcsMode));
    }

    String doValidateModule(IProject project, String modulePath, String method, boolean dcsMode,
        CancelIndicator cancel, ModuleProgress progress)
    {
        try
        {
            IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
            if (file == null || !file.exists())
                return ToolResult.error("Module '" + modulePath
                    + "' not found. Use list_modules to choose a stored BSL modulePath.").toJson();
            return validateModuleText(project, BslModuleUtils.readFileText(file), method, dcsMode, cancel, progress);
        }
        catch (Exception e)
        {
            return ToolResult.error(moduleReadError(modulePath, e)).toJson();
        }
    }

    /** The extractor's refusals already name the queryText fallback; name it once, not twice. */
    static String moduleReadError(String modulePath, Exception e)
    {
        String message = "Could not read query literals from modulePath '" + modulePath + "': " + e.getMessage();
        return e.getMessage() != null && e.getMessage().contains(QUERY_TEXT) ? message
            : message + ". Check read_module_source or pass queryText directly.";
    }

    String validateModuleText(IProject project, String modulePath, String source, String method,
        boolean dcsMode, CancelIndicator cancel)
    {
        return validateModuleText(project, source, method, dcsMode, cancel, new ModuleProgress(modulePath, dcsMode));
    }

    private String validateModuleText(IProject project, String source, String method,
        boolean dcsMode, CancelIndicator cancel, ModuleProgress progress)
    {
        boolean available = true;
        for (var literal : ModuleQueryExtractor.extract(source, method))
        {
            if (cancel.isCanceled())
            {
                return incomplete(progress, "Module query validation was interrupted."); //$NON-NLS-1$
            }
            Map<String, Object> entry = literal.location();
            if (literal.reason() != null)
            {
                entry.put("reason", literal.reason());
                progress.addSkipped(entry);
                continue;
            }
            var validation = validator.validate(project, literal.queryText(), dcsMode, cancel);
            if (cancel.isCanceled())
            {
                return incomplete(progress, "Module query validation was interrupted.");
            }
            available &= validation.available();
            Map<String, Object> values = new LinkedHashMap<>(validation.values());
            @SuppressWarnings("unchecked")
            var issues = (List<Map<String, Object>>)values.get("issues");
            values.put("issues", issues.stream().map(literal::mapIssue).toList());
            entry.putAll(values);
            progress.addQuery(entry);
        }
        ToolResult result = available ? ToolResult.success()
            : ToolResult.error("Native query validation was unavailable for some literals. "
                + "Check each incompleteReason, wait for project loading and retry.");
        progress.populate(result);
        if (progress.queries.isEmpty())
            result.put("message", progress.skipped.isEmpty() ? "no query literal found. Pass queryText to validate a query directly."
                : "No standalone query literal validated. Check skipped reasons or pass queryText directly.");
        return result.toJson();
    }

    static final class ModuleProgress
    {
        private final String modulePath;
        private final boolean dcsMode;
        private final List<Map<String, Object>> queries = new ArrayList<>();
        private final List<Map<String, Object>> skipped = new ArrayList<>();

        ModuleProgress(String modulePath, boolean dcsMode)
        {
            this.modulePath = modulePath;
            this.dcsMode = dcsMode;
        }

        synchronized void addQuery(Map<String, Object> entry) { queries.add(entry); }
        synchronized void addSkipped(Map<String, Object> entry) { skipped.add(entry); }

        synchronized ToolResult populate(ToolResult result)
        {
            return result.put(McpKeys.MODULE_PATH, modulePath).put(DCS_MODE, dcsMode)
                .put("queries", List.copyOf(queries)).put("skipped", List.copyOf(skipped))
                .put("foundCount", queries.size()).put("skippedCount", skipped.size());
        }
    }
}

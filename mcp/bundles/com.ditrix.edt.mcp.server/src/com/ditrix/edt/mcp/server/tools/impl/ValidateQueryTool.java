/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.ModuleQueryExtractor;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.QueryValidationService;

/**
 * Tool to validate 1C:Enterprise query language (QL) text in the context of a project.
 * Uses Xtext QL infrastructure to parse and validate query text, returning syntax and
 * semantic errors with line numbers and messages.
 */
public class ValidateQueryTool implements IMcpTool
{
    public static final String NAME = "validate_query"; //$NON-NLS-1$
    
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

        return hasModule ? doValidateModule(ctx.project(), modulePath, method, dcsMode)
            : doValidateQuery(ctx.project(), queryText, dcsMode);
    }

    String doValidateQuery(IProject project, String queryText, boolean dcsMode)
    {
        return validator.validate(project, queryText, dcsMode).toJson();
    }

    String doValidateModule(IProject project, String modulePath, String method, boolean dcsMode)
    {
        try
        {
            IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
            if (file == null || !file.exists())
                return ToolResult.error("Module '" + modulePath
                    + "' not found. Use list_modules to choose a stored BSL modulePath.").toJson();
            return validateModuleText(project, modulePath, BslModuleUtils.readFileText(file), method, dcsMode);
        }
        catch (Exception e)
        {
            return ToolResult.error("Could not read query literals from modulePath '" + modulePath + "': "
                + e.getMessage() + ". Check read_module_source or pass queryText directly.").toJson();
        }
    }

    String validateModuleText(IProject project, String modulePath, String source, String method, boolean dcsMode)
    {
        List<Map<String, Object>> queries = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        boolean available = true;
        for (var literal : ModuleQueryExtractor.extract(source, method))
        {
            Map<String, Object> entry = literal.location();
            if (literal.reason() != null)
            {
                entry.put("reason", literal.reason());
                skipped.add(entry);
                continue;
            }
            var validation = validator.validate(project, literal.queryText(), dcsMode);
            available &= validation.available();
            Map<String, Object> values = new LinkedHashMap<>(validation.values());
            @SuppressWarnings("unchecked")
            var issues = (List<Map<String, Object>>)values.get("issues");
            values.put("issues", issues.stream().map(literal::mapIssue).toList());
            entry.putAll(values);
            queries.add(entry);
        }
        ToolResult result = available ? ToolResult.success()
            : ToolResult.error("Native query validation was unavailable for some literals. "
                + "Check each incompleteReason, wait for project loading and retry.");
        result.put(McpKeys.MODULE_PATH, modulePath).put(DCS_MODE, dcsMode)
            .put("queries", queries).put("skipped", skipped)
            .put("foundCount", queries.size()).put("skippedCount", skipped.size());
        if (queries.isEmpty())
            result.put("message", skipped.isEmpty() ? "no query literal found. Pass queryText to validate a query directly."
                : "No standalone query literal validated. Check skipped reasons or pass queryText directly.");
        return result.toJson();
    }
    
}

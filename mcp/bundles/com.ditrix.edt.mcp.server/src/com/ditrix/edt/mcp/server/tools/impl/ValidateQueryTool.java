/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
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
        return "Check a 1C query for syntax and metadata-reference errors. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('validate_query')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(PROJECT_NAME,
                "EDT project name (required).", true) //$NON-NLS-1$
            .stringProperty(QUERY_TEXT,
                "Full 1C query text to validate (required), e.g. 'SELECT Ref FROM Catalog.Products'.", //$NON-NLS-1$
                true)
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
        boolean dcsMode = JsonUtils.extractBooleanArgument(params, DCS_MODE, false);

        String err = JsonUtils.requireArguments(params, PROJECT_NAME, QUERY_TEXT);
        if (err != null)
        {
            return err;
        }
        
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

        return doValidateQuery(ctx.project(), queryText, dcsMode);
    }

    String doValidateQuery(IProject project, String queryText, boolean dcsMode)
    {
        return validator.validate(project, queryText, dcsMode).toJson();
    }
    
}

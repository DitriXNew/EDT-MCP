/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ResourceTextSearch;
import com.ditrix.edt.mcp.server.utils.SearchResultFormatter;

/**
 * Tool for full-text search across all BSL modules in a project.
 * Supports plain text and regex search with context lines.
 */
public class SearchInCodeTool implements IMcpTool
{
    public static final String NAME = "search_in_code"; //$NON-NLS-1$

    /** Input param: the search string or regex pattern. */
    private static final String KEY_QUERY = "query"; //$NON-NLS-1$

    /** Input param: deprecated alias for the result limit. */
    private static final String KEY_MAX_RESULTS = "maxResults"; //$NON-NLS-1$

    /** Default and maximum limits */
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int ABSOLUTE_MAX_RESULTS = 500;
    private static final int DEFAULT_CONTEXT_LINES = 2;
    private static final int MAX_CONTEXT_LINES = 5;

    /** Output modes */
    private static final String MODE_FULL = "full"; //$NON-NLS-1$
    private static final String MODE_COUNT = "count"; //$NON-NLS-1$
    private static final String MODE_FILES = "files"; //$NON-NLS-1$

    /** Input param: lines of context before/after each match. */
    private static final String KEY_CONTEXT_LINES = "contextLines"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Literal/regex full-text search across BSL modules. Matching is textual and NOT ru/en " //$NON-NLS-1$
            + "dialect-aware, so a query in one BSL language will not find the other spelling - for a " //$NON-NLS-1$
            + "symbol's usages use find_references, for where it is defined use go_to_definition. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('search_in_code')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_QUERY,
                "Search string or regex pattern (required); matched literally unless isRegex=true", true) //$NON-NLS-1$
            .booleanProperty("caseSensitive", //$NON-NLS-1$
                "Case-sensitive search. Default: false") //$NON-NLS-1$
            .booleanProperty("isRegex", //$NON-NLS-1$
                "Treat query as a regular expression. Default: false") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Max matches returned with context. Default: 100, max: 500") //$NON-NLS-1$
            .integerProperty(KEY_MAX_RESULTS,
                "Deprecated alias for 'limit'. Default: 100, max: 500") //$NON-NLS-1$
            .integerProperty(KEY_CONTEXT_LINES,
                "Lines of context before/after each match. Default: 2, max: 5") //$NON-NLS-1$
            .stringProperty("fileMask", //$NON-NLS-1$
                "Filter by module path substring (e.g. 'CommonModules' or 'Documents/SalesOrder')") //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Filter by metadata type (e.g. 'documents', 'catalogs', 'commonModules'); " + //$NON-NLS-1$
                "more precise than fileMask. See guide for the full list.") //$NON-NLS-1$
            .enumProperty("outputMode", //$NON-NLS-1$
                "Output mode: 'full' (matches with context, default), 'count', or 'files'", //$NON-NLS-1$
                "full", MODE_COUNT, MODE_FILES) //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String query = JsonUtils.extractStringArgument(params, KEY_QUERY);
        if (query != null && !query.isEmpty())
        {
            String safeName = query.replaceAll("[^a-zA-Z0-9\\u0400-\\u04FF]", "-") //$NON-NLS-1$ //$NON-NLS-2$
                .toLowerCase();
            if (safeName.length() > 40)
            {
                safeName = safeName.substring(0, 40);
            }
            return "search-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "search-results.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String query = JsonUtils.extractStringArgument(params, KEY_QUERY);
        boolean caseSensitive = JsonUtils.extractBooleanArgument(params, "caseSensitive", false); //$NON-NLS-1$
        boolean isRegex = JsonUtils.extractBooleanArgument(params, "isRegex", false); //$NON-NLS-1$
        int configuredMaxResults = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, KEY_MAX_RESULTS, DEFAULT_MAX_RESULTS);
        int configuredContextLines = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, KEY_CONTEXT_LINES, DEFAULT_CONTEXT_LINES);
        // Canonical param is "limit" (consistent with other paginated tools); // NOSONAR explanatory comment, not commented-out code
        // "maxResults" is kept as a deprecated alias (precedence: limit, then maxResults).
        int maxResultsAlias = JsonUtils.extractIntArgument(params, KEY_MAX_RESULTS, configuredMaxResults);
        int maxResults = JsonUtils.extractIntArgument(params, "limit", maxResultsAlias); //$NON-NLS-1$
        int contextLines = JsonUtils.extractIntArgument(params, KEY_CONTEXT_LINES, configuredContextLines);
        String fileMask = JsonUtils.extractStringArgument(params, "fileMask"); //$NON-NLS-1$
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String outputMode = JsonUtils.extractStringArgument(params, "outputMode"); //$NON-NLS-1$

        // Validate required parameters
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_QUERY);
        if (err != null)
        {
            return err;
        }

        // Normalize output mode
        if (outputMode == null || outputMode.isEmpty())
        {
            outputMode = MODE_FULL;
        }
        outputMode = outputMode.toLowerCase();
        if (!MODE_FULL.equals(outputMode) && !MODE_COUNT.equals(outputMode) && !MODE_FILES.equals(outputMode))
        {
            return ToolResult.error("outputMode must be 'full', 'count', or 'files'").toJson(); //$NON-NLS-1$
        }

        // Clamp limits
        maxResults = Pagination.clampLimit(maxResults, ABSOLUTE_MAX_RESULTS);
        contextLines = Math.min(Math.max(0, contextLines), MAX_CONTEXT_LINES);

        // Get project
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        // Compile pattern
        Pattern pattern;
        try
        {
            int flags = Pattern.UNICODE_CHARACTER_CLASS;
            if (!caseSensitive)
            {
                flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            }
            if (isRegex)
            {
                pattern = Pattern.compile(query, flags);
            }
            else
            {
                pattern = Pattern.compile(Pattern.quote(query), flags);
            }
        }
        catch (PatternSyntaxException e)
        {
            return ToolResult.error("Invalid regex pattern '" + query + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Resolve metadataType to folder prefix
        String metadataFolderPrefix = resolveMetadataFolder(metadataType);
        if (metadataType != null && !metadataType.isEmpty() && metadataFolderPrefix == null)
        {
            return ToolResult.error("Unknown metadataType '" + metadataType + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                "Supported: documents, catalogs, commonModules, informationRegisters, " + //$NON-NLS-1$
                "accumulationRegisters, reports, dataProcessors, exchangePlans, " + //$NON-NLS-1$
                "businessProcesses, tasks, constants, commonCommands, commonForms, " + //$NON-NLS-1$
                "webServices, httpServices").toJson(); //$NON-NLS-1$
        }

        // Search
        boolean collectDetails = MODE_FULL.equals(outputMode);
        ResourceTextSearch search = new ResourceTextSearch(pattern, ".bsl", fileMask, //$NON-NLS-1$
            metadataFolderPrefix, maxResults, contextLines, collectDetails);

        try
        {
            IResource srcFolder = project.findMember("src"); //$NON-NLS-1$
            if (srcFolder != null)
            {
                srcFolder.accept(search);
            }
            else
            {
                return ToolResult.error("src/ folder not found in project " + projectName).toJson(); //$NON-NLS-1$
            }
        }
        catch (CoreException e)
        {
            return ToolResult.error("Failed to search project: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // Format output
        return SearchResultFormatter.forCode().format(outputMode, query, search);
    }

    /**
     * Resolves a metadataType string to the corresponding folder prefix.
     *
     * @return folder prefix (e.g. "Documents/") or null if type is unknown
     */
    private String resolveMetadataFolder(String metadataType)
    {
        if (metadataType == null || metadataType.isEmpty())
        {
            return null;
        }
        switch (metadataType.toLowerCase())
        {
            case "documents": //$NON-NLS-1$
                return "Documents/"; //$NON-NLS-1$
            case "catalogs": //$NON-NLS-1$
                return "Catalogs/"; //$NON-NLS-1$
            case "commonmodules": //$NON-NLS-1$
                return "CommonModules/"; //$NON-NLS-1$
            case "informationregisters": //$NON-NLS-1$
                return "InformationRegisters/"; //$NON-NLS-1$
            case "accumulationregisters": //$NON-NLS-1$
                return "AccumulationRegisters/"; //$NON-NLS-1$
            case "reports": //$NON-NLS-1$
                return "Reports/"; //$NON-NLS-1$
            case "dataprocessors": //$NON-NLS-1$
                return "DataProcessors/"; //$NON-NLS-1$
            case "exchangeplans": //$NON-NLS-1$
                return "ExchangePlans/"; //$NON-NLS-1$
            case "businessprocesses": //$NON-NLS-1$
                return "BusinessProcesses/"; //$NON-NLS-1$
            case "tasks": //$NON-NLS-1$
                return "Tasks/"; //$NON-NLS-1$
            case "constants": //$NON-NLS-1$
                return "Constants/"; //$NON-NLS-1$
            case "commoncommands": //$NON-NLS-1$
                return "CommonCommands/"; //$NON-NLS-1$
            case "commonforms": //$NON-NLS-1$
                return "CommonForms/"; //$NON-NLS-1$
            case "webservices": //$NON-NLS-1$
                return "WebServices/"; //$NON-NLS-1$
            case "httpservices": //$NON-NLS-1$
                return "HTTPServices/"; //$NON-NLS-1$
            case "enums": //$NON-NLS-1$
                return "Enums/"; //$NON-NLS-1$
            case "chartsofcharacteristictypes": //$NON-NLS-1$
                return "ChartsOfCharacteristicTypes/"; //$NON-NLS-1$
            case "chartsofaccounts": //$NON-NLS-1$
                return "ChartsOfAccounts/"; //$NON-NLS-1$
            case "chartsofcalculationtypes": //$NON-NLS-1$
                return "ChartsOfCalculationTypes/"; //$NON-NLS-1$
            default:
                return null;
        }
    }
}

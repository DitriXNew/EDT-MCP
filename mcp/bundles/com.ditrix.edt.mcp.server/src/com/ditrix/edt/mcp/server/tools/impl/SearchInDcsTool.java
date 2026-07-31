/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ResourceLineSearch;

/**
 * Literal / regex search across a project's Data Composition Schema ({@code .dcs}) files - the
 * serialized report / data-processor composition schemas (datasets and query text, field paths,
 * calculated / total-field expressions, parameters, and the settings: selection / order / filter /
 * variants). A purely textual scan of the serialized XML, so it finds anything the schema stores;
 * matching is NOT ru/en dialect-aware.
 */
public class SearchInDcsTool implements IMcpTool
{
    public static final String NAME = "search_in_dcs"; //$NON-NLS-1$

    private static final String KEY_QUERY = "query"; //$NON-NLS-1$
    private static final String KEY_CONTEXT_LINES = "contextLines"; //$NON-NLS-1$
    private static final String DCS_EXTENSION = ".dcs"; //$NON-NLS-1$

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int ABSOLUTE_MAX_RESULTS = 500;
    private static final int DEFAULT_CONTEXT_LINES = 1;
    private static final int MAX_CONTEXT_LINES = 5;

    private static final String MODE_FULL = "full"; //$NON-NLS-1$
    private static final String MODE_COUNT = "count"; //$NON-NLS-1$
    private static final String MODE_FILES = "files"; //$NON-NLS-1$

    private static final String QUOTE_NEWLINES = "\"\n\n"; //$NON-NLS-1$
    private static final String WARNING_PREFIX = "**Warning:** "; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Literal/regex search across all Data Composition Schema (.dcs) files in a project - " //$NON-NLS-1$
            + "the serialized report / data-processor schemas (dataset query text, field paths, " //$NON-NLS-1$
            + "calculated/total expressions, parameters, and the selection/order/filter/variants of " //$NON-NLS-1$
            + "the settings). Matching is purely textual over the serialized XML and NOT ru/en " //$NON-NLS-1$
            + "dialect-aware. Use it to find where a field, query fragment or expression is used across " //$NON-NLS-1$
            + "report schemas; to EDIT a schema use create_metadata by FQN. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('search_in_dcs')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_QUERY,
                "Search string or regex pattern (required); matched literally unless isRegex=true", true) //$NON-NLS-1$
            .booleanProperty("caseSensitive", "Case-sensitive search. Default: false") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("isRegex", "Treat query as a regular expression. Default: false") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Max matches returned with context. Default: 100, max: 500") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty(KEY_CONTEXT_LINES,
                "Lines of context before/after each match. Default: 1, max: 5") //$NON-NLS-1$
            .stringProperty("fileMask", //$NON-NLS-1$
                "Filter by .dcs path substring (e.g. 'Reports/Sales' or a report name)") //$NON-NLS-1$
            .enumProperty("outputMode", //$NON-NLS-1$
                "Output mode: 'full' (matches with context, default), 'count', or 'files'", //$NON-NLS-1$
                MODE_FULL, MODE_COUNT, MODE_FILES)
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String query = JsonUtils.extractStringArgument(params, KEY_QUERY);
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_QUERY);
        if (err != null)
        {
            return err;
        }
        boolean caseSensitive = JsonUtils.extractBooleanArgument(params, "caseSensitive", false); //$NON-NLS-1$
        boolean isRegex = JsonUtils.extractBooleanArgument(params, "isRegex", false); //$NON-NLS-1$
        int maxResults = Pagination.clampLimit(
            JsonUtils.extractIntArgument(params, "limit", DEFAULT_MAX_RESULTS), ABSOLUTE_MAX_RESULTS); //$NON-NLS-1$
        int contextLines = Math.min(Math.max(0,
            JsonUtils.extractIntArgument(params, KEY_CONTEXT_LINES, DEFAULT_CONTEXT_LINES)), MAX_CONTEXT_LINES);
        String fileMask = JsonUtils.extractStringArgument(params, "fileMask"); //$NON-NLS-1$

        String outputMode = JsonUtils.extractStringArgument(params, "outputMode"); //$NON-NLS-1$
        if (outputMode == null || outputMode.isEmpty())
        {
            outputMode = MODE_FULL;
        }
        outputMode = outputMode.toLowerCase();
        if (!MODE_FULL.equals(outputMode) && !MODE_COUNT.equals(outputMode) && !MODE_FILES.equals(outputMode))
        {
            return ToolResult.error("outputMode must be 'full', 'count', or 'files'").toJson(); //$NON-NLS-1$
        }

        Pattern pattern;
        try
        {
            int flags = Pattern.UNICODE_CHARACTER_CLASS;
            if (!caseSensitive)
            {
                flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            }
            pattern = Pattern.compile(isRegex ? query : Pattern.quote(query), flags);
        }
        catch (PatternSyntaxException e)
        {
            return ToolResult.error("Invalid regex pattern '" + query + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();
        if (!ResourceLineSearch.hasSrc(project))
        {
            return ToolResult.error("src/ folder not found in project " + projectName).toJson(); //$NON-NLS-1$
        }

        String maskLower = fileMask != null && !fileMask.isEmpty() ? fileMask.toLowerCase() : null;
        Predicate<String> pathFilter = maskLower == null ? p -> true
            : p -> p.toLowerCase().contains(maskLower);

        ResourceLineSearch.Result result;
        try
        {
            result = ResourceLineSearch.search(project, DCS_EXTENSION, pathFilter, pattern, maxResults,
                contextLines, MODE_FULL.equals(outputMode));
        }
        catch (CoreException e)
        {
            return ToolResult.error("Failed to search project: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        if (MODE_COUNT.equals(outputMode))
        {
            return formatCount(query, result);
        }
        if (MODE_FILES.equals(outputMode))
        {
            return formatFiles(query, result);
        }
        return formatFull(query, result);
    }

    private String formatCount(String query, ResourceLineSearch.Result result)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## DCS search count for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$
        sb.append("**Total matches:** ").append(result.totalMatches); //$NON-NLS-1$
        sb.append(" in **").append(result.totalMatchedFiles).append("** .dcs file(s)\n"); //$NON-NLS-1$ //$NON-NLS-2$
        appendWarnings(sb, result);
        return sb.toString();
    }

    private String formatFiles(String query, ResourceLineSearch.Result result)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## DCS search files for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$
        sb.append("**Total matches:** ").append(result.totalMatches); //$NON-NLS-1$
        sb.append(" in **").append(result.totalMatchedFiles).append("** .dcs file(s)\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        appendWarnings(sb, result);
        if (result.matchCountByFile.isEmpty())
        {
            sb.append("No matches found.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        sb.append(MarkdownUtils.tableHeader("File", "Matches")); //$NON-NLS-1$ //$NON-NLS-2$
        for (Map.Entry<String, Integer> entry : result.matchCountByFile.entrySet())
        {
            sb.append(MarkdownUtils.tableRow(entry.getKey(), String.valueOf(entry.getValue())));
        }
        return sb.toString();
    }

    private String formatFull(String query, ResourceLineSearch.Result result)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## DCS search results for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$
        sb.append("**Total:** ").append(result.totalMatches).append(" matches in ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(result.totalMatchedFiles).append(" .dcs file(s)"); //$NON-NLS-1$
        sb.append(Pagination.truncationNotice(result.shownMatches, result.totalMatches));
        sb.append("\n\n"); //$NON-NLS-1$
        appendWarnings(sb, result);
        if (result.matchesByFile.isEmpty())
        {
            sb.append("No matches found.\n"); //$NON-NLS-1$
            return sb.toString();
        }
        for (Map.Entry<String, List<ResourceLineSearch.Match>> entry : result.matchesByFile.entrySet())
        {
            sb.append("### ").append(entry.getKey()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            for (ResourceLineSearch.Match match : entry.getValue())
            {
                sb.append("**Line ").append(match.lineNumber).append(":**\n"); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append("```xml\n"); //$NON-NLS-1$
                for (String contextLine : match.contextLines)
                {
                    sb.append(contextLine).append("\n"); //$NON-NLS-1$
                }
                sb.append("```\n\n"); //$NON-NLS-1$
            }
        }
        return sb.toString();
    }

    private void appendWarnings(StringBuilder sb, ResourceLineSearch.Result result)
    {
        if (result.skippedFiles > 0)
        {
            sb.append(WARNING_PREFIX).append(result.skippedFiles)
                .append(" file(s) could not be read\n"); //$NON-NLS-1$
        }
        if (result.interrupted)
        {
            sb.append(WARNING_PREFIX).append("search was interrupted, results may be incomplete\n"); //$NON-NLS-1$
        }
    }
}

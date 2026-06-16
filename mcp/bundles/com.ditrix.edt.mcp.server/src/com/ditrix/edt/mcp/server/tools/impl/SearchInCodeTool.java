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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

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

    /** Closing quote of a heading echoing the query, followed by a blank line. */
    private static final String QUOTE_NEWLINES = "\"\n\n"; //$NON-NLS-1$

    /** Markdown prefix for inline warnings in the rendered output. */
    private static final String WARNING_PREFIX = "**Warning:** "; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Literal/regex full-text search across all BSL modules in a project. " + //$NON-NLS-1$
               "Matching is purely textual and NOT ru/en dialect-aware, so a query in one " + //$NON-NLS-1$
               "BSL language won't find the other spelling; for identifier lookup use " + //$NON-NLS-1$
               "get_symbol_info, find_references or get_method_call_hierarchy instead. " + //$NON-NLS-1$
               "Use this for a literal text scan; for a symbol's USAGES use find_references, " + //$NON-NLS-1$
               "for where it is DEFINED use go_to_definition. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('search_in_code')."; //$NON-NLS-1$
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
        SearchCollector collector = new SearchCollector(pattern, fileMask, metadataFolderPrefix,
            maxResults, contextLines, collectDetails);

        try
        {
            IResource srcFolder = project.findMember("src"); //$NON-NLS-1$
            if (srcFolder != null)
            {
                srcFolder.accept(collector);
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
        return formatOutput(outputMode, query, collector);
    }

    /**
     * Formats the collected search results according to the requested output mode.
     */
    private String formatOutput(String outputMode, String query, SearchCollector collector)
    {
        if (MODE_COUNT.equals(outputMode))
        {
            return formatCountOutput(query, collector);
        }
        else if (MODE_FILES.equals(outputMode))
        {
            return formatFilesOutput(query, collector);
        }
        return formatFullOutput(query, collector);
    }

    /**
     * Formats count-only output.
     */
    private String formatCountOutput(String query, SearchCollector collector)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Count for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$
        sb.append("**Total matches:** ").append(collector.totalMatches); //$NON-NLS-1$
        sb.append(" in **").append(collector.totalMatchedFiles).append("** files\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (collector.skippedFiles > 0)
        {
            sb.append(WARNING_PREFIX).append(collector.skippedFiles)
              .append(" file(s) could not be read\n"); //$NON-NLS-1$
        }
        if (collector.wasInterrupted())
        {
            sb.append("**Warning:** Search was interrupted, results may be incomplete\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Formats files-only output (file list with per-file match counts).
     */
    private String formatFilesOutput(String query, SearchCollector collector)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Files for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$
        sb.append("**Total matches:** ").append(collector.totalMatches); //$NON-NLS-1$
        sb.append(" in **").append(collector.totalMatchedFiles).append("** files\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (collector.skippedFiles > 0)
        {
            sb.append(WARNING_PREFIX).append(collector.skippedFiles)
              .append(" file(s) could not be read\n\n"); //$NON-NLS-1$
        }
        if (collector.wasInterrupted())
        {
            sb.append("**Warning:** Search was interrupted, results may be incomplete\n\n"); //$NON-NLS-1$
        }

        if (collector.matchCountByFile.isEmpty())
        {
            sb.append("No matches found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append(MarkdownUtils.tableHeader("File", "Matches")); //$NON-NLS-1$ //$NON-NLS-2$

        for (Map.Entry<String, Integer> entry : collector.matchCountByFile.entrySet())
        {
            sb.append(MarkdownUtils.tableRow(entry.getKey(), String.valueOf(entry.getValue())));
        }

        return sb.toString();
    }

    /**
     * Formats full search results as markdown with context.
     */
    private String formatFullOutput(String query, SearchCollector collector)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Search Results for \"").append(query).append(QUOTE_NEWLINES); //$NON-NLS-1$

        int totalMatches = collector.totalMatches;
        int totalFiles = collector.totalMatchedFiles;
        int shownMatches = collector.getShownMatches();

        sb.append("**Total:** ").append(totalMatches); //$NON-NLS-1$
        sb.append(" matches in ").append(totalFiles).append(" files"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(Pagination.truncationNotice(shownMatches, totalMatches));
        sb.append("\n"); //$NON-NLS-1$

        if (collector.skippedFiles > 0)
        {
            sb.append(WARNING_PREFIX).append(collector.skippedFiles)
              .append(" file(s) could not be read (check EDT Error Log)\n"); //$NON-NLS-1$
        }
        if (collector.wasInterrupted())
        {
            sb.append("**Warning:** Search was interrupted, results may be incomplete\n"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$

        if (collector.matchesByFile.isEmpty())
        {
            sb.append("No matches found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        // Group by file
        for (Map.Entry<String, List<MatchInfo>> entry : collector.matchesByFile.entrySet())
        {
            sb.append("### ").append(entry.getKey()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            for (MatchInfo match : entry.getValue())
            {
                sb.append("**Line ").append(match.lineNumber).append(":**\n"); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append("```bsl\n"); //$NON-NLS-1$
                for (String contextLine : match.contextLines)
                {
                    sb.append(contextLine).append("\n"); //$NON-NLS-1$
                }
                sb.append("```\n\n"); //$NON-NLS-1$
            }
        }

        return sb.toString();
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

    /**
     * Holds a single match with context.
     */
    private static class MatchInfo
    {
        int lineNumber;
        List<String> contextLines = new ArrayList<>();
    }

    /**
     * Resource visitor that searches BSL files for matches.
     * Always scans all files to get accurate total counts,
     * but only collects detailed match info up to the limit.
     */
    private static class SearchCollector implements IResourceVisitor
    {
        private final Pattern pattern;
        private final String fileMask;
        private final String metadataFolderPrefix;
        private final int maxResults;
        private final int contextLines;
        private final boolean collectDetails;

        final Map<String, List<MatchInfo>> matchesByFile = new LinkedHashMap<>();
        final Map<String, Integer> matchCountByFile = new LinkedHashMap<>();
        int totalMatches = 0;
        int totalMatchedFiles = 0;
        int skippedFiles = 0;
        private int collectedMatches = 0;
        private boolean wasInterrupted = false;

        SearchCollector(Pattern pattern, String fileMask, String metadataFolderPrefix,
            int maxResults, int contextLines, boolean collectDetails)
        {
            this.pattern = pattern;
            this.fileMask = fileMask;
            this.metadataFolderPrefix = metadataFolderPrefix;
            this.maxResults = maxResults;
            this.contextLines = contextLines;
            this.collectDetails = collectDetails;
        }

        @Override
        public boolean visit(IResource resource) throws CoreException
        {
            if (Thread.currentThread().isInterrupted())
            {
                wasInterrupted = true;
                return false;
            }

            if (resource.getType() != IResource.FILE)
            {
                return true; // Continue visiting children
            }

            // Only .bsl files
            String name = resource.getName();
            if (!name.endsWith(".bsl")) //$NON-NLS-1$
            {
                return false;
            }

            // Apply file mask filter
            String relativePath = resource.getProjectRelativePath().toString();
            // Remove src/ prefix for display
            String displayPath = relativePath;
            if (displayPath.startsWith("src/")) //$NON-NLS-1$
            {
                displayPath = displayPath.substring(4);
            }

            if (fileMask != null && !fileMask.isEmpty())
            {
                if (!displayPath.toLowerCase().contains(fileMask.toLowerCase()))
                {
                    return false;
                }
            }

            // Apply metadata type filter
            if (metadataFolderPrefix != null)
            {
                if (!displayPath.startsWith(metadataFolderPrefix))
                {
                    return false;
                }
            }

            // Search in file
            IFile file = (IFile) resource;
            try
            {
                searchInFile(file, displayPath);
            }
            catch (Exception e)
            {
                Activator.logWarning("Failed to search in file: " + displayPath + " - " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                skippedFiles++;
            }

            return false;
        }

        private void searchInFile(IFile file, String displayPath) throws Exception
        {
            List<String> lines = BslModuleUtils.readFileLines(file);
            int fileMatches = 0;

            for (int i = 0; i < lines.size(); i++)
            {
                Matcher matcher = pattern.matcher(lines.get(i));
                if (matcher.find())
                {
                    totalMatches++;
                    fileMatches++;

                    // Collect detailed match info only in full mode and within limit
                    if (collectDetails && collectedMatches < maxResults)
                    {
                        MatchInfo match = new MatchInfo();
                        match.lineNumber = i + 1;

                        // Add context lines
                        int from = Math.max(0, i - contextLines);
                        int to = Math.min(lines.size() - 1, i + contextLines);

                        for (int j = from; j <= to; j++)
                        {
                            String prefix = (j + 1) + ": "; //$NON-NLS-1$
                            match.contextLines.add(prefix + lines.get(j));
                        }

                        matchesByFile.computeIfAbsent(displayPath, k -> new ArrayList<>()).add(match);
                        collectedMatches++;
                    }
                }
            }

            if (fileMatches > 0)
            {
                totalMatchedFiles++;
                matchCountByFile.put(displayPath, fileMatches);
            }
        }

        int getShownMatches()
        {
            return collectedMatches;
        }

        boolean wasInterrupted()
        {
            return wasInterrupted;
        }
    }
}

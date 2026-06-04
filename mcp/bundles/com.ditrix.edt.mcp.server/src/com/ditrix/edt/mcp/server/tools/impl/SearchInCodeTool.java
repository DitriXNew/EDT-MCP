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

    /** Default and maximum limits */
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int ABSOLUTE_MAX_RESULTS = 500;
    private static final int DEFAULT_CONTEXT_LINES = 2;
    private static final int MAX_CONTEXT_LINES = 5;

    /** Output modes */
    private static final String MODE_FULL = "full"; //$NON-NLS-1$
    private static final String MODE_COUNT = "count"; //$NON-NLS-1$
    private static final String MODE_FILES = "files"; //$NON-NLS-1$

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
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("query", //$NON-NLS-1$
                "Search string or regex pattern (required); matched literally unless isRegex=true", true) //$NON-NLS-1$
            .booleanProperty("caseSensitive", //$NON-NLS-1$
                "Case-sensitive search. Default: false") //$NON-NLS-1$
            .booleanProperty("isRegex", //$NON-NLS-1$
                "Treat query as a regular expression. Default: false") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Max matches returned with context. Default: 100, max: 500") //$NON-NLS-1$
            .integerProperty("maxResults", //$NON-NLS-1$
                "Deprecated alias for 'limit'. Default: 100, max: 500") //$NON-NLS-1$
            .integerProperty("contextLines", //$NON-NLS-1$
                "Lines of context before/after each match. Default: 2, max: 5") //$NON-NLS-1$
            .stringProperty("fileMask", //$NON-NLS-1$
                "Filter by module path substring (e.g. 'CommonModules' or 'Documents/SalesOrder')") //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Filter by metadata type (e.g. 'documents', 'catalogs', 'commonModules'); " + //$NON-NLS-1$
                "more precise than fileMask. See guide for the full list.") //$NON-NLS-1$
            .enumProperty("outputMode", //$NON-NLS-1$
                "Output mode: 'full' (matches with context, default), 'count', or 'files'", //$NON-NLS-1$
                "full", "count", "files") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# search_in_code\n\n" //$NON-NLS-1$
            + "Literal or regex full-text search across every BSL module (`*.bsl`) under the " //$NON-NLS-1$
            + "project's `src/` folder. Returns matches with surrounding context, or a " //$NON-NLS-1$
            + "lightweight count / file list.\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- Find a literal string, comment, message, or regex pattern in BSL source.\n" //$NON-NLS-1$
            + "- Scope a query to one metadata family (`metadataType`) or a path " //$NON-NLS-1$
            + "(`fileMask`).\n" //$NON-NLS-1$
            + "- Use `outputMode='count'` or `'files'` first for a cheap overview before " //$NON-NLS-1$
            + "pulling full context.\n\n" //$NON-NLS-1$

            + "## When NOT to use (ru/en dialect trap)\n" //$NON-NLS-1$
            + "Matching is **purely textual and NOT dialect-aware**. A query in one BSL " //$NON-NLS-1$
            + "language will not match its other-language spelling: searching the English " //$NON-NLS-1$
            + "`Procedure` will NOT find a module written with the Russian keyword " //$NON-NLS-1$
            + "`\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430`, and vice versa. " //$NON-NLS-1$
            + "To locate an identifier (method, variable, object) regardless of ru/en " //$NON-NLS-1$
            + "spelling, use `get_symbol_info`, `find_references` or " //$NON-NLS-1$
            + "`get_method_call_hierarchy` instead.\n\n" //$NON-NLS-1$

            + "## Parameters\n" //$NON-NLS-1$
            + "- `projectName` (required) - EDT project name.\n" //$NON-NLS-1$
            + "- `query` (required) - search string or regex; matched literally unless " //$NON-NLS-1$
            + "`isRegex=true`.\n" //$NON-NLS-1$
            + "- `caseSensitive` - default `false`.\n" //$NON-NLS-1$
            + "- `isRegex` - treat `query` as a Java regular expression; default `false`. " //$NON-NLS-1$
            + "An invalid pattern returns an error.\n" //$NON-NLS-1$
            + "- `limit` - max matches returned with context; default 100, max 500. " //$NON-NLS-1$
            + "Counts in `count`/`files` mode are always exact regardless of `limit`.\n" //$NON-NLS-1$
            + "- `maxResults` - deprecated alias for `limit` (used only when `limit` is " //$NON-NLS-1$
            + "absent).\n" //$NON-NLS-1$
            + "- `contextLines` - lines before/after each match; default 2, max 5 " //$NON-NLS-1$
            + "(`full` mode only).\n" //$NON-NLS-1$
            + "- `fileMask` - case-insensitive substring of the module path " //$NON-NLS-1$
            + "(e.g. `CommonModules`, `Documents/SalesOrder`).\n" //$NON-NLS-1$
            + "- `metadataType` - restrict to one family by folder prefix; more precise " //$NON-NLS-1$
            + "than `fileMask`. Allowed: documents, catalogs, commonModules, " //$NON-NLS-1$
            + "informationRegisters, accumulationRegisters, reports, dataProcessors, " //$NON-NLS-1$
            + "exchangePlans, businessProcesses, tasks, constants, commonCommands, " //$NON-NLS-1$
            + "commonForms, webServices, httpServices, enums, " //$NON-NLS-1$
            + "chartsOfCharacteristicTypes, chartsOfAccounts, chartsOfCalculationTypes. " //$NON-NLS-1$
            + "An unknown value returns an error.\n\n" //$NON-NLS-1$

            + "## Output modes (`outputMode`)\n" //$NON-NLS-1$
            + "- `full` (default) - matches grouped by file with `contextLines` of " //$NON-NLS-1$
            + "context, capped at `limit`.\n" //$NON-NLS-1$
            + "- `count` - only the total match and file counts; fastest.\n" //$NON-NLS-1$
            + "- `files` - one row per file with its per-file match count; no context.\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- Literal search: `{projectName, query: \"FixedDate\"}`.\n" //$NON-NLS-1$
            + "- Regex, case-sensitive: `{projectName, query: \"Sum\\\\d+\", isRegex: true, " //$NON-NLS-1$
            + "caseSensitive: true}`.\n" //$NON-NLS-1$
            + "- Scoped count: `{projectName, query: \"Export\", metadataType: " //$NON-NLS-1$
            + "\"commonModules\", outputMode: \"count\"}`.\n" //$NON-NLS-1$
            + "- File overview: `{projectName, query: \"TODO\", outputMode: \"files\"}`.\n\n" //$NON-NLS-1$

            + "## Notes & gotchas\n" //$NON-NLS-1$
            + "- Searches `src/` only; a project without a `src/` folder returns an error.\n" //$NON-NLS-1$
            + "- Only `.bsl` files are scanned (no form/query/XML files).\n" //$NON-NLS-1$
            + "- Each match is a single line; a pattern spanning multiple lines won't match.\n" //$NON-NLS-1$
            + "- Unreadable files are skipped and reported as a warning, not an error.\n" //$NON-NLS-1$
            + "- `fileMask` and `metadataType` combine (both must match).\n"; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String query = JsonUtils.extractStringArgument(params, "query"); //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String query = JsonUtils.extractStringArgument(params, "query"); //$NON-NLS-1$
        boolean caseSensitive = JsonUtils.extractBooleanArgument(params, "caseSensitive", false); //$NON-NLS-1$
        boolean isRegex = JsonUtils.extractBooleanArgument(params, "isRegex", false); //$NON-NLS-1$
        int configuredMaxResults = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, "maxResults", DEFAULT_MAX_RESULTS); //$NON-NLS-1$
        int configuredContextLines = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, "contextLines", DEFAULT_CONTEXT_LINES); //$NON-NLS-1$
        // Canonical param is "limit" (consistent with other paginated tools);
        // "maxResults" is kept as a deprecated alias (precedence: limit, then maxResults).
        int maxResultsAlias = JsonUtils.extractIntArgument(params, "maxResults", configuredMaxResults); //$NON-NLS-1$
        int maxResults = JsonUtils.extractIntArgument(params, "limit", maxResultsAlias); //$NON-NLS-1$
        int contextLines = JsonUtils.extractIntArgument(params, "contextLines", configuredContextLines); //$NON-NLS-1$
        String fileMask = JsonUtils.extractStringArgument(params, "fileMask"); //$NON-NLS-1$
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$
        String outputMode = JsonUtils.extractStringArgument(params, "outputMode"); //$NON-NLS-1$

        // Validate required parameters
        String err = JsonUtils.requireArguments(params, "projectName", "query"); //$NON-NLS-1$ //$NON-NLS-2$
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
        sb.append("## Search Count for \"").append(query).append("\"\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Total matches:** ").append(collector.totalMatches); //$NON-NLS-1$
        sb.append(" in **").append(collector.totalMatchedFiles).append("** files\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (collector.skippedFiles > 0)
        {
            sb.append("**Warning:** ").append(collector.skippedFiles) //$NON-NLS-1$
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
        sb.append("## Search Files for \"").append(query).append("\"\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Total matches:** ").append(collector.totalMatches); //$NON-NLS-1$
        sb.append(" in **").append(collector.totalMatchedFiles).append("** files\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        if (collector.skippedFiles > 0)
        {
            sb.append("**Warning:** ").append(collector.skippedFiles) //$NON-NLS-1$
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
        sb.append("## Search Results for \"").append(query).append("\"\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        int totalMatches = collector.totalMatches;
        int totalFiles = collector.totalMatchedFiles;
        int shownMatches = collector.getShownMatches();

        sb.append("**Total:** ").append(totalMatches); //$NON-NLS-1$
        sb.append(" matches in ").append(totalFiles).append(" files"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(Pagination.truncationNotice(shownMatches, totalMatches));
        sb.append("\n"); //$NON-NLS-1$

        if (collector.skippedFiles > 0)
        {
            sb.append("**Warning:** ").append(collector.skippedFiles) //$NON-NLS-1$
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

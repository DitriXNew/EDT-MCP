/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;

/**
 * Tool for full-text search across all BSL code in the configuration.
 * Supports plain text, regex, case-sensitivity, whole-word matching, and context lines.
 */
public class SearchInCodeTool implements IMcpTool
{
    public static final String NAME = "search_in_code"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Full-text search across all BSL code modules in the configuration. " + //$NON-NLS-1$
               "Supports plain text and regex patterns, case-sensitivity, whole-word matching, " + //$NON-NLS-1$
               "and context lines around each match. Can be restricted to specific modules or metadata types."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("query", //$NON-NLS-1$
                "Search text or regex pattern (required)", true) //$NON-NLS-1$
            .booleanProperty("caseSensitive", //$NON-NLS-1$
                "Case-sensitive search. Default: false") //$NON-NLS-1$
            .booleanProperty("wholeWord", //$NON-NLS-1$
                "Match whole words only. Default: false") //$NON-NLS-1$
            .booleanProperty("regex", //$NON-NLS-1$
                "Treat query as regular expression. Default: false") //$NON-NLS-1$
            .integerProperty("contextLines", //$NON-NLS-1$
                "Number of surrounding lines to include with each match. Default: 2") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum number of matches to return. Default: 50") //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Restrict search to a specific module path " + //$NON-NLS-1$
                "(e.g. 'CommonModules/MyModule/Module.bsl')") //$NON-NLS-1$
            .stringProperty("metadataType", //$NON-NLS-1$
                "Restrict search to modules of this metadata type " + //$NON-NLS-1$
                "(e.g. 'Documents', 'CommonModules')") //$NON-NLS-1$
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
        String query = JsonUtils.extractStringArgument(params, "query"); //$NON-NLS-1$
        if (query != null && !query.isEmpty())
        {
            String safeName = query.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            if (safeName.length() > 30)
            {
                safeName = safeName.substring(0, 30);
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
        String caseSensitiveStr = JsonUtils.extractStringArgument(params, "caseSensitive"); //$NON-NLS-1$
        String wholeWordStr = JsonUtils.extractStringArgument(params, "wholeWord"); //$NON-NLS-1$
        String regexStr = JsonUtils.extractStringArgument(params, "regex"); //$NON-NLS-1$
        String contextLinesStr = JsonUtils.extractStringArgument(params, "contextLines"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String metadataType = JsonUtils.extractStringArgument(params, "metadataType"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (query == null || query.isEmpty())
        {
            return "Error: query is required"; //$NON-NLS-1$
        }

        boolean caseSensitive = "true".equalsIgnoreCase(caseSensitiveStr); //$NON-NLS-1$
        boolean wholeWord = "true".equalsIgnoreCase(wholeWordStr); //$NON-NLS-1$
        boolean useRegex = "true".equalsIgnoreCase(regexStr); //$NON-NLS-1$

        int contextLines = 2;
        if (contextLinesStr != null && !contextLinesStr.isEmpty())
        {
            try
            {
                contextLines = Math.min((int) Double.parseDouble(contextLinesStr), 10);
            }
            catch (NumberFormatException e)
            {
                // Use default
            }
        }

        int limit = 50;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min((int) Double.parseDouble(limitStr), 500);
            }
            catch (NumberFormatException e)
            {
                // Use default
            }
        }

        IProject project = BslModuleUtils.resolveProject(projectName);
        if (project == null)
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        // Compile search pattern
        Pattern searchPattern;
        try
        {
            searchPattern = compilePattern(query, caseSensitive, wholeWord, useRegex);
        }
        catch (Exception e)
        {
            return "Error: Invalid regex pattern: " + e.getMessage(); //$NON-NLS-1$
        }

        // Get files to search
        List<IFile> filesToSearch;
        if (modulePath != null && !modulePath.isEmpty())
        {
            // Single file search
            IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
            if (file == null)
            {
                return "Error: Module not found: " + modulePath; //$NON-NLS-1$
            }
            filesToSearch = List.of(file);
        }
        else if (metadataType != null && !metadataType.isEmpty())
        {
            filesToSearch = BslModuleUtils.findBslFilesByType(project, metadataType);
        }
        else
        {
            filesToSearch = BslModuleUtils.findAllBslFiles(project);
        }

        // Search
        Map<String, List<SearchMatch>> resultsByFile = new LinkedHashMap<>();
        int totalMatches = 0;

        for (IFile file : filesToSearch)
        {
            if (totalMatches >= limit)
            {
                break;
            }

            List<SearchMatch> matches = searchInFile(file, searchPattern, contextLines,
                limit - totalMatches);
            if (!matches.isEmpty())
            {
                String relPath = BslModuleUtils.getRelativeModulePath(project, file);
                resultsByFile.put(relPath, matches);
                totalMatches += matches.size();
            }
        }

        // Format output
        return formatOutput(query, projectName, caseSensitive, wholeWord, useRegex,
            resultsByFile, totalMatches, limit, filesToSearch.size());
    }

    /**
     * Compiles the search pattern based on options.
     */
    private Pattern compilePattern(String query, boolean caseSensitive,
        boolean wholeWord, boolean useRegex)
    {
        String pattern;
        if (useRegex)
        {
            pattern = query;
        }
        else
        {
            pattern = Pattern.quote(query);
        }

        if (wholeWord)
        {
            pattern = "\\b" + pattern + "\\b"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
        return Pattern.compile(pattern, flags);
    }

    /**
     * Searches for matches in a single file.
     */
    private List<SearchMatch> searchInFile(IFile file, Pattern pattern,
        int contextLines, int maxMatches)
    {
        List<SearchMatch> matches = new ArrayList<>();
        List<String> allLines = new ArrayList<>();

        // Read all lines
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), file.getCharset())))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                allLines.add(line);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error reading file for search", e); //$NON-NLS-1$
            return matches;
        }

        // Search line by line
        for (int i = 0; i < allLines.size(); i++)
        {
            if (matches.size() >= maxMatches)
            {
                break;
            }

            String line = allLines.get(i);
            Matcher matcher = pattern.matcher(line);
            if (matcher.find())
            {
                int lineNum = i + 1; // 1-based

                // Extract context lines
                int contextStart = Math.max(0, i - contextLines);
                int contextEnd = Math.min(allLines.size() - 1, i + contextLines);

                StringBuilder context = new StringBuilder();
                for (int j = contextStart; j <= contextEnd; j++)
                {
                    int num = j + 1;
                    String prefix = (j == i) ? ">" : " "; //$NON-NLS-1$ //$NON-NLS-2$
                    context.append(String.format("%s%5d | %s%n", prefix, num, allLines.get(j))); //$NON-NLS-1$
                }

                matches.add(new SearchMatch(lineNum, line.trim(), context.toString()));
            }
        }

        return matches;
    }

    /**
     * Formats the search results as markdown.
     */
    private String formatOutput(String query, String projectName,
        boolean caseSensitive, boolean wholeWord, boolean useRegex,
        Map<String, List<SearchMatch>> resultsByFile,
        int totalMatches, int limit, int filesSearched)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("## Search Results: \"").append(escapeMarkdown(query)).append("\"\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Project:** ").append(projectName); //$NON-NLS-1$
        sb.append(" | **Files searched:** ").append(filesSearched); //$NON-NLS-1$
        sb.append(" | **Total matches:** ").append(totalMatches); //$NON-NLS-1$
        if (totalMatches >= limit)
        {
            sb.append(" (limit reached)"); //$NON-NLS-1$
        }
        sb.append("\n"); //$NON-NLS-1$

        // Options
        List<String> options = new ArrayList<>();
        if (caseSensitive) options.add("case-sensitive"); //$NON-NLS-1$
        if (wholeWord) options.add("whole-word"); //$NON-NLS-1$
        if (useRegex) options.add("regex"); //$NON-NLS-1$
        if (!options.isEmpty())
        {
            sb.append("**Options:** ").append(String.join(", ", options)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        sb.append("\n"); //$NON-NLS-1$

        if (resultsByFile.isEmpty())
        {
            sb.append("No matches found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        // Output by file
        for (Map.Entry<String, List<SearchMatch>> entry : resultsByFile.entrySet())
        {
            String filePath = entry.getKey();
            List<SearchMatch> matches = entry.getValue();

            sb.append("### ").append(filePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            for (SearchMatch match : matches)
            {
                sb.append("**Line ").append(match.lineNumber).append(":**\n"); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append("```bsl\n"); //$NON-NLS-1$
                sb.append(match.context);
                sb.append("```\n\n"); //$NON-NLS-1$
            }
        }

        return sb.toString();
    }

    /**
     * Escapes markdown special characters in text.
     */
    private String escapeMarkdown(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        return text.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("`", "\\`") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("*", "\\*") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("_", "\\_") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("[", "\\[") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("]", "\\]"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Holds a single search match result.
     */
    private static class SearchMatch
    {
        final int lineNumber;
        final String matchLine;
        final String context;

        SearchMatch(int lineNumber, String matchLine, String context)
        {
            this.lineNumber = lineNumber;
            this.matchLine = matchLine;
            this.context = context;
        }
    }
}

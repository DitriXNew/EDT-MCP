/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils.MetadataTypeInfo;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ResourceTextSearch;
import com.ditrix.edt.mcp.server.utils.SearchResultFormatter;
import com.ditrix.edt.mcp.server.utils.XmlEntityDecoder;

/**
 * Literal/regex full-text search over the DCS schemas of a project: every {@code Template.dcs}
 * (and any other {@code *.dcs}) file under {@code src/}. It is the discovery step before
 * {@code dcs}: recognised export layouts include the template FQN to pass to {@code dcs}.
 * <p>
 * The scan and the rendering are the ones {@code search_in_code} uses
 * ({@link ResourceTextSearch}, {@link SearchResultFormatter}); this tool differs only in the
 * files it reads ({@code .dcs}), XML entity decoding for matching, the code fence ({@code xml}),
 * the template address per recognised layout,
 * and an empty result that never claims absence on an incomplete scan.
 */
public class SearchInDcsTool implements IMcpTool
{
    public static final String NAME = "search_in_dcs"; //$NON-NLS-1$

    /** Input param: the search string or regex pattern. */
    private static final String KEY_QUERY = "query"; //$NON-NLS-1$

    /** Input param: lines of context before/after each match. */
    private static final String KEY_CONTEXT_LINES = "contextLines"; //$NON-NLS-1$

    /** The file suffix this tool scans. */
    static final String DCS_EXTENSION = ".dcs"; //$NON-NLS-1$

    /** Default and maximum limits */
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_CONTEXT_LINES = 2;
    private static final int MAX_CONTEXT_LINES = 5;

    /** The file name EDT gives every exported template body. */
    private static final String TEMPLATE_FILE = "Template" + DCS_EXTENSION; //$NON-NLS-1$

    /** The folder an owner object keeps its templates in. */
    private static final String TEMPLATES_FOLDER = "Templates"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Literal/regex full-text search inside DCS schemas (*.dcs under src/); names " //$NON-NLS-1$
            + "the template FQN when the layout is recognised. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('search_in_dcs')."; //$NON-NLS-1$
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
            .integerProperty(KEY_CONTEXT_LINES,
                "Lines of context before/after each match. Default: 2, max: 5") //$NON-NLS-1$
            .stringProperty("fileMask", //$NON-NLS-1$
                "Filter by file path substring, path without src/ (e.g. 'Reports/Stock' or 'CommonTemplates')") //$NON-NLS-1$
            .enumProperty("outputMode", //$NON-NLS-1$
                "Output mode: 'full' (matches with context, default), 'count', or 'files'", //$NON-NLS-1$
                SearchResultFormatter.MODE_FULL, SearchResultFormatter.MODE_COUNT,
                SearchResultFormatter.MODE_FILES)
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
                .toLowerCase(Locale.ROOT);
            if (safeName.length() > 40)
            {
                safeName = safeName.substring(0, 40);
            }
            return "search-dcs-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "search-dcs-results.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_QUERY);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String query = JsonUtils.extractStringArgument(params, KEY_QUERY);
        boolean caseSensitive = JsonUtils.extractBooleanArgument(params, "caseSensitive", false); //$NON-NLS-1$
        boolean isRegex = JsonUtils.extractBooleanArgument(params, "isRegex", false); //$NON-NLS-1$
        int limit = JsonUtils.extractIntArgument(params, "limit", DEFAULT_LIMIT); //$NON-NLS-1$
        int contextLines = JsonUtils.extractIntArgument(params, KEY_CONTEXT_LINES, DEFAULT_CONTEXT_LINES);
        String fileMask = JsonUtils.extractStringArgument(params, "fileMask"); //$NON-NLS-1$
        String outputMode = JsonUtils.extractStringArgument(params, "outputMode"); //$NON-NLS-1$

        outputMode = normalizeOutputMode(outputMode);
        if (outputMode == null)
        {
            return ToolResult.error("outputMode must be 'full', 'count', or 'files'").toJson(); //$NON-NLS-1$
        }

        limit = Pagination.clampLimit(limit, MAX_LIMIT);
        contextLines = Math.min(Math.max(0, contextLines), MAX_CONTEXT_LINES);

        Pattern pattern;
        try
        {
            pattern = compile(query, isRegex, caseSensitive);
        }
        catch (PatternSyntaxException e)
        {
            return ToolResult.error("Invalid regex pattern '" + query + "': " + e.getDescription() //$NON-NLS-1$ //$NON-NLS-2$
                + ". Fix the pattern, or pass isRegex=false to search for the text literally.").toJson(); //$NON-NLS-1$
        }

        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        ResourceTextSearch search = new ResourceTextSearch(pattern, DCS_EXTENSION, fileMask, null,
            limit, contextLines, SearchResultFormatter.MODE_FULL.equals(outputMode),
            BslModuleUtils::readFileLines, XmlEntityDecoder::decode);
        try
        {
            IResource srcFolder = project.findMember("src"); //$NON-NLS-1$
            if (srcFolder == null)
            {
                return ToolResult.error("Project " + projectName + " has no src/ folder. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "It may not be a configuration or extension project, or it is not loaded yet. " //$NON-NLS-1$
                    + "Use list_projects to check its type and state.").toJson(); //$NON-NLS-1$
            }
            srcFolder.accept(search);
        }
        catch (CoreException e)
        {
            return ToolResult.error("Failed to search project " + projectName + ": " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return SearchResultFormatter.withAddress("xml", "Template", SearchInDcsTool::templateFqn) //$NON-NLS-1$ //$NON-NLS-2$
            .format(outputMode, query, search);
    }

    /**
     * @param outputMode the raw argument
     * @return the lower-cased mode ({@code full} when absent), or {@code null} when it is not one
     *     of the three
     */
    static String normalizeOutputMode(String outputMode)
    {
        if (outputMode == null || outputMode.isEmpty())
        {
            return SearchResultFormatter.MODE_FULL;
        }
        String mode = outputMode.toLowerCase(Locale.ROOT);
        if (SearchResultFormatter.MODE_FULL.equals(mode) || SearchResultFormatter.MODE_COUNT.equals(mode)
            || SearchResultFormatter.MODE_FILES.equals(mode))
        {
            return mode;
        }
        return null;
    }

    /**
     * Compiles the query with the flags {@code search_in_code} uses: Unicode character classes,
     * and Unicode-aware case folding unless the search is case-sensitive.
     *
     * @throws PatternSyntaxException when {@code isRegex} and the pattern is invalid
     */
    static Pattern compile(String query, boolean isRegex, boolean caseSensitive)
    {
        int flags = Pattern.UNICODE_CHARACTER_CLASS;
        if (!caseSensitive)
        {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        return Pattern.compile(isRegex ? query : Pattern.quote(query), flags);
    }

    /**
     * The FQN {@code dcs} addresses the template a {@code .dcs} file belongs to, read off EDT's
     * export layout:
     * <ul>
     * <li>{@code CommonTemplates/<Name>/Template.dcs} &rarr; {@code CommonTemplate.<Name>};</li>
     * <li>{@code <TypeFolder>/<Owner>/Templates/<Name>/Template.dcs} &rarr;
     * {@code <Type>.<Owner>.Template.<Name>}, where {@code <TypeFolder>} is a metadata type's
     * exact folder name (e.g. {@code Reports}, {@code DataProcessors}) and that type can own
     * templates.</li>
     * </ul>
     * Any other layout has no address: the file is reported by path only, never with a guessed FQN.
     *
     * @param displayPath the file path without {@code src/}
     * @return the template FQN, or {@code null} when the layout is not a recognised template
     */
    static String templateFqn(String displayPath)
    {
        if (displayPath == null)
        {
            return null;
        }
        String[] segments = displayPath.split("/", -1); //$NON-NLS-1$
        for (String segment : segments)
        {
            if (segment.isEmpty())
            {
                return null;
            }
        }
        if (!TEMPLATE_FILE.equals(segments[segments.length - 1]))
        {
            return null;
        }
        MetadataTypeInfo type = folderType(segments[0]);
        if (type == null)
        {
            return null;
        }
        boolean commonTemplate = type == MetadataTypeInfo.COMMON_TEMPLATE;
        if (segments.length == 3 && commonTemplate)
        {
            return type.getEnglishSingular() + '.' + segments[1];
        }
        if (segments.length == 5 && !commonTemplate && TEMPLATES_FOLDER.equals(segments[2])
            && MetadataTypeUtils.typeCanContain(type.getEnglishSingular(), "templates")) //$NON-NLS-1$
        {
            return type.getEnglishSingular() + '.' + segments[1] + ".Template." + segments[3]; //$NON-NLS-1$
        }
        return null;
    }

    /** The metadata type whose export folder is exactly {@code folder}, or {@code null}. */
    private static MetadataTypeInfo folderType(String folder)
    {
        MetadataTypeInfo type = MetadataTypeUtils.resolve(folder);
        return type != null && folder.equals(type.getEnglishPlural()) ? type : null;
    }
}

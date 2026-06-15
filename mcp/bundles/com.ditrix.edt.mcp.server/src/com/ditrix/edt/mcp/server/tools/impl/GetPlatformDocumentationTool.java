/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.doc.PlatformDocumentationService;
import com.ditrix.edt.mcp.server.utils.Pagination;

/**
 * Tool to get platform documentation for types, methods, properties, etc.
 * Supports searching by type name (ValueTable, Array), member name (Add, Insert),
 * and different categories (type, builtin).
 */
public class GetPlatformDocumentationTool implements IMcpTool
{
    public static final String NAME = "get_platform_documentation"; //$NON-NLS-1$

    /** Category constants */
    private static final String CATEGORY_TYPE = "type"; //$NON-NLS-1$
    private static final String CATEGORY_BUILTIN = "builtin"; //$NON-NLS-1$

    /** Input param: the platform type/builtin name to document. */
    private static final String KEY_TYPE_NAME = "typeName"; //$NON-NLS-1$

    /** Member type constant */
    private static final String MEMBER_ALL = "all"; //$NON-NLS-1$

    /** Response format constants for the {@code responseFormat} parameter. */
    private static final String FORMAT_CONCISE = "concise"; //$NON-NLS-1$
    private static final String FORMAT_DETAILED = "detailed"; //$NON-NLS-1$

    /** Closed set of values accepted by the {@code memberType} filter parameter. */
    static final java.util.List<String> MEMBER_TYPE_VALUES =
        java.util.Arrays.asList("method", "property", "constructor", "event", "all"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Look up 1C:Enterprise platform documentation for built-in types " + //$NON-NLS-1$
               "(ValueTable, Array, Structure) and global built-in functions, including " + //$NON-NLS-1$
               "their methods, properties, constructors and events. Use when you need the " + //$NON-NLS-1$
               "exact platform API signature rather than configuration metadata. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('get_platform_documentation')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_TYPE_NAME,
                "Type or symbol name, English or Russian (e.g. 'ValueTable').", true) //$NON-NLS-1$
            .enumProperty("category", //$NON-NLS-1$
                "'type' (platform types) or 'builtin' (global functions). Default: 'type'.", //$NON-NLS-1$
                CATEGORY_TYPE, CATEGORY_BUILTIN)
            .stringProperty("memberName", //$NON-NLS-1$
                "Filter members by name, partial match (e.g. 'Add').") //$NON-NLS-1$
            .enumProperty("memberType", //$NON-NLS-1$
                "Filter by member kind. Default: 'all'.", //$NON-NLS-1$
                "method", "property", "constructor", "event", "all") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name to pin the platform version. Optional.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Max results. Default: 50, max 200.") //$NON-NLS-1$
            .enumProperty("language", //$NON-NLS-1$
                "Output language. Default: 'en'.", //$NON-NLS-1$
                "en", "ru") //$NON-NLS-1$ //$NON-NLS-2$
            .enumProperty("responseFormat", //$NON-NLS-1$
                "'concise' (default) = leaner: headers + member names only; " //$NON-NLS-1$
                + "'detailed' = full member signatures, parameters, types and flags.", //$NON-NLS-1$
                FORMAT_CONCISE, FORMAT_DETAILED)
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
        String typeName = JsonUtils.extractStringArgument(params, KEY_TYPE_NAME);
        if (typeName != null && !typeName.isEmpty())
        {
            return "doc-" + typeName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "platform-documentation.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String typeName = JsonUtils.extractStringArgument(params, KEY_TYPE_NAME);
        String category = JsonUtils.extractStringArgument(params, "category"); //$NON-NLS-1$
        String memberName = JsonUtils.extractStringArgument(params, "memberName"); //$NON-NLS-1$
        String memberType = JsonUtils.extractStringArgument(params, "memberType"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        String responseFormat = JsonUtils.extractStringArgument(params, "responseFormat"); //$NON-NLS-1$

        // Output format: 'concise' (default) unless explicitly 'detailed'. Any other
        // value (absent, blank, unrecognized) falls back to concise rather than erroring.
        boolean detailed = FORMAT_DETAILED.equalsIgnoreCase(responseFormat);

        // Validate required parameter
        String err = JsonUtils.requireArgument(params, KEY_TYPE_NAME);
        if (err != null)
        {
            return err;
        }

        // Set defaults
        if (category == null || category.isEmpty())
        {
            category = CATEGORY_TYPE;
        }
        if (memberType == null || memberType.isEmpty())
        {
            memberType = MEMBER_ALL;
        }
        else if (!MEMBER_TYPE_VALUES.contains(memberType.toLowerCase()))
        {
            // Reject an out-of-set memberType instead of silently matching no members.
            // Echo the rejected value so the caller sees WHAT it sent that was wrong.
            return ToolResult.error("Invalid memberType: '" + memberType //$NON-NLS-1$
                + "'. Must be one of: " //$NON-NLS-1$
                + String.join(", ", MEMBER_TYPE_VALUES)).toJson(); //$NON-NLS-1$
        }
        if (language == null || language.isEmpty())
        {
            language = "en"; //$NON-NLS-1$
        }

        int limit = JsonUtils.extractIntArgument(params, "limit", 50); //$NON-NLS-1$
        limit = Pagination.clampLimit(limit, 200);

        boolean useRussian = "ru".equalsIgnoreCase(language); //$NON-NLS-1$

        PlatformDocumentationService service = new PlatformDocumentationService();

        // Execute based on category. The data gathering / model reads are identical for
        // both formats; only the rendered markdown is condensed afterwards for 'concise'.
        String result;
        switch (category.toLowerCase())
        {
            case CATEGORY_TYPE:
                result = service.getTypeDocumentation(typeName, memberName, memberType, projectName, limit, useRussian);
                break;
            case CATEGORY_BUILTIN:
                result = service.getBuiltinFunctionDocumentation(typeName, useRussian);
                break;
            default:
                return ToolResult.error("Unknown category '" + category + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                       "Supported: 'type', 'builtin'").toJson(); //$NON-NLS-1$
        }

        // The service signals a not-found by returning a soft markdown banner that
        // begins "Error: Type not found: <name>" / "Error: Built-in function not
        // found: <name>" followed by the available-types/functions list. Returned as
        // plain markdown it would reach the client as isError=false (a contract
        // violation: a machine client cannot detect the miss). Surface it as a real
        // ToolResult.error, preserving the bad value AND the actionable list as the
        // error body. The banner detect/strip lives in the service so this tool holds
        // no bare "Error:" literal (BareErrorStringRatchetTest scans tool classes).
        if (PlatformDocumentationService.isNotFoundBanner(result))
        {
            return ToolResult.error(PlatformDocumentationService.stripNotFoundBanner(result)).toJson();
        }

        return detailed ? result : condense(result);
    }

    /**
     * Produces the leaner 'concise' rendering from the full 'detailed' markdown.
     * <p>
     * Keeps every structural / actionable line — the H1 type/function header, the
     * {@code **Type Info:**} block, {@code **Collection element types:**}, the
     * {@code **Category:**} line, every section ({@code ## ...}) and member
     * ({@code ### ...}) heading, and the results-limit footer — so the full member
     * inventory and the headers asserted by callers/e2e survive. It drops only the
     * verbose per-member body: {@code **Parameters:**} and their bullet lines,
     * {@code **Overload N:**}, {@code **Returns:** ...}, {@code **Type:** ...},
     * {@code *Access: ...*}, {@code *Returns a value*} / {@code *Procedure ...*}
     * flags and {@code *No parameters*}.
     * <p>
     * Pass-through (not condensed) when the input is not a rendered doc: a
     * {@code ToolResult.error} JSON payload (starts with '{') or a soft "Error: ...
     * not found" banner. A rendered doc always begins with the H1 marker "# ".
     */
    private static String condense(String full)
    {
        if (full == null || !full.startsWith("# ")) //$NON-NLS-1$
        {
            // Not a rendered doc (error JSON or a soft not-found banner) -> verbatim.
            return full;
        }

        StringBuilder out = new StringBuilder();
        boolean inTypeInfo = false;
        boolean lastBlank = false;
        for (String line : full.split("\n", -1)) //$NON-NLS-1$
        {
            String trimmed = line.trim();

            // The "Type Info" bullets ("- Iterable: ...") look like parameter bullets,
            // so track the block explicitly: it opens on its header and closes at the
            // next blank line.
            if (trimmed.equals("**Type Info:**")) //$NON-NLS-1$
            {
                inTypeInfo = true;
            }
            else if (trimmed.isEmpty())
            {
                inTypeInfo = false;
            }

            boolean keep = shouldKeepLine(line, trimmed, inTypeInfo);

            if (!keep)
            {
                continue;
            }

            // Collapse runs of blank lines so dropped bodies do not leave gaps.
            if (trimmed.isEmpty())
            {
                if (lastBlank || out.length() == 0)
                {
                    continue;
                }
                lastBlank = true;
            }
            else
            {
                lastBlank = false;
            }
            out.append(line).append("\n"); //$NON-NLS-1$
        }
        return out.toString();
    }

    /**
     * Decides whether a single line of the 'detailed' markdown survives into the 'concise' rendering —
     * the keep-predicate of {@link #condense}. Keeps blank lines (collapsed by the caller), the H1/H2/H3
     * headings, the {@code **Type Info:**} header and its bullets (only while {@code inTypeInfo}), the
     * {@code **Collection element types:**} / {@code **Category:**} lines and the results-limit footer;
     * drops everything else. Pure predicate over the supplied line state.
     */
    private static boolean shouldKeepLine(String line, String trimmed, boolean inTypeInfo)
    {
        return trimmed.isEmpty() // blank lines kept (collapsed below)
            || line.startsWith("# ") //$NON-NLS-1$
            || line.startsWith("## ") //$NON-NLS-1$
            || line.startsWith("### ") //$NON-NLS-1$
            || trimmed.equals("**Type Info:**") //$NON-NLS-1$
            || (inTypeInfo && trimmed.startsWith("- ")) //$NON-NLS-1$
            || trimmed.startsWith("**Collection element types:**") //$NON-NLS-1$
            || trimmed.startsWith("**Category:**") //$NON-NLS-1$
            || trimmed.startsWith("*Results limited to "); //$NON-NLS-1$
    }
}

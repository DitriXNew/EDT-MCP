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
            .stringProperty("typeName", //$NON-NLS-1$
                "Type or symbol name, English or Russian (e.g. 'ValueTable').", true) //$NON-NLS-1$
            .enumProperty("category", //$NON-NLS-1$
                "'type' (platform types) or 'builtin' (global functions). Default: 'type'.", //$NON-NLS-1$
                "type", "builtin") //$NON-NLS-1$ //$NON-NLS-2$
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
                "concise", "detailed") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# get_platform_documentation\n\n" //$NON-NLS-1$
            + "Returns 1C:Enterprise *platform* API documentation (the built-in language and " //$NON-NLS-1$
            + "type system), not configuration metadata. Use the metadata tools for catalogs, " //$NON-NLS-1$
            + "documents and your own objects; use this tool for platform types like " //$NON-NLS-1$
            + "ValueTable / Array / Structure and for global built-in functions.\n\n" //$NON-NLS-1$
            + "## When to use\n\n" //$NON-NLS-1$
            + "- You need the exact signature, parameters or return value of a platform method " //$NON-NLS-1$
            + "or property.\n" //$NON-NLS-1$
            + "- You are unsure which members a platform type exposes.\n" //$NON-NLS-1$
            + "- You need a global built-in function's description.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- **typeName** (required): the type or symbol name. Both the English name and its " //$NON-NLS-1$
            + "Russian equivalent are accepted (e.g. the English 'ValueTable' or its Russian name).\n" //$NON-NLS-1$
            + "- **category**: `type` (platform types, the default) or `builtin` (global " //$NON-NLS-1$
            + "built-in functions). For `builtin` only `typeName` and `language` apply; the " //$NON-NLS-1$
            + "member filters are ignored.\n" //$NON-NLS-1$
            + "- **memberName**: filter the returned members by name, partial (substring) " //$NON-NLS-1$
            + "match. Example: 'Add', 'Insert', 'Count'.\n" //$NON-NLS-1$
            + "- **memberType**: one of `method`, `property`, `constructor`, `event`, `all`. " //$NON-NLS-1$
            + "Default `all`. An out-of-set value is rejected with an error rather than " //$NON-NLS-1$
            + "silently matching nothing.\n" //$NON-NLS-1$
            + "- **projectName**: an EDT project name used to pin which platform version's " //$NON-NLS-1$
            + "documentation to read. Optional; omit to use the default.\n" //$NON-NLS-1$
            + "- **limit**: maximum number of results. Default 50, clamped to a maximum of " //$NON-NLS-1$
            + "200.\n" //$NON-NLS-1$
            + "- **language**: `en` (default) or `ru` — the language of the returned " //$NON-NLS-1$
            + "documentation text.\n" //$NON-NLS-1$
            + "- **responseFormat**: `concise` (default) or `detailed`. `concise` keeps " //$NON-NLS-1$
            + "the type/function header, the Type Info block and every section and member " //$NON-NLS-1$
            + "heading (so you see the full member inventory), but omits the verbose " //$NON-NLS-1$
            + "per-member body — parameter lists, overloads, return/property types and " //$NON-NLS-1$
            + "access flags. Re-query with `detailed` (optionally narrowed by `memberName`) " //$NON-NLS-1$
            + "to get the full signatures.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "- All members of a type: `typeName='ValueTable'`.\n" //$NON-NLS-1$
            + "- A specific method: `typeName='Array', memberName='Add'`.\n" //$NON-NLS-1$
            + "- Only methods: `typeName='ValueTable', memberType='method'`.\n" //$NON-NLS-1$
            + "- Russian output: `typeName='Structure', language='ru'`.\n" //$NON-NLS-1$
            + "- A built-in function: `category='builtin', typeName='Message'`.\n\n" //$NON-NLS-1$
            + "## Notes\n\n" //$NON-NLS-1$
            + "- Resolution is bilingual on `typeName`: an English or Russian platform name " //$NON-NLS-1$
            + "resolves to the same type. The `language` parameter controls only the output " //$NON-NLS-1$
            + "text, not which name you may pass in.\n" //$NON-NLS-1$
            + "- Output is Markdown.\n"; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String typeName = JsonUtils.extractStringArgument(params, "typeName"); //$NON-NLS-1$
        if (typeName != null && !typeName.isEmpty())
        {
            return "doc-" + typeName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "platform-documentation.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String typeName = JsonUtils.extractStringArgument(params, "typeName"); //$NON-NLS-1$
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
        String err = JsonUtils.requireArgument(params, "typeName"); //$NON-NLS-1$
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

            boolean keep = trimmed.isEmpty() // blank lines kept (collapsed below)
                || line.startsWith("# ") //$NON-NLS-1$
                || line.startsWith("## ") //$NON-NLS-1$
                || line.startsWith("### ") //$NON-NLS-1$
                || trimmed.equals("**Type Info:**") //$NON-NLS-1$
                || (inTypeInfo && trimmed.startsWith("- ")) //$NON-NLS-1$
                || trimmed.startsWith("**Collection element types:**") //$NON-NLS-1$
                || trimmed.startsWith("**Category:**") //$NON-NLS-1$
                || trimmed.startsWith("*Results limited to "); //$NON-NLS-1$

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
}

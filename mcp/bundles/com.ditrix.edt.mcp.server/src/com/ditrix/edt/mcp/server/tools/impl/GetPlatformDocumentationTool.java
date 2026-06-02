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

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Get platform documentation for 1C:Enterprise types, methods, properties, " + //$NON-NLS-1$
               "and built-in functions. " + //$NON-NLS-1$
               "Examples: typeName='ValueTable', typeName='Array' memberName='Add'"; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("typeName", //$NON-NLS-1$
                "Type or symbol name (e.g. 'ValueTable', 'Array', 'Structure'). " + //$NON-NLS-1$
                "Supports both English and Russian names.", true) //$NON-NLS-1$
            .stringProperty("category", //$NON-NLS-1$
                "Category: 'type' (platform types like ValueTable), " + //$NON-NLS-1$
                "'builtin' (built-in functions). Default: 'type'") //$NON-NLS-1$
            .stringProperty("memberName", //$NON-NLS-1$
                "Filter by member name (method/property). Supports partial match. " + //$NON-NLS-1$
                "Example: 'Add', 'Insert', 'Count'") //$NON-NLS-1$
            .stringProperty("memberType", //$NON-NLS-1$
                "Filter by member type: 'method', 'property', 'constructor', 'event', 'all'. " + //$NON-NLS-1$
                "Default: 'all'") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name to determine platform version. Optional.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum number of results to return. Default: 50") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Output language: 'en' (English) or 'ru' (Russian). Default: 'en'") //$NON-NLS-1$
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
        if (language == null || language.isEmpty())
        {
            language = "en"; //$NON-NLS-1$
        }

        int limit = JsonUtils.extractIntArgument(params, "limit", 50); //$NON-NLS-1$
        limit = Pagination.clampLimit(limit, 200);

        boolean useRussian = "ru".equalsIgnoreCase(language); //$NON-NLS-1$

        PlatformDocumentationService service = new PlatformDocumentationService();

        // Execute based on category
        switch (category.toLowerCase())
        {
            case CATEGORY_TYPE:
                return service.getTypeDocumentation(typeName, memberName, memberType, projectName, limit, useRussian);
            case CATEGORY_BUILTIN:
                return service.getBuiltinFunctionDocumentation(typeName, useRussian);
            default:
                return ToolResult.error("Unknown category '" + category + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                       "Supported: 'type', 'builtin'").toJson(); //$NON-NLS-1$
        }
    }
}

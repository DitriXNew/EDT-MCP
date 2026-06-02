/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.symbol.SymbolInfoService;

/**
 * Tool to get type/hover information about a symbol at a specific position in a BSL file.
 * Returns the same information that EDT shows on mouse hover, including inferred types.
 * Falls back to structural EObject analysis if hover is not available.
 * <p>
 * This class is a thin adapter over the IMcpTool contract: it owns the schema,
 * reads/validates the parameters and then delegates the domain logic (editor/hover
 * resolution and the EMF fallback) to {@link SymbolInfoService}.
 */
public class GetSymbolInfoTool implements IMcpTool
{
    public static final String NAME = "get_symbol_info"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Get type/hover info about a symbol at a position in a BSL module. " + //$NON-NLS-1$
               "Returns inferred types, signatures, and documentation."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", //$NON-NLS-1$
                "BSL file path from src/, e.g. 'CommonModules/MyModule/Module.bsl'", true) //$NON-NLS-1$
            .integerProperty("line", "Line number (1-based)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("column", "Column number (1-based)", true) //$NON-NLS-1$ //$NON-NLS-2$
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
        String lineStr = JsonUtils.extractStringArgument(params, "line"); //$NON-NLS-1$
        String columnStr = JsonUtils.extractStringArgument(params, "column"); //$NON-NLS-1$
        return "symbol-info-" + (lineStr != null ? lineStr : "0") + //$NON-NLS-1$ //$NON-NLS-2$
               "-" + (columnStr != null ? columnStr : "0") + ".md"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArguments(params, "projectName", "filePath"); //$NON-NLS-1$ //$NON-NLS-2$
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String lineStr = JsonUtils.extractStringArgument(params, "line"); //$NON-NLS-1$
        String columnStr = JsonUtils.extractStringArgument(params, "column"); //$NON-NLS-1$

        int line;
        int column;
        try
        {
            // Handle both integer ("33") and double ("33.0") formats
            line = (int) Double.parseDouble(lineStr);
            column = (int) Double.parseDouble(columnStr);
        }
        catch (NumberFormatException | NullPointerException e)
        {
            return ToolResult.error("Invalid line or column number").toJson(); //$NON-NLS-1$
        }

        if (line < 1 || column < 1)
        {
            return ToolResult.error("Line and column must be >= 1").toJson(); //$NON-NLS-1$
        }

        return new SymbolInfoService().getSymbolInfo(projectName, filePath, line, column);
    }
}

/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;


import com.ditrix.edt.mcp.server.utils.EdtVersion;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to get 1C:EDT version.
 */
public class GetEdtVersionTool implements IMcpTool
{
    public static final String NAME = "get_edt_version"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Identify the installed 1C:EDT version. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('get_edt_version')."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.TEXT;
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        return EdtVersion.getEdtVersion();
    }
    
}

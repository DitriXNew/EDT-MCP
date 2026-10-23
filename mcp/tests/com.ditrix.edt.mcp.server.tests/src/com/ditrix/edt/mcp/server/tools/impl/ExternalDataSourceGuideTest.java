/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;

/** Render through the real wire tool, including its schema and complete guide body. */
public class ExternalDataSourceGuideTest
{
    @After public void clearRegistry()
    {
        McpToolRegistry.getInstance().clear();
    }

    @Test public void fullModifyGuideStillFitsTheOutputCap()
    {
        McpToolRegistry.getInstance().register(new ModifyMetadataTool());
        String rendered = new GetToolGuideTool().execute(Map.of("toolName", "modify_metadata"));
        System.out.println("EDS448 modify_metadata rendered characters=" + rendered.length());
        assertTrue(rendered, rendered.contains("External data source structure"));
        assertTrue("Rendered guide length: " + rendered.length(), rendered.length() <= 100_000);
        assertFalse(rendered.contains("OUTPUT TRUNCATED"));
    }

    @Test public void createGuideOwnsTheCompleteEdsContract()
    {
        McpToolRegistry.getInstance().register(new CreateMetadataTool());
        String rendered = new GetToolGuideTool().execute(Map.of("toolName", "create_metadata"));
        for (String text : new String[] { "## External data source structure", "parentDataSource",
            "nameInDataSource", "String(10)", "allowNull=true", "External Data Source wizard",
            "keyFields", "assignable:true", "rights[].object" })
        {
            assertTrue(text, rendered.contains(text));
        }
    }
}

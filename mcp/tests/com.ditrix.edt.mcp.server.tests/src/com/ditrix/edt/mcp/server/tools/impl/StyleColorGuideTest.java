/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.GuideLoader;

/** The full guides describe the platform color forms at the paths that accept them. */
public class StyleColorGuideTest
{
    @After
    public void clearRegistry()
    {
        McpToolRegistry.getInstance().clear();
        GuideLoader.clearCache();
    }

    @Test
    public void testFullModifyMetadataGuideNamesPlatformColorsAndFitsTheOutputLimit()
    {
        String guide = guide(new ModifyMetadataTool());
        System.out.println("R7_MODIFY_METADATA_GUIDE_CHARS=" + guide.length());
        assertTrue("must load the actual full guide", guide.length() > 90_000);
        assertTrue("full guide must fit the tool output limit: " + guide.length(), guide.length() <= 100_000);
        String bullet = guide.lines().filter(line -> line.startsWith("- Color (named):")).findFirst().orElseThrow();
        assertTrue(bullet, bullet.contains("a configuration item first, else the platform color"));
        assertTrue(bullet, bullet.contains("{color:{web:'Red'}}"));
        assertTrue(bullet, bullet.contains("{color:{windows:'WindowText'}}"));
        assertFalse(bullet, bullet.contains("must exist in the project configuration"));
    }

    @Test
    public void testScaleColorStorageAdviceIsOnTheOutputParameterRow()
    {
        String guide = guide(new DcsTool());
        String advice = "The `ChartType.ScaleColor` output parameter refuses `{color:'auto'}` because "
            + "EDT cannot reload it; pass an explicit RGB color or omit the parameter.";
        String output = guide.lines().filter(line -> line.startsWith("| `outputParameter` |")).findFirst().orElseThrow();
        String appearance = guide.lines().filter(line -> line.startsWith("| `conditionalAppearance` |")).findFirst().orElseThrow();
        assertTrue(output, output.contains(advice));
        assertFalse(appearance, appearance.contains("ChartType.ScaleColor"));
        assertEquals(1, guide.split(java.util.regex.Pattern.quote(advice), -1).length - 1);
    }

    private static String guide(com.ditrix.edt.mcp.server.tools.IMcpTool target)
    {
        GuideLoader.clearCache();
        McpToolRegistry.getInstance().register(target);
        // No section or mode: measure exactly GetToolGuideTool.execute's full result.
        return new GetToolGuideTool().execute(Map.of("toolName", target.getName()));
    }
}

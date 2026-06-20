/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetTemplateScreenshotTool}.
 * <p>
 * Covers tool metadata, the IMAGE response type, the input schema, the guide pointer, the result
 * file name, and the Display-free argument validation ("projectName is required" /
 * "templatePath is required") that returns before any {@code Display} access. The actual render
 * needs a live workbench with the template editor and is covered by the E2E suite.
 */
public class GetTemplateScreenshotToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_template_screenshot", new GetTemplateScreenshotTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetTemplateScreenshotTool.NAME, new GetTemplateScreenshotTool().getName());
    }

    @Test
    public void testResponseTypeImage()
    {
        assertEquals(ResponseType.IMAGE, new GetTemplateScreenshotTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndPointsAtGuide()
    {
        String desc = new GetTemplateScreenshotTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        // The slim description must point at the on-demand guide (the shared tool convention).
        assertTrue(desc.contains("get_tool_guide('get_template_screenshot')")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetTemplateScreenshotTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"templatePath\"")); //$NON-NLS-1$
        // Both are required.
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        String guide = new GetTemplateScreenshotTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        // Detail kept out of the slim description/schema must live in the guide.
        assertTrue(guide.contains("CommonTemplate")); //$NON-NLS-1$
        assertTrue(guide.contains("SpreadsheetDocument")); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameFromTemplatePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("templatePath", "CommonTemplate.PrintForm"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("PrintForm.png", new GetTemplateScreenshotTool().getResultFileName(params)); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameDefault()
    {
        assertEquals("template.png", //$NON-NLS-1$
            new GetTemplateScreenshotTool().getResultFileName(new HashMap<>()));
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("templatePath", "CommonTemplate.PrintForm"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetTemplateScreenshotTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingTemplatePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetTemplateScreenshotTool().execute(params);
        assertTrue(result.contains("templatePath is required")); //$NON-NLS-1$
    }
}

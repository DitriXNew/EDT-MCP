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
 * Tests for {@link CreateInfobaseTool}.
 * <p>
 * Covers tool metadata, schema parity, and the argument-validation guards that
 * execute BEFORE any workspace or platform-services access. The real create path
 * (platform probe -> background Job -> IInfobaseCreationOperation -> associate) needs
 * a live EDT with a registered 1C platform runtime and is covered by the e2e suite.
 */
public class CreateInfobaseToolTest
{
    @Test
    public void testName()
    {
        assertEquals("create_infobase", new CreateInfobaseTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CreateInfobaseTool.NAME, new CreateInfobaseTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CreateInfobaseTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndContainsToolGuideHint()
    {
        String desc = new CreateInfobaseTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_infobase')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAllParameters()
    {
        String schema = new CreateInfobaseTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectName", schema.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare infobaseFile", schema.contains("\"infobaseFile\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare infobaseName", schema.contains("\"infobaseName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare platform", schema.contains("\"platform\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare setDefault", schema.contains("\"setDefault\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRequiredParametersInSchema()
    {
        String schema = new CreateInfobaseTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("infobaseFile must be required", tail.contains("\"infobaseFile\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // Optional parameters must NOT be in the required array.
        // The required block is between the first '[' and ']' after "required".
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        if (open >= 0 && close > open)
        {
            String requiredBlock = schema.substring(open, close + 1);
            assertTrue("infobaseName must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"infobaseName\"")); //$NON-NLS-1$
            assertTrue("platform must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"platform\"")); //$NON-NLS-1$
            assertTrue("setDefault must NOT be required", //$NON-NLS-1$
                !requiredBlock.contains("\"setDefault\"")); //$NON-NLS-1$
        }
    }

    @Test
    public void testOutputSchemaDeclaresExpectedFields()
    {
        String schema = new CreateInfobaseTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare applicationId", schema.contains("\"applicationId\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare infobaseFile", schema.contains("\"infobaseFile\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare applications", schema.contains("\"applications\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideExists()
    {
        String guide = new CreateInfobaseTool().getGuide();
        assertNotNull("guide must not be null", guide); //$NON-NLS-1$
        assertTrue("guide must not be empty", guide.length() > 0); //$NON-NLS-1$
        assertTrue("guide must document infobaseFile parameter", guide.contains("infobaseFile")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must mention platform requirement", //$NON-NLS-1$
            guide.toLowerCase().contains("platform")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("infobaseFile", "C:\\infobases\\test"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertTrue("missing projectName must produce an error", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingInfobaseFileIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new CreateInfobaseTool().execute(params);
        assertTrue("missing infobaseFile must produce an error", //$NON-NLS-1$
            result.contains("infobaseFile is required")); //$NON-NLS-1$
    }

    @Test
    public void testBothRequiredParamsMissingNamedFirst()
    {
        Map<String, String> params = new HashMap<>();
        // With no params, projectName is checked first.
        String result = new CreateInfobaseTool().execute(params);
        assertTrue("missing both params — projectName checked first", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }
}

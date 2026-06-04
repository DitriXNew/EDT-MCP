/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight contract tests for {@link CreateMetadataTool}: tool metadata and JSON schema,
 * without needing the Eclipse/EDT runtime. The {@code execute()} path requires a live workbench
 * and BM model, so the create / duplicate / property-rejection behaviour is covered by the E2E suite.
 */
public class CreateMetadataToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("create_metadata", new CreateMetadataTool().getName()); //$NON-NLS-1$
        assertEquals(CreateMetadataTool.NAME, new CreateMetadataTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new CreateMetadataTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new CreateMetadataTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('create_metadata')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"properties\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"expectedNotExists\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", tail.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOptionalParametersNotRequired()
    {
        String schema = new CreateMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("properties must not be required", tail.contains("\"properties\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("expectedNotExists must not be required", //$NON-NLS-1$
            tail.contains("\"expectedNotExists\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideCarriesKeyDetail()
    {
        String guide = new CreateMetadataTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        // bilingual synonym detail retained
        assertTrue("guide should keep the language CODE detail", guide.contains("language CODE")); //$NON-NLS-1$ //$NON-NLS-2$
        // member kinds documented
        assertTrue("guide should list member kinds", guide.contains("EnumValue")); //$NON-NLS-1$ //$NON-NLS-2$
        // the nested-member limitation is disclosed
        assertTrue("guide should disclose the nested-member limitation", //$NON-NLS-1$
            guide.contains("not yet supported")); //$NON-NLS-1$
    }
}

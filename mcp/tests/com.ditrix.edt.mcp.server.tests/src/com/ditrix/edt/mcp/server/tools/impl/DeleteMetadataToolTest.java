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
 * Lightweight contract tests for {@link DeleteMetadataTool}: tool metadata and JSON schema, without
 * the Eclipse/EDT runtime. The execute() path (refactoring preview / perform) needs a live workbench
 * and BM model, so it is covered by the E2E suite.
 */
public class DeleteMetadataToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("delete_metadata", new DeleteMetadataTool().getName()); //$NON-NLS-1$
        assertEquals(DeleteMetadataTool.NAME, new DeleteMetadataTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new DeleteMetadataTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new DeleteMetadataTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('delete_metadata')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new DeleteMetadataTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"confirm\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new DeleteMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", tail.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testConfirmIsOptional()
    {
        String schema = new DeleteMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue(requiredIdx >= 0);
        String tail = schema.substring(requiredIdx);
        assertFalse("confirm must not be required", tail.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideCarriesKeyDetail()
    {
        String guide = new DeleteMetadataTool().getGuide();
        assertNotNull(guide);
        assertFalse("guide must be non-empty", guide.isEmpty()); //$NON-NLS-1$
        assertTrue("guide should warn it is a cascading delete", guide.contains("Think twice")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should document the two-phase workflow", guide.contains("confirm=true")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide should list member kinds", guide.contains("EnumValue")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

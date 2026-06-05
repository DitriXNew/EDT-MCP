/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight contract tests for {@link ModifyMetadataTool}: tool metadata and JSON schema, without
 * the Eclipse/EDT runtime. The execute() path (validation + BM write) needs a live workbench and BM
 * model, so the validation / apply behaviour is covered by the E2E suite.
 */
public class ModifyMetadataToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("modify_metadata", new ModifyMetadataTool().getName()); //$NON-NLS-1$
        assertEquals(ModifyMetadataTool.NAME, new ModifyMetadataTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new ModifyMetadataTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new ModifyMetadataTool().getDescription();
        assertNotNull(desc);
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('modify_metadata')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new ModifyMetadataTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"properties\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new ModifyMetadataTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", tail.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("properties must be required", tail.contains("\"properties\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideCarriesValidationDetail()
    {
        String guide = new ModifyMetadataTool().getGuide();
        assertNotNull(guide);
        // the actionable-validation contract is documented
        assertTrue("guide should explain the allowed-values validation", //$NON-NLS-1$
            guide.contains("allowed")); //$NON-NLS-1$
        assertTrue("guide should steer discovery to get_metadata_details(assignable:true)", //$NON-NLS-1$
            guide.contains("assignable:true")); //$NON-NLS-1$
        // renaming is refused with a pointer to rename_metadata_object
        assertTrue("guide should point a rename at rename_metadata_object", //$NON-NLS-1$
            guide.contains("rename_metadata_object")); //$NON-NLS-1$
    }
}

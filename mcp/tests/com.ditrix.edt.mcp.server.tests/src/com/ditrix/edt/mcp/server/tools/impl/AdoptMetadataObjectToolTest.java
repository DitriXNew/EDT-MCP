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
 * Lightweight contract tests for {@link AdoptMetadataObjectTool}: tool metadata and JSON schema,
 * without needing the Eclipse/EDT runtime. The {@code execute()} path requires a live workbench +
 * BM model + an extension project, so the actual adopt behaviour (objectBelonging=ADOPTED,
 * extendedConfigurationObject link, multi-extension selection) is covered by the E2E suite.
 */
public class AdoptMetadataObjectToolTest
{
    @Test
    public void testNameConstant()
    {
        assertEquals("adopt_metadata_object", new AdoptMetadataObjectTool().getName()); //$NON-NLS-1$
        assertEquals(AdoptMetadataObjectTool.NAME, new AdoptMetadataObjectTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.JSON, new AdoptMetadataObjectTool().getResponseType());
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        String desc = new AdoptMetadataObjectTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue("description should point to get_tool_guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('adopt_metadata_object')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        String schema = new AdoptMetadataObjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"extensionProjectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new AdoptMetadataObjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", tail.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // extensionProjectName is optional (auto-selected when there is a single extension).
        assertFalse("extensionProjectName must NOT be required", //$NON-NLS-1$
            tail.contains("\"extensionProjectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresContract()
    {
        String schema = new AdoptMetadataObjectTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"action\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectBelonging\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"persisted\"")); //$NON-NLS-1$
    }
}

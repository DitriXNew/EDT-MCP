/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight tests for {@link TranslateConfigurationTool} that exercise tool
 * metadata and JSON schema without needing the Eclipse runtime.
 */
public class TranslateConfigurationToolTest
{
    @Test
    public void testName()
    {
        assertEquals("translate_configuration", new TranslateConfigurationTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.MARKDOWN, new TranslateConfigurationTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new TranslateConfigurationTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed =
            GsonProvider.get().fromJson(new TranslateConfigurationTool().getInputSchema(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parsed.get("properties"); //$NON-NLS-1$
        assertNotNull("schema must declare properties", properties); //$NON-NLS-1$
        assertTrue(properties.containsKey("projectName")); //$NON-NLS-1$
        assertTrue(properties.containsKey("targetLanguages")); //$NON-NLS-1$
    }

    @Test
    public void testBothParamsRequired()
    {
        // Parse the schema and verify the required array contains exactly
        // both parameters — string-substring matching would also match the
        // properties section and is fragile to serialization-order changes.
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed =
            GsonProvider.get().fromJson(new TranslateConfigurationTool().getInputSchema(), Map.class);
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) parsed.get("required"); //$NON-NLS-1$
        assertNotNull("schema must declare required array", required); //$NON-NLS-1$
        assertEquals("required must be exactly [projectName, targetLanguages]", //$NON-NLS-1$
            Arrays.asList("projectName", "targetLanguages"), required); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

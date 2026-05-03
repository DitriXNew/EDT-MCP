/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Lightweight tests for {@link GetTranslationProjectInfoTool} that exercise
 * tool metadata and JSON schema without needing the Eclipse runtime.
 */
public class GetTranslationProjectInfoToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_translation_project_info", new GetTranslationProjectInfoTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.MARKDOWN, new GetTranslationProjectInfoTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetTranslationProjectInfoTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsProjectName()
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed =
            GsonProvider.get().fromJson(new GetTranslationProjectInfoTool().getInputSchema(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parsed.get("properties"); //$NON-NLS-1$
        assertNotNull("schema must declare properties", properties); //$NON-NLS-1$
        assertTrue(properties.containsKey("projectName")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameRequired()
    {
        // Parse the schema and verify the required array contains exactly
        // projectName — string-substring matching would also match the
        // properties section and is fragile to serialization-order changes.
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed =
            GsonProvider.get().fromJson(new GetTranslationProjectInfoTool().getInputSchema(), Map.class);
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) parsed.get("required"); //$NON-NLS-1$
        assertNotNull("schema must declare required array", required); //$NON-NLS-1$
        assertEquals("required must be exactly [projectName]", //$NON-NLS-1$
            Collections.singletonList("projectName"), required); //$NON-NLS-1$
    }
}

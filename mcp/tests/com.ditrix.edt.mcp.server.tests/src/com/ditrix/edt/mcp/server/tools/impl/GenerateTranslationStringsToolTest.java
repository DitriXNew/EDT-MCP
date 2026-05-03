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
 * Lightweight tests for {@link GenerateTranslationStringsTool} that exercise
 * tool metadata and JSON schema without needing the Eclipse runtime.
 */
public class GenerateTranslationStringsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("generate_translation_strings", new GenerateTranslationStringsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.MARKDOWN, new GenerateTranslationStringsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GenerateTranslationStringsTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsAllParameters()
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed =
            GsonProvider.get().fromJson(new GenerateTranslationStringsTool().getInputSchema(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) parsed.get("properties"); //$NON-NLS-1$
        assertNotNull("schema must declare properties", properties); //$NON-NLS-1$
        assertTrue(properties.containsKey("projectName")); //$NON-NLS-1$
        assertTrue(properties.containsKey("targetLanguages")); //$NON-NLS-1$
        assertTrue(properties.containsKey("storageId")); //$NON-NLS-1$
        assertTrue(properties.containsKey("collectInterface")); //$NON-NLS-1$
        assertTrue(properties.containsKey("collectModel")); //$NON-NLS-1$
        assertTrue(properties.containsKey("collectModelType")); //$NON-NLS-1$
        assertTrue(properties.containsKey("fillUpType")); //$NON-NLS-1$
        assertTrue(properties.containsKey("providerId")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredArrayMarksOnlyMandatoryParams()
    {
        // Parse the schema and verify the required array contains exactly
        // the mandatory parameters. Asserting on the parsed structure (rather
        // than substring-matching the JSON text) keeps the test stable across
        // serialization-order changes in JsonSchemaBuilder.
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed =
            GsonProvider.get().fromJson(new GenerateTranslationStringsTool().getInputSchema(), Map.class);
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) parsed.get("required"); //$NON-NLS-1$
        assertNotNull("schema must declare required array", required); //$NON-NLS-1$
        assertEquals("required must be exactly [projectName, targetLanguages]", //$NON-NLS-1$
            Arrays.asList("projectName", "targetLanguages"), required); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import org.junit.Test;

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
        String schema = new GenerateTranslationStringsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"targetLanguages\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"storageId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"collectInterface\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"collectModel\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"collectModelType\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fillUpType\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"providerId\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredArrayMarksOnlyMandatoryParams()
    {
        String schema = new GenerateTranslationStringsTool().getInputSchema();
        // Required array must contain projectName + targetLanguages and nothing else.
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("required must include projectName", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("required must include targetLanguages", tail.contains("\"targetLanguages\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("storageId must NOT be required", tail.contains("\"storageId\",") //$NON-NLS-1$ //$NON-NLS-2$
            || tail.contains(",\"storageId\"")); //$NON-NLS-1$
        assertFalse("providerId must NOT be required", tail.contains("\"providerId\",") //$NON-NLS-1$ //$NON-NLS-2$
            || tail.contains(",\"providerId\"")); //$NON-NLS-1$
    }
}

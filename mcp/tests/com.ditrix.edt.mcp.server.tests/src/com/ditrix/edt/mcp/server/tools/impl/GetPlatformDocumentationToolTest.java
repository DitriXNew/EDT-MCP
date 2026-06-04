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
 * Tests for {@link GetPlatformDocumentationTool}.
 * <p>
 * Covers tool metadata, the input schema, and the typeName required-argument
 * validation that returns before any documentation lookup. Resolving platform
 * documentation needs the bundled doc data / platform version and is covered by
 * the E2E suite.
 */
public class GetPlatformDocumentationToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_platform_documentation", new GetPlatformDocumentationTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetPlatformDocumentationTool.NAME, new GetPlatformDocumentationTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetPlatformDocumentationTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetPlatformDocumentationTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        // The exhaustive detail moved out of the slim description/schema into the
        // on-demand guide channel; assert it is present and non-empty there.
        String guide = new GetPlatformDocumentationTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("category")); //$NON-NLS-1$
        assertTrue(guide.contains("builtin")); //$NON-NLS-1$
        assertTrue(guide.contains("memberType")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetPlatformDocumentationTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"typeName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"category\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"memberName\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaClosedVocabulariesAreEnums()
    {
        // category, memberType and language are closed vocabularies and are
        // advertised as JSON Schema enums.
        String schema = new GetPlatformDocumentationTool().getInputSchema();
        assertTrue(schema.contains("\"enum\":[\"type\",\"builtin\"]")); //$NON-NLS-1$
        assertTrue(schema.contains(
            "\"enum\":[\"method\",\"property\",\"constructor\",\"event\",\"all\"]")); //$NON-NLS-1$
        assertTrue(schema.contains("\"enum\":[\"en\",\"ru\"]")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresResponseFormatEnum()
    {
        // The output-size control is advertised as a closed concise|detailed enum so
        // schema-driven clients can pick a format. The execute() read of the value is
        // schema<->code parity enforced.
        String schema = new GetPlatformDocumentationTool().getInputSchema();
        assertTrue(schema.contains("\"responseFormat\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"enum\":[\"concise\",\"detailed\"]")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsResponseFormat()
    {
        // The guide explains the concise default and what it omits vs detailed.
        String guide = new GetPlatformDocumentationTool().getGuide();
        assertTrue(guide.contains("responseFormat")); //$NON-NLS-1$
        assertTrue(guide.contains("concise")); //$NON-NLS-1$
        assertTrue(guide.contains("detailed")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live access needed) ====================

    @Test
    public void testMissingTypeName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetPlatformDocumentationTool().execute(params);
        assertTrue(result.contains("typeName is required")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidMemberTypeRejected()
    {
        // typeName is supplied so validation passes the required-argument gate and
        // reaches the memberType check, which runs before any live doc lookup.
        Map<String, String> params = new HashMap<>();
        params.put("typeName", "ValueTable"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("memberType", "field"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetPlatformDocumentationTool().execute(params);
        // Delimiter-free substrings only (Gson HTML-escapes apostrophes / '>=').
        // The message now ECHOES the rejected value and lists the valid set.
        assertTrue(result.contains("Invalid memberType")); //$NON-NLS-1$
        assertTrue("rejected value must be echoed", result.contains("field")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.contains("method")); //$NON-NLS-1$
        assertTrue(result.contains("property")); //$NON-NLS-1$
    }
}

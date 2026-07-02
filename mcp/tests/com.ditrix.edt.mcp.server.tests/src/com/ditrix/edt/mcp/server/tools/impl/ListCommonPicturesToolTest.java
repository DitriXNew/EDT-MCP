/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Ratchet tests for {@link ListCommonPicturesTool}.
 * <p>
 * Covers tool metadata (name/constant, response type, description-steers-to-guide, input schema
 * with schema&lt;-&gt;execute parameter parity + the lowerCamelCase convention, the required array,
 * the null output schema for a MARKDOWN tool, the result file name, the guide), and the two
 * Display-free error paths {@code execute(Map)} reaches BEFORE any UI or BM access: a missing
 * {@code projectName} (required-argument guard) and an unknown project (value-naming
 * "Project not found" via {@code ProjectContext}). The variant enumeration and Markdown formatting
 * need a live EDT workspace + a CommonPicture and are covered by the E2E suite (Slice D).
 */
public class ListCommonPicturesToolTest
{
    /** The exact set of input parameters {@code execute()} reads. Keep in lockstep with the schema. */
    private static final String[] EXECUTE_PARAMS = {"projectName", "language", "limit"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    // ==================== Metadata: name / response type ====================

    @Test
    public void testName()
    {
        assertEquals("list_common_pictures", new ListCommonPicturesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ListCommonPicturesTool.NAME, new ListCommonPicturesTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        // The tool renders a Markdown overview (per-picture variant tables), so it must be MARKDOWN.
        assertEquals(ResponseType.MARKDOWN, new ListCommonPicturesTool().getResponseType());
    }

    // ==================== Metadata: description ====================

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ListCommonPicturesTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testDescriptionSteersToGuideAndSiblingTool()
    {
        // The lean description must point at the on-demand guide and at the PNG-export sibling.
        String desc = new ListCommonPicturesTool().getDescription();
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('list_common_pictures')")); //$NON-NLS-1$
        assertTrue("description must point at export_common_picture for image bytes", //$NON-NLS-1$
            desc.contains("export_common_picture")); //$NON-NLS-1$
    }

    // ==================== Metadata: input schema ====================

    @Test
    public void testSchemaDeclaresAllExecuteParams()
    {
        String schema = new ListCommonPicturesTool().getInputSchema();
        assertNotNull(schema);
        for (String param : EXECUTE_PARAMS)
        {
            assertTrue("input schema must declare execute() param: " + param, //$NON-NLS-1$
                schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testSchemaDeclaresNoExtraParams()
    {
        // Parity (schema -> execute): every property name in the schema must be one execute() reads.
        String schema = new ListCommonPicturesTool().getInputSchema();
        for (String declared : declaredPropertyNames(schema))
        {
            assertTrue("input schema declares a param execute() does not read: " + declared, //$NON-NLS-1$
                contains(EXECUTE_PARAMS, declared));
        }
    }

    @Test
    public void testAllParamsLowerCamelCase()
    {
        String schema = new ListCommonPicturesTool().getInputSchema();
        for (String declared : declaredPropertyNames(schema))
        {
            assertTrue("param must be lowerCamelCase: " + declared, isLowerCamelCase(declared)); //$NON-NLS-1$
        }
    }

    @Test
    public void testProjectNameIsTheOnlyRequiredParam()
    {
        // projectName is the only required parameter; language and limit must NOT be in the required array.
        String schema = new ListCommonPicturesTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("language must NOT be required", requiredBlock.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("limit must NOT be required", requiredBlock.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Metadata: output schema ====================

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        // MARKDOWN tools return content, not structuredContent, so they must inherit the IMcpTool
        // default null output schema (over-declaring one would lie about a structured envelope).
        assertNull("markdown tool must not declare an output schema", //$NON-NLS-1$
            new ListCommonPicturesTool().getOutputSchema());
    }

    // ==================== Metadata: result file name (both branches, no workspace) ====================

    @Test
    public void testResultFileNameUsesLowercasedProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("common-pictures-myproject.md", //$NON-NLS-1$
            new ListCommonPicturesTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameFallbackWhenProjectNameMissing()
    {
        Map<String, String> params = new HashMap<>();
        assertEquals("common-pictures.md", //$NON-NLS-1$
            new ListCommonPicturesTool().getResultFileName(params));
    }

    // ==================== Metadata: guide ====================

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The full per-tool detail lives in the on-demand guide. It must be non-empty and carry the
        // migrated specifics: the column set, the no-base64 rule, and the edt-bridge attribution.
        String guide = new ListCommonPicturesTool().getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
        assertTrue("guide must document the PictureDirection column", //$NON-NLS-1$
            guide.contains("PictureDirection")); //$NON-NLS-1$
        assertTrue("guide must point at export_common_picture for image bytes", //$NON-NLS-1$
            guide.contains("export_common_picture")); //$NON-NLS-1$
        assertTrue("guide must credit edt-bridge (edt_picture_export, Apache-2.0)", //$NON-NLS-1$
            guide.contains("edt-bridge") && guide.contains("edt_picture_export")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (returns before any UI / BM access) ====================

    @Test
    public void testMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ListCommonPicturesTool().execute(params);
        assertTrue("missing projectName must produce a 'projectName is required' error", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue("error payload must be a failure JSON", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingProjectNameCarriesDiscoveryHint()
    {
        // The required-argument guard appends a discovery hint pointing at list_projects.
        Map<String, String> params = new HashMap<>();
        String result = new ListCommonPicturesTool().execute(params);
        assertTrue("error must steer the caller to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEmptyProjectNameIsError()
    {
        // A blank projectName is rejected by the same guard, before any workspace access.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ListCommonPicturesTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownProjectIsNamedError()
    {
        // A nonexistent project resolves (via ProjectContext) to a value-naming "Project not found"
        // error that names the bad value AND points at list_projects — reached before any UI/BM access.
        String badProject = "NoSuchPictureProject_" + System.nanoTime(); //$NON-NLS-1$
        Map<String, String> params = new HashMap<>();
        params.put("projectName", badProject); //$NON-NLS-1$
        String result = new ListCommonPicturesTool().execute(params);
        assertTrue("error must name the bad project value", result.contains(badProject)); //$NON-NLS-1$
        assertTrue("error must indicate failure", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error must steer the caller to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== helpers ====================

    /** Extracts the property names declared under the schema's {@code "properties"} object. */
    private static java.util.List<String> declaredPropertyNames(String schema)
    {
        java.util.List<String> names = new java.util.ArrayList<>();
        int propsIdx = schema.indexOf("\"properties\""); //$NON-NLS-1$
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        if (propsIdx < 0)
        {
            return names;
        }
        // The "properties" object precedes "required" in JsonSchemaBuilder.build(); scope the scan.
        String propsBlock = requiredIdx > propsIdx ? schema.substring(propsIdx, requiredIdx)
            : schema.substring(propsIdx);
        Matcher matcher = Pattern.compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\\{").matcher(propsBlock); //$NON-NLS-1$
        boolean first = true;
        while (matcher.find())
        {
            if (first)
            {
                // Skip the leading "properties":{ match itself.
                first = false;
                continue;
            }
            names.add(matcher.group(1));
        }
        return names;
    }

    private static boolean isLowerCamelCase(String name)
    {
        return name.matches("[a-z][a-zA-Z0-9]*"); //$NON-NLS-1$
    }

    private static boolean contains(String[] array, String value)
    {
        for (String item : array)
        {
            if (item.equals(value))
            {
                return true;
            }
        }
        return false;
    }
}

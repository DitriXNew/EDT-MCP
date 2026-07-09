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

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils.MetadataTypeInfo;

/**
 * Tests for {@link ExportCommonPictureTool}.
 * <p>
 * Covers tool metadata, the JSON response type, schema&lt;-&gt;execute parity, the permissive output
 * schema, the guide pointer/content, and the Display-free argument validation ("projectName is
 * required" / "fqn is required") that returns a {@code ToolResult.error} JSON payload BEFORE any
 * project / BM access. The actual export (project resolution, bilingual CommonPicture resolution
 * inside a BM read transaction and the picture-content read) runs directly on the calling thread (no
 * SWT Display) and needs a live workbench and model, so it is covered by the E2E suite (Slice D).
 */
public class ExportCommonPictureToolTest
{
    @Test
    public void testName()
    {
        assertEquals("export_common_picture", new ExportCommonPictureTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ExportCommonPictureTool.NAME, new ExportCommonPictureTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new ExportCommonPictureTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndPointsAtGuide()
    {
        String desc = new ExportCommonPictureTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        // The slim description must steer to the on-demand guide (shared tool convention).
        assertTrue(desc.contains("get_tool_guide('export_common_picture')")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ExportCommonPictureTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"variant\"")); //$NON-NLS-1$
    }

    @Test
    public void testRequiredParameters()
    {
        // projectName and fqn are required; variant is optional (schema<->execute parity is
        // exercised by the execute() guards below).
        String schema = new ExportCommonPictureTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("fqn must be required", requiredBlock.contains("\"fqn\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("variant must NOT be required", requiredBlock.contains("\"variant\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaIsPermissive()
    {
        String outputSchema = new ExportCommonPictureTool().getOutputSchema();
        assertNotNull(outputSchema);
        assertTrue(outputSchema.contains("\"fqn\"")); //$NON-NLS-1$
        assertTrue(outputSchema.contains("\"variants\"")); //$NON-NLS-1$
        assertTrue(outputSchema.contains("\"selected\"")); //$NON-NLS-1$
        // Permissive: 'selected' is optional, so additionalProperties must NOT be forbidden.
        assertFalse("output schema must stay permissive (no additionalProperties:false)", //$NON-NLS-1$
            outputSchema.contains("\"additionalProperties\":false")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        String guide = new ExportCommonPictureTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        // Detail kept out of the slim description/schema must live in the guide.
        assertTrue(guide.contains("CommonPicture")); //$NON-NLS-1$
        assertTrue(guide.contains("best")); //$NON-NLS-1$
        assertTrue(guide.contains("svg")); //$NON-NLS-1$
        // The edt-bridge Apache-2.0 credit must be present per the slice contract.
        assertTrue(guide.contains("edt-bridge")); //$NON-NLS-1$
        assertTrue(guide.contains("Apache-2.0")); //$NON-NLS-1$
    }

    @Test
    public void testFqnTypeTokenResolvesBilingually()
    {
        // The bilingual ratchet: the FQN is resolved via the shared MetadataTypeUtils +
        // MetadataNodeResolver pair, so both the English "CommonPicture" token and the Russian
        // "ОбщаяКартинка" token must normalize to the SAME MetadataTypeInfo. This documents that
        // export_common_picture accepts "CommonPicture.<Name>" and "ОбщаяКартинка.<Name>"
        // interchangeably, without needing a live model. The full resolve-by-Name path (programmatic
        // Name inside a BM read transaction) is exercised by the E2E suite (Slice D).
        // Escaped so the RU token survives a non-UTF-8 Tycho build (see CLAUDE.md hard don't #7).
        String russianToken =
            "\u041E\u0431\u0449\u0430\u044F\u041A\u0430\u0440\u0442\u0438\u043D\u043A\u0430"; // ОбщаяКартинка //$NON-NLS-1$
        MetadataTypeInfo english = MetadataTypeUtils.resolve("CommonPicture"); //$NON-NLS-1$
        MetadataTypeInfo russian = MetadataTypeUtils.resolve(russianToken);
        assertNotNull("English CommonPicture token must resolve", english); //$NON-NLS-1$
        assertEquals("EN and RU CommonPicture tokens must resolve to the same type", //$NON-NLS-1$
            english, russian);
        assertEquals(MetadataTypeUtils.MetadataTypeInfo.COMMON_PICTURE, english);
    }

    // ==================== Argument validation (returns before any workbench access) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("fqn", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ExportCommonPictureTool().execute(params);
        // Genuine errors return a ToolResult.error JSON payload (success=false, error=<message>).
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("\"error\"")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameCarriesDiscoveryHint()
    {
        // The required-argument guard appends an actionable discovery hint pointing the caller at
        // list_projects; pin it so the lean error stays self-service.
        Map<String, String> params = new HashMap<>();
        params.put("fqn", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ExportCommonPictureTool().execute(params);
        assertTrue("error must steer the caller to list_projects", //$NON-NLS-1$
            result.contains("list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("fqn", "CommonPicture.Logo"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ExportCommonPictureTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testMissingFqn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ExportCommonPictureTool().execute(params);
        assertTrue(result.contains("fqn is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("\"error\"")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyFqnIsError()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("fqn", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ExportCommonPictureTool().execute(params);
        assertTrue(result.contains("fqn is required")); //$NON-NLS-1$
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameCheckedBeforeFqn()
    {
        // With BOTH required arguments missing, the projectName guard fires first (guard order),
        // and neither guard touches the workbench.
        Map<String, String> params = new HashMap<>();
        String result = new ExportCommonPictureTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertFalse(result.contains("fqn is required")); //$NON-NLS-1$
    }
}

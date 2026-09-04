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

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.form.FormLayoutSnapshotService;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe.NativeRenderMode;

/**
 * Tests for {@link GetFormLayoutSnapshotTool}.
 * <p>
 * Covers tool metadata, the TEXT response type, the input schema, and the
 * "projectName is required when formPath is specified" validation that returns
 * before any {@code Display} access. Capturing the WYSIWYG layout needs a live
 * workbench and is covered by the E2E suite.
 */
public class GetFormLayoutSnapshotToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_form_layout_snapshot", new GetFormLayoutSnapshotTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetFormLayoutSnapshotTool.NAME, new GetFormLayoutSnapshotTool().getName());
    }

    @Test
    public void testResponseTypeText()
    {
        assertEquals(ResponseType.TEXT, new GetFormLayoutSnapshotTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetFormLayoutSnapshotTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetFormLayoutSnapshotTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"mode\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The exhaustive detail moved out of getDescription()/getInputSchema() into
        // getGuide(); assert it is non-empty and still carries the migrated keywords.
        String guide = new GetFormLayoutSnapshotTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("nativeFormBufferedLayoutRender")); //$NON-NLS-1$
        assertTrue(guide.contains("nativeFormLayoutRender")); //$NON-NLS-1$
        assertTrue(guide.contains("get_server_status")); //$NON-NLS-1$
        assertTrue(guide.contains("compact")); //$NON-NLS-1$
        assertTrue(guide.contains("full")); //$NON-NLS-1$
    }

    // ==================== No-bounds diagnosis (pure/headless) ====================

    @Test
    public void testNoBoundsWarningForNativeRenderExplainsStructuralLimitation()
    {
        String warning = FormLayoutSnapshotService.buildNoBoundsWarning(0, NativeRenderMode.ON);

        assertTrue(warning.contains("does not produce Java-side per-element bounds")); //$NON-NLS-1$
        assertTrue(warning.contains("C++ visualizer")); //$NON-NLS-1$
        assertTrue(warning.contains("structural rather than transient")); //$NON-NLS-1$
        assertTrue(warning.contains("will not help")); //$NON-NLS-1$
        assertTrue(warning.contains("relaunching EDT with -DnativeFormLayoutRender=false")); //$NON-NLS-1$
        assertTrue(warning.contains("get_form_screenshot's image path uses")); //$NON-NLS-1$
        assertTrue(warning.contains("get_metadata_details")); //$NON-NLS-1$
        assertFalse("native-render diagnosis must not tell the caller to retry", //$NON-NLS-1$
            warning.contains("Retry the call")); //$NON-NLS-1$
    }

    @Test
    public void testNoBoundsWarningForJavaRenderSuggestsRetry()
    {
        String warning = FormLayoutSnapshotService.buildNoBoundsWarning(0, NativeRenderMode.OFF);

        assertTrue(warning.contains("Native render mode is off")); //$NON-NLS-1$
        assertTrue(warning.contains("may not have finished rendering")); //$NON-NLS-1$
        assertTrue(warning.contains("Retry the call")); //$NON-NLS-1$
        assertTrue(warning.contains("refresh is true")); //$NON-NLS-1$
        assertFalse(warning.contains("will not help")); //$NON-NLS-1$
    }

    @Test
    public void testNoBoundsWarningForUnknownRenderModeAssertsNeitherCause()
    {
        String warning = FormLayoutSnapshotService.buildNoBoundsWarning(0, NativeRenderMode.UNKNOWN);

        assertTrue(warning.contains("effective native render mode could not be read")); //$NON-NLS-1$
        assertTrue(warning.contains("when native render mode is on")); //$NON-NLS-1$
        assertTrue(warning.contains("when native render mode is off")); //$NON-NLS-1$
        assertTrue(warning.contains("get_metadata_details with the form FQN")); //$NON-NLS-1$
        assertTrue(warning.contains("regardless of render mode")); //$NON-NLS-1$
        assertTrue(warning.contains("native render by default")); //$NON-NLS-1$
        assertTrue(warning.contains("-DnativeFormLayoutRender is not set explicitly")); //$NON-NLS-1$
        assertFalse("unknown diagnosis must not use get_server_status as an effective-mode oracle", //$NON-NLS-1$
            warning.contains("get_server_status")); //$NON-NLS-1$
        assertFalse("unknown diagnosis must not assert that a retry will work", //$NON-NLS-1$
            warning.contains("Retry the call")); //$NON-NLS-1$
        assertFalse("unknown diagnosis must not assert that retries cannot work", //$NON-NLS-1$
            warning.contains("will not help")); //$NON-NLS-1$
    }

    @Test
    public void testNoBoundsWarningOmittedWhenBoundsExist()
    {
        assertNull(FormLayoutSnapshotService.buildNoBoundsWarning(1, NativeRenderMode.ON));
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testFormPathWithoutProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("formPath", "Catalog.Products.Forms.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetFormLayoutSnapshotTool().execute(params);
        assertTrue(result.contains("projectName is required when formPath is specified")); //$NON-NLS-1$
    }
}

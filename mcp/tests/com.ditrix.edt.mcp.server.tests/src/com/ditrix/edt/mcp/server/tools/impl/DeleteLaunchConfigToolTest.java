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
 * Tests for {@link DeleteLaunchConfigTool}.
 *
 * <p>Covers tool metadata, input/output schema declarations, the confirm-preview
 * gate and the required-argument guard (name), and the guide. The actual
 * {@code ILaunchConfiguration.delete()} path requires a live
 * {@link org.eclipse.debug.core.DebugPlugin} and is therefore e2e-only
 * (see {@code tests/e2e/tools/test_delete_launch_config.py}).
 */
public class DeleteLaunchConfigToolTest
{
    @Test
    public void testName()
    {
        assertEquals("delete_launch_config", new DeleteLaunchConfigTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DeleteLaunchConfigTool.NAME, new DeleteLaunchConfigTool().getName());
    }

    @Test
    public void testResponseTypeIsJson()
    {
        assertEquals(ResponseType.JSON, new DeleteLaunchConfigTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new DeleteLaunchConfigTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('delete_launch_config')")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsConfirmPreview()
    {
        String desc = new DeleteLaunchConfigTool().getDescription();
        assertTrue("description must advertise the confirm-preview gate", //$NON-NLS-1$
            desc.toLowerCase().contains("confirm")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresParameters()
    {
        String schema = new DeleteLaunchConfigTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare confirm", schema.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInputSchemaRequiredContainsNameOnly()
    {
        String schema = new DeleteLaunchConfigTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("name must be required", tail.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("confirm must NOT be required", !tail.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresConfirmPreviewFields()
    {
        String schema = new DeleteLaunchConfigTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare success", schema.contains("\"success\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare confirmationRequired", //$NON-NLS-1$
            schema.contains("\"confirmationRequired\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare name", schema.contains("\"name\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare message", schema.contains("\"message\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideDocumentsTwoPhaseAndRunningGuard()
    {
        String guide = new DeleteLaunchConfigTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue("guide must document the preview phase", //$NON-NLS-1$
            guide.toLowerCase().contains("preview")); //$NON-NLS-1$
        assertTrue("guide must document the confirm parameter", guide.contains("confirm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document the running-config guard", //$NON-NLS-1$
            guide.contains("terminate_launch")); //$NON-NLS-1$
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Argument validation (returns before any workspace access)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    public void testMissingNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        String result = new DeleteLaunchConfigTool().execute(params);
        assertTrue("missing name must error", result.contains("name is required")); //$NON-NLS-1$ //$NON-NLS-2$
        // The required-arg guard must steer to list_configurations.
        assertTrue("error must suggest list_configurations", result.contains("list_configurations")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

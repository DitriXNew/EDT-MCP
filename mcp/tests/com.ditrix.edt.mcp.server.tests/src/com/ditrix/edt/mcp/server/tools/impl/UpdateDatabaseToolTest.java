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
 * Tests for {@link UpdateDatabaseTool}.
 * <p>
 * Covers tool metadata, the input schema, and the projectName/applicationId
 * required-argument validation in the "no launchConfigurationName" branch, which
 * returns before any live launch-manager access. This is a destructive tool —
 * the tests only exercise the argument-validation sentinels (which return before
 * any database update); the actual update is covered by the E2E suite.
 */
public class UpdateDatabaseToolTest
{
    @Test
    public void testName()
    {
        assertEquals("update_database", new UpdateDatabaseTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(UpdateDatabaseTool.NAME, new UpdateDatabaseTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new UpdateDatabaseTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new UpdateDatabaseTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new UpdateDatabaseTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"launchConfigurationName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"fullUpdate\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"autoRestructure\"")); //$NON-NLS-1$
        assertTrue("schema must declare the confirm gate", schema.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresConfirmPreviewFields()
    {
        // The confirm-preview adds action ('preview'/'updated') + confirmationRequired to the
        // success envelope so a client can distinguish a preview from an applied update.
        String schema = new UpdateDatabaseTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare confirmationRequired", //$NON-NLS-1$
            schema.contains("\"confirmationRequired\"")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsConfirmPreview()
    {
        // The always-loaded description must advertise the two-phase guard so an agent does not
        // expect a bare call to mutate the infobase.
        String desc = new UpdateDatabaseTool().getDescription();
        assertTrue("description must mention the confirm-preview gate", //$NON-NLS-1$
            desc.toLowerCase().contains("confirm")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsTwoPhaseConfirm()
    {
        // The guide documents the preview/confirm workflow (and the confirm parameter).
        String guide = new UpdateDatabaseTool().getGuide();
        assertTrue("guide must document the preview phase", //$NON-NLS-1$
            guide.toLowerCase().contains("preview")); //$NON-NLS-1$
        assertTrue("guide must document the confirm parameter", guide.contains("confirm")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionPointsToGuide()
    {
        // The slimmed description must still steer the agent to the on-demand guide.
        String desc = new UpdateDatabaseTool().getDescription();
        assertTrue(desc.contains("get_tool_guide('update_database')")); //$NON-NLS-1$
    }

    @Test
    public void testGuideNotEmptyAndHoldsMigratedDetail()
    {
        // The exhaustive detail moved out of the description/schema and into the guide:
        // assert it is non-empty and still carries the migrated concepts.
        String guide = new UpdateDatabaseTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        // Exclusive-lock guidance migrated from the old description.
        assertTrue(guide.contains("terminate_launch")); //$NON-NLS-1$
        assertTrue(guide.contains("exclusive")); //$NON-NLS-1$
        // fullUpdate/autoRestructure rationale migrated from the old schema descriptions.
        assertTrue(guide.contains("autoRestructure")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresSessionTerminationParams()
    {
        // The transparent terminate-before-update behaviour is controllable per-call.
        String schema = new UpdateDatabaseTool().getInputSchema();
        assertTrue("schema must declare terminateRunningClients", //$NON-NLS-1$
            schema.contains("\"terminateRunningClients\"")); //$NON-NLS-1$
        assertTrue("schema must declare updateTimeoutSeconds", //$NON-NLS-1$
            schema.contains("\"updateTimeoutSeconds\"")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresTerminatedClientsAndFullUpdateRequired()
    {
        String schema = new UpdateDatabaseTool().getOutputSchema();
        assertTrue("outputSchema must declare terminatedClients", //$NON-NLS-1$
            schema.contains("\"terminatedClients\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare fullUpdateRequired", //$NON-NLS-1$
            schema.contains("\"fullUpdateRequired\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsTransparentTermination()
    {
        String guide = new UpdateDatabaseTool().getGuide();
        assertTrue("guide must document the auto-termination of clients", //$NON-NLS-1$
            guide.contains("terminateRunningClients")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live launch manager needed) ====================

    @Test
    public void testMissingProjectName()
    {
        // No launchConfigurationName -> project+application mode -> projectName required.
        Map<String, String> params = new HashMap<>();
        String result = new UpdateDatabaseTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingApplicationId()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new UpdateDatabaseTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
    }
}

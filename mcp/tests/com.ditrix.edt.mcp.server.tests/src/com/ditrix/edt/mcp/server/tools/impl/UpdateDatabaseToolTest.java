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
import com.e1c.g5.dt.applications.ApplicationException;

/**
 * Tests for {@link UpdateDatabaseTool}.
 * <p>
 * Covers tool metadata, the input schema, and the projectName/applicationId
 * required-argument validation in the "no launchConfigurationName" branch, which
 * returns before any live launch-manager access. This is a destructive tool —
 * the tests only exercise the argument-validation sentinels (which return before
 * any database update); the actual update is covered by the E2E suite.
 * <p>
 * Also covers the #258 {@code InternalInfo} error-hint logic (via the package-private
 * {@link UpdateDatabaseTool#describeInternalInfoHint} and
 * {@link UpdateDatabaseTool#buildApplicationErrorResult} seams) and the guide's
 * documentation of the long-running-update / {@code get_mcp_history} workflow.
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
        assertTrue("schema must declare the confirm gate", schema.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare the terminateRunningClients opt-out", //$NON-NLS-1$
            schema.contains("\"terminateRunningClients\"")); //$NON-NLS-1$
        // autoRestructure was removed: the EDT update API (IApplicationManager.update /
        // ExecutionContext) has no per-call restructure-confirmation switch, so the parameter
        // could never influence the update — advertising it misled unattended clients.
        assertFalse("autoRestructure must not reappear without being wired into the EDT call", //$NON-NLS-1$
            schema.contains("\"autoRestructure\"")); //$NON-NLS-1$
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
    public void testOutputSchemaDeclaresTerminateFields()
    {
        // The free-the-infobase behaviour reports terminatedClient on an applied update and
        // willTerminateRunningClients on a preview, so a client can see the lock-freeing side effect.
        String schema = new UpdateDatabaseTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare terminatedClient", //$NON-NLS-1$
            schema.contains("\"terminatedClient\"")); //$NON-NLS-1$
        assertTrue("outputSchema must declare willTerminateRunningClients", //$NON-NLS-1$
            schema.contains("\"willTerminateRunningClients\"")); //$NON-NLS-1$
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
        // The guide must state that a DB restructure is EDT-confirmed and not controllable
        // per call (the former autoRestructure parameter was a no-op and was removed).
        assertTrue(guide.contains("restructure")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsTerminateRunningClients()
    {
        // The default-on free-the-infobase behaviour (and its opt-out) must be documented so an
        // agent knows the tool now terminates a running client itself instead of failing on the lock.
        String guide = new UpdateDatabaseTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the terminateRunningClients parameter", //$NON-NLS-1$
            guide.contains("terminateRunningClients")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsServerApplicationRunModeSideEffect()
    {
        // Ratchet: updating a standalone-server application through
        // this tool STARTS the standalone server in RUN mode (EDT-native behaviour of
        // the server-application update); a subsequent debug launch will then restart
        // it. The guide must warn about this side effect and point at the launch tools'
        // deferred (coordinated) update as the preferred path.
        String guide = new UpdateDatabaseTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must name the ServerApplication. id prefix",
            guide.contains("ServerApplication.")); //$NON-NLS-1$
        assertTrue("guide must warn the update starts the standalone server in RUN mode",
            guide.contains("STARTS the standalone server in RUN mode")); //$NON-NLS-1$
        assertTrue("guide must say a subsequent debug launch restarts the server",
            guide.contains("restart that server in DEBUG mode")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsLongRunningUpdatesAndHistoryRetrieval()
    {
        // #258: large-configuration updates run minutes and often outlast an MCP client's own
        // call timeout; the guide must point the caller at get_mcp_history to retrieve the real
        // outcome afterwards, and at the CLI workaround for the InternalInfo pipeline limitation.
        String guide = new UpdateDatabaseTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document retrieving the outcome via get_mcp_history", //$NON-NLS-1$
            guide.contains("get_mcp_history")); //$NON-NLS-1$
        assertTrue("guide must document the CLI workaround for the InternalInfo limitation", //$NON-NLS-1$
            guide.contains("LoadConfigFromFiles")); //$NON-NLS-1$
    }

    // ==================== #258 InternalInfo error hint (package-private surface) ====================

    /**
     * Mirrors the real EDT cause type reported in #258: its simple name matches the legacy
     * {@code describeAuthHint} "Synchronization" keyword, so a naive detector would (wrongly)
     * append the credentials hint to this InternalInfo failure.
     */
    private static class InfobaseSynchronizationException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        InfobaseSynchronizationException(String message)
        {
            super(message);
        }
    }

    /** Mirrors the type-name-only detection branch of {@code describeInternalInfoHint}. */
    private static class ConfigurationLoadException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        ConfigurationLoadException(String message)
        {
            super(message);
        }
    }

    @Test
    public void testInternalInfoHintMatchesMarkerInNestedCause()
    {
        // The real Russian EDT message reported in #258.
        Throwable cause = new RuntimeException(
            "Отсутствует внутренняя информация (узел InternalInfo) для объекта Configuration"); //$NON-NLS-1$
        ApplicationException e = new ApplicationException("Failed to load configuration", cause); //$NON-NLS-1$

        String hint = UpdateDatabaseTool.describeInternalInfoHint(e);

        assertFalse("hint must be non-empty for the InternalInfo marker", hint.isEmpty()); //$NON-NLS-1$
        assertTrue("hint must point at the CLI workaround", //$NON-NLS-1$
            hint.contains("LoadConfigFromFiles")); //$NON-NLS-1$
    }

    @Test
    public void testInternalInfoHintEmptyForUnrelatedException()
    {
        ApplicationException e = new ApplicationException("Unrelated failure", //$NON-NLS-1$
            new RuntimeException("some other cause")); //$NON-NLS-1$

        String hint = UpdateDatabaseTool.describeInternalInfoHint(e);

        assertEquals("", hint); //$NON-NLS-1$
    }

    @Test
    public void testInternalInfoHintMatchesMarkerThreeLevelsDeep()
    {
        Throwable level3 = new RuntimeException("InternalInfo node is missing"); //$NON-NLS-1$
        Throwable level2 = new RuntimeException("wrapping level 2", level3); //$NON-NLS-1$
        Throwable level1 = new RuntimeException("wrapping level 1", level2); //$NON-NLS-1$
        ApplicationException e = new ApplicationException("top-level failure", level1); //$NON-NLS-1$

        String hint = UpdateDatabaseTool.describeInternalInfoHint(e);

        assertFalse("hint must match a marker 3 cause-hops deep", hint.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testInternalInfoHintMatchesConfigurationLoadExceptionTypeName()
    {
        // The type-name branch: matches even when the message itself carries no marker text.
        ApplicationException e = new ApplicationException("Load failed", //$NON-NLS-1$
            new ConfigurationLoadException("generic load failure")); //$NON-NLS-1$

        String hint = UpdateDatabaseTool.describeInternalInfoHint(e);

        assertFalse("hint must match on the ConfigurationLoadException type name", hint.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testInternalInfoHintTakesPriorityOverAuthHintInErrorResult()
    {
        // Reproduces #258 problem (1): the cause is an InfobaseSynchronizationException (which
        // would trip the legacy "Synchronization" auth-hint keyword) AND its message carries the
        // InternalInfo marker. The final error JSON must carry the InternalInfo hint and must NOT
        // carry the misleading credentials hint.
        ApplicationException e = new ApplicationException("Failed to load configuration", //$NON-NLS-1$
            new InfobaseSynchronizationException("missing InternalInfo node")); //$NON-NLS-1$

        String result =
            UpdateDatabaseTool.buildApplicationErrorResult(e, "MyProject", "app1", false); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("result must carry the InternalInfo hint", //$NON-NLS-1$
            result.contains("LoadConfigFromFiles")); //$NON-NLS-1$
        assertFalse("result must NOT carry the misleading credentials hint", //$NON-NLS-1$
            result.contains("set_infobase_credentials")); //$NON-NLS-1$
    }

    @Test
    public void testAuthHintStillAppliesWhenNoInternalInfoMarker()
    {
        // Unchanged today's behaviour: an actual auth/connection/sync failure with no InternalInfo
        // marker anywhere in the cause chain must still get the credentials hint.
        ApplicationException e = new ApplicationException("Failed to load configuration", //$NON-NLS-1$
            new InfobaseSynchronizationException("connection refused")); //$NON-NLS-1$

        String result =
            UpdateDatabaseTool.buildApplicationErrorResult(e, "MyProject", "app1", false); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("result must still carry the credentials hint when InternalInfo does not match", //$NON-NLS-1$
            result.contains("set_infobase_credentials")); //$NON-NLS-1$
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

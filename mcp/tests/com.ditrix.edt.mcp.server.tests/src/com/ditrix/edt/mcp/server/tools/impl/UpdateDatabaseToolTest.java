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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.UpdateDatabaseTool.ApplicationFallback; // same package: explicit for the nested seam type
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

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
 * <p>
 * Issue #379 added four package-private seams, all reachable without a live EDT:
 * {@link UpdateDatabaseTool#resolveLaunchConfigTarget} (the whole named-configuration
 * decision, against a mocked {@code ILaunchConfiguration}),
 * {@link UpdateDatabaseTool#effectiveApplicationId} (the config-vs-caller merge rule),
 * {@link UpdateDatabaseTool#resolveSoleApplicationId} (the narrowed default-application
 * fallback for a configuration with no application binding — substitute only when the
 * project has EXACTLY ONE application, refuse otherwise) and
 * {@link UpdateDatabaseTool#describeLaunchIdentifierHint} (the diagnosis for the synthetic
 * {@code launch:}/{@code attach:} identifiers {@code list_configurations} publishes under
 * its own {@code applicationId} key).
 * <p>
 * <b>What these seams do NOT pin</b>, deliberately: the two lines of {@code execute} that look
 * the configuration up by name ({@code DebugPlugin.getDefault().getLaunchManager()}) and then
 * call the fallback when the merged id came back empty. Both need a live launch manager and an
 * EDT-contributed launch type, which this bundle's unit runtime does not have — the same reason
 * the pre-existing tests here stop at argument validation. Everything either side of those two
 * lines is covered above.
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
    public void testConnectsToInfobaseIsTrue()
    {
        // #270: update_database opens a live connection to run the update — it must arm
        // the auth-dialog suppressor's activity window.
        assertTrue(new UpdateDatabaseTool().connectsToInfobase());
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

    // ============ #379: fallback for a launch config with no application binding ============
    //
    // The direct projectName+applicationId mode is NOT affected: applicationId stays
    // mandatory there (testMissingApplicationId above pins that), so this fallback is
    // reachable only through launchConfigurationName.

    private static final String PROJECT = "MyProject"; //$NON-NLS-1$
    private static final String CONFIG = "MyProject / ThinClient"; //$NON-NLS-1$

    private static IApplication app(String id, String name)
    {
        IApplication application = mock(IApplication.class);
        when(application.getId()).thenReturn(id);
        when(application.getName()).thenReturn(name);
        return application;
    }

    /**
     * A mocked runtime-client launch configuration carrying the two attributes; {@code appId}
     * {@code null} means the read THROWS (the "unreadable", not "unbound", case).
     */
    private static ILaunchConfiguration runtimeClientConfig(String project, String appId)
        throws CoreException
    {
        ILaunchConfigurationType type = mock(ILaunchConfigurationType.class);
        when(type.getIdentifier()).thenReturn(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID);
        ILaunchConfiguration cfg = mock(ILaunchConfiguration.class);
        when(cfg.getName()).thenReturn(CONFIG);
        when(cfg.getType()).thenReturn(type);
        when(cfg.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, "")).thenReturn(project); //$NON-NLS-1$
        if (appId == null)
        {
            when(cfg.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, "")) //$NON-NLS-1$
                .thenThrow(new CoreException(new Status(IStatus.ERROR, "test", "attribute is not a String"))); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            when(cfg.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, "")).thenReturn(appId); //$NON-NLS-1$
        }
        return cfg;
    }

    @Test
    public void testLaunchConfigTargetTakesBothFromABoundConfiguration() throws CoreException
    {
        UpdateDatabaseTool.LaunchTarget target = UpdateDatabaseTool.resolveLaunchConfigTarget(
            runtimeClientConfig(PROJECT, "cfg-app"), "caller-app"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(target.errorJson);
        assertEquals(PROJECT, target.projectName);
        assertEquals("a bound configuration fixes the pair", "cfg-app", target.applicationId); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testLaunchConfigTargetKeepsTheCallersIdWhenTheConfigIsUnbound() throws CoreException
    {
        // The wiring #379 is about: an unbound configuration used to be refused outright, and
        // overwriting the caller's explicit target with the configuration's EMPTY attribute
        // would drop a target the caller actually named.
        UpdateDatabaseTool.LaunchTarget target = UpdateDatabaseTool.resolveLaunchConfigTarget(
            runtimeClientConfig(PROJECT, ""), "caller-app"); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull("an unbound configuration is no longer refused outright", target.errorJson); //$NON-NLS-1$
        assertEquals(PROJECT, target.projectName);
        assertEquals("the caller's explicit id must survive", "caller-app", target.applicationId); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testLaunchConfigTargetDefersToTheProjectWhenNothingIsBoundOrRequested()
        throws CoreException
    {
        // The empty applicationId is the signal execute() uses to run resolveSoleApplicationId.
        UpdateDatabaseTool.LaunchTarget target = UpdateDatabaseTool.resolveLaunchConfigTarget(
            runtimeClientConfig(PROJECT, ""), null); //$NON-NLS-1$

        assertNull(target.errorJson);
        assertEquals(PROJECT, target.projectName);
        assertEquals("", target.applicationId); //$NON-NLS-1$
    }

    @Test
    public void testLaunchConfigTargetRefusesAnUnreadableAttributeSeparately() throws CoreException
    {
        // An attribute that cannot be READ must not be mistaken for one the configuration does
        // not have: that would turn an unreadable binding into a project-derived write.
        UpdateDatabaseTool.LaunchTarget target = UpdateDatabaseTool.resolveLaunchConfigTarget(
            runtimeClientConfig(PROJECT, null), null);

        assertNotNull("an unreadable attribute must be refused", target.errorJson); //$NON-NLS-1$
        assertNull(target.applicationId);
        assertTrue("the refusal must say the configuration could not be READ", //$NON-NLS-1$
            target.errorJson.contains("could not be read")); //$NON-NLS-1$
        assertTrue("the refusal must carry the platform reason", //$NON-NLS-1$
            target.errorJson.contains("attribute is not a String")); //$NON-NLS-1$
        assertFalse("it must NOT be reported as an absent binding", //$NON-NLS-1$
            target.errorJson.contains("has no applicationId attribute")); //$NON-NLS-1$
    }

    @Test
    public void testLaunchConfigTargetRefusesAConfigurationWithNoProject() throws CoreException
    {
        UpdateDatabaseTool.LaunchTarget target = UpdateDatabaseTool.resolveLaunchConfigTarget(
            runtimeClientConfig("", "cfg-app"), null); //$NON-NLS-1$ //$NON-NLS-2$

        assertNotNull(target.errorJson);
        assertTrue("the refusal must name the missing project attribute", //$NON-NLS-1$
            target.errorJson.contains("has no project attribute")); //$NON-NLS-1$
    }

    @Test
    public void testLaunchConfigTargetRefusesANonRuntimeClientConfiguration() throws CoreException
    {
        ILaunchConfigurationType type = mock(ILaunchConfigurationType.class);
        when(type.getIdentifier()).thenReturn(LaunchConfigUtils.TYPE_REMOTE_RUNTIME);
        ILaunchConfiguration cfg = mock(ILaunchConfiguration.class);
        when(cfg.getName()).thenReturn(CONFIG);
        when(cfg.getType()).thenReturn(type);

        UpdateDatabaseTool.LaunchTarget target =
            UpdateDatabaseTool.resolveLaunchConfigTarget(cfg, null);

        assertNotNull("an Attach configuration must be refused by type", target.errorJson); //$NON-NLS-1$
        assertTrue(target.errorJson.contains("is not a runtime-client config")); //$NON-NLS-1$
        // The type gate must come FIRST: the application attribute of a configuration we reject
        // by type is never read (and so never mistaken for an absent binding).
        verify(cfg, never()).getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
    }

    @Test
    public void testConfigurationBindingWinsOverTheCallersApplicationId()
    {
        // Unchanged rule: a configuration that HAS a binding fixes the pair.
        assertEquals("cfg-app", //$NON-NLS-1$
            UpdateDatabaseTool.effectiveApplicationId("cfg-app", "caller-app")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("cfg-app", //$NON-NLS-1$
            UpdateDatabaseTool.effectiveApplicationId("cfg-app", null)); //$NON-NLS-1$
    }

    @Test
    public void testCallersApplicationIdSurvivesAnUnboundConfiguration()
    {
        // The caller named the target themselves — refusing them (old behaviour) or overwriting
        // their value with the configuration's EMPTY attribute would both be wrong, and the
        // project-derived fallback must not be consulted at all in this case.
        assertEquals("an explicit id must survive an unbound configuration", //$NON-NLS-1$
            "caller-app", UpdateDatabaseTool.effectiveApplicationId("", "caller-app")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testUnboundConfigurationWithNoCallerIdDefersToTheProject()
    {
        // The empty return is the signal execute() uses to run resolveSoleApplicationId.
        assertEquals("", UpdateDatabaseTool.effectiveApplicationId("", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", UpdateDatabaseTool.effectiveApplicationId("", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("", UpdateDatabaseTool.effectiveApplicationId(null, null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSoleApplicationIsSubstituted() throws ApplicationException
    {
        IProject project = mock(IProject.class);
        IApplication only = app("app-only", "The only infobase"); //$NON-NLS-1$ //$NON-NLS-2$
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.singletonList(only));
        when(mgr.getDefaultApplication(project)).thenReturn(Optional.of(only));

        ApplicationFallback result =
            UpdateDatabaseTool.resolveSoleApplicationId(project, mgr, PROJECT, CONFIG);

        assertNull("an unambiguous project must not be refused", result.errorJson); //$NON-NLS-1$
        assertEquals("the project's single application is the target", //$NON-NLS-1$
            "app-only", result.applicationId); //$NON-NLS-1$
    }

    @Test
    public void testSoleApplicationSubstitutedWhenNoDefaultIsRecorded() throws ApplicationException
    {
        // A project can have exactly one application and no recorded default; that is not a
        // disagreement — with one candidate the answer is still unambiguous.
        IProject project = mock(IProject.class);
        IApplication only = app("app-only", "The only infobase"); //$NON-NLS-1$ //$NON-NLS-2$
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.singletonList(only));
        when(mgr.getDefaultApplication(project)).thenReturn(Optional.empty());

        ApplicationFallback result =
            UpdateDatabaseTool.resolveSoleApplicationId(project, mgr, PROJECT, CONFIG);

        assertNull("no recorded default must not be refused", result.errorJson); //$NON-NLS-1$
        assertEquals("app-only", result.applicationId); //$NON-NLS-1$
    }

    @Test
    public void testSeveralApplicationsAreRefusedAndCandidatesNamed() throws ApplicationException
    {
        // THE point of the narrowed fallback: this tool WRITES to a database, so an ambiguous
        // project must produce a refusal that lets the caller choose — never a silent guess.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        // Build the applications BEFORE opening the outer stubbing: app() stubs its own mock,
        // and a nested when(...) inside thenReturn(...) is an UnfinishedStubbingException.
        List<IApplication> candidates = Arrays.asList(
            app("app-dev", "Dev base"), app("app-prod", "Prod base")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        when(mgr.getApplications(project)).thenReturn(candidates);

        ApplicationFallback result =
            UpdateDatabaseTool.resolveSoleApplicationId(project, mgr, PROJECT, CONFIG);

        assertNull("an ambiguous project must not resolve to any target", //$NON-NLS-1$
            result.applicationId);
        assertNotNull("an ambiguous project must be refused", result.errorJson); //$NON-NLS-1$
        assertTrue("the refusal must name the configuration", result.errorJson.contains(CONFIG)); //$NON-NLS-1$
        assertTrue("the refusal must name the project", result.errorJson.contains(PROJECT)); //$NON-NLS-1$
        assertTrue("the refusal must list candidate id app-dev", //$NON-NLS-1$
            result.errorJson.contains("app-dev")); //$NON-NLS-1$
        assertTrue("the refusal must list candidate id app-prod", //$NON-NLS-1$
            result.errorJson.contains("app-prod")); //$NON-NLS-1$
        assertTrue("the refusal must carry the display names so the caller can tell them apart", //$NON-NLS-1$
            result.errorJson.contains("Prod base")); //$NON-NLS-1$
        assertTrue("the refusal must name the next step", //$NON-NLS-1$
            result.errorJson.contains("get_applications")); //$NON-NLS-1$
        // No default lookup at all on the ambiguous branch: the decision is already made.
        verify(mgr, never()).getDefaultApplication(any(IProject.class));
    }

    @Test
    public void testManyApplicationsAreCappedButStillPointAtGetApplications()
        throws ApplicationException
    {
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        List<IApplication> many = new ArrayList<>();
        for (int i = 0; i < 13; i++)
        {
            many.add(app("app-" + i, "Base " + i)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        when(mgr.getApplications(project)).thenReturn(many);

        ApplicationFallback result =
            UpdateDatabaseTool.resolveSoleApplicationId(project, mgr, PROJECT, CONFIG);

        assertNotNull(result.errorJson);
        assertTrue("the listing must be capped rather than dumping every application", //$NON-NLS-1$
            result.errorJson.contains("and 3 more")); //$NON-NLS-1$
        assertFalse("the 11th application must not be spelled out", //$NON-NLS-1$
            result.errorJson.contains("app-12")); //$NON-NLS-1$
        assertTrue("the full count must still be stated", result.errorJson.contains("13")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoApplicationsIsRefusedWithTheOwningProjectHint() throws ApplicationException
    {
        // EDT resolves an application id ONLY through the project that owns it
        // (ApplicationManager.getApplication filters getApplications(project)), so for a
        // dependent/extension project the refusal must send the caller to the owning project
        // instead of promising that get_applications hands back a usable id for this one.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.emptyList());

        ApplicationFallback result =
            UpdateDatabaseTool.resolveSoleApplicationId(project, mgr, PROJECT, CONFIG);

        assertNull(result.applicationId);
        assertNotNull("a project with no applications must be refused", result.errorJson); //$NON-NLS-1$
        assertTrue("the refusal must name the project", result.errorJson.contains(PROJECT)); //$NON-NLS-1$
        assertTrue("the refusal must explain the extension/base-project ownership", //$NON-NLS-1$
            result.errorJson.contains("base configuration")); //$NON-NLS-1$
    }

    @Test
    public void testSingleApplicationWithNoUsableIdIsRefusedNotForwarded() throws ApplicationException
    {
        // A blank id would reach the application lookup as if nothing had been asked for; the
        // fallback must never hand the writer a target it cannot name. All three unusable
        // shapes are checked from one loop so the guard cannot be narrowed to just one of them.
        for (String unusable : Arrays.asList(null, "", "  \t")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            IProject project = mock(IProject.class);
            IApplication broken = mock(IApplication.class);
            when(broken.getId()).thenReturn(unusable);
            IApplicationManager mgr = mock(IApplicationManager.class);
            when(mgr.getApplications(project)).thenReturn(Collections.singletonList(broken));

            ApplicationFallback result =
                UpdateDatabaseTool.resolveSoleApplicationId(project, mgr, PROJECT, CONFIG);

            assertNull("an unusable application id (" + unusable //$NON-NLS-1$
                + ") must not become the update target", result.applicationId); //$NON-NLS-1$
            assertNotNull("an unnameable application must be refused", result.errorJson); //$NON-NLS-1$
        }
    }

    @Test
    public void testNullApplicationListIsRefusedNotDereferenced() throws ApplicationException
    {
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(null);

        ApplicationFallback result =
            UpdateDatabaseTool.resolveSoleApplicationId(project, mgr, PROJECT, CONFIG);

        assertNull(result.applicationId);
        assertNotNull("a null application list must be refused, not dereferenced", //$NON-NLS-1$
            result.errorJson);
    }

    @Test
    public void testApplicationListFailureIsRefusedWithTheReason() throws ApplicationException
    {
        // A failure to LIST is not "no applications": saying so would send the caller off to
        // create an infobase they already have. Name the reason and the retry.
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(PROJECT);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenThrow(new ApplicationException("index is cold")); //$NON-NLS-1$

        ApplicationFallback result =
            UpdateDatabaseTool.resolveSoleApplicationId(project, mgr, PROJECT, CONFIG);

        assertNull(result.applicationId);
        assertNotNull(result.errorJson);
        assertTrue("the refusal must carry the platform reason", //$NON-NLS-1$
            result.errorJson.contains("index is cold")); //$NON-NLS-1$
        assertTrue("a listing failure must not be reported as 'no applications'", //$NON-NLS-1$
            !result.errorJson.contains("has no applications of its own")); //$NON-NLS-1$
    }

    @Test
    public void testSingleApplicationDisagreeingWithTheDefaultIsRefused() throws ApplicationException
    {
        // Unreachable in a consistent EDT state (getDefaultApplication returns the sole
        // application when the project has exactly one), so it can only mean the enumeration
        // this decision was made on and the resolver disagree about the project. Fail closed:
        // picking either could update a database nobody named.
        IProject project = mock(IProject.class);
        IApplication only = app("app-only", "The only infobase"); //$NON-NLS-1$ //$NON-NLS-2$
        IApplication other = app("app-elsewhere", "Another infobase"); //$NON-NLS-1$ //$NON-NLS-2$
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplications(project)).thenReturn(Collections.singletonList(only));
        when(mgr.getDefaultApplication(project)).thenReturn(Optional.of(other));

        ApplicationFallback result =
            UpdateDatabaseTool.resolveSoleApplicationId(project, mgr, PROJECT, CONFIG);

        assertNull("a disagreement must not resolve to either candidate", result.applicationId); //$NON-NLS-1$
        assertNotNull(result.errorJson);
        assertTrue("the refusal must name both disagreeing ids", //$NON-NLS-1$
            result.errorJson.contains("app-only") && result.errorJson.contains("app-elsewhere")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ============ #379: diagnosing a synthetic launch identifier passed as applicationId ============

    @Test
    public void testLaunchPrefixedIdIsDiagnosedAsALaunchIdentifier()
    {
        String hint = UpdateDatabaseTool.describeLaunchIdentifierHint("launch:ERP ThinClient"); //$NON-NLS-1$

        assertTrue("a launch: id must be called out as not an application id", //$NON-NLS-1$
            hint.contains("not an application id")); //$NON-NLS-1$
        assertTrue("the diagnosis must echo the configuration name", //$NON-NLS-1$
            hint.contains("ERP ThinClient")); //$NON-NLS-1$
        assertTrue("the diagnosis must point at the launchConfigurationName route", //$NON-NLS-1$
            hint.contains("launchConfigurationName")); //$NON-NLS-1$
    }

    @Test
    public void testAttachPrefixedIdIsNotSentToLaunchConfigurationName()
    {
        // update_database rejects an Attach config by type, so advising launchConfigurationName
        // here would only buy the caller a second refusal.
        String hint = UpdateDatabaseTool.describeLaunchIdentifierHint("attach:Server debug"); //$NON-NLS-1$

        assertTrue("an attach: id must be called out as not an application id", //$NON-NLS-1$
            hint.contains("not an application id")); //$NON-NLS-1$
        assertTrue("the diagnosis must echo the configuration name", //$NON-NLS-1$
            hint.contains("Server debug")); //$NON-NLS-1$
        assertTrue("the diagnosis must say why that config cannot be the target", //$NON-NLS-1$
            hint.contains("runtime-client configuration")); //$NON-NLS-1$
        assertFalse("an Attach id must NOT be sent to launchConfigurationName", //$NON-NLS-1$
            hint.contains("pass it as launchConfigurationName")); //$NON-NLS-1$
    }

    @Test
    public void testServerApplicationIdGetsNoLaunchIdentifierDiagnosis()
    {
        // ServerApplication. is the prefix REAL 1C standalone-server applications carry in
        // their own IApplication.getId() — LaunchConfigUtils.isSyntheticApplicationId matches it
        // too, which is exactly why this diagnosis must not be built on that predicate. A
        // missing/stale server application must NOT be told it is "not an application id".
        assertEquals("", UpdateDatabaseTool.describeLaunchIdentifierHint( //$NON-NLS-1$
            "ServerApplication.ERP")); //$NON-NLS-1$
    }

    @Test
    public void testOrdinaryAndDegenerateIdsGetNoDiagnosis()
    {
        assertEquals("", UpdateDatabaseTool.describeLaunchIdentifierHint(null)); //$NON-NLS-1$
        assertEquals("", UpdateDatabaseTool.describeLaunchIdentifierHint("")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a real UUID-shaped id is not a launch identifier", //$NON-NLS-1$
            "", UpdateDatabaseTool.describeLaunchIdentifierHint( //$NON-NLS-1$
                "3f6c0b1e-9d28-49db-9273-2903d2ab859a")); //$NON-NLS-1$
        assertEquals("a bare prefix carries no configuration name to advise about", //$NON-NLS-1$
            "", UpdateDatabaseTool.describeLaunchIdentifierHint("launch:")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", UpdateDatabaseTool.describeLaunchIdentifierHint("attach:")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the prefix must be a real prefix, not a substring", //$NON-NLS-1$
            "", UpdateDatabaseTool.describeLaunchIdentifierHint("my-launch:thing")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideDocumentsTheNarrowedFallbackAndTheSyntheticId()
    {
        String guide = new UpdateDatabaseTool().getGuide();
        assertTrue("guide must document that an app-less config resolves from the project", //$NON-NLS-1$
            guide.contains("exactly one")); //$NON-NLS-1$
        assertTrue("guide must document the ambiguity refusal", //$NON-NLS-1$
            guide.contains("REFUSED")); //$NON-NLS-1$
        assertTrue("guide must warn that list_configurations can report a launch: identifier", //$NON-NLS-1$
            guide.contains("launch:<name>")); //$NON-NLS-1$
    }
}

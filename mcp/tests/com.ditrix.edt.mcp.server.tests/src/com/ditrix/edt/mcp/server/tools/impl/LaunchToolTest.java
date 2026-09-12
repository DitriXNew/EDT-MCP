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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.junit.Test;
import org.mockito.Mockito;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.LaunchTool.AlreadyRunningContext;
import com.ditrix.edt.mcp.server.tools.impl.LaunchTool.StartOutcome;
import com.ditrix.edt.mcp.server.utils.AttributableCancel;
import com.ditrix.edt.mcp.server.utils.AsyncLaunchOutcomes;
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.ExternalInfobaseChangesPolicy;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchOverrides;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.ExistingClientSession;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery;
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link LaunchTool}.
 * <p>
 * Covers tool metadata, the input schema, and the two headless-reachable
 * required-argument validations in the project+application launch mode
 * (projectName, then applicationId), which return before the first
 * {@code ProjectStateChecker}/launch-manager access. NOTE: these checks are
 * only reachable when {@code launchConfigurationName} is absent — supplying it
 * enters the by-name launch mode whose first statement touches the live launch
 * manager. The headless E2E suite (test_launch.py) covers only the
 * sentinel/negative matrix; an ACTUAL launch needs a live workbench plus a
 * running infobase and is not automated. The launch-path decision logic is
 * therefore unit-covered through seams instead: handleExistingClientSession
 * (restartIfRunning/alreadyRunning), runLaunchJobBody (background-Job body),
 * runPreLaunchUpdateStep and performLaunch here, and the session
 * detect/terminate helpers in LaunchLifecycleUtilsSessionTest.
 */
public class LaunchToolTest
{
    private static final String STANDALONE_SERVER_TYPE_ID =
        "com.e1c.g5.v8.dt.platform.standaloneserver.launchConfigurationType"; //$NON-NLS-1$

    /** Reflective baseline-safe access to the new by-name resolution seam. */
    private static Object resolveNamedConfiguration(ILaunchManager launchManager, String name)
    {
        try
        {
            Method method = LaunchTool.class.getDeclaredMethod(
                "resolveNamedConfiguration", ILaunchManager.class, String.class); //$NON-NLS-1$
            method.setAccessible(true);
            return method.invoke(null, launchManager, name);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("the by-name configuration resolution seam is missing or unusable", e); //$NON-NLS-1$
        }
    }

    /** Reads one no-argument accessor from the by-name resolution result. */
    private static Object namedResolutionValue(Object resolution, String accessor)
    {
        try
        {
            Method method = resolution.getClass().getDeclaredMethod(accessor);
            method.setAccessible(true);
            return method.invoke(resolution);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError("named-resolution accessor is missing: " + accessor, e); //$NON-NLS-1$
        }
    }

    @Test
    public void testName()
    {
        assertEquals("launch", new LaunchTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(LaunchTool.NAME, new LaunchTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new LaunchTool().getResponseType());
    }

    @Test
    public void testConnectsToInfobaseIsTrue()
    {
        // #270: config.launch(...) connects a runtime client to the infobase — it must arm
        // the auth-dialog suppressor's activity window.
        assertTrue(new LaunchTool().connectsToInfobase());
    }

    @Test
    public void failedLaunchStatusGainsAccessDialogDiagnosticOnlyWhenCounterMoved()
    {
        IStatus failure = new Status(IStatus.ERROR, "test", "launch failed"); //$NON-NLS-1$ //$NON-NLS-2$

        assertSame(failure, LaunchTool.appendAccessSettingsDialogFailure(failure, 7, 7));
        IStatus observed = LaunchTool.appendAccessSettingsDialogFailure(failure, 7, 8);

        String note = InfobaseAuthDialogSuppressor.accessSettingsDialogFailureNote(7, 8);
        assertEquals("launch failed " + note, observed.getMessage()); //$NON-NLS-1$
        assertTrue(observed.getMessage().contains("while this call ran")); //$NON-NLS-1$
        assertFalse(observed.getMessage().contains("by this call")); //$NON-NLS-1$
        assertFalse(observed.isMultiStatus());
        assertEquals(failure.getSeverity(), observed.getSeverity());
        assertEquals(failure.getPlugin(), observed.getPlugin());
        assertEquals(failure.getCode(), observed.getCode());
        assertSame(failure.getException(), observed.getException());
    }

    @Test
    public void failedMultiStatusKeepsItsChildrenWhenAccessDialogDiagnosticIsAdded()
    {
        IStatus first = new Status(IStatus.WARNING, "first-plugin", 11, "first detail", null); //$NON-NLS-1$ //$NON-NLS-2$
        IStatus second = new Status(IStatus.ERROR, "second-plugin", 12, "second detail", null); //$NON-NLS-1$ //$NON-NLS-2$
        RuntimeException failureCause = new RuntimeException("launch cause"); //$NON-NLS-1$
        MultiStatus failure = new MultiStatus("parent-plugin", 27, //$NON-NLS-1$
            new IStatus[] { first, second }, "launch failed", failureCause); //$NON-NLS-1$

        IStatus observed = LaunchTool.appendAccessSettingsDialogFailure(failure, 7, 8);

        assertTrue(observed instanceof MultiStatus);
        assertTrue(observed.isMultiStatus());
        assertEquals(failure.getSeverity(), observed.getSeverity());
        assertEquals("parent-plugin", observed.getPlugin()); //$NON-NLS-1$
        assertEquals(27, observed.getCode());
        assertSame(failureCause, observed.getException());
        String note = InfobaseAuthDialogSuppressor.accessSettingsDialogFailureNote(7, 8);
        assertEquals("launch failed " + note, observed.getMessage()); //$NON-NLS-1$
        assertTrue(observed.getMessage().contains("while this call ran")); //$NON-NLS-1$
        IStatus[] children = observed.getChildren();
        assertEquals("every original child must remain", 2, children.length); //$NON-NLS-1$
        assertSame("the first diagnostic child must keep its position", first, children[0]); //$NON-NLS-1$
        assertSame("the second diagnostic child must keep its position", second, children[1]); //$NON-NLS-1$
    }

    @Test
    public void successfulLaunchStatusNeverGainsAccessDialogDiagnostic()
    {
        assertSame(Status.OK_STATUS,
            LaunchTool.appendAccessSettingsDialogFailure(Status.OK_STATUS, 7, 8));
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new LaunchTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new LaunchTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"launchConfigurationName\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresDebugAndRunModes()
    {
        JsonObject mode = JsonParser.parseString(new LaunchTool().getInputSchema())
            .getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("mode"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("string", mode.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(2, mode.getAsJsonArray("enum").size()); //$NON-NLS-1$
        assertTrue(mode.getAsJsonArray("enum").contains(JsonParser.parseString("\"debug\""))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(mode.getAsJsonArray("enum").contains(JsonParser.parseString("\"run\""))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testModeDefaultsToDebug()
    {
        assertEquals(LaunchTool.MODE_DEBUG, LaunchTool.extractLaunchMode(new HashMap<>()));

        Map<String, String> params = new HashMap<>();
        params.put("mode", "run"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(LaunchTool.MODE_RUN, LaunchTool.extractLaunchMode(params));
    }

    @Test
    public void testUnknownModeNamesValueAndAcceptedModes()
    {
        Map<String, String> params = new HashMap<>();
        params.put("mode", "profile"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject result = JsonParser.parseString(new LaunchTool().execute(params)).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        String error = result.get("error").getAsString(); //$NON-NLS-1$
        assertTrue(error.contains("profile")); //$NON-NLS-1$
        assertTrue(error.contains("debug")); //$NON-NLS-1$
        assertTrue(error.contains("run")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresRestartIfRunning()
    {
        // restartIfRunning must be a declared input so the
        // schema<->execute parity test passes and clients can discover it. Its
        // read in execute() is enforced by SchemaExecuteParamParityTest.
        String schema = new LaunchTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare restartIfRunning",
            schema.contains("\"restartIfRunning\"")); //$NON-NLS-1$
        assertTrue("schema must document the default short-circuit contract",
            schema.contains("alreadyRunning:true")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresTheLaunchOverrides()
    {
        // The three per-launch overrides of issue #344. They must be DECLARED (a schema-driven
        // client cannot pass what it cannot see) and their prose must carry the two facts the
        // schema itself cannot: that nothing is persisted, and that an external object is named
        // inside a project rather than pathed to a built file.
        String schema = new LaunchTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare startupOption",
            schema.contains("\"startupOption\"")); //$NON-NLS-1$
        assertTrue("schema must declare externalObjectProjectName",
            schema.contains("\"externalObjectProjectName\"")); //$NON-NLS-1$
        assertTrue("schema must declare externalObjectName",
            schema.contains("\"externalObjectName\"")); //$NON-NLS-1$
        assertTrue("startupOption must say the saved configuration is left alone",
            schema.contains("saved EDT configuration is not modified")); //$NON-NLS-1$
        assertTrue("the external-object prose must rule out a prebuilt file",
            schema.contains("prebuilt")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsTheExternalObjectLaunch()
    {
        // The guide is where the platform reasoning lives: WHY a path cannot work, and why a
        // misconfigured request is refused instead of launched (the delegate only logs and
        // starts a session that runs nothing).
        String guide = new LaunchTool().getGuide();
        assertTrue("guide must document the external-object launch",
            guide.contains("## Debugging an external data processor")); //$NON-NLS-1$
        assertTrue("guide must state that the object is named, not pathed",
            guide.contains("named, never pathed")); //$NON-NLS-1$
        assertTrue("guide must explain the silent-success refusal",
            guide.contains("no `/Execute` at all")); //$NON-NLS-1$
        assertTrue("guide must name the Attach refusal",
            guide.contains("rejected on an **Attach** configuration")); //$NON-NLS-1$
    }

    @Test
    public void testRestartIfRunningDefaultsToFalse()
    {
        // Pin the ACTUAL default through the same extraction execute() uses
        // (the extractRestartIfRunning seam): absent -> false, and an explicit
        // "true" flips it — so this cannot pass against a hardcoded false. The
        // downstream effect of false vs true (alreadyRunning short-circuit vs
        // terminate+relaunch) is unit-covered by the handleExistingClientSession
        // tests below.
        assertFalse("absent restartIfRunning must default to false",
            LaunchTool.extractRestartIfRunning(new HashMap<>()));
        Map<String, String> params = new HashMap<>();
        params.put("restartIfRunning", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("explicit restartIfRunning=true must override the default",
            LaunchTool.extractRestartIfRunning(params));
    }

    @Test
    public void testGuideDocumentsRestartIfRunningAndTargetManagerGuard()
    {
        // The guide must document both halves of the fix — the new
        // restartIfRunning switch and that the already-running guard now also catches
        // a session EDT tracks only through its debug target manager (the code-1003
        // "Debug session already exists" modal source). Ratchet so it can't drift.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document restartIfRunning",
            guide.contains("restartIfRunning")); //$NON-NLS-1$
        assertTrue("guide must document the target-manager already-running detection",
            guide.contains("target manager")); //$NON-NLS-1$
        assertTrue("guide must reference the 'Debug session already exists' modal it prevents",
            guide.contains("Debug session already exists")); //$NON-NLS-1$
    }

    @Test
    public void testOutputSchemaDeclaresLaunchingStatus()
    {
        // The launch is async: a fresh launch emits status:"launching" so the caller
        // knows to poll debug_status. This test only asserts the SCHEMA half of that
        // contract — that the output schema advertises the field; the emitted result
        // needs a real launch (live workbench + infobase) and is not automated. The
        // closest headless coverage of the emission path is the runLaunchJobBody /
        // performLaunch seam tests below (the dispatch that precedes the emission).
        // The coherence check below ties the metadata (schema + guide) together so the
        // promise can't silently drift in one place only.
        String schema = new LaunchTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"mode\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"status\"")); //$NON-NLS-1$
        assertTrue(schema.contains("launching")); //$NON-NLS-1$
    }

    @Test
    public void testLaunchingContractIsCoherentAcrossMetadata()
    {
        // Runtime-free coherence check: the async "launching" contract must be
        // declared consistently in BOTH the output schema and the guide, so neither
        // can advertise it while the other forgets to. The actual result emission
        // needs a real launch (live workbench + infobase) and is not automated; the
        // dispatch it sits on is unit-covered by the runLaunchJobBody seam tests.
        LaunchTool tool = new LaunchTool();
        String schema = tool.getOutputSchema();
        String guide = tool.getGuide();
        assertNotNull(schema);
        assertNotNull(guide);
        assertTrue(schema.contains("launching")); //$NON-NLS-1$
        assertTrue(guide.contains("launching")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDescribesAsyncLaunch()
    {
        // The launch dispatch is non-blocking (a background Job): the guide
        // must tell the caller it returns status:"launching" immediately and to poll
        // debug_status for readiness rather than expecting a running session synchronously.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.contains("launching")); //$NON-NLS-1$
        assertTrue(guide.contains("debug_status")); //$NON-NLS-1$
    }

    @Test
    public void testUpdateBeforeLaunchFalseContractIsCoherent()
    {
        // With updateBeforeLaunch=false the DB update is skipped AND
        // the launch delegate's modal is NOT auto-confirmed (auto-pressing "Update
        // then run" would perform the very update the caller disabled). Both
        // metadata halves must keep documenting that the platform may then show
        // the modal, so the contract can't silently drift in one place only.
        LaunchTool tool = new LaunchTool();
        String schema = tool.getInputSchema();
        String guide = tool.getGuide();
        assertNotNull(schema);
        assertNotNull(guide);
        assertTrue("schema must document that updateBeforeLaunch=false may show the modal",
            schema.contains("may then show that modal")); //$NON-NLS-1$
        assertTrue("guide must document the updateBeforeLaunch=false contract",
            guide.contains("updateBeforeLaunch=false")); //$NON-NLS-1$
        assertTrue("guide must document that updateBeforeLaunch=false may show the modal",
            guide.contains("may then show that modal")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsRunModeAlreadyRunningGuard()
    {
        // The already-running guard covers RUN-mode launches (no debug
        // target) in BOTH selection modes (by-name and project+application); the
        // guide documents that promise — keep it ratcheted.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the RUN-mode already-running guard",
            guide.contains("RUN mode")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The exhaustive detail (Attach mode, the alreadyRunning short-circuit and
        // updateBeforeLaunch nuances) moved out of the slimmed description/schema
        // into getGuide(); assert it survived there rather than vanishing.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("Attach")); //$NON-NLS-1$
        assertTrue(guide.contains("alreadyRunning")); //$NON-NLS-1$
        assertTrue(guide.contains("updateBeforeLaunch")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        // No launchConfigurationName -> project+application mode -> projectName required.
        Map<String, String> params = new HashMap<>();
        String result = new LaunchTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingApplicationId()
    {
        // projectName present, no launchConfigurationName, applicationId omitted.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new LaunchTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerConfigurationRoutesToTheStandaloneBranch() throws Exception
    {
        String name = "Standalone server for B"; //$NON-NLS-1$
        assertEquals(STANDALONE_SERVER_TYPE_ID, LaunchConfigUtils.class
            .getField("STANDALONE_SERVER_LAUNCH_CONFIG_TYPE_ID").get(null)); //$NON-NLS-1$
        ILaunchManager launchManager = mock(ILaunchManager.class);
        ILaunchConfigurationType runtimeType = mock(ILaunchConfigurationType.class);
        ILaunchConfigurationType standaloneType = mock(ILaunchConfigurationType.class);
        ILaunchConfiguration standalone = mock(ILaunchConfiguration.class);
        when(launchManager.getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID))
            .thenReturn(runtimeType);
        when(launchManager.getLaunchConfigurations(runtimeType))
            .thenReturn(new ILaunchConfiguration[0]);
        when(launchManager.getLaunchConfigurationType(STANDALONE_SERVER_TYPE_ID))
            .thenReturn(standaloneType);
        when(launchManager.getLaunchConfigurations(standaloneType))
            .thenReturn(new ILaunchConfiguration[] { standalone });
        when(standalone.getName()).thenReturn(name);
        when(standalone.getType()).thenReturn(standaloneType);
        when(standaloneType.getIdentifier()).thenReturn(STANDALONE_SERVER_TYPE_ID);

        Object resolution = resolveNamedConfiguration(launchManager, name);

        assertSame(standalone, namedResolutionValue(resolution, "config")); //$NON-NLS-1$
        assertNull(namedResolutionValue(resolution, "error")); //$NON-NLS-1$
        assertTrue(LaunchTool.isStandaloneServerConfiguration(
            LaunchConfigUtils.getConfigTypeId(standalone)));
    }

    @Test
    public void testSchemaAndGuideStateTheStandaloneUpdateException()
    {
        JsonObject update = JsonParser.parseString(new LaunchTool().getInputSchema())
            .getAsJsonObject().getAsJsonObject("properties") //$NON-NLS-1$
            .getAsJsonObject("updateBeforeLaunch"); //$NON-NLS-1$
        String description = update.get("description").getAsString(); //$NON-NLS-1$
        assertEquals("Default true: silently apply the configuration->DB update before launching " //$NON-NLS-1$
            + "so no 'Update database?' modal blocks the call (even on a Russian-locale EDT the " //$NON-NLS-1$
            + "dialog is auto-confirmed); false skips the update and the platform may then show " //$NON-NLS-1$
            + "that modal. Ignored for Attach. A standalone-server configuration performs no " //$NON-NLS-1$
            + "database update on this route even when omitted, and an explicit true is refused " //$NON-NLS-1$
            + "- run update_database separately.", description); //$NON-NLS-1$
        // Both halves must survive: the standalone carve-out, and the modal behaviour it
        // must not displace - dropping either one silently changes what a caller is told.
        assertTrue(description.contains("apply the configuration->DB update before launching")); //$NON-NLS-1$
        assertTrue(description.contains(
            "performs no database update on this route even when omitted")); //$NON-NLS-1$

        String guide = new LaunchTool().getGuide();
        String contract = "For a named standalone-server launch configuration, no database " //$NON-NLS-1$
            + "update is performed on this route, including when this parameter is omitted. An " //$NON-NLS-1$
            + "explicit `updateBeforeLaunch=true` is refused; run `update_database` separately, " //$NON-NLS-1$
            + "then call `launch` with `updateBeforeLaunch=false`."; //$NON-NLS-1$
        assertTrue(guide.contains(contract));
        assertFalse(guide.contains("For a named standalone-server launch configuration, " //$NON-NLS-1$
            + "the default database update is performed.")); //$NON-NLS-1$
    }

    @Test
    public void testRuntimeClientConfigurationResolutionIsUnaffected() throws Exception
    {
        String name = "B Thin Client"; //$NON-NLS-1$
        ILaunchManager launchManager = mock(ILaunchManager.class);
        ILaunchConfigurationType runtimeType = mock(ILaunchConfigurationType.class);
        ILaunchConfiguration runtime = mock(ILaunchConfiguration.class);
        when(launchManager.getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID))
            .thenReturn(runtimeType);
        when(launchManager.getLaunchConfigurations(runtimeType))
            .thenReturn(new ILaunchConfiguration[] { runtime });
        when(runtime.getName()).thenReturn(name);

        Object resolution = resolveNamedConfiguration(launchManager, name);

        assertSame("the existing runtime-client lookup result must flow through unchanged", runtime, //$NON-NLS-1$
            namedResolutionValue(resolution, "config")); //$NON-NLS-1$
        assertNull(namedResolutionValue(resolution, "error")); //$NON-NLS-1$
        verify(launchManager, never()).getLaunchConfigurationType(
            STANDALONE_SERVER_TYPE_ID);
    }

    // ==================== performLaunch headless routing ====================

    @Test
    public void testPerformLaunchHeadlessExecutesSynchronously() throws Exception
    {
        // The display probe must NOT create a display.
        // Display.getDefault() never returns null (per the SWT contract it creates
        // a display, making the calling thread the UI thread), so the documented
        // headless fallback was dead code and asyncExec queued the launch onto an
        // event loop no thread pumps — the launch silently never ran while the
        // tool had already reported status:"launching". With the workbench-aware
        // probe, this headless test JVM takes the SYNCHRONOUS path: the launch
        // really executes on the calling thread.
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        String error = new LaunchTool().performLaunch(config, false, ExternalInfobaseChangesPolicy.DEFAULT);
        assertNull("successful headless launch must return null", error);
        Mockito.verify(config).launch(eq(ILaunchManager.DEBUG_MODE), isA(AttributableCancel.class));
    }

    @Test
    public void testPerformLaunchHeadlessUsesRunMode() throws Exception
    {
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);

        String error = new LaunchTool().performLaunch(config, false,
            ExternalInfobaseChangesPolicy.DEFAULT, null, ILaunchManager.RUN_MODE);

        assertNull("successful headless run launch must return null", error);
        Mockito.verify(config).launch(eq(ILaunchManager.RUN_MODE), isA(AttributableCancel.class));
        Mockito.verify(config, never()).launch(eq(ILaunchManager.DEBUG_MODE), any());
    }

    @Test
    public void testPerformLaunchHeadlessSurfacesLaunchError() throws Exception
    {
        // The synchronous (headless) path is the only one that can still report a
        // launch failure to the caller — keep that contract real, not dead code.
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        Mockito.when(config.launch(eq(ILaunchManager.DEBUG_MODE), any(IProgressMonitor.class))).thenThrow(
            new CoreException(new Status(IStatus.ERROR, "test", "launch refused"))); //$NON-NLS-1$ //$NON-NLS-2$
        String error = new LaunchTool().performLaunch(config, false, ExternalInfobaseChangesPolicy.DEFAULT);
        assertNotNull("headless launch failure must be surfaced synchronously", error);
        assertTrue(error.contains("launch refused")); //$NON-NLS-1$
    }

    // ============ launch runs in a background Job, off the EDT UI thread ============
    //
    // performLaunch used to dispatch config.launch via display.asyncExec, which ran the
    // ENTIRE RuntimeClientLaunchDelegate.doLaunch (incl. a minutes-long standalone-server
    // non-debug→debug restart) ON the SWT UI thread and froze the workbench. It now
    // schedules a background Job (mirroring EDT's DebugUIPlugin.launchInBackground) whose
    // body is the package-private seam runLaunchJobBody. Scheduling a real Job needs a
    // live workbench and is not automated (the headless E2E never reaches a real
    // launch); the seam IS the coverage — these tests exercise it directly:
    // success → OK_STATUS, CoreException → its own status (logged, not thrown), any other
    // Throwable → an ERROR status (logged, not thrown — the Job must never die silently),
    // and in EVERY outcome the confirmer disarm in finally runs without breaking the chain
    // (arm/disarm are headless no-ops here, so "the finally chain never throws" is the
    // observable pairing contract).

    @Test
    public void testRunLaunchJobBodySuccessReturnsOkAndWrapsMonitor() throws Exception
    {
        // The Job body launches with the JOB'S monitor (so the Progress view shows the
        // delegate's steps) and reports OK — and the arm/disarm pair around the launch
        // completes cleanly.
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        IProgressMonitor monitor = new NullProgressMonitor();
        IStatus status = LaunchTool.runLaunchJobBody(config, true, ExternalInfobaseChangesPolicy.DEFAULT, monitor);
        assertNotNull(status);
        assertTrue("successful launch must report OK", status.isOK());
        Mockito.verify(config).launch(eq(ILaunchManager.DEBUG_MODE), isA(AttributableCancel.class));
    }

    @Test
    public void testRunLaunchJobBodyReportsADelegateCancelledMonitor() throws Exception
    {
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        when(config.getName()).thenReturn("Cancelled launch"); //$NON-NLS-1$
        NullProgressMonitor monitor = new NullProgressMonitor();
        doAnswer(invocation -> {
            ((IProgressMonitor)invocation.getArgument(1)).setCanceled(true);
            return mock(ILaunch.class);
        }).when(config).launch(eq(ILaunchManager.DEBUG_MODE), any(IProgressMonitor.class));

        IStatus status = LaunchTool.runLaunchJobBody(config, false,
            ExternalInfobaseChangesPolicy.DEFAULT, monitor);

        String expected = "Launch of 'Cancelled launch' was abandoned by EDT " //$NON-NLS-1$
            + "(the launch delegate cancelled it); no reason was logged. Check the EDT error log."; //$NON-NLS-1$
        assertEquals(IStatus.ERROR, status.getSeverity());
        assertEquals(expected, status.getMessage());
        assertTrue(AsyncLaunchOutcomes.recent().stream().anyMatch(outcome ->
            "Cancelled launch".equals(outcome.launchConfiguration()) //$NON-NLS-1$
                && expected.equals(outcome.message())));
    }

    @Test
    public void testRunLaunchJobBodyIgnoresCancellationFromAnotherThread() throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        NullProgressMonitor monitor = new NullProgressMonitor();
        doAnswer(invocation -> {
            IProgressMonitor launchMonitor = invocation.getArgument(1);
            Thread external = new Thread(() -> launchMonitor.setCanceled(true),
                "external-launch-canceller"); //$NON-NLS-1$
            external.start();
            external.join();
            return mock(ILaunch.class);
        }).when(config).launch(eq(ILaunchManager.DEBUG_MODE), any(IProgressMonitor.class));

        IStatus status = LaunchTool.runLaunchJobBody(config, false,
            ExternalInfobaseChangesPolicy.DEFAULT, monitor);

        assertTrue("an external cancellation does not claim EDT abandoned the launch", status.isOK());
        assertTrue("the underlying Job monitor still receives the cancellation", monitor.isCanceled());
    }

    @Test
    public void testRunLaunchJobBodyIgnoresMonitorCancelledBeforeLaunch() throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        NullProgressMonitor monitor = new NullProgressMonitor();
        monitor.setCanceled(true);
        doAnswer(invocation -> {
            ((IProgressMonitor)invocation.getArgument(1)).setCanceled(true);
            return mock(ILaunch.class);
        }).when(config).launch(eq(ILaunchManager.DEBUG_MODE), any(IProgressMonitor.class));

        IStatus status = LaunchTool.runLaunchJobBody(config, false,
            ExternalInfobaseChangesPolicy.DEFAULT, monitor);

        assertTrue("a monitor already cancelled on entry is not attributed to EDT", status.isOK());
    }

    @Test
    public void testAbandonedLaunchMessageIncludesTheCapturedReason()
    {
        String message = LaunchTool.abandonedLaunchMessage("Client", "update failed"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Launch of 'Client' was abandoned by EDT (the launch delegate cancelled it). " //$NON-NLS-1$
            + "EDT logged while it ran: update failed. That error may belong to another operation " //$NON-NLS-1$
            + "running at the same time.", message); //$NON-NLS-1$
        assertFalse(message.endsWith("EDT logged while it ran: update failed")); //$NON-NLS-1$
    }

    @Test
    public void testRunLaunchJobBodyCoreExceptionReturnsItsStatusNotThrown() throws Exception
    {
        // A CoreException from the delegate is logged and surfaced as the Job's result
        // status (the exception's OWN status, as DebugUIPlugin does) — never rethrown,
        // so the Job can't die on it; the finally-disarm still ran (no exception here).
        Status refusal = new Status(IStatus.ERROR, "test", "launch refused"); //$NON-NLS-1$ //$NON-NLS-2$
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        Mockito.when(config.launch(eq(ILaunchManager.DEBUG_MODE), any()))
            .thenThrow(new CoreException(refusal));
        IStatus status = LaunchTool.runLaunchJobBody(config, false, ExternalInfobaseChangesPolicy.DEFAULT, new NullProgressMonitor());
        assertNotNull(status);
        assertSame("the CoreException's own status must be the Job result", refusal, status);
    }

    @Test
    public void testRunLaunchJobBodyRuntimeExceptionReturnsErrorStatusNotThrown() throws Exception
    {
        // A NON-CoreException failure must not escape either (an uncaught Throwable
        // kills the Job silently): it is logged and converted to an ERROR status that
        // carries the original exception.
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        IllegalStateException boom = new IllegalStateException("boom"); //$NON-NLS-1$
        Mockito.when(config.launch(eq(ILaunchManager.DEBUG_MODE), any())).thenThrow(boom);
        IStatus status = LaunchTool.runLaunchJobBody(config, true, ExternalInfobaseChangesPolicy.DEFAULT, new NullProgressMonitor());
        assertNotNull(status);
        assertEquals("a runtime failure must be an ERROR status", IStatus.ERROR, status.getSeverity());
        assertSame("the status must carry the original exception", boom, status.getException());
        assertTrue("the status message must name the failure",
            status.getMessage().contains("boom")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerStartSurfacesTheReturnedStatusMessage()
    {
        IStatus refusal = new Status(IStatus.ERROR, "test", "server start refused"); //$NON-NLS-1$ //$NON-NLS-2$

        StartOutcome outcome = LaunchTool.startStandaloneServerWithPolicy(
            new FakeStandaloneStartService(refusal), new Object(), "Standalone", //$NON-NLS-1$
            ILaunchManager.RUN_MODE, null, null, StandaloneServerPortConflictPolicy.CANCEL);

        assertEquals("server start refused", outcome.failure()); //$NON-NLS-1$
        assertTrue(outcome.conclusive());
        JsonObject error = JsonParser.parseString(
            LaunchTool.standaloneAttemptError("Standalone", outcome.failure())).getAsJsonObject(); //$NON-NLS-1$
        assertFalse(error.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(error.get("error").getAsString().contains("server start refused")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error.get("error").getAsString().contains("thin-client configuration")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStandaloneApplicationLookupReturnsAPreconditionFailureOnItsDeadline()
        throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(manager.getApplication(project, "ServerApplication.Test")).thenAnswer(invocation -> { //$NON-NLS-1$
            started.countDown();
            try
            {
                release.await(30, TimeUnit.SECONDS);
                return Optional.empty();
            }
            finally
            {
                finished.countDown();
            }
        });

        try
        {
            StandaloneServerSupport.ApplicationLookup lookup =
                StandaloneServerSupport.lookupApplicationBounded(manager, project,
                    "ServerApplication.Test", 250L); //$NON-NLS-1$

            assertTrue("the lookup must have entered EDT before this test calls it stalled", //$NON-NLS-1$
                started.await(5, TimeUnit.SECONDS));
            assertNull(lookup.application());
            assertNotNull(lookup.failure());
            assertTrue(lookup.failure().contains("EDT application lookup")); //$NON-NLS-1$
            assertTrue(lookup.failure().contains("did not finish within 250ms")); //$NON-NLS-1$
            assertTrue(lookup.failure().contains("may still be running")); //$NON-NLS-1$
            assertFalse("a stalled lookup must not be misreported as a measured not-found", //$NON-NLS-1$
                lookup.failure().contains("was not found")); //$NON-NLS-1$

            JsonObject error = JsonParser.parseString(LaunchTool.standalonePreconditionError(
                "Standalone", lookup.failure())).getAsJsonObject(); //$NON-NLS-1$
            assertFalse(error.get("success").getAsBoolean()); //$NON-NLS-1$
            assertTrue(error.get("error").getAsString().contains("application lookup")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse("a precondition timeout must not suggest that a start was attempted", //$NON-NLS-1$
                error.get("error").getAsString().contains("thin-client configuration")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            release.countDown();
            assertTrue("the timed-out lookup Job must not leak into later tests", //$NON-NLS-1$
                finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testStandaloneBoundedLookupCarriesStartAttributionWithoutASecondEdtRead()
        throws Exception
    {
        IApplicationManager manager = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);
        IApplication application = mock(IApplication.class,
            Mockito.withSettings().extraInterfaces(ServerBackedApplication.class));
        NamedStandaloneServer server = new NamedStandaloneServer("Standalone server for Base"); //$NON-NLS-1$
        when(manager.getApplication(project, "ServerApplication.Test")) //$NON-NLS-1$
            .thenReturn(Optional.of(application));
        when(((ServerBackedApplication)application).getServer()).thenReturn(server);
        when(application.getName()).thenReturn("Base"); //$NON-NLS-1$

        StandaloneServerSupport.ApplicationLookup lookup =
            StandaloneServerSupport.lookupApplicationBounded(manager, project,
                "ServerApplication.Test", 5_000L); //$NON-NLS-1$

        assertNull(lookup.failure());
        assertSame(application, lookup.application());
        assertSame(server, lookup.server());
        assertEquals("Base", lookup.infobaseName()); //$NON-NLS-1$
        assertEquals("Standalone server for Base", lookup.serverName()); //$NON-NLS-1$
        verify(manager, times(1)).getApplication(project, "ServerApplication.Test"); //$NON-NLS-1$
    }

    @Test
    public void testWholeStandalonePreparationStopsAtOneDeadline() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        BoundedJob.Result result = LaunchTool.runStandalonePreparationBounded(
            "Standalone", 250L, monitor -> { //$NON-NLS-1$
                started.countDown();
                try
                {
                    release.await(30, TimeUnit.SECONDS);
                }
                finally
                {
                    finished.countDown();
                }
            });

        try
        {
            assertTrue("the complete preparation must enter its one bounded job", //$NON-NLS-1$
                started.await(5, TimeUnit.SECONDS));
            assertEquals(BoundedJob.Outcome.TIMED_OUT, result.getOutcome());
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testStandaloneServerStartReportsItsCapturedPortConflict()
    {
        StartOutcome outcome = LaunchTool.startStandaloneServerWithPolicy(
            new PortConflictingStandaloneStartService(), new Object(), "Standalone", //$NON-NLS-1$
            ILaunchManager.DEBUG_MODE, null, null, StandaloneServerPortConflictPolicy.CANCEL);

        assertNotNull(outcome.failure());
        assertTrue(outcome.failure().contains("network ports are already in use")); //$NON-NLS-1$
        assertTrue(outcome.failure().contains("standaloneServerPortConflict='reassign'")); //$NON-NLS-1$
        assertFalse(outcome.portsReassigned());
    }

    @Test
    public void testStandaloneServerStartClassifiesOnlyFinishedFailuresAsConclusive()
    {
        assertTrue(BoundedJob.isInconclusive(BoundedJob.Outcome.TIMED_OUT));
        assertTrue(BoundedJob.isInconclusive(BoundedJob.Outcome.INTERRUPTED));
        assertFalse(BoundedJob.isInconclusive(BoundedJob.Outcome.TIMED_OUT_BEFORE_START));

        StartOutcome captured = LaunchTool.startStandaloneServerWithPolicy(
            new ThrowingStandaloneStartService(), new Object(), "Standalone", //$NON-NLS-1$
            ILaunchManager.DEBUG_MODE, null, null, StandaloneServerPortConflictPolicy.CANCEL);
        assertEquals("captured start failure", captured.failure()); //$NON-NLS-1$
        assertTrue(captured.conclusive());
    }

    @Test
    public void testConclusiveStandaloneFailureDisarmsBeforeTheCallReturns()
        throws Exception
    {
        IStatus refusal = new Status(IStatus.ERROR, "test", "server start refused"); //$NON-NLS-1$ //$NON-NLS-2$
        AtomicInteger inFlight = authInFlightCounter();
        int originalAuth = inFlight.get();
        int originalPortArms = portConflictArmCount();
        int originalConflictWatches = conflictWatchCount();
        armPortConflictForTest(StandaloneServerPortConflictPolicy.REASSIGN,
            "Foreign infobase", "Foreign server"); //$NON-NLS-1$ //$NON-NLS-2$

        try
        {
            StartOutcome outcome = LaunchTool.startStandaloneServerWithPolicy(
                new FakeStandaloneStartService(refusal), new Object(), "Standalone", //$NON-NLS-1$
                ILaunchManager.DEBUG_MODE, null, null, StandaloneServerPortConflictPolicy.CANCEL,
                5_000L, 5_000L);

            assertNotNull(outcome.failure());
            assertTrue(outcome.conclusive());
            assertEquals("the auth guard must be released before a conclusive call returns", //$NON-NLS-1$
                originalAuth, inFlight.get());
            assertEquals("the conflict watch must close before a conclusive call returns", //$NON-NLS-1$
                originalConflictWatches, conflictWatchCount());
            assertEquals("headless cleanup must not release another invocation's port guard", //$NON-NLS-1$
                originalPortArms + 1, portConflictArmCount());
        }
        finally
        {
            while (portConflictArmCount() > originalPortArms)
            {
                disarmPortConflictForTest(StandaloneServerPortConflictPolicy.REASSIGN,
                    "Foreign infobase", "Foreign server"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    @Test
    public void testInconclusiveStandaloneStartStaysArmedUntilItsJobCompletes() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<StartOutcome> answer = new AtomicReference<>();
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        AtomicInteger inFlight = authInFlightCounter();
        int originalAuth = inFlight.get();
        int originalPortArms = portConflictArmCount();
        int originalConflictWatches = conflictWatchCount();
        // Seed another invocation's port arm; the headless acquisition reports false, so neither
        // eager nor deferred cleanup may consume this marker.
        armPortConflictForTest(StandaloneServerPortConflictPolicy.CANCEL,
            "Foreign infobase", "Foreign server"); //$NON-NLS-1$ //$NON-NLS-2$
        BlockingStandaloneStartService service =
            new BlockingStandaloneStartService(started, release);
        Thread caller = new Thread(() -> {
            try
            {
                answer.set(LaunchTool.startStandaloneServerWithPolicy(service, new Object(),
                    "Standalone", ILaunchManager.DEBUG_MODE, null, null, //$NON-NLS-1$
                    StandaloneServerPortConflictPolicy.REASSIGN, 100L, 5_000L));
            }
            catch (Throwable t)
            {
                callerFailure.set(t);
            }
        }, "test: bounded standalone caller"); //$NON-NLS-1$

        caller.start();
        try
        {
            assertTrue("the underlying start must be running before the bounded wait returns", //$NON-NLS-1$
                started.await(5, TimeUnit.SECONDS));
            caller.join(5_000L);
            assertFalse("the bounded caller must have returned", caller.isAlive()); //$NON-NLS-1$
            assertNull(callerFailure.get());
            assertNotNull(answer.get());
            assertFalse(answer.get().conclusive());
            assertFalse("an inconclusive snapshot must not assert that no port rewrite occurred", //$NON-NLS-1$
                answer.get().portsReassigned());
            assertTrue(answer.get().portReassignmentOutcomeUnknown());
            assertTrue(answer.get().failure().contains(
                "ports may have been rewritten while the start continued")); //$NON-NLS-1$
            JsonObject error = JsonParser.parseString(LaunchTool.standaloneStartFailure(
                LaunchTool.standaloneInconclusiveAttemptError(
                    "Standalone", answer.get().failure()), //$NON-NLS-1$
                answer.get().portsReassigned(), answer.get().portReassignmentOutcomeUnknown()))
                .getAsJsonObject();
            assertTrue(error.get("mutationOutcomeUnknown").getAsBoolean()); //$NON-NLS-1$
            assertFalse("unknown must not be misreported as a committed reassignment", //$NON-NLS-1$
                error.has("mutationCommitted")); //$NON-NLS-1$
            assertFalse("unknown must not emit the exact-reassignment field", //$NON-NLS-1$
                error.has("standaloneServerPortsReassigned")); //$NON-NLS-1$
            assertEquals("the old eager auth release must be absent while the start is in flight", //$NON-NLS-1$
                originalAuth + 1, inFlight.get());
            assertEquals(
                "the old eager conflict-watch close must be absent while the start is in flight", //$NON-NLS-1$
                originalConflictWatches + 1, conflictWatchCount());
            assertEquals("headless cleanup must not release another invocation's port guard", //$NON-NLS-1$
                originalPortArms + 1, portConflictArmCount());

            release.countDown();
            long deadline = System.currentTimeMillis() + 5_000L;
            while ((inFlight.get() != originalAuth
                || conflictWatchCount() != originalConflictWatches)
                && System.currentTimeMillis() < deadline)
            {
                Thread.sleep(10L);
            }
            assertEquals("job completion must release the deferred auth guard", //$NON-NLS-1$
                originalAuth, inFlight.get());
            assertEquals("job completion must close the deferred conflict watch", //$NON-NLS-1$
                originalConflictWatches, conflictWatchCount());
            assertEquals("job completion must leave another invocation's port guard armed", //$NON-NLS-1$
                originalPortArms + 1, portConflictArmCount());
        }
        finally
        {
            release.countDown();
            caller.join(5_000L);
            long cleanupDeadline = System.currentTimeMillis() + 5_000L;
            while ((inFlight.get() != originalAuth
                || conflictWatchCount() != originalConflictWatches)
                && System.currentTimeMillis() < cleanupDeadline)
            {
                Thread.sleep(10L);
            }
            while (portConflictArmCount() > originalPortArms)
            {
                disarmPortConflictForTest(StandaloneServerPortConflictPolicy.CANCEL,
                    "Foreign infobase", "Foreign server"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    @Test
    public void testDirectStandaloneFailureSurfacesCancelledAccessSettingsDialog()
    {
        StartOutcome outcome = LaunchTool.startStandaloneServerWithPolicy(
            new AuthCancellingStandaloneStartService(), new Object(), "Standalone", //$NON-NLS-1$
            ILaunchManager.DEBUG_MODE, null, null, StandaloneServerPortConflictPolicy.CANCEL);

        assertNotNull(outcome.failure());
        assertTrue(outcome.failure().contains("infobase access-settings dialog")); //$NON-NLS-1$
        assertTrue(outcome.failure().contains("set_infobase_credentials")); //$NON-NLS-1$
        assertFalse("the old bare service-status failure must be absent", //$NON-NLS-1$
            "server start refused".equals(outcome.failure())); //$NON-NLS-1$
    }

    @Test
    public void testDeferredStandaloneDisarmRunsAtMostOnceForCompletionAndCap() throws Exception
    {
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicInteger cleanups = new AtomicInteger();
        BoundedJob.DeferredCleanup cleanup = new BoundedJob.DeferredCleanup(() -> {
            cleanups.incrementAndGet();
            cleaned.countDown();
        }, 100L, "test: standalone cleanup cap"); //$NON-NLS-1$

        cleanup.afterBoundedWait(false);
        cleanup.jobFinished();
        assertTrue(cleaned.await(5, TimeUnit.SECONDS));
        Thread.sleep(250L); // let the independently scheduled hard-cap path arrive too
        cleanup.jobFinished();

        assertEquals("job completion and the cap must share one exactly-once release", //$NON-NLS-1$
            1, cleanups.get());
    }

    @Test
    public void testDeferredStandaloneDisarmCapAlsoWinsAtMostOnce() throws Exception
    {
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicInteger cleanups = new AtomicInteger();
        BoundedJob.DeferredCleanup cleanup = new BoundedJob.DeferredCleanup(() -> {
            cleanups.incrementAndGet();
            cleaned.countDown();
        }, 25L, "test: standalone cleanup cap"); //$NON-NLS-1$

        cleanup.afterBoundedWait(false);
        assertTrue("the cap must release a confirmer whose job never finishes", //$NON-NLS-1$
            cleaned.await(5, TimeUnit.SECONDS));
        cleanup.jobFinished();

        assertEquals("a late job completion must not release the arm twice", //$NON-NLS-1$
            1, cleanups.get());
    }

    @Test
    public void testStandaloneServerRecoversStaleStateBeforeDispatchingStart()
    {
        AtomicInteger order = new AtomicInteger();

        StartOutcome result = LaunchTool.startStandaloneServerGuarded("Standalone", null, //$NON-NLS-1$
            () -> assertTrue("the preflight runs first", order.compareAndSet(0, 1)),
            () -> {
                assertTrue("the service starts only after recovery", order.compareAndSet(1, 2));
                return new StartOutcome(null, true, false);
            });

        assertNull(result.failure());
        assertEquals(2, order.get());
    }

    @Test
    public void testStandaloneServerDoesNotStartWhenRecoveryRefuses()
    {
        AtomicInteger starts = new AtomicInteger();

        StartOutcome result = LaunchTool.startStandaloneServerGuarded("Standalone", null, //$NON-NLS-1$
            () -> { throw new ApplicationException("the stale server is still stopping"); }, //$NON-NLS-1$
            () -> {
                starts.incrementAndGet();
                return new StartOutcome(null, true, false);
            });

        assertEquals(0, starts.get());
        assertTrue(result.failure().contains("the stale server is still stopping")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerFailedStartRestoresAStaleServerStoppedByItsPreflight()
    {
        StartOutcome result = LaunchTool.startStandaloneServerGuarded("Standalone", null, //$NON-NLS-1$
            () -> recordOperationStop("ServerApplication.Test"), //$NON-NLS-1$
            () -> new StartOutcome("server start refused", true, false)); //$NON-NLS-1$

        assertTrue(result.failure().contains("server start refused")); //$NON-NLS-1$
        assertTrue(result.failure().contains(
            "was stopped for this operation and could NOT be started again")); //$NON-NLS-1$
        assertTrue(result.failure().contains("launchConfigurationName='Standalone'")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerFailedStartDoesNotClaimRestorationWhenNothingWasStopped()
    {
        StartOutcome result = LaunchTool.startStandaloneServerGuarded("Standalone", null, () -> { }, //$NON-NLS-1$
            () -> new StartOutcome("server start refused", true, false)); //$NON-NLS-1$

        assertTrue(result.failure().contains("server start refused")); //$NON-NLS-1$
        assertFalse(result.failure().contains("stopped for this operation")); //$NON-NLS-1$
    }

    @Test
    public void testInconclusiveStandaloneStartDoesNotRestoreTheStoppedServer()
    {
        StartOutcome result = LaunchTool.startStandaloneServerGuarded("Standalone", null, //$NON-NLS-1$
            () -> recordOperationStop("ServerApplication.Test"), //$NON-NLS-1$
            () -> new StartOutcome("the wait for the standalone-server start was interrupted; " //$NON-NLS-1$
                + "the start may still be running", false, false)); //$NON-NLS-1$

        JsonObject error = JsonParser.parseString(result.failure()).getAsJsonObject();
        assertFalse(error.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Failed to start standalone server 'Standalone': the wait for the " //$NON-NLS-1$
            + "standalone-server start was interrupted; the start may still be running. The " //$NON-NLS-1$
            + "standalone server 'ServerApplication.Test' was stopped for this operation and was " //$NON-NLS-1$
            + "left stopped instead of scheduling a second start because the original start may " //$NON-NLS-1$
            + "still be running. Wait for the in-flight start to settle, then check debug_status " //$NON-NLS-1$
            + "and EDT's Servers view before starting anything. Only if the server is stopped, " //$NON-NLS-1$
            + "call launch(launchConfigurationName='Standalone').", //$NON-NLS-1$
            error.get("error").getAsString()); //$NON-NLS-1$
        assertFalse(error.get("error").getAsString().contains("has been started again")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(error.get("error").getAsString().contains("could NOT be started again")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an inconclusive start must not recommend a second start through a client", //$NON-NLS-1$
            error.get("error").getAsString().contains("thin-client configuration")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error.get("error").getAsString().contains("check debug_status")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStandaloneServerPortPolicyKeepsTheCallersReassignChoice() throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, "")) //$NON-NLS-1$
            .thenReturn("Project"); //$NON-NLS-1$
        when(config.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, "")) //$NON-NLS-1$
            .thenReturn("ServerApplication.Test"); //$NON-NLS-1$

        assertSame(StandaloneServerPortConflictPolicy.REASSIGN,
            LaunchTool.standaloneServerPortPolicy(config,
                StandaloneServerPortConflictPolicy.REASSIGN));
    }

    @Test
    public void theUpdateRequestIsReadFromPRESENCE_notFromTheSchemaDefault()
    {
        assertFalse("a caller that never passed updateBeforeLaunch has requested nothing; reading " //$NON-NLS-1$
            + "the defaulted true here would refuse every plain standalone-server start", //$NON-NLS-1$
            LaunchTool.explicitUpdateRequest(Map.of("launchConfigurationName", "Standalone"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("an explicit updateBeforeLaunch=true is a real request", //$NON-NLS-1$
            LaunchTool.explicitUpdateRequest(Map.of("updateBeforeLaunch", "true"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("an explicit updateBeforeLaunch=false is not a request", //$NON-NLS-1$
            LaunchTool.explicitUpdateRequest(Map.of("updateBeforeLaunch", "false"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theExternalChangesPolicyRequestIsReadFromPRESENCE_notFromItsDefault()
    {
        assertFalse(LaunchTool.explicitPolicyRequest(
            Map.of("launchConfigurationName", "Standalone"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(LaunchTool.explicitPolicyRequest(
            Map.of("externalInfobaseChanges", "  "))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(LaunchTool.explicitPolicyRequest(
            Map.of("externalInfobaseChanges", "override"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStandaloneServerRefusesOnlyAnExplicitUpdateRequest()
    {
        String refusal = LaunchTool.standaloneUpdateRefusal("Standalone", true, //$NON-NLS-1$
            false, ExternalInfobaseChangesPolicy.DEFAULT);

        assertNotNull(refusal);
        assertTrue(refusal.contains("'updateBeforeLaunch'")); //$NON-NLS-1$
        assertTrue(refusal.contains("update_database")); //$NON-NLS-1$
        assertTrue(refusal.contains("updateBeforeLaunch=false")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerRefusesANonDefaultExternalChangesPolicy()
    {
        String refusal = LaunchTool.standaloneUpdateRefusal("Standalone", false, //$NON-NLS-1$
            true, ExternalInfobaseChangesPolicy.IMPORT);

        assertNotNull(refusal);
        assertTrue(refusal.contains("'externalInfobaseChanges'='import'")); //$NON-NLS-1$
        assertTrue(refusal.contains("update_database")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerRefusesAnExplicitOverrideButAcceptsAnOmittedPolicy()
    {
        String refusal = LaunchTool.standaloneUpdateRefusal("Standalone", false, true, //$NON-NLS-1$
            ExternalInfobaseChangesPolicy.DEFAULT);
        JsonObject error = JsonParser.parseString(refusal).getAsJsonObject();
        assertFalse(error.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Parameter 'externalInfobaseChanges'='override' cannot be honoured for " //$NON-NLS-1$
            + "standalone-server launch configuration 'Standalone' because this direct route " //$NON-NLS-1$
            + "performs no database update. Run update_database with " //$NON-NLS-1$
            + "externalInfobaseChanges='override' first, then call launch with " //$NON-NLS-1$
            + "updateBeforeLaunch=false and omit externalInfobaseChanges.", //$NON-NLS-1$
            error.get("error").getAsString()); //$NON-NLS-1$
        assertFalse(error.get("error").getAsString().contains("='import'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(LaunchTool.standaloneUpdateRefusal("Standalone", false, false, //$NON-NLS-1$
            ExternalInfobaseChangesPolicy.DEFAULT));
    }

    @Test
    public void testStandaloneServerRefusesEveryClientOnlyParameterByName()
    {
        String startup = LaunchTool.standaloneOverridesRefusal("Standalone", //$NON-NLS-1$
            LaunchOverrides.of("run tests", null, null)); //$NON-NLS-1$
        assertTrue(startup.contains("'startupOption'")); //$NON-NLS-1$
        assertTrue(startup.contains("standalone server starts no client")); //$NON-NLS-1$

        String project = LaunchTool.standaloneOverridesRefusal("Standalone", //$NON-NLS-1$
            LaunchOverrides.of(null, "ExternalObjects", null)); //$NON-NLS-1$
        assertTrue(project.contains("'externalObjectProjectName'")); //$NON-NLS-1$
        assertTrue(project.contains("standalone server starts no client")); //$NON-NLS-1$

        String object = LaunchTool.standaloneOverridesRefusal("Standalone", //$NON-NLS-1$
            LaunchOverrides.of(null, null, "Runner")); //$NON-NLS-1$
        assertTrue(object.contains("'externalObjectName'")); //$NON-NLS-1$
        assertTrue(object.contains("standalone server starts no client")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerWithNoClientOnlyParametersStillStarts()
    {
        assertNull(LaunchTool.standaloneOverridesRefusal("Standalone", //$NON-NLS-1$
            LaunchOverrides.of(null, null, null)));
        AtomicInteger starts = new AtomicInteger();
        assertNull(LaunchTool.startStandaloneServerGuarded("Standalone", null, () -> { }, () -> { //$NON-NLS-1$
            starts.incrementAndGet();
            return new StartOutcome(null, true, false);
        }).failure());
        assertEquals(1, starts.get());
    }

    @Test
    public void testStandalonePreconditionErrorOmitsTheThinClientFallback()
    {
        JsonObject error = JsonParser.parseString(LaunchTool.standalonePreconditionError(
            "Standalone", "Project is closed: Project")).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(error.get("error").getAsString().contains("Project is closed: Project")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(error.get("error").getAsString().contains("thin-client configuration")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStandaloneServerSuccessReportsEffectiveDebugMode()
    {
        JsonObject result = JsonParser.parseString(LaunchTool.standaloneStartSuccess(
            "Standalone", STANDALONE_SERVER_TYPE_ID, "Project", //$NON-NLS-1$ //$NON-NLS-2$
            "ServerApplication.Test", false)).getAsJsonObject(); //$NON-NLS-1$

        assertTrue(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("debug", result.get("mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("running", result.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.get("message").getAsString() //$NON-NLS-1$
            .contains("regardless of the requested mode")); //$NON-NLS-1$
        assertTrue(result.get("message").getAsString() //$NON-NLS-1$
            .contains("No database update was performed")); //$NON-NLS-1$
        assertFalse(result.has("standaloneServerPortsReassigned")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerSuccessReportsReassignedPorts()
    {
        StartOutcome outcome = LaunchTool.startStandaloneServerWithPolicy(
            new PortReassigningStandaloneStartService(), new Object(), "Standalone", //$NON-NLS-1$
            ILaunchManager.DEBUG_MODE, null, null, StandaloneServerPortConflictPolicy.REASSIGN);
        assertNull(outcome.failure());
        assertTrue(outcome.portsReassigned());

        JsonObject result = JsonParser.parseString(LaunchTool.standaloneStartSuccess(
            "Standalone", STANDALONE_SERVER_TYPE_ID, "Project", //$NON-NLS-1$ //$NON-NLS-2$
            "ServerApplication.Test", outcome.portsReassigned())).getAsJsonObject(); //$NON-NLS-1$
        assertTrue(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.get("standaloneServerPortsReassigned").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.has("portsReassigned")); //$NON-NLS-1$
        assertEquals("Standalone server 'Standalone' is running in DEBUG mode. EDT starts " //$NON-NLS-1$
            + "standalone servers in DEBUG mode regardless of the requested mode. No database " //$NON-NLS-1$
            + "update was performed. NOTE: the standalone server's ports were busy, so EDT moved " //$NON-NLS-1$
            + "it to free ports and rewrote its configuration " //$NON-NLS-1$
            + "(standaloneServerPortConflict=reassign) — clients must use the new address.", //$NON-NLS-1$
            result.get("message").getAsString()); //$NON-NLS-1$

        JsonObject outputProperties = JsonParser.parseString(new LaunchTool().getOutputSchema())
            .getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
        assertTrue(outputProperties.has("standaloneServerPortsReassigned")); //$NON-NLS-1$
        assertFalse(outputProperties.has("portsReassigned")); //$NON-NLS-1$
    }

    @Test
    public void testStandaloneServerFailureReportsReassignedPortsAsPostMutation()
    {
        StartOutcome outcome = LaunchTool.startStandaloneServerWithPolicy(
            new PortReassigningFailingStandaloneStartService(), new Object(), "Standalone", //$NON-NLS-1$
            ILaunchManager.DEBUG_MODE, null, null, StandaloneServerPortConflictPolicy.REASSIGN);
        assertNotNull(outcome.failure());
        assertTrue(outcome.portsReassigned());

        JsonObject result = JsonParser.parseString(LaunchTool.standaloneStartFailure(
            LaunchTool.standaloneAttemptError("Standalone", outcome.failure()), //$NON-NLS-1$
            outcome.portsReassigned())).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.get("standaloneServerPortsReassigned").getAsBoolean()); //$NON-NLS-1$
        assertTrue(result.get("mutationCommitted").getAsBoolean()); //$NON-NLS-1$
        assertFalse(result.has("mutationOutcomeUnknown")); //$NON-NLS-1$

        JsonObject unchanged = JsonParser.parseString(LaunchTool.standaloneStartFailure(
            LaunchTool.standaloneAttemptError("Standalone", "server start refused"), false)) //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject();
        assertFalse(unchanged.has("standaloneServerPortsReassigned")); //$NON-NLS-1$
        assertFalse(unchanged.has("mutationCommitted")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsBackgroundJobLaunch()
    {
        // Ratchet: the guide must document that the launch runs as a
        // background EDT Job — the workbench stays responsive even through a minutes-long
        // standalone-server mode-switch restart, with progress in the Progress view.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the background-Job dispatch",
            guide.contains("background EDT Job")); //$NON-NLS-1$
        assertTrue("guide must say the workbench stays responsive",
            guide.contains("stays responsive")); //$NON-NLS-1$
        assertTrue("guide must point to the Progress view",
            guide.contains("Progress view")); //$NON-NLS-1$
    }

    // ==================== updateBeforeLaunch synthetic-id contract ====================

    @Test
    public void testGuideDocumentsSyntheticIdPreflightSkip()
    {
        // A config without a persisted ATTR_APPLICATION_ID is
        // tracked under a synthetic 'launch:<configName>' id, which cannot be
        // resolved through IApplicationManager. The DB-update preflight must SKIP
        // such ids (isSyntheticApplicationId) instead of failing the launch with
        // 'Application not found: launch:<name>'. Ratchet the guide half of that
        // contract so the documented behavior can't silently drift.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the synthetic-id preflight skip",
            guide.contains("launch:<configName>")); //$NON-NLS-1$
    }

    // ============ 1003 confirmer armed independently of updateBeforeLaunch ============

    @Test
    public void testPerformLaunchHeadlessWithUpdateConfirmExecutesSynchronously() throws Exception
    {
        // The debug path now arms with (updateDialog=autoConfirmUpdateDialog,
        // sessionDialog=true) via the split arm(boolean,boolean). The synchronous
        // headless path must still launch cleanly with autoConfirmUpdateDialog=true:
        // the confirmer arm/disarm is a no-op in this no-workbench harness and must
        // not break the launch or its finally chain.
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        String error = new LaunchTool().performLaunch(config, true, ExternalInfobaseChangesPolicy.DEFAULT);
        assertNull("successful headless launch must return null even with update auto-confirm", error);
        Mockito.verify(config).launch(eq(ILaunchManager.DEBUG_MODE), isA(AttributableCancel.class));
    }

    // ============ alreadyRunning detects a live CLIENT session only ============

    @Test
    public void testGuideDocumentsClientOnlyAlreadyRunningDetect()
    {
        // The already-running detect is scoped to a live CLIENT session — a
        // thread-less standalone-SERVER debug target sharing the same app id no longer
        // blocks the client launch, and launching a client WHILE a debug-server is up
        // is allowed (it attaches). Ratchet the guide so this can't silently drift back
        // to the earlier over-broad app-id-only detect.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document that already-running is scoped to a live CLIENT session",
            guide.contains("live CLIENT session")); //$NON-NLS-1$
        assertTrue("guide must document that launching a client while a debug-server is up is allowed",
            guide.contains("launching a client WHILE a debug-server")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsRestartIfRunningNeverTerminatesServer()
    {
        // restartIfRunning only ever terminates a live CLIENT session, never the
        // debug server (a server target has no live thread, so it is not the duplicate).
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document restartIfRunning never terminates the debug server",
            guide.contains("NEVER a debug server")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocuments1003KeepExistingButton()
    {
        // The 1003 safety-net now presses 'Keep existing and start new'
        // (LAUNCH_ANYWAY) so an already-running session survives — not the default
        // 'Stop existing and start new' that would terminate it.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the 1003 confirmer presses 'Keep existing and start new'",
            guide.contains("Keep existing and start new")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocuments1003ConfirmerIndependentOfUpdateBeforeLaunch()
    {
        // The code-1003 "debug session already exists" auto-confirmer
        // is armed on EVERY debug launch, independent of updateBeforeLaunch (it
        // performs no DB update, so it does not undo the updateBeforeLaunch=false
        // opt-out). Only the separate 'Application update' modal stays gated on
        // updateBeforeLaunch. Ratchet the guide so this can't drift.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the 1003 confirmer fires regardless of updateBeforeLaunch",
            guide.contains("regardless of `updateBeforeLaunch`")); //$NON-NLS-1$
    }

    // ============ unified existing-session detection (client-only + restartIfRunning) ============
    //
    // The duplicate-session decision was unified so by-name AND by-project+application,
    // and the ILaunchManager guards AND the target-manager detect, all funnel through
    // one resolveExistingClientSession + handleExistingClientSession. A follow-up moved the
    // decision/terminate helpers to LaunchLifecycleUtils (shared with the YAXUnit debug
    // path) and made the discriminator thread-TYPE-aware — their unit tests moved
    // alongside (see LaunchLifecycleUtilsSessionTest). What stays here is the part
    // LaunchTool keeps: handleExistingClientSession honoring restartIfRunning in
    // BOTH directions, and the alreadyRunning JSON shaping (AlreadyRunningContext).

    private static IThread liveThread()
    {
        IThread t = mock(IThread.class);
        when(t.isTerminated()).thenReturn(false);
        return t;
    }

    private static IDebugTarget targetWithThreads(IThread... threads) throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenReturn(threads);
        return target;
    }

    @Test
    public void testHandleExistingClientSessionRestartFalseReturnsAlreadyRunning() throws Exception
    {
        // restartIfRunning=false: a real CLIENT session short-circuits with
        // alreadyRunning:true and is NOT terminated — the documented default contract,
        // now honored uniformly through the single decision point.
        IDebugTarget client = targetWithThreads(liveThread());
        ExistingClientSession session = new ExistingClientSession(null, client, "debug"); //$NON-NLS-1$
        AlreadyRunningContext ctx = new AlreadyRunningContext("already running msg"); //$NON-NLS-1$
        ctx.launchConfiguration = "MyApp / Client"; //$NON-NLS-1$
        ctx.project = "MyProject"; //$NON-NLS-1$
        String json = new LaunchTool()
            .handleExistingClientSession(session, "app-1", false, ctx); //$NON-NLS-1$
        assertNotNull("restartIfRunning=false must short-circuit (non-null JSON)", json);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue("must report alreadyRunning:true", obj.get("alreadyRunning").getAsBoolean()); //$NON-NLS-1$
        assertFalse("alreadyRunning short-circuit must NOT carry status:launching",
            obj.has("status")); //$NON-NLS-1$
        assertEquals("debug", obj.get("mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("app-1", obj.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        // The matched client target must NOT have been terminated on the default path.
        verify(client, never()).terminate();
    }

    @Test
    public void testHandleExistingClientSessionRestartTrueTerminatesClientTargetAndProceeds()
        throws Exception
    {
        // restartIfRunning=true on a live CLIENT debug target: the SAME non-interactive
        // terminate the target-manager path already did now runs for the ILaunchManager-
        // sourced client too — it terminates the existing client and returns null so the
        // caller relaunches (NOT alreadyRunning). This is the unification fix: restartIfRunning is
        // honored in every path, including MCP/ILaunch-registered client sessions.
        IDebugTarget client = targetWithThreads(liveThread());
        when(client.canTerminate()).thenReturn(true);
        when(client.isTerminated()).thenReturn(false, true); // dies right after terminate()
        ExistingClientSession session = new ExistingClientSession(null, client, "debug"); //$NON-NLS-1$
        AlreadyRunningContext ctx = new AlreadyRunningContext("msg"); //$NON-NLS-1$
        String result = new LaunchTool()
            .handleExistingClientSession(session, "app-2", true, ctx); //$NON-NLS-1$
        assertNull("restartIfRunning=true must proceed to relaunch (null), not short-circuit",
            result);
        verify(client, times(1)).terminate();
    }

    @Test
    public void testHandleExistingClientSessionRestartTrueTerminatesRunModeLaunchAndProceeds()
        throws Exception
    {
        // restartIfRunning=true on a RUN-mode launch (no debug target): the launch
        // analogue terminate runs and the caller proceeds (null). Confirms the flag is
        // honored for the RUN-mode already-running guard too, not just DEBUG targets.
        ILaunch runLaunch = mock(ILaunch.class);
        when(runLaunch.canTerminate()).thenReturn(true);
        when(runLaunch.isTerminated()).thenReturn(false, true);
        ExistingClientSession session = new ExistingClientSession(runLaunch, null, "run"); //$NON-NLS-1$
        AlreadyRunningContext ctx = new AlreadyRunningContext("msg"); //$NON-NLS-1$
        String result = new LaunchTool()
            .handleExistingClientSession(session, "app-3", true, ctx); //$NON-NLS-1$
        assertNull(result);
        verify(runLaunch, times(1)).terminate();
    }

    @Test
    public void testHandleExistingClientSessionRestartFalseRunModeReportsRunMode() throws Exception
    {
        // The RUN-mode already-running guard still short-circuits with alreadyRunning:true
        // and reports mode:"run" — the discriminator preserves it (a RUN launch is a real
        // running client, never confused with a thread-less server session).
        ILaunch runLaunch = mock(ILaunch.class);
        ExistingClientSession session = new ExistingClientSession(runLaunch, null, "run"); //$NON-NLS-1$
        AlreadyRunningContext ctx = new AlreadyRunningContext("msg"); //$NON-NLS-1$
        String json = new LaunchTool()
            .handleExistingClientSession(session, "app-4", false, ctx); //$NON-NLS-1$
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(obj.get("alreadyRunning").getAsBoolean()); //$NON-NLS-1$
        assertEquals("run", obj.get("mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        // A RUN-mode launch carries no debug target, so nothing is terminated on default.
        verify(runLaunch, never()).terminate();
    }

    @Test
    public void testAlreadyRunningContextEmitsSuppliedIdentityFields()
    {
        // Output-schema parity: the unified payload echoes every identity field the
        // call site supplied, and applicationId/mode/alreadyRunning.
        AlreadyRunningContext ctx = new AlreadyRunningContext("the message"); //$NON-NLS-1$
        ctx.launchConfiguration = "Cfg"; //$NON-NLS-1$
        ctx.configurationType = "type.id"; //$NON-NLS-1$
        ctx.attach = Boolean.TRUE;
        ctx.project = "Proj"; //$NON-NLS-1$
        JsonObject obj = JsonParser.parseString(
            ctx.buildAlreadyRunning("debug", "the-app").toJson()).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(obj.get("alreadyRunning").getAsBoolean()); //$NON-NLS-1$
        assertEquals("debug", obj.get("mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the message", obj.get("message").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Cfg", obj.get("launchConfiguration").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("type.id", obj.get("configurationType").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(obj.get("attach").getAsBoolean()); //$NON-NLS-1$
        assertEquals("Proj", obj.get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the-app", obj.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAlreadyRunningContextOmitsUnsetOptionalFields()
    {
        AlreadyRunningContext ctx = new AlreadyRunningContext("m"); //$NON-NLS-1$
        ctx.project = "P"; //$NON-NLS-1$
        ctx.attach = Boolean.FALSE;
        JsonObject obj = JsonParser.parseString(
            ctx.buildAlreadyRunning("debug", "a").toJson()).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("absent launchConfiguration must be omitted", obj.has("launchConfiguration")); //$NON-NLS-1$
        assertFalse("absent configurationType must be omitted", obj.has("configurationType")); //$NON-NLS-1$
        assertEquals("P", obj.get("project").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("attach=false is still emitted (Boolean set)", !obj.has("attach")); //$NON-NLS-1$
        assertFalse(obj.get("attach").getAsBoolean()); //$NON-NLS-1$
    }

    // The decideExistingClientSession / resolveExistingClientSession /
    // firstLiveClientThreadTarget / terminate-half tests moved to
    // LaunchLifecycleUtilsSessionTest alongside the helpers.

    // ============ type-aware CLIENT discriminator ============

    @Test
    public void testGuideDocumentsTypeAwareClientDiscriminator()
    {
        // The discriminator is the live thread's TYPE, not bare liveness — a
        // debug-mode standalone server carries a LIVE thread typed SERVER («Сервер»)
        // and must never be detected as the client duplicate. Ratchet the guide so
        // the contract can't drift back to the liveness-only detect.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document the thread-TYPE discriminator",
            guide.contains("getType()")); //$NON-NLS-1$
        assertTrue("guide must document that a debug-mode server's live thread is typed SERVER",
            guide.contains("typed SERVER")); //$NON-NLS-1$
        assertTrue("guide must say only CLIENT-typed sessions are considered",
            guide.contains("CLIENT-typed sessions")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsServerNeverBlocksOrRestarts()
    {
        // A debug-mode standalone server never blocks a client launch and is
        // never restarted/terminated by restartIfRunning.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must say the server is never restarted or terminated",
            guide.contains("never restarted or terminated")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsRestartIfRunningTerminateAndRelaunch()
    {
        // The guide must keep documenting that restartIfRunning=true terminates the
        // existing CLIENT session and relaunches (now honored in every path). Ratchet so
        // the unified-policy promise can't drift out of the docs.
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must document restartIfRunning terminate+relaunch",
            guide.contains("terminate the existing CLIENT session")); //$NON-NLS-1$
    }

    // ============ server-application pre-update deferred to the launch delegate ============
    //
    // IApplicationManager.update on a ServerApplication starts the standalone server in
    // RUN mode and caches a live designer-agent connection (DesignerSessionPool); the
    // launch delegate then needs the server in DEBUG mode, and the restart's teardown of
    // that connection wedges the launch. The gate (runPreLaunchUpdateStep) therefore
    // defers the update for ServerApplication.* ids to the delegate's coordinated path
    // (auto-confirmed by the armed update confirmer), while non-server (file /
    // client-server infobase) applications keep the programmatic pre-update exactly as
    // before, and updateBeforeLaunch=false keeps skipping it for everyone.

    private static IProject mockOpenProject()
    {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("MyProject"); //$NON-NLS-1$
        return project;
    }

    @Test
    public void testPreLaunchUpdateStepServerApplicationSkipsProgrammaticUpdate() throws Exception
    {
        // THE SERVER-APPLICATION GATE: a ServerApplication.* id with updateBeforeLaunch=true must NOT be
        // updated out-of-band — the step returns null (proceed to performLaunch, where
        // the armed confirmer covers the delegate's coordinated update dialog) WITHOUT
        // touching the application manager at all.
        IApplicationManager mgr = mock(IApplicationManager.class);
        String error = LaunchTool.runPreLaunchUpdateStep(mockOpenProject(),
            "ServerApplication.MyServer", mgr, true, ExternalInfobaseChangesPolicy.DEFAULT); //$NON-NLS-1$
        assertNull("server application must proceed without a programmatic update", error);
        verify(mgr, never()).update(any(), any(), any(), any());
        verify(mgr, never()).getUpdateState(any());
        verify(mgr, never()).getApplication(any(IProject.class), anyString());
    }

    @Test
    public void testPreLaunchUpdateStepNonServerApplicationStillUpdates() throws Exception
    {
        // Non-server (file / client-server infobase) application: the programmatic
        // pre-update KEEPS running exactly as before — the hang is server-app-specific,
        // ordinary infobase apps don't start a standalone server. A stale IB is updated
        // and the step proceeds on success.
        IProject project = mockOpenProject();
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq("infobase-app-uuid"))) //$NON-NLS-1$
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        when(mgr.update(eq(app), eq(ApplicationUpdateType.INCREMENTAL),
            any(ExecutionContext.class), any())).thenReturn(ApplicationUpdateState.UPDATED);

        String error = LaunchTool.runPreLaunchUpdateStep(project,
            "infobase-app-uuid", mgr, true, ExternalInfobaseChangesPolicy.DEFAULT); //$NON-NLS-1$
        assertNull("a successful non-server update must proceed", error);
        verify(mgr, times(1)).update(eq(app), eq(ApplicationUpdateType.INCREMENTAL),
            any(ExecutionContext.class), any());
    }

    @Test
    public void testPreLaunchUpdateStepOptOutSkipsUpdateForServerAndNonServer() throws Exception
    {
        // updateBeforeLaunch=false semantics unchanged (the documented opt-out): no programmatic
        // update for ANY application kind — server or not — and the step never touches
        // the application manager (performLaunch then leaves the update confirmer
        // unarmed, so the platform's modal — if any — is a human's).
        IApplicationManager mgr = mock(IApplicationManager.class);
        assertNull(LaunchTool.runPreLaunchUpdateStep(mockOpenProject(),
            "ServerApplication.MyServer", mgr, false, ExternalInfobaseChangesPolicy.DEFAULT)); //$NON-NLS-1$
        assertNull(LaunchTool.runPreLaunchUpdateStep(mockOpenProject(),
            "infobase-app-uuid", mgr, false, ExternalInfobaseChangesPolicy.DEFAULT)); //$NON-NLS-1$
        verify(mgr, never()).update(any(), any(), any(), any());
        verify(mgr, never()).getUpdateState(any());
        verify(mgr, never()).getApplication(any(IProject.class), anyString());
    }

    @Test
    public void testGuideDocumentsServerApplicationDeferredUpdate()
    {
        // Ratchet: the guide must document that on standalone-server applications the
        // DB update is performed by EDT's coordinated launch flow (auto-confirmed), not
        // by an out-of-band pre-update (which started the server in RUN mode and wedged
        // the debug restart).
        String guide = new LaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue("guide must name the ServerApplication. id prefix gate",
            guide.contains("ServerApplication.")); //$NON-NLS-1$
        assertTrue("guide must say server apps are not pre-updated out-of-band",
            guide.contains("does NOT pre-update such applications out-of-band")); //$NON-NLS-1$
        assertTrue("guide must document the coordinated launch flow performing the update",
            guide.contains("coordinated launch flow")); //$NON-NLS-1$
    }

    // ==================== extractRestartIfRunning — full default/override matrix ====================
    //
    // extractRestartIfRunning is the pure (no-EDT) seam execute() reads. The two
    // headline branches (absent->false, "true"->true) are pinned above; these add the
    // remaining JsonUtils.extractBooleanArgument outcomes so the flag's parsing can't
    // silently change: the OTHER truthy tokens, the explicit-false tokens, an empty
    // value (falls back to the default), and an unrecognized token (also the default).

    @Test
    public void testExtractRestartIfRunningAlternateTruthyTokensAreTrue()
    {
        // "1" and "yes" are JsonUtils' other truthy spellings — they must flip the flag
        // exactly like "true", so a non-canonical client value still restarts.
        Map<String, String> one = new HashMap<>();
        one.put("restartIfRunning", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("restartIfRunning=1 must be true",
            LaunchTool.extractRestartIfRunning(one));
        Map<String, String> yes = new HashMap<>();
        yes.put("restartIfRunning", "YES"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("restartIfRunning=YES (case-insensitive) must be true",
            LaunchTool.extractRestartIfRunning(yes));
    }

    @Test
    public void testExtractRestartIfRunningExplicitFalseTokensAreFalse()
    {
        // The explicit-false spellings must stay false (not flip to the true side).
        Map<String, String> f = new HashMap<>();
        f.put("restartIfRunning", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("restartIfRunning=false must be false",
            LaunchTool.extractRestartIfRunning(f));
        Map<String, String> zero = new HashMap<>();
        zero.put("restartIfRunning", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("restartIfRunning=0 must be false",
            LaunchTool.extractRestartIfRunning(zero));
        Map<String, String> no = new HashMap<>();
        no.put("restartIfRunning", "no"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("restartIfRunning=no must be false",
            LaunchTool.extractRestartIfRunning(no));
    }

    @Test
    public void testExtractRestartIfRunningEmptyAndGarbageFallBackToDefault()
    {
        // An empty value and an unrecognized token both fall back to the documented
        // default (false) — the safe non-destructive choice, never an accidental restart.
        Map<String, String> empty = new HashMap<>();
        empty.put("restartIfRunning", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("empty restartIfRunning must fall back to the false default",
            LaunchTool.extractRestartIfRunning(empty));
        Map<String, String> garbage = new HashMap<>();
        garbage.put("restartIfRunning", "maybe"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("unrecognized restartIfRunning token must fall back to the false default",
            LaunchTool.extractRestartIfRunning(garbage));
    }

    // ==================== execute() early-validation — empty-value branches ====================
    //
    // execute()'s required-argument guards test (value == null || value.isEmpty()), so a
    // present-but-EMPTY value is a distinct branch from a missing key — and an empty
    // launchConfigurationName must NOT enter mode 1 (the guard is also
    // !configName.isEmpty()), so it falls through to mode 2's projectName guard. All
    // three checks return BEFORE the first ProjectStateChecker/workspace access, so no
    // live workbench is needed.

    @Test
    public void testEmptyProjectNameIsRequiredError()
    {
        // projectName present as "" (no launchConfigurationName) must hit the same
        // "projectName is required" guard as a missing key, not slip past it.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new LaunchTool().execute(params);
        assertTrue("empty projectName must produce 'projectName is required'",
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyLaunchConfigurationNameFallsThroughToProjectMode()
    {
        // launchConfigurationName="" must NOT enter the by-name mode (whose first
        // statement touches the live launch manager); with no projectName it falls
        // through to the project+application guard and reports projectName required.
        Map<String, String> params = new HashMap<>();
        params.put("launchConfigurationName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new LaunchTool().execute(params);
        assertTrue("empty launchConfigurationName must fall through to the projectName guard",
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyApplicationIdIsRequiredError()
    {
        // projectName present, no launchConfigurationName, applicationId present as ""
        // must hit the "applicationId is required" guard (the .isEmpty() half) before
        // any workspace access.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("applicationId", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new LaunchTool().execute(params);
        assertTrue("empty applicationId must produce 'applicationId is required'",
            result.contains("applicationId is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameErrorSteersToLaunchConfigurationName()
    {
        // The projectName-required error must keep advertising the alternative
        // (launchConfigurationName) so the caller can recover without guessing.
        Map<String, String> params = new HashMap<>();
        String result = new LaunchTool().execute(params);
        assertTrue("projectName error must mention the launchConfigurationName alternative",
            result.contains("launchConfigurationName")); //$NON-NLS-1$
    }

    @Test
    public void testMissingApplicationIdErrorSteersToGetApplications()
    {
        // The applicationId-required error must point the caller at get_applications
        // (the discovery source) and at the launchConfigurationName alternative.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new LaunchTool().execute(params);
        assertTrue("applicationId error must steer to get_applications",
            result.contains("get_applications")); //$NON-NLS-1$
        assertTrue("applicationId error must mention the launchConfigurationName alternative",
            result.contains("launchConfigurationName")); //$NON-NLS-1$
    }

    @Test
    public void testEarlyValidationErrorsAreSuccessFalseJson()
    {
        // The early-validation errors are well-formed error JSON: parseable and
        // success:false — the wire shape every MCP client keys off.
        JsonObject obj = JsonParser.parseString(new LaunchTool().execute(new HashMap<>()))
            .getAsJsonObject();
        assertTrue("error result must carry success:false", obj.has("success")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("a validation failure must be success:false", obj.get("success").getAsBoolean()); //$NON-NLS-1$
    }

    // ==================== AlreadyRunningContext.buildAlreadyRunning — pure JSON shaping ====================
    //
    // The alreadyRunning payload builder is pure (Gson only) — exercise its branches
    // without mocking any debug type. The set-all and unset-optional cases are pinned
    // above; these add the EMPTY-STRING optional omissions, a null/empty applicationId,
    // and the "run" mode value, so the omit-when-blank contract is fully covered.

    @Test
    public void testBuildAlreadyRunningOmitsEmptyStringOptionalFields()
    {
        // Optional identity fields set to "" (not just null) must be omitted too — the
        // guard is (field != null && !field.isEmpty()). project="" must drop, and a "run"
        // mode flows straight through to the mode field.
        AlreadyRunningContext ctx = new AlreadyRunningContext("m"); //$NON-NLS-1$
        ctx.launchConfiguration = ""; //$NON-NLS-1$
        ctx.configurationType = ""; //$NON-NLS-1$
        ctx.project = ""; //$NON-NLS-1$
        JsonObject obj = JsonParser.parseString(
            ctx.buildAlreadyRunning("run", "the-app").toJson()).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("empty launchConfiguration must be omitted", obj.has("launchConfiguration")); //$NON-NLS-1$
        assertFalse("empty configurationType must be omitted", obj.has("configurationType")); //$NON-NLS-1$
        assertFalse("empty project must be omitted", obj.has("project")); //$NON-NLS-1$
        assertEquals("run", obj.get("mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the-app", obj.get("applicationId").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBuildAlreadyRunningOmitsNullAndEmptyApplicationId()
    {
        // applicationId is itself optional in the payload: a null or "" id is omitted,
        // while the always-present alreadyRunning/mode/message stay.
        AlreadyRunningContext ctx = new AlreadyRunningContext("the message"); //$NON-NLS-1$
        JsonObject nullId = JsonParser.parseString(
            ctx.buildAlreadyRunning("debug", null).toJson()).getAsJsonObject(); //$NON-NLS-1$
        assertFalse("null applicationId must be omitted", nullId.has("applicationId")); //$NON-NLS-1$
        assertTrue(nullId.get("alreadyRunning").getAsBoolean()); //$NON-NLS-1$
        assertEquals("debug", nullId.get("mode").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("the message", nullId.get("message").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject emptyId = JsonParser.parseString(
            ctx.buildAlreadyRunning("debug", "").toJson()).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("empty applicationId must be omitted", emptyId.has("applicationId")); //$NON-NLS-1$
    }

    @Test
    public void testBuildAlreadyRunningAlwaysCarriesCoreFieldsAndNoLaunchingStatus()
    {
        // The minimal payload (no optional identity fields supplied) still carries the
        // three invariants — alreadyRunning:true, mode, message — and NEVER the
        // status:"launching" field (that belongs only to the fresh-launch path).
        AlreadyRunningContext ctx = new AlreadyRunningContext("running"); //$NON-NLS-1$
        JsonObject obj = JsonParser.parseString(
            ctx.buildAlreadyRunning("debug", "a").toJson()).getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("alreadyRunning must be true", obj.get("alreadyRunning").getAsBoolean()); //$NON-NLS-1$
        assertTrue("success must be true on a short-circuit", obj.get("success").getAsBoolean()); //$NON-NLS-1$
        assertFalse("the alreadyRunning short-circuit must never carry status:launching",
            obj.has("status")); //$NON-NLS-1$
        assertFalse("attach must be omitted when unset", obj.has("attach")); //$NON-NLS-1$
    }

    /** A standalone-server service that returns a chosen start status. */
    public static final class FakeStandaloneStartService
    {
        private final IStatus status;

        FakeStandaloneStartService(IStatus status)
        {
            this.status = status;
        }

        public IStatus startServer(Object server, String launchMode, Object monitor)
        {
            return status;
        }
    }

    /** A service that exposes a port-conflict event while the direct start window is open. */
    public static final class PortConflictingStandaloneStartService
    {
        public IStatus startServer(Object server, String launchMode, Object monitor)
        {
            try
            {
                Method method = LaunchUpdateDialogAutoConfirmer.class.getDeclaredMethod(
                    "recordPortConflictForTest", String.class); //$NON-NLS-1$
                method.setAccessible(true);
                method.invoke(null, "8429 - HTTP gate port"); //$NON-NLS-1$
                return Status.OK_STATUS;
            }
            catch (ReflectiveOperationException e)
            {
                throw new AssertionError(e);
            }
        }
    }

    /** A service that records a successful EDT port reassignment before returning OK. */
    public static final class PortReassigningStandaloneStartService
    {
        public IStatus startServer(Object server, String launchMode, Object monitor)
        {
            recordPortReassign();
            return Status.OK_STATUS;
        }
    }

    /** A service that persistently reassigns its ports and then reports a failed start. */
    public static final class PortReassigningFailingStandaloneStartService
    {
        public IStatus startServer(Object server, String launchMode, Object monitor)
        {
            recordPortReassign();
            return new Status(IStatus.ERROR, "test", "server start refused after reassign"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** A direct start whose failed connect raised and auto-cancelled EDT's auth dialog. */
    public static final class AuthCancellingStandaloneStartService
    {
        public IStatus startServer(Object server, String launchMode, Object monitor)
        {
            recordAuthDialogCancel();
            return new Status(IStatus.ERROR, "test", "server start refused"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /** A start that ignores cancellation until the test explicitly releases it. */
    public static final class BlockingStandaloneStartService
    {
        private final CountDownLatch started;
        private final CountDownLatch release;

        BlockingStandaloneStartService(CountDownLatch started, CountDownLatch release)
        {
            this.started = started;
            this.release = release;
        }

        public IStatus startServer(Object server, String launchMode, Object monitor)
        {
            started.countDown();
            try
            {
                release.await(60, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return Status.OK_STATUS;
        }
    }

    /** Extra EDT server-application surface supplied to a Mockito application mock. */
    public interface ServerBackedApplication
    {
        Object getServer();
    }

    /** Minimal already-resolved WST server used by the bounded attribution test. */
    public static final class NamedStandaloneServer
    {
        private final String name;

        NamedStandaloneServer(String name)
        {
            this.name = name;
        }

        public String getName()
        {
            return name;
        }
    }

    /** A standalone-server service whose start throws inside the bounded job. */
    public static final class ThrowingStandaloneStartService
    {
        public IStatus startServer(Object server, String launchMode, Object monitor)
        {
            throw new IllegalStateException("captured start failure"); //$NON-NLS-1$
        }
    }

    /** Records the successful reassign event while the launch conflict window is open. */
    private static void recordPortReassign()
    {
        try
        {
            Method method = LaunchUpdateDialogAutoConfirmer.class.getDeclaredMethod(
                "recordPortReassignForTest", String.class); //$NON-NLS-1$
            method.setAccessible(true);
            method.invoke(null, "8429 - HTTP gate port"); //$NON-NLS-1$
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(e);
        }
    }

    private static void recordAuthDialogCancel()
    {
        try
        {
            Method method = InfobaseAuthDialogSuppressor.class.getDeclaredMethod(
                "recordAutoCancelledDialog", boolean.class); //$NON-NLS-1$
            method.setAccessible(true);
            method.invoke(null, false);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(e);
        }
    }

    private static int portConflictArmCount() throws Exception
    {
        Method method = LaunchUpdateDialogAutoConfirmer.class.getDeclaredMethod(
            "portConflictArmsForTest"); //$NON-NLS-1$
        method.setAccessible(true);
        return ((Integer)method.invoke(null)).intValue();
    }

    private static int conflictWatchCount() throws Exception
    {
        Field lockField = LaunchUpdateDialogAutoConfirmer.class.getDeclaredField("LOCK"); //$NON-NLS-1$
        lockField.setAccessible(true);
        Object lock = lockField.get(null);
        Field watchesField = LaunchUpdateDialogAutoConfirmer.class.getDeclaredField(
            "CONFLICT_WATCHES"); //$NON-NLS-1$
        watchesField.setAccessible(true);
        synchronized (lock)
        {
            return ((java.util.List<?>)watchesField.get(null)).size();
        }
    }

    private static void armPortConflictForTest(StandaloneServerPortConflictPolicy policy,
        String infobaseName, String serverName) throws Exception
    {
        Method method = LaunchUpdateDialogAutoConfirmer.class.getDeclaredMethod(
            "armPortConflictForTest", StandaloneServerPortConflictPolicy.class, //$NON-NLS-1$
            String.class, String.class);
        method.setAccessible(true);
        method.invoke(null, policy, infobaseName, serverName);
    }

    private static void disarmPortConflictForTest(StandaloneServerPortConflictPolicy policy,
        String infobaseName, String serverName) throws Exception
    {
        Method method = LaunchUpdateDialogAutoConfirmer.class.getDeclaredMethod(
            "disarmPortConflictForTest", StandaloneServerPortConflictPolicy.class, //$NON-NLS-1$
            String.class, String.class);
        method.setAccessible(true);
        method.invoke(null, policy, infobaseName, serverName);
    }

    /** Reads the suppressor's package-private activity counter without widening production API. */
    private static AtomicInteger authInFlightCounter() throws Exception
    {
        Field field = InfobaseAuthDialogSuppressor.class.getDeclaredField("IN_FLIGHT"); //$NON-NLS-1$
        field.setAccessible(true);
        return (AtomicInteger)field.get(null);
    }

    /** Records the stop that {@code ensureStartable} normally records inside the guarded scope. */
    private static void recordOperationStop(String applicationId)
    {
        try
        {
            Method method = StandaloneServerStateRecovery.class.getDeclaredMethod(
                "recordStoppedServer", String.class); //$NON-NLS-1$
            method.setAccessible(true);
            method.invoke(null, applicationId);
        }
        catch (ReflectiveOperationException e)
        {
            throw new AssertionError(e);
        }
    }
}

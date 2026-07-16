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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.junit.Test;
import org.mockito.Mockito;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.DebugLaunchTool.AlreadyRunningContext;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.ExistingClientSession;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link DebugLaunchTool}.
 * <p>
 * Covers tool metadata, the input schema, and the two headless-reachable
 * required-argument validations in the project+application launch mode
 * (projectName, then applicationId), which return before the first
 * {@code ProjectStateChecker}/launch-manager access. NOTE: these checks are
 * only reachable when {@code launchConfigurationName} is absent — supplying it
 * enters the by-name launch mode whose first statement touches the live launch
 * manager. The headless E2E suite (test_debug_launch.py) covers only the
 * sentinel/negative matrix; an ACTUAL launch needs a live workbench plus a
 * running infobase and is not automated. The launch-path decision logic is
 * therefore unit-covered through seams instead: handleExistingClientSession
 * (restartIfRunning/alreadyRunning), runLaunchJobBody (background-Job body),
 * runPreLaunchUpdateStep and performLaunch here, and the session
 * detect/terminate helpers in LaunchLifecycleUtilsSessionTest.
 */
public class DebugLaunchToolTest
{
    @Test
    public void testName()
    {
        assertEquals("debug_launch", new DebugLaunchTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DebugLaunchTool.NAME, new DebugLaunchTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new DebugLaunchTool().getResponseType());
    }

    @Test
    public void testConnectsToInfobaseIsTrue()
    {
        // #270: config.launch(...) connects a runtime client to the infobase — it must arm
        // the auth-dialog suppressor's activity window.
        assertTrue(new DebugLaunchTool().connectsToInfobase());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new DebugLaunchTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new DebugLaunchTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"launchConfigurationName\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresRestartIfRunning()
    {
        // restartIfRunning must be a declared input so the
        // schema<->execute parity test passes and clients can discover it. Its
        // read in execute() is enforced by SchemaExecuteParamParityTest.
        String schema = new DebugLaunchTool().getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare restartIfRunning",
            schema.contains("\"restartIfRunning\"")); //$NON-NLS-1$
        assertTrue("schema must document the default short-circuit contract",
            schema.contains("alreadyRunning:true")); //$NON-NLS-1$
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
            DebugLaunchTool.extractRestartIfRunning(new HashMap<>()));
        Map<String, String> params = new HashMap<>();
        params.put("restartIfRunning", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("explicit restartIfRunning=true must override the default",
            DebugLaunchTool.extractRestartIfRunning(params));
    }

    @Test
    public void testGuideDocumentsRestartIfRunningAndTargetManagerGuard()
    {
        // The guide must document both halves of the fix — the new
        // restartIfRunning switch and that the already-running guard now also catches
        // a session EDT tracks only through its debug target manager (the code-1003
        // "Debug session already exists" modal source). Ratchet so it can't drift.
        String guide = new DebugLaunchTool().getGuide();
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
        String schema = new DebugLaunchTool().getOutputSchema();
        assertNotNull(schema);
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
        DebugLaunchTool tool = new DebugLaunchTool();
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
        String guide = new DebugLaunchTool().getGuide();
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
        DebugLaunchTool tool = new DebugLaunchTool();
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
        String guide = new DebugLaunchTool().getGuide();
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
        String guide = new DebugLaunchTool().getGuide();
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
        String result = new DebugLaunchTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingApplicationId()
    {
        // projectName present, no launchConfigurationName, applicationId omitted.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DebugLaunchTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
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
        String error = new DebugLaunchTool().performLaunch(config, false);
        assertNull("successful headless launch must return null", error);
        Mockito.verify(config).launch(ILaunchManager.DEBUG_MODE, null);
    }

    @Test
    public void testPerformLaunchHeadlessSurfacesLaunchError() throws Exception
    {
        // The synchronous (headless) path is the only one that can still report a
        // launch failure to the caller — keep that contract real, not dead code.
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        Mockito.when(config.launch(ILaunchManager.DEBUG_MODE, null)).thenThrow(
            new CoreException(new Status(IStatus.ERROR, "test", "launch refused"))); //$NON-NLS-1$ //$NON-NLS-2$
        String error = new DebugLaunchTool().performLaunch(config, false);
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
    public void testRunLaunchJobBodySuccessReturnsOkAndPassesMonitor() throws Exception
    {
        // The Job body launches with the JOB'S monitor (so the Progress view shows the
        // delegate's steps) and reports OK — and the arm/disarm pair around the launch
        // completes cleanly.
        ILaunchConfiguration config = Mockito.mock(ILaunchConfiguration.class);
        IProgressMonitor monitor = new NullProgressMonitor();
        IStatus status = DebugLaunchTool.runLaunchJobBody(config, true, monitor);
        assertNotNull(status);
        assertTrue("successful launch must report OK", status.isOK());
        Mockito.verify(config).launch(ILaunchManager.DEBUG_MODE, monitor);
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
        IStatus status = DebugLaunchTool.runLaunchJobBody(config, false, new NullProgressMonitor());
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
        IStatus status = DebugLaunchTool.runLaunchJobBody(config, true, new NullProgressMonitor());
        assertNotNull(status);
        assertEquals("a runtime failure must be an ERROR status", IStatus.ERROR, status.getSeverity());
        assertSame("the status must carry the original exception", boom, status.getException());
        assertTrue("the status message must name the failure",
            status.getMessage().contains("boom")); //$NON-NLS-1$
    }

    @Test
    public void testGuideDocumentsBackgroundJobLaunch()
    {
        // Ratchet: the guide must document that the launch runs as a
        // background EDT Job — the workbench stays responsive even through a minutes-long
        // standalone-server mode-switch restart, with progress in the Progress view.
        String guide = new DebugLaunchTool().getGuide();
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
        String guide = new DebugLaunchTool().getGuide();
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
        String error = new DebugLaunchTool().performLaunch(config, true);
        assertNull("successful headless launch must return null even with update auto-confirm", error);
        Mockito.verify(config).launch(ILaunchManager.DEBUG_MODE, null);
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
        String guide = new DebugLaunchTool().getGuide();
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
        String guide = new DebugLaunchTool().getGuide();
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
        String guide = new DebugLaunchTool().getGuide();
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
        String guide = new DebugLaunchTool().getGuide();
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
    // DebugLaunchTool keeps: handleExistingClientSession honoring restartIfRunning in
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
        String json = new DebugLaunchTool()
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
        String result = new DebugLaunchTool()
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
        String result = new DebugLaunchTool()
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
        String json = new DebugLaunchTool()
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
        String guide = new DebugLaunchTool().getGuide();
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
        String guide = new DebugLaunchTool().getGuide();
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
        String guide = new DebugLaunchTool().getGuide();
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
        String error = DebugLaunchTool.runPreLaunchUpdateStep(mockOpenProject(),
            "ServerApplication.MyServer", mgr, true); //$NON-NLS-1$
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

        String error = DebugLaunchTool.runPreLaunchUpdateStep(project,
            "infobase-app-uuid", mgr, true); //$NON-NLS-1$
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
        assertNull(DebugLaunchTool.runPreLaunchUpdateStep(mockOpenProject(),
            "ServerApplication.MyServer", mgr, false)); //$NON-NLS-1$
        assertNull(DebugLaunchTool.runPreLaunchUpdateStep(mockOpenProject(),
            "infobase-app-uuid", mgr, false)); //$NON-NLS-1$
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
        String guide = new DebugLaunchTool().getGuide();
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
            DebugLaunchTool.extractRestartIfRunning(one));
        Map<String, String> yes = new HashMap<>();
        yes.put("restartIfRunning", "YES"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("restartIfRunning=YES (case-insensitive) must be true",
            DebugLaunchTool.extractRestartIfRunning(yes));
    }

    @Test
    public void testExtractRestartIfRunningExplicitFalseTokensAreFalse()
    {
        // The explicit-false spellings must stay false (not flip to the true side).
        Map<String, String> f = new HashMap<>();
        f.put("restartIfRunning", "false"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("restartIfRunning=false must be false",
            DebugLaunchTool.extractRestartIfRunning(f));
        Map<String, String> zero = new HashMap<>();
        zero.put("restartIfRunning", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("restartIfRunning=0 must be false",
            DebugLaunchTool.extractRestartIfRunning(zero));
        Map<String, String> no = new HashMap<>();
        no.put("restartIfRunning", "no"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("restartIfRunning=no must be false",
            DebugLaunchTool.extractRestartIfRunning(no));
    }

    @Test
    public void testExtractRestartIfRunningEmptyAndGarbageFallBackToDefault()
    {
        // An empty value and an unrecognized token both fall back to the documented
        // default (false) — the safe non-destructive choice, never an accidental restart.
        Map<String, String> empty = new HashMap<>();
        empty.put("restartIfRunning", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("empty restartIfRunning must fall back to the false default",
            DebugLaunchTool.extractRestartIfRunning(empty));
        Map<String, String> garbage = new HashMap<>();
        garbage.put("restartIfRunning", "maybe"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("unrecognized restartIfRunning token must fall back to the false default",
            DebugLaunchTool.extractRestartIfRunning(garbage));
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
        String result = new DebugLaunchTool().execute(params);
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
        String result = new DebugLaunchTool().execute(params);
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
        String result = new DebugLaunchTool().execute(params);
        assertTrue("empty applicationId must produce 'applicationId is required'",
            result.contains("applicationId is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameErrorSteersToLaunchConfigurationName()
    {
        // The projectName-required error must keep advertising the alternative
        // (launchConfigurationName) so the caller can recover without guessing.
        Map<String, String> params = new HashMap<>();
        String result = new DebugLaunchTool().execute(params);
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
        String result = new DebugLaunchTool().execute(params);
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
        JsonObject obj = JsonParser.parseString(new DebugLaunchTool().execute(new HashMap<>()))
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
}

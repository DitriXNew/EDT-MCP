/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Mock-driven tests for {@link LaunchLifecycleUtils#updateApplicationIfNeeded}
 * and the supporting infobase-sync gate.
 *
 * <p>Covers:
 * <ul>
 *   <li>the settle-before-decide window — a lagging {@code UPDATED} that flips to
 *       {@code …UPDATE_REQUIRED} must NOT be trusted as "no update needed";</li>
 *   <li>the block-until-{@code UPDATED} gate — {@code appManager.update()} returning
 *       a non-{@code UPDATED} (e.g. {@code BEING_UPDATED}) state must be followed by a
 *       poll that waits for the IB to actually apply;</li>
 *   <li>refusal on a never-applied IB — if sync is never observed, the auto-chain
 *       must ABORT with an explicit out-of-sync error rather than a stale green.</li>
 * </ul>
 *
 * <p>The sync timing windows are shrunk to a few milliseconds in {@link #shrinkTimings}
 * so the polling paths run instantly and deterministically.
 */
public class LaunchLifecycleUtilsUpdateTest
{
    private static final String APP_ID = "app-1";

    @Before
    public void shrinkTimings()
    {
        // settleWindow=40ms, applyTimeout=200ms, pollInterval=5ms — enough iterations
        // to exercise the settle/poll loops without slowing the suite.
        LaunchLifecycleUtils.setSyncTimingsForTest(40L, 200L, 5L);
    }

    @After
    public void restoreTimings()
    {
        LaunchLifecycleUtils.resetSyncTimingsForTest();
    }

    private static IProject mockOpenProject()
    {
        IProject project = mock(IProject.class);
        when(project.exists()).thenReturn(true);
        when(project.isOpen()).thenReturn(true);
        when(project.getName()).thenReturn("MyProject");
        return project;
    }

    @Test
    public void testNullAppManagerReturnsError()
    {
        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, null);
        assertTrue(result.isPresent());
        assertTrue("error must mention IApplicationManager",
            result.get().contains("IApplicationManager"));
    }

    @Test
    public void testNullProjectReturnsError()
    {
        IApplicationManager mgr = mock(IApplicationManager.class);
        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            null, APP_ID, mgr);
        assertTrue(result.isPresent());
        assertTrue("error must mention project availability",
            result.get().contains("Project is not available"));
    }

    @Test
    public void testApplicationNotFoundReturnsError() throws ApplicationException
    {
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.empty());

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertTrue(result.isPresent());
        assertTrue("error must mention applicationId",
            result.get().contains(APP_ID));
    }

    @Test
    public void testStablyUpdatedIsNoOp() throws ApplicationException
    {
        // The settle path (settleAfterPossibleRecompute=true): getUpdateState reads
        // UPDATED and KEEPS reading UPDATED across the whole settle window → genuinely
        // in sync → no-op, no update issued.
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.UPDATED);

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr, true);
        assertFalse("stable UPDATED across the settle window must be a no-op", result.isPresent());
        verify(mgr, never()).update(any(), any(), any(), any());
    }

    @Test
    public void testUpdatedWithoutRecomputeReturnsImmediatelyNoOp() throws ApplicationException
    {
        // The plain debug_launch / update_database path (the 3-arg overload,
        // settleAfterPossibleRecompute=false) must NOT wait the settle window on an
        // already-synced IB. A single entry read of UPDATED short-circuits to a no-op
        // with no further getUpdateState polling and no update() — restoring the fast
        // debug_launch on a synced IB undercut by the unconditional settle wait.
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.UPDATED);

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertFalse("an UPDATED IB on the no-recompute path must be an immediate no-op",
            result.isPresent());
        verify(mgr, never()).update(any(), any(), any(), any());
        // Exactly one getUpdateState read (the cheap entry read) — no settle-window
        // polling. The 3-arg path trusts the cached UPDATED.
        verify(mgr, times(1)).getUpdateState(app);
    }

    @Test
    public void testUpdatedNoRecomputeIgnoresLaterFlip() throws ApplicationException
    {
        // Complement to the lag test: on the no-recompute (false) path, even if a later
        // read WOULD surface a flip to …UPDATE_REQUIRED, the method must already have
        // returned on the first UPDATED — it never polls again, so no update() runs.
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        AtomicInteger reads = new AtomicInteger(0);
        when(mgr.getUpdateState(app)).thenAnswer(inv -> {
            int n = reads.getAndIncrement();
            return n == 0 ? ApplicationUpdateState.UPDATED
                : ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED;
        });

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertFalse("no-recompute path returns on the first UPDATED, ignoring a later flip",
            result.isPresent());
        verify(mgr, never()).update(any(), any(), any(), any());
    }

    @Test
    public void testLaggingUpdatedThatFlipsTriggersUpdate() throws ApplicationException
    {
        // The lagging-UPDATED core case, now exercised via the settle path (the YAXUnit
        // fresh-launch overload, settleAfterPossibleRecompute=true): getUpdateState
        // reads a STALE UPDATED right after the recompute, then flips to
        // INCREMENTAL_UPDATE_REQUIRED inside the settle window. We must NOT
        // short-circuit — we must run the update.
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));

        AtomicInteger reads = new AtomicInteger(0);
        when(mgr.getUpdateState(app)).thenAnswer(inv -> {
            int n = reads.getAndIncrement();
            if (n == 0)
            {
                // First (entry) read: stale UPDATED.
                return ApplicationUpdateState.UPDATED;
            }
            if (n == 1)
            {
                // Settle poll surfaces the lagging desync flag.
                return ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED;
            }
            // After update() runs, the IB is applied.
            return ApplicationUpdateState.UPDATED;
        });
        when(mgr.update(eq(app), eq(ApplicationUpdateType.INCREMENTAL),
            any(ExecutionContext.class), any())).thenReturn(ApplicationUpdateState.UPDATED);

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr, true);
        assertFalse("a lagging UPDATED that flips must still update and succeed",
            result.isPresent());
        verify(mgr, times(1)).update(eq(app), eq(ApplicationUpdateType.INCREMENTAL),
            any(ExecutionContext.class), any());
    }

    @Test
    public void testBeingUpdatedThenSettlesUpdatedReturnsOk() throws ApplicationException
    {
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));

        // First call: BEING_UPDATED (entering the block-until-applied poll).
        // Subsequent calls: UPDATED (settled).
        AtomicInteger pollCount = new AtomicInteger(0);
        when(mgr.getUpdateState(app)).thenAnswer(inv -> {
            int n = pollCount.getAndIncrement();
            return n == 0 ? ApplicationUpdateState.BEING_UPDATED : ApplicationUpdateState.UPDATED;
        });

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertFalse("polling to UPDATED must yield success", result.isPresent());
        verify(mgr, never()).update(any(), any(), any(), any());
    }

    @Test
    public void testBeingUpdatedThatNeverAppliesAborts() throws ApplicationException
    {
        // Another update is in progress and never reaches UPDATED within the apply
        // timeout → we must NOT launch; abort with an out-of-sync error.
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.BEING_UPDATED);

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertTrue("a never-applying in-progress update must abort", result.isPresent());
        assertTrue("error must say the IB is out of sync",
            result.get().contains("out of sync"));
        verify(mgr, never()).update(any(), any(), any(), any());
    }

    @Test
    public void testUpdateCalledWhenStateNeedsUpdate() throws ApplicationException
    {
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        when(mgr.update(eq(app), eq(ApplicationUpdateType.INCREMENTAL),
            any(ExecutionContext.class), any())).thenReturn(ApplicationUpdateState.UPDATED);

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertFalse("update returning UPDATED must yield success", result.isPresent());
        verify(mgr, times(1)).update(eq(app), eq(ApplicationUpdateType.INCREMENTAL),
            any(ExecutionContext.class), any());
    }

    @Test
    public void testUpdateNeverReachesUpdatedReturnsError() throws ApplicationException
    {
        // Bug-fix branch: update() runs but the IB never reaches UPDATED (stays
        // INCREMENTAL_UPDATE_REQUIRED). We must NOT silently report success —
        // otherwise the run would execute against a stale IB.
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        when(mgr.update(eq(app), any(), any(), any()))
            .thenReturn(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertTrue("an IB that never applies must yield an error", result.isPresent());
        assertTrue("error must surface the final state",
            result.get().contains("INCREMENTAL_UPDATE_REQUIRED"));
        assertTrue("error must say the IB is out of sync",
            result.get().contains("out of sync"));
        assertTrue("error must mention update_database fallback",
            result.get().contains("update_database"));
    }

    @Test
    public void testUpdateReturningTerminalRequiredStateFailsFast() throws ApplicationException
    {
        // update() itself returning FULL_UPDATE_REQUIRED is a terminal
        // state that cannot transition to UPDATED without user action (e.g. an
        // interactive restructure). The method must return the out-of-sync error
        // IMMEDIATELY instead of stalling on the SYNCED await for the full apply
        // timeout only to fail with the same error.
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        when(mgr.update(eq(app), any(), any(), any()))
            .thenReturn(ApplicationUpdateState.FULL_UPDATE_REQUIRED);

        // Generous apply timeout so a fast return proves the early exit, not a
        // shrunken timeout.
        LaunchLifecycleUtils.setSyncTimingsForTest(40L, 5000L, 5L);
        long start = System.currentTimeMillis();
        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("a terminal post-update state must yield an error", result.isPresent());
        assertTrue("error must surface the final state",
            result.get().contains("FULL_UPDATE_REQUIRED"));
        assertTrue("error must say the IB is out of sync",
            result.get().contains("out of sync"));
        assertTrue("error must mention update_database fallback",
            result.get().contains("update_database"));
        assertTrue("must fail fast instead of awaiting the 5s apply timeout, took "
            + elapsed + "ms", elapsed < 2000L);
    }

    @Test
    public void testUpdateReturnsBeingUpdatedThenSettles() throws ApplicationException
    {
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));

        // Entry read: needs update. update() returns BEING_UPDATED, then the poll
        // sees BEING_UPDATED once more and finally UPDATED.
        AtomicInteger reads = new AtomicInteger(0);
        when(mgr.getUpdateState(app)).thenAnswer(inv -> {
            int n = reads.getAndIncrement();
            if (n == 0)
            {
                return ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED;
            }
            if (n == 1)
            {
                return ApplicationUpdateState.BEING_UPDATED;
            }
            return ApplicationUpdateState.UPDATED;
        });
        when(mgr.update(eq(app), any(), any(), any()))
            .thenReturn(ApplicationUpdateState.BEING_UPDATED);

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertFalse("post-update poll to UPDATED must yield success", result.isPresent());
    }

    @Test
    public void testUpdateThrowsApplicationExceptionReturnsError() throws ApplicationException
    {
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        when(mgr.update(eq(app), any(), any(), any()))
            .thenThrow(new ApplicationException("update boom"));

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertTrue(result.isPresent());
        assertNotNull(result.get());
        assertTrue("error must mention the failure", result.get().contains("update boom"));
    }
}

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
import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Mock-driven tests for {@link LaunchLifecycleUtils#updateApplicationIfNeeded}.
 *
 * <p>Covers the post-update state branches that determine whether the
 * auto-chain proceeds to {@code workingCopy.launch()} or aborts with a clear
 * error — including the previously-broken branch where {@code appManager.update()}
 * returned a non-{@code UPDATED} state and the auto-chain still claimed success.
 */
public class LaunchLifecycleUtilsUpdateTest
{
    private static final String APP_ID = "app-1";

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
    public void testAlreadyUpdatedIsNoOp() throws ApplicationException
    {
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.UPDATED);

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertFalse("UPDATED state must be a no-op", result.isPresent());
        verify(mgr, never()).update(any(), any(), any(), any());
    }

    @Test
    public void testBeingUpdatedThenSettlesUpdatedReturnsOk() throws ApplicationException
    {
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));

        // First call: BEING_UPDATED (entering the poll branch).
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
    public void testUpdateReturnsNonUpdatedReturnsError() throws ApplicationException
    {
        // This is the bug-fix branch: appManager.update() returns a non-UPDATED state
        // (e.g. FULL_UPDATE_REQUIRED because the INCREMENTAL we ran isn't enough) and
        // we must NOT silently report success — otherwise the launch delegate would
        // pop its modal "Update database?" dialog and block the MCP call.
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        when(mgr.update(eq(app), any(), any(), any()))
            .thenReturn(ApplicationUpdateState.FULL_UPDATE_REQUIRED);

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertTrue("non-UPDATED post-state must yield an error", result.isPresent());
        assertTrue("error must surface the final state",
            result.get().contains("FULL_UPDATE_REQUIRED"));
        assertTrue("error must mention update_database fallback",
            result.get().contains("update_database"));
    }

    @Test
    public void testUpdateReturnsIncrementalRequiredIsSuccess() throws ApplicationException
    {
        // The fix for the extension-bearing case: appManager.update() leaves the app
        // reporting INCREMENTAL_UPDATE_REQUIRED (the launch delegate's modal is handled
        // by LaunchUpdateDialogAutoConfirmer). The changes ARE published, so this must
        // be a success — NOT an error that pushes the caller toward a full update.
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        when(mgr.update(eq(app), any(), any(), any()))
            .thenReturn(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);

        Optional<String> result = LaunchLifecycleUtils.updateApplicationIfNeeded(
            mockOpenProject(), APP_ID, mgr);
        assertFalse("INCREMENTAL_UPDATE_REQUIRED post-update is the cosmetic extension " //$NON-NLS-1$
            + "state and must be success", result.isPresent());
        verify(mgr, times(1)).update(eq(app), any(), any(), any());
    }

    @Test
    public void testUpdateReturnsBeingUpdatedThenSettles() throws ApplicationException
    {
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(any(IProject.class), eq(APP_ID)))
            .thenReturn(Optional.of(app));
        when(mgr.getUpdateState(app)).thenReturn(ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED);
        when(mgr.update(eq(app), any(), any(), any()))
            .thenAnswer(inv -> {
                // After update returns, switch getUpdateState() to first BEING_UPDATED,
                // then UPDATED on the next poll.
                AtomicInteger pollAfter = new AtomicInteger(0);
                when(mgr.getUpdateState(app)).thenAnswer(i -> {
                    int n = pollAfter.getAndIncrement();
                    return n == 0 ? ApplicationUpdateState.BEING_UPDATED
                        : ApplicationUpdateState.UPDATED;
                });
                return ApplicationUpdateState.BEING_UPDATED;
            });

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

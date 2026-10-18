/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.Test;

import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

public class ApplicationDefaultChangeTest
{
    @Test
    public void productionObserverReadsTheExplicitPreferenceAndWritesOnlyThroughTheManager() throws Exception
    {
        var project = mock(IProject.class);
        String name = "mcp-default-probe-" + UUID.randomUUID(); //$NON-NLS-1$
        when(project.getName()).thenReturn(name);
        var preferences = InstanceScope.INSTANCE.getNode("com.e1c.g5.dt.applications") //$NON-NLS-1$
            .node("applicationDefaults").node("applications"); //$NON-NLS-1$ //$NON-NLS-2$
        var manager = mock(IApplicationManager.class);
        var previous = mock(IApplication.class);
        var next = mock(IApplication.class);
        when(previous.getId()).thenReturn("prior"); //$NON-NLS-1$
        when(next.getId()).thenReturn("owned"); //$NON-NLS-1$
        when(manager.getApplication(project, "prior")).thenReturn(Optional.of(previous)); //$NON-NLS-1$
        var cache = new AtomicReference<IApplication>(previous);
        preferences.put(name, "prior"); //$NON-NLS-1$
        doAnswer(call -> {
            IApplication app = call.getArgument(1);
            cache.set(app);
            if (app == null)
            {
                preferences.remove(name);
            }
            else
            {
                preferences.put(name, app.getId());
            }
            return null;
        }).when(manager).setDefaultApplication(eq(project), any());
        try
        {
            var change = ApplicationDefaultChange.capture(manager, project);
            assertNull(change.apply(next, () -> { }));
            assertSame(next, cache.get());
            assertEquals("owned", preferences.get(name, null)); //$NON-NLS-1$
            assertEquals(ApplicationDefaultChange.Restoration.RESTORED, change.restore());
            assertSame(previous, cache.get());
            assertEquals("prior", preferences.get(name, null)); //$NON-NLS-1$
            verify(manager, never()).getDefaultApplication(any());
            verify(manager, times(2)).setDefaultApplication(eq(project), any());
        }
        finally
        {
            preferences.remove(name);
        }
    }

    @Test
    public void anUnresolvableExplicitPriorIsNeverOverwritten() throws Exception
    {
        var project = mock(IProject.class);
        var manager = mock(IApplicationManager.class);
        when(manager.getApplication(project, "prior")).thenReturn(Optional.empty()); //$NON-NLS-1$
        var raw = new AtomicReference<String>("prior"); //$NON-NLS-1$
        var change = ApplicationDefaultChange.capture(manager, project, raw::get);
        assertNotNull(change.apply(mock(IApplication.class), () -> { }));
        assertEquals(ApplicationDefaultChange.Restoration.NOT_CHANGED, change.restore());
        assertEquals("prior", raw.get()); //$NON-NLS-1$
        verify(manager, never()).setDefaultApplication(any(), any());
    }

    @Test
    public void aConcurrentClearAfterOurWriteIsPreserved() throws Exception
    {
        var project = mock(IProject.class);
        var manager = mock(IApplicationManager.class);
        var next = mock(IApplication.class);
        when(next.getId()).thenReturn("owned"); //$NON-NLS-1$
        var raw = new AtomicReference<String>();
        doAnswer(call -> { raw.set("owned"); return null; }).when(manager).setDefaultApplication(project, next); //$NON-NLS-1$
        var change = ApplicationDefaultChange.capture(manager, project, raw::get);
        assertNull(change.apply(next, () -> { }));
        raw.set(null);
        assertEquals(ApplicationDefaultChange.Restoration.PRESERVED_OTHER, change.restore());
        verify(manager, times(1)).setDefaultApplication(any(), any());
        assertNull(raw.get());
    }

    @Test
    public void aNewChoiceDuringCreationPreventsOurOptionalWrite() throws Exception
    {
        var project = mock(IProject.class);
        var manager = mock(IApplicationManager.class);
        var raw = new AtomicReference<String>();
        var change = ApplicationDefaultChange.capture(manager, project, raw::get);
        raw.set("newer-choice"); //$NON-NLS-1$
        assertNotNull(change.apply(mock(IApplication.class), () -> { }));
        assertEquals(ApplicationDefaultChange.Restoration.NOT_CHANGED, change.restore());
        verify(manager, never()).setDefaultApplication(any(), any());
        assertEquals("newer-choice", raw.get()); //$NON-NLS-1$
    }

    @Test
    public void aPriorRemovedDuringNativeRestorationIsNotReportedAsRestored() throws Exception
    {
        var project = mock(IProject.class);
        var manager = mock(IApplicationManager.class);
        var previous = mock(IApplication.class);
        var next = mock(IApplication.class);
        when(previous.getId()).thenReturn("prior"); //$NON-NLS-1$
        when(next.getId()).thenReturn("owned"); //$NON-NLS-1$
        var present = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(manager.getApplication(project, "prior")).thenAnswer(call -> //$NON-NLS-1$
            present.get() ? Optional.of(previous) : Optional.empty());
        var raw = new AtomicReference<String>("prior"); //$NON-NLS-1$
        doAnswer(call -> {
            IApplication application = call.getArgument(1);
            raw.set(application.getId());
            if (application == previous)
            {
                present.set(false);
            }
            return null;
        }).when(manager).setDefaultApplication(eq(project), any());
        var change = ApplicationDefaultChange.capture(manager, project, raw::get);
        assertNull(change.apply(next, () -> { }));
        assertEquals(ApplicationDefaultChange.Restoration.UNVERIFIED, change.restore());
        assertEquals("prior", raw.get()); //$NON-NLS-1$
        verify(manager, never()).getDefaultApplication(any());
    }

    @Test
    public void aNewChoiceDuringPriorResolutionIsPreserved() throws Exception
    {
        var project = mock(IProject.class);
        var manager = mock(IApplicationManager.class);
        var previous = mock(IApplication.class);
        var next = mock(IApplication.class);
        when(previous.getId()).thenReturn("prior"); //$NON-NLS-1$
        when(next.getId()).thenReturn("owned"); //$NON-NLS-1$
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var raw = new AtomicReference<String>("prior"); //$NON-NLS-1$
        when(manager.getApplication(project, "prior")).thenAnswer(call -> { //$NON-NLS-1$
            if (reads.incrementAndGet() > 1)
            {
                raw.set("newer"); //$NON-NLS-1$
            }
            return Optional.of(previous);
        });
        doAnswer(call -> { raw.set("owned"); return null; }).when(manager).setDefaultApplication(project, next); //$NON-NLS-1$
        var change = ApplicationDefaultChange.capture(manager, project, raw::get);
        assertNull(change.apply(next, () -> { }));
        assertEquals(ApplicationDefaultChange.Restoration.PRESERVED_OTHER, change.restore());
        assertEquals("newer", raw.get()); //$NON-NLS-1$
        verify(manager, times(1)).setDefaultApplication(any(), any());
    }

    @Test
    public void anAdmittedNativeRestorationReportsPendingAndCanStillFinishLater() throws Exception
    {
        var project = mock(IProject.class);
        var manager = mock(IApplicationManager.class);
        var previous = mock(IApplication.class);
        var next = mock(IApplication.class);
        when(previous.getId()).thenReturn("prior"); //$NON-NLS-1$
        when(next.getId()).thenReturn("owned"); //$NON-NLS-1$
        when(manager.getApplication(project, "prior")).thenReturn(Optional.of(previous)); //$NON-NLS-1$
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CompletableFuture<Void>();
        var raw = new AtomicReference<String>("prior"); //$NON-NLS-1$
        doAnswer(call -> {
            IApplication application = call.getArgument(1);
            if (application == previous)
            {
                entered.countDown();
                release.join(); // Native code has its permit and does not poll cancellation.
            }
            raw.set(application.getId());
            return null;
        }).when(manager).setDefaultApplication(eq(project), any());
        var change = ApplicationDefaultChange.capture(manager, project, raw::get);
        assertNull(change.apply(next, () -> { }));
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try
        {
            var result = caller.submit(() -> change.restore(1000));
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(ApplicationDefaultChange.Restoration.PENDING,
                result.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("owned", raw.get()); //$NON-NLS-1$
            release.complete(null);
            try (var coordination = InfobaseMutationCoordinator.acquire(3000))
            {
                assertEquals("prior", raw.get()); //$NON-NLS-1$
                verify(manager, times(2)).setDefaultApplication(any(), any());
            }
        }
        finally
        {
            release.complete(null);
            caller.shutdownNow();
        }
    }

    @Test
    public void timedOutPriorResolutionCannotRestoreLater() throws Exception
    {
        var project = mock(IProject.class);
        var manager = mock(IApplicationManager.class);
        var previous = mock(IApplication.class);
        var next = mock(IApplication.class);
        when(previous.getId()).thenReturn("prior"); //$NON-NLS-1$
        when(next.getId()).thenReturn("owned"); //$NON-NLS-1$
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CompletableFuture<Void>();
        when(manager.getApplication(project, "prior")).thenAnswer(call -> { //$NON-NLS-1$
            if (reads.incrementAndGet() > 1)
            {
                entered.countDown();
                release.join(); // Emulate a native lookup that ignores cancellation.
            }
            return Optional.of(previous);
        });
        var raw = new AtomicReference<String>("prior"); //$NON-NLS-1$
        doAnswer(call -> { raw.set("owned"); return null; }).when(manager).setDefaultApplication(project, next); //$NON-NLS-1$
        var change = ApplicationDefaultChange.capture(manager, project, raw::get);
        assertNull(change.apply(next, () -> { }));
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try
        {
            var result = caller.submit(() -> change.restore(1000));
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(ApplicationDefaultChange.Restoration.UNVERIFIED,
                result.get(3, java.util.concurrent.TimeUnit.SECONDS));
            release.complete(null);
            try (var coordination = InfobaseMutationCoordinator.acquire(3000))
            {
                assertEquals("owned", raw.get()); //$NON-NLS-1$
                verify(manager, times(1)).setDefaultApplication(any(), any());
            }
        }
        finally
        {
            release.complete(null);
            caller.shutdownNow();
        }
    }
}

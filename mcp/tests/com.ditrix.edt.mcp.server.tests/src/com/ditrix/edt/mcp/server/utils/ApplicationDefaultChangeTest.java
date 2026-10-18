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
}

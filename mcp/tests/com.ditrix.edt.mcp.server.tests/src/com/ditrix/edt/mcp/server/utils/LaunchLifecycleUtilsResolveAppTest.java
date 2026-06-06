/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Mock-driven tests for {@link LaunchLifecycleUtils#resolveDefaultApplicationId}.
 *
 * <p>This is the fix that keeps a runtime-client launch configuration WITHOUT an
 * explicit applicationId binding from triggering the EDT launch delegate's blocking
 * "Update infobase before launch?" modal: when the id is empty, the effective target
 * falls back to the project's default application so the programmatic
 * {@code updateBeforeLaunch} has something to update.
 */
public class LaunchLifecycleUtilsResolveAppTest
{
    private static final String GIVEN_ID = "app-given";
    private static final String DEFAULT_ID = "app-default";

    @Test
    public void testNonEmptyIdReturnedUnchangedAndNoLookup()
    {
        IApplicationManager mgr = mock(IApplicationManager.class);
        IProject project = mock(IProject.class);

        String result = LaunchLifecycleUtils.resolveDefaultApplicationId(project, GIVEN_ID, mgr);

        assertEquals("a set id must be returned unchanged", GIVEN_ID, result);
        // No default-application lookup when an explicit id is already present.
        verify(mgr, never()).getDefaultApplication(any(IProject.class));
    }

    @Test
    public void testEmptyIdFallsBackToProjectDefault()
    {
        IApplication app = mock(IApplication.class);
        when(app.getId()).thenReturn(DEFAULT_ID);
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getDefaultApplication(project)).thenReturn(Optional.of(app));

        String result = LaunchLifecycleUtils.resolveDefaultApplicationId(project, "", mgr);

        assertEquals("an empty id must fall back to the project default app", DEFAULT_ID, result);
    }

    @Test
    public void testNullIdFallsBackToProjectDefault()
    {
        IApplication app = mock(IApplication.class);
        when(app.getId()).thenReturn(DEFAULT_ID);
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getDefaultApplication(project)).thenReturn(Optional.of(app));

        String result = LaunchLifecycleUtils.resolveDefaultApplicationId(project, null, mgr);

        assertEquals("a null id must fall back to the project default app", DEFAULT_ID, result);
    }

    @Test
    public void testEmptyIdNoDefaultReturnsOriginal()
    {
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getDefaultApplication(project)).thenReturn(Optional.empty());

        String result = LaunchLifecycleUtils.resolveDefaultApplicationId(project, "", mgr);

        assertEquals("no default application -> the original empty id is preserved", "", result);
    }

    @Test
    public void testNullAppManagerReturnsOriginal()
    {
        IProject project = mock(IProject.class);
        assertEquals("", LaunchLifecycleUtils.resolveDefaultApplicationId(project, "", null));
        assertNull(LaunchLifecycleUtils.resolveDefaultApplicationId(project, null, null));
    }

    @Test
    public void testNullProjectReturnsOriginal()
    {
        IApplicationManager mgr = mock(IApplicationManager.class);
        assertEquals("", LaunchLifecycleUtils.resolveDefaultApplicationId(null, "", mgr));
        // No lookup possible without a project.
        verify(mgr, never()).getDefaultApplication(any(IProject.class));
    }

    @Test
    public void testDefaultLookupThrowsDegradesToOriginal() throws ApplicationException
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("MyProject");
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getDefaultApplication(project)).thenThrow(new ApplicationException("boom"));

        // A lookup failure must not propagate; the original id is preserved so the
        // caller behaves exactly as it did before the fallback existed.
        String result = LaunchLifecycleUtils.resolveDefaultApplicationId(project, "", mgr);

        assertEquals("a getDefaultApplication failure degrades to the original id", "", result);
    }
}

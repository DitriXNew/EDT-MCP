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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

public class ApplicationSupportDefaultApplicationTest
{
    @Test
    public void effectiveDefaultKeepsResolvedAndAbsentOutcomesDistinct() throws Exception
    {
        var project = mock(IProject.class);
        var manager = mock(IApplicationManager.class);
        var application = mock(IApplication.class);
        when(application.getId()).thenReturn("application-id");
        when(manager.getDefaultApplication(project)).thenReturn(Optional.of(application), Optional.empty());
        var resolved = ApplicationSupport.getDefaultApplicationIdBounded(manager, project, 5_000);
        assertTrue(resolved.concluded());
        assertEquals(Optional.of("application-id"), resolved.valueOrRethrow());
        var absent = ApplicationSupport.getDefaultApplicationIdBounded(manager, project, 5_000);
        assertTrue(absent.concluded());
        assertEquals(Optional.empty(), absent.valueOrRethrow());
        verify(manager, times(2)).getDefaultApplication(project);
    }

    @Test
    public void lookupAndIdAccessorFailuresKeepTheOriginalException() throws Exception
    {
        for (boolean accessorFails : new boolean[] {false, true})
        {
            var project = mock(IProject.class);
            var manager = mock(IApplicationManager.class);
            var application = mock(IApplication.class);
            var failure = new ApplicationException("lookup unavailable");
            if (accessorFails)
            {
                when(manager.getDefaultApplication(project)).thenReturn(Optional.of(application));
                when(application.getId()).thenThrow(failure);
            }
            else
            {
                when(manager.getDefaultApplication(project)).thenThrow(failure);
            }
            var read = ApplicationSupport.getDefaultApplicationIdBounded(manager, project, 5_000);
            assertTrue(read.concluded());
            assertSame(failure, read.failure());
            try
            {
                read.valueOrRethrow();
                fail("a failed lookup must not become measured absence");
            }
            catch (ApplicationException actual)
            {
                assertSame(failure, actual);
            }
        }
    }

    @Test
    public void exhaustedAndExpiredReadsDoNotInventAnAbsentDefault() throws Exception
    {
        var project = mock(IProject.class);
        when(project.getName()).thenReturn("DefaultDeadline");
        var manager = mock(IApplicationManager.class);
        var notRun = ApplicationSupport.getDefaultApplicationIdBounded(manager, project, 0);
        assertFalse(notRun.concluded());
        verify(manager, never()).getDefaultApplication(any());
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(manager.getDefaultApplication(project)).thenAnswer(call -> {
            try
            {
                release.await(5, TimeUnit.SECONDS);
                return Optional.empty();
            }
            finally { finished.countDown(); }
        });
        try
        {
            var read = ApplicationSupport.getDefaultApplicationIdBounded(manager, project, 100);
            assertFalse(read.concluded());
            assertEquals(BoundedJob.Outcome.TIMED_OUT, read.outcome());
            assertTrue(read.deadlineFailure().contains("DefaultDeadline"));
            assertTrue(read.deadlineFailure().contains("may yet change EDT's stored state"));
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void storedChoiceIsReadWithoutInferringOrClearingTheEffectiveDefault() throws Exception
    {
        var project = mock(IProject.class);
        String name = "default-reader-" + UUID.randomUUID();
        when(project.getName()).thenReturn(name);
        var preferences = InstanceScope.INSTANCE.getNode("com.e1c.g5.dt.applications")
            .node("applicationDefaults").node("applications");
        var manager = mock(IApplicationManager.class);
        var soleApplication = mock(IApplication.class);
        when(soleApplication.getId()).thenReturn("implicit-single-application");
        when(manager.getDefaultApplication(project)).thenAnswer(call -> {
            preferences.remove(name);
            return Optional.of(soleApplication);
        });
        try
        {
            assertNull(ApplicationSupport.getStoredDefaultApplicationId(project));
            assertEquals(Optional.of("implicit-single-application"),
                ApplicationSupport.getDefaultApplicationIdBounded(manager, project, 5_000).valueOrRethrow());
            assertNull(ApplicationSupport.getStoredDefaultApplicationId(project));
            preferences.put(name, "explicit-application");
            assertEquals("explicit-application", ApplicationSupport.getStoredDefaultApplicationId(project));
            assertEquals("explicit-application", preferences.get(name, null));
            verify(manager, times(1)).getDefaultApplication(project);
            verify(manager, never()).setDefaultApplication(any(), any());
        }
        finally { preferences.remove(name); }
    }
}

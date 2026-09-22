/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.ILaunchConfiguration;
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

    // ====== #622/C: a read that did not CONCLUDE is not the same as "there is no default" ======

    @Test
    public void testAWedgedDefaultLookupIsReportedInconclusiveNotAsAnAnswer() throws Exception
    {
        // The String-returning form degrades to the id it was given - here the EMPTY id a
        // configuration with no binding carries. That value keys a DIFFERENT lockFor mutex than
        // the real application's, so a caller that turns it into an identity silently drops the
        // mutual exclusion #621 exists to preserve. It must be able to see that the read failed.
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Wedged");
        IApplicationManager mgr = mock(IApplicationManager.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(mgr.getDefaultApplication(project)).thenAnswer(invocation -> {
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
            LaunchLifecycleUtils.DefaultApplicationLookup lookup =
                LaunchLifecycleUtils.resolveDefaultApplication(project, "", mgr, 250L);

            assertTrue("a wedged read must be reported as inconclusive", lookup.inconclusive());
            assertEquals("the degraded id stays what it was given", "", lookup.id());
            assertNotNull("the reason must be carried, not swallowed", lookup.failure());
            assertTrue("the reason must name the wedged read", lookup.failure()
                .contains("the EDT default-application lookup for project 'Wedged'"));
            // The lock identity the degradation silently changed.
            assertNotSame("an empty id keys a different lock than a real application id",
                LaunchLifecycleUtils.lockFor("Wedged", lookup.id()),
                LaunchLifecycleUtils.lockFor("Wedged", DEFAULT_ID));
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testARaisedDefaultLookupIsInconclusiveToo() throws Exception
    {
        // The hole the deadline fix left open. getDefaultApplication RAISES (a registered default
        // pointing at a WST server entry that is gone is what the provision delegates actually
        // raise on), the catch handed back the empty id with failure == null, so inconclusive()
        // was false - and RunYaxunitTestsTool.deriveLaunchContext, which refuses only on
        // inconclusive(), ran the whole suite under lockFor(project, ""), a DIFFERENT mutex from
        // the one a concurrent update_database holds.
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Raising");
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getDefaultApplication(project))
            .thenThrow(new ApplicationException("the registered default no longer resolves"));

        LaunchLifecycleUtils.DefaultApplicationLookup lookup =
            LaunchLifecycleUtils.resolveDefaultApplication(project, "", mgr, 30_000L);

        assertTrue("a raised lookup established nothing either", lookup.inconclusive());
        assertNotNull("the raise must be carried, not swallowed", lookup.failure());
        assertTrue("the reason must name the project and the raise: " + lookup.failure(),
            lookup.failure().contains("the EDT default-application lookup for project 'Raising'"));
        assertTrue("the reason must name what the manager said: " + lookup.failure(),
            lookup.failure().contains("the registered default no longer resolves"));
        assertEquals("the id stays the degradation it was given", "", lookup.id());
        // The identity the degradation silently changed - the whole point of #621.
        assertNotSame("an empty id keys a different lock than a real application id",
            LaunchLifecycleUtils.lockFor("Raising", lookup.id()),
            LaunchLifecycleUtils.lockFor("Raising", DEFAULT_ID));
    }

    @Test
    public void testAConcludedAbsenceOfADefaultIsNotInconclusive() throws ApplicationException
    {
        // The other edge: a read that ran and reported no default must NOT be flagged, or every
        // unbound configuration would start refusing.
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Healthy");
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getDefaultApplication(project)).thenReturn(Optional.empty());

        LaunchLifecycleUtils.DefaultApplicationLookup lookup =
            LaunchLifecycleUtils.resolveDefaultApplication(project, "", mgr, 30_000L);

        assertFalse("a measured absence is a conclusion", lookup.inconclusive());
        assertNull(lookup.failure());
        assertEquals("", lookup.id());
    }

    @Test
    public void testAResolvedDefaultIsNotInconclusiveEither() throws ApplicationException
    {
        IProject project = mock(IProject.class);
        IApplication app = mock(IApplication.class);
        when(app.getId()).thenReturn(DEFAULT_ID);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getDefaultApplication(project)).thenReturn(Optional.of(app));

        LaunchLifecycleUtils.DefaultApplicationLookup lookup =
            LaunchLifecycleUtils.resolveDefaultApplication(project, "", mgr, 30_000L);

        assertFalse(lookup.inconclusive());
        assertEquals(DEFAULT_ID, lookup.id());
    }

    // ====== #622/B+F: ONE bounded read for both attribution names, and it reports its own failure ======

    @Test
    public void testBothAttributionNamesComeFromASingleBoundedRead() throws Exception
    {
        // A launch arms BOTH matchers, and the two names come off the same IApplication - so
        // reading it twice doubled what a wedged manager costs the launch for no new information.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplication app = mock(IApplication.class);
        when(app.getName()).thenReturn("Agent Base");
        when(mgr.getApplication(project, "Infobase.Agent")).thenReturn(Optional.of(app));

        LaunchLifecycleUtils.AttributionNames names =
            LaunchLifecycleUtils.attributionNames(mgr, project, "Infobase.Agent");

        assertEquals("Agent Base", names.infobaseName());
        assertFalse("a concluded read is not inconclusive", names.inconclusive());
        verify(mgr, times(1)).getApplication(project, "Infobase.Agent");
    }

    @Test
    public void testAWedgedAttributionLookupSaysTheNameIsUnknownNotAbsent() throws Exception
    {
        // This is the read whose null name silently degrades externalInfobaseChanges to 'cancel'.
        // "this application has no name" is permanent; a ten-second deadline is not, and the
        // advice attached to the two is opposite - so the arm has to be able to tell them apart.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(mgr.getApplication(project, "Infobase.Wedged")).thenAnswer(invocation -> {
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
            LaunchLifecycleUtils.AttributionNames names =
                LaunchLifecycleUtils.attributionNames(mgr, project, "Infobase.Wedged", 250L);

            assertNull(names.infobaseName());
            assertNull(names.serverName());
            assertTrue("a wedged attribution read must say so", names.inconclusive());
        }
        finally
        {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testARaisedAttributionLookupSaysUnknownRatherThanNoSuchName() throws Exception
    {
        // Same asymmetry as the default-application lookup: a raise came back inconclusive=false,
        // so the caller attached the PERMANENT advice ("repeating the same policy would only
        // degrade again") to a manager failure a retry may well get past.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(project, "Infobase.Raising"))
            .thenThrow(new ApplicationException("the provision delegate is unavailable"));

        LaunchLifecycleUtils.AttributionNames names =
            LaunchLifecycleUtils.attributionNames(mgr, project, "Infobase.Raising");

        assertNull(names.infobaseName());
        assertNull(names.serverName());
        assertTrue("a raised attribution read must say the names are UNKNOWN",
            names.inconclusive());
    }

    // ====== an unanswered DEFAULT lookup must keep the delegate's attribution unknown ======

    @Test
    public void testAnUnansweredDefaultLookupKeepsTheDelegateAttributionInconclusive() throws Exception
    {
        // No persisted application id, and the default lookup RAISES. Resolving the delegate id
        // first threw that away: the id came back null and the names said "definitively none",
        // which attaches the permanent "retrying will not help" advice to a transient failure.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("Raising");
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getDefaultApplication(project))
            .thenThrow(new ApplicationException("the registered default no longer resolves"));

        LaunchLifecycleUtils.AttributionNames names =
            LaunchLifecycleUtils.delegateAttributionNames(config, project, mgr);

        assertTrue("an unanswered default lookup leaves the names UNKNOWN", names.inconclusive());
        assertNull(names.infobaseName());
        verify(mgr, never()).getApplication(any(), any());
    }

    @Test
    public void testAMeasuredAbsenceOfADefaultIsADefinitiveNoName() throws Exception
    {
        // The other edge: the lookup ANSWERED "no default", so the names really are absent.
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getDefaultApplication(project)).thenReturn(Optional.empty());

        LaunchLifecycleUtils.AttributionNames names =
            LaunchLifecycleUtils.delegateAttributionNames(config, project, mgr);

        assertFalse("a measured absence is a conclusion", names.inconclusive());
        assertNull(names.infobaseName());
    }

    @Test
    public void testAPersistedApplicationIdIsAttributedWithoutTheDefaultLookup() throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, "")).thenReturn("Infobase.Agent");
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        IApplication app = mock(IApplication.class);
        when(app.getName()).thenReturn("Agent Base");
        when(mgr.getApplication(project, "Infobase.Agent")).thenReturn(Optional.of(app));

        LaunchLifecycleUtils.AttributionNames names =
            LaunchLifecycleUtils.delegateAttributionNames(config, project, mgr);

        assertEquals("Agent Base", names.infobaseName());
        assertFalse(names.inconclusive());
        verify(mgr, never()).getDefaultApplication(any());
    }

    @Test
    public void testAnUnansweredLookupFactoryIsInconclusive()
    {
        LaunchLifecycleUtils.AttributionNames names = LaunchLifecycleUtils.attributionUnanswered();
        assertTrue(names.inconclusive());
        assertNull(names.infobaseName());
        assertNull(names.serverName());
    }

    @Test
    public void testAnApplicationThatSimplyHasNoNameIsNotInconclusive() throws ApplicationException
    {
        // The other edge, and the one the old advice was written for: the read CONCLUDED and the
        // application resolves nothing. Retrying really will not help there.
        IProject project = mock(IProject.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getApplication(project, "Infobase.Nameless")).thenReturn(Optional.empty());

        LaunchLifecycleUtils.AttributionNames names =
            LaunchLifecycleUtils.attributionNames(mgr, project, "Infobase.Nameless");

        assertNull(names.infobaseName());
        assertFalse("a concluded empty lookup is an answer, not a deadline", names.inconclusive());
    }
}

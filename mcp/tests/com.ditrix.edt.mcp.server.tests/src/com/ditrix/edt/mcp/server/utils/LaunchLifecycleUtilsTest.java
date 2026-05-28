/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PreLaunchResult;

/**
 * Tests for {@link LaunchLifecycleUtils}.
 *
 * <p>Focus is on {@link PreLaunchResult} construction and {@code summary()}
 * formatting — the parts that don't require an Eclipse runtime. The actual
 * {@code prepareForFreshLaunch} / {@code terminateAndWait} / {@code
 * updateApplicationIfNeeded} flows need {@code ILaunchManager} +
 * {@code IApplicationManager} and are covered by mvn verify compilation
 * against the EDT target platform.
 */
public class LaunchLifecycleUtilsTest
{
    @Test
    public void testFailedResultSummaryIncludesError()
    {
        PreLaunchResult result = newResult(false, 0, "boom");
        assertFalse(result.isOk());
        assertEquals("boom", result.getError());
        assertTrue("summary should expose the error",
            result.summary().contains("failed") && result.summary().contains("boom"));
    }

    @Test
    public void testOkZeroTerminatedIsNoOp()
    {
        PreLaunchResult result = newResult(true, 0, null);
        assertTrue(result.isOk());
        assertNull(result.getError());
        assertEquals(0, result.getTerminatedCount());
        String summary = result.summary();
        assertTrue("no-op summary should say so",
            summary.contains("no-op") && summary.contains("DB ready"));
    }

    @Test
    public void testOkOneTerminatedSingularPhrasing()
    {
        PreLaunchResult result = newResult(true, 1, null);
        String summary = result.summary();
        assertTrue("must mention terminated count",
            summary.contains("terminated 1 live launch"));
        // singular: no trailing "es"
        assertFalse("singular should not say 'launches'",
            summary.contains("launches"));
        assertTrue("DB readiness phrased", summary.contains("DB ready"));
    }

    @Test
    public void testOkMultipleTerminatedPluralPhrasing()
    {
        PreLaunchResult result = newResult(true, 3, null);
        String summary = result.summary();
        assertTrue("must use plural 'launches'",
            summary.contains("terminated 3 live launches"));
        assertTrue("DB readiness phrased", summary.contains("DB ready"));
    }

    @Test
    public void testTerminateTimeoutPreferenceFallback()
    {
        // Outside the Eclipse OSGi runtime the preference store is unavailable,
        // so this must fall back to the parameter's static default (10s).
        int value = LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds();
        assertTrue("timeout should be a sane positive value",
            value >= 1 && value <= 120);
    }

    @Test
    public void testTerminateAndWaitNullLaunchIsNoOp()
    {
        // No Eclipse mock needed for this branch — null short-circuits immediately.
        assertTrue("null launch must be treated as already terminated",
            LaunchLifecycleUtils.terminateAndWait(null, 1));
    }

    @Test
    public void testRegisterOwnedLaunchNullIsNoOp()
    {
        // Should not throw.
        LaunchLifecycleUtils.registerOwnedLaunch(null);
        LaunchLifecycleUtils.unregisterOwnedLaunch(null);
    }

    @Test
    public void testLockForReturnsSameInstanceForSameKey()
    {
        Object a = LaunchLifecycleUtils.lockFor("MyProject", "app-1");
        Object b = LaunchLifecycleUtils.lockFor("MyProject", "app-1");
        assertEquals("same (project, appId) must return same lock instance", a, b);
    }

    @Test
    public void testLockForDistinguishesProjectsWithSpaces()
    {
        // Guards against printable-delimiter collisions:
        //   {project='My', appId='Project x'} vs {project='My Project', appId='x'}.
        Object a = LaunchLifecycleUtils.lockFor("My", "Project x");
        Object b = LaunchLifecycleUtils.lockFor("My Project", "x");
        assertFalse("locks for distinct (project, appId) must not collide", a == b);
    }

    /**
     * Reflective constructor invocation because PreLaunchResult's constructor
     * is private (the class is only instantiated by {@code prepareForFreshLaunch}).
     */
    private static PreLaunchResult newResult(boolean ok, int terminated, String error)
    {
        try
        {
            Constructor<PreLaunchResult> ctor = PreLaunchResult.class.getDeclaredConstructor(
                boolean.class, int.class, String.class);
            ctor.setAccessible(true);
            return ctor.newInstance(ok, terminated, error);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("Failed to instantiate PreLaunchResult via reflection", e);
        }
    }

    @Test
    public void testPreLaunchResultFieldAccessors()
    {
        PreLaunchResult result = newResult(true, 2, null);
        assertEquals(2, result.getTerminatedCount());
        assertTrue(result.isOk());
        assertNull(result.getError());
    }
}

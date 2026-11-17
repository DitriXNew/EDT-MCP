/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.ProfilingSessionRegistry;

/**
 * Tests for {@link StartProfilingTool}.
 * <p>
 * Covers tool metadata, the input schema, the {@code applicationId} required-argument
 * validation, and the start-only / idempotent contract: when profiling is already
 * marked active for an applicationId, {@code start_profiling} returns an
 * "already profiling" result <em>without</em> toggling (which would otherwise switch
 * profiling off). Resolving the active debug target and the real toggle go through the
 * live debug model and the EDT profiling bundles and are covered by the E2E suite.
 */
public class StartProfilingToolTest
{
    @Before
    public void resetState()
    {
        ProfilingSessionRegistry.clearStateForTests();
    }

    @After
    public void clearState()
    {
        ProfilingSessionRegistry.clearStateForTests();
    }

    @Test
    public void testName()
    {
        assertEquals("start_profiling", new StartProfilingTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(StartProfilingTool.NAME, new StartProfilingTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new StartProfilingTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new StartProfilingTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testDescriptionMentionsStop()
    {
        // start_profiling must point the client at the deterministic stop tool.
        assertTrue(new StartProfilingTool().getGuide().contains("stop_profiling")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new StartProfilingTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testMissingApplicationId()
    {
        Map<String, String> params = new HashMap<>();
        String result = new StartProfilingTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
    }

    // ==================== Start-only / idempotent state contract ====================

    @Test
    public void testAlreadyActiveReturnsIdempotentResultWithoutToggling()
    {
        String appId = "app-1"; //$NON-NLS-1$
        ProfilingSessionRegistry.markActive(appId);

        Map<String, String> params = new HashMap<>();
        params.put("applicationId", appId); //$NON-NLS-1$
        String result = new StartProfilingTool().execute(params);

        // Benign idempotent result, not an error, and profiling stays active.
        assertTrue(result.contains("already active")); //$NON-NLS-1$
        assertTrue(result.contains("\"started\":false")); //$NON-NLS-1$
        assertTrue(result.contains("\"active\":true")); //$NON-NLS-1$
        assertTrue(ProfilingSessionRegistry.isProfilingActive(appId));
    }

    @Test
    public void flagFromAnEarlierSessionOfTheSameApplicationIdIsDropped()
    {
        // A relaunch keeps the applicationId; the flag must follow the target, not the id.
        org.eclipse.debug.core.model.IDebugTarget first = target(), relaunched = target();
        ProfilingSessionRegistry.markActive("app", first); //$NON-NLS-1$
        ProfilingSessionRegistry.reconcile("app", first); //$NON-NLS-1$
        assertTrue(ProfilingSessionRegistry.isProfilingActive("app")); //$NON-NLS-1$
        ProfilingSessionRegistry.reconcile("app", relaunched); //$NON-NLS-1$
        assertFalse(ProfilingSessionRegistry.isProfilingActive("app")); //$NON-NLS-1$
        // No live session at all (terminated while profiling): start_profiling must not answer "already active".
        ProfilingSessionRegistry.markActive("app", first); //$NON-NLS-1$
        ProfilingSessionRegistry.reconcile("app", null); //$NON-NLS-1$
        assertFalse(ProfilingSessionRegistry.isProfilingActive("app")); //$NON-NLS-1$
    }

    /** A stop acts on the live target that owns the flag, not on whichever target discovery returned. */
    @Test
    public void stopActsOnTheLiveOwnerNotTheLookedUpTarget()
    {
        org.eclipse.debug.core.model.IDebugTarget owner = target(), lookedUp = target(), gone = target();
        java.util.function.Predicate<org.eclipse.debug.core.model.IDebugTarget> ended = t -> t == gone;
        assertEquals(owner, ProfilingSessionRegistry.profiledTarget(owner, lookedUp, ended));
        assertEquals(lookedUp, ProfilingSessionRegistry.profiledTarget(gone, lookedUp, ended));
        assertEquals(lookedUp, ProfilingSessionRegistry.profiledTarget(null, lookedUp, ended));
        assertEquals(lookedUp, ProfilingSessionRegistry.profiledTarget(new Object(), lookedUp, ended));
        assertEquals(owner, ProfilingSessionRegistry.profiledTarget(owner, null, ended));
    }

    /** EDT's own state for the looked-up target replaces only a missing, same, unknown or ended owner. */
    @Test
    public void readableStateNeverOverridesALiveOtherOwner()
    {
        org.eclipse.debug.core.model.IDebugTarget looked = target(), live = target(), gone = target();
        java.util.function.Predicate<org.eclipse.debug.core.model.IDebugTarget> ended = t -> t == gone;
        assertEquals(looked, ProfilingSessionRegistry.ownerAfterReading(null, looked, true, ended));
        assertEquals(null, ProfilingSessionRegistry.ownerAfterReading(looked, looked, false, ended));
        assertEquals(live, ProfilingSessionRegistry.ownerAfterReading(live, looked, true, ended));
        assertEquals(live, ProfilingSessionRegistry.ownerAfterReading(live, looked, false, ended));
        assertEquals(null, ProfilingSessionRegistry.ownerAfterReading(gone, looked, false, ended));
        assertEquals(looked, ProfilingSessionRegistry.ownerAfterReading(gone, looked, true, ended));
        assertEquals(null, ProfilingSessionRegistry.ownerAfterReading(new Object(), looked, false, ended));
        // A reading taken before its own target ended never resurrects that target as the owner.
        assertEquals(null, ProfilingSessionRegistry.ownerAfterReading(null, gone, true, ended));
    }

    /** A stale lookup (an older target, or none) never clears the flag of a newer owner that is still live. */
    @Test
    public void staleTargetKeepsTheFlagOfALiveNewerOwner()
    {
        org.eclipse.debug.core.model.IDebugTarget newer = org.mockito.Mockito.mock(org.eclipse.debug.core.model.IDebugTarget.class);
        org.eclipse.debug.core.ILaunch launch = org.mockito.Mockito.mock(org.eclipse.debug.core.ILaunch.class);
        org.mockito.Mockito.when(newer.getLaunch()).thenReturn(launch);
        org.mockito.Mockito.when(launch.getProcesses()).thenReturn(new org.eclipse.debug.core.model.IProcess[] {
            org.mockito.Mockito.mock(org.eclipse.debug.core.model.IProcess.class) });
        ProfilingSessionRegistry.markActive("app", newer); //$NON-NLS-1$
        ProfilingSessionRegistry.reconcile("app", target()); //$NON-NLS-1$
        assertTrue(ProfilingSessionRegistry.isProfilingActive("app")); //$NON-NLS-1$
        ProfilingSessionRegistry.reconcile("app", null); //$NON-NLS-1$
        assertTrue(ProfilingSessionRegistry.isProfilingActive("app")); //$NON-NLS-1$
    }

    /** A lookup miss alone keeps the flag of a target whose launch is still live (a relaunch race). */
    @Test
    public void lookupMissKeepsTheFlagOfALiveOwner()
    {
        org.eclipse.debug.core.model.IDebugTarget owner = org.mockito.Mockito.mock(org.eclipse.debug.core.model.IDebugTarget.class);
        org.eclipse.debug.core.ILaunch launch = org.mockito.Mockito.mock(org.eclipse.debug.core.ILaunch.class);
        org.mockito.Mockito.when(owner.getLaunch()).thenReturn(launch);
        assertFalse(ProfilingSessionRegistry.ended(owner, l -> l == launch));
        // Removed from the launch manager while its client still runs (an unconfirmed orphan): still live.
        org.eclipse.debug.core.model.IProcess process = org.mockito.Mockito.mock(org.eclipse.debug.core.model.IProcess.class);
        org.mockito.Mockito.when(launch.getProcesses()).thenReturn(new org.eclipse.debug.core.model.IProcess[] { process });
        assertFalse(ProfilingSessionRegistry.ended(owner, l -> false));
        // Removed and its client exited (terminate while profiling): ended.
        org.mockito.Mockito.when(process.isTerminated()).thenReturn(true);
        assertTrue(ProfilingSessionRegistry.ended(owner, l -> false));
        assertFalse(ProfilingSessionRegistry.ended(owner, l -> l == launch));
        org.mockito.Mockito.when(launch.isTerminated()).thenReturn(true);
        assertTrue(ProfilingSessionRegistry.ended(owner, l -> l == launch));
        org.mockito.Mockito.when(owner.isTerminated()).thenReturn(true);
        assertTrue(ProfilingSessionRegistry.ended(owner, l -> true));
    }

    private static org.eclipse.debug.core.model.IDebugTarget target()
    {
        return (org.eclipse.debug.core.model.IDebugTarget)java.lang.reflect.Proxy.newProxyInstance(
            StartProfilingToolTest.class.getClassLoader(), new Class<?>[] { org.eclipse.debug.core.model.IDebugTarget.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> proxy == args[0]; //$NON-NLS-1$
                case "hashCode" -> System.identityHashCode(proxy); //$NON-NLS-1$
                case "toString" -> "target"; //$NON-NLS-1$ //$NON-NLS-2$
                default -> method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            });
    }

    @Test
    public void testStateHelpersTrackPerApplicationId()
    {
        assertFalse(ProfilingSessionRegistry.isProfilingActive("x")); //$NON-NLS-1$
        assertFalse(ProfilingSessionRegistry.isAnyProfilingActive());

        ProfilingSessionRegistry.markActive("x"); //$NON-NLS-1$
        assertTrue(ProfilingSessionRegistry.isProfilingActive("x")); //$NON-NLS-1$
        assertTrue(ProfilingSessionRegistry.isAnyProfilingActive());
        assertFalse(ProfilingSessionRegistry.isProfilingActive("y")); //$NON-NLS-1$

        ProfilingSessionRegistry.markInactive("x"); //$NON-NLS-1$
        assertFalse(ProfilingSessionRegistry.isProfilingActive("x")); //$NON-NLS-1$
        assertFalse(ProfilingSessionRegistry.isAnyProfilingActive());
    }

    @Test public void unknownPlatformStateRefusesWithoutToggling()
    {
        var target = org.mockito.Mockito.mock(org.eclipse.debug.core.model.IDebugTarget.class,
            org.mockito.Mockito.withSettings().extraInterfaces(com._1c.g5.v8.dt.profiling.core.IProfileTarget.class));
        var service = org.mockito.Mockito.mock(com._1c.g5.v8.dt.profiling.core.IProfilingService.class);
        String refusal = com.ditrix.edt.mcp.server.utils.ProfilingSupport.startProfilingIfKnown(service, target);
        assertTrue(refusal, refusal.contains("unknown"));
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).toggleProfiling(
            org.mockito.ArgumentMatchers.any());
    }

}

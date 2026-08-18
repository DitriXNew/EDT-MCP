/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationException;

/**
 * Tests for {@link StandaloneServerStateRecovery}: recognising EDT's stale standalone-server
 * refusal, and saying something actionable about it.
 *
 * <p>The failure shape here is the real one — EDT reports the refusal as an
 * {@code ApplicationException} whose status says only "An internal error occurred during:
 * "Starting Standalone server for X"", with the sentence that names the reason three hops down in
 * an {@link IllegalStateException}. A recogniser that looks at the headline sees nothing.
 */
public class StandaloneServerStateRecoveryTest
{
    private static final String PLUGIN = "com.ditrix.edt.mcp.server";

    private static final String HEADLINE =
        "An internal error occurred during: \"Starting Standalone server for TestBase\".";

    /** The refusal exactly as EDT's standalone-server behaviour delegate phrases it. */
    private static Throwable refusal(int state)
    {
        IllegalStateException reason = new IllegalStateException(
            "Can only start server that is stopped but current server state is " + state);
        CoreException inner = new CoreException(new Status(IStatus.ERROR, PLUGIN, HEADLINE, reason));
        return new ApplicationException(new Status(IStatus.ERROR, PLUGIN, HEADLINE, inner));
    }

    @Test
    public void testRefusalIsFoundThroughTheWholeWrapping()
    {
        String message = StandaloneServerStateRecovery.refusalMessage(refusal(2));
        assertNotNull("the refusal is three hops below the headline and must still be found",
            message);
        assertTrue(message.contains("current server state is 2"));
    }

    @Test
    public void testTheHeadlineAloneIsNotARefusal()
    {
        // The guard that keeps the recovery from firing on every failed server start.
        assertFalse(StandaloneServerStateRecovery.isStaleServerState(
            new ApplicationException(new Status(IStatus.ERROR, PLUGIN, HEADLINE))));
        assertFalse(StandaloneServerStateRecovery.isStaleServerState(null));
        assertFalse(StandaloneServerStateRecovery.isStaleServerState(
            new IllegalStateException("Server \"S\" start attempt failed.")));
    }

    @Test
    public void testRefusalIsFoundInsideAChildStatus()
    {
        // EDT aggregates publish results into a MultiStatus; the refusal can arrive as a child.
        MultiStatus status = new MultiStatus(PLUGIN, 0, "Database update failed", null);
        status.add(new Status(IStatus.INFO, PLUGIN, "Publishing configuration"));
        status.add(new Status(IStatus.ERROR, PLUGIN,
            "Can only start server that is stopped but current server state is 2"));
        assertTrue(StandaloneServerStateRecovery.isStaleServerState(
            new ApplicationException(status)));
    }

    @Test
    public void testStateNamesFollowWst()
    {
        assertEquals("STARTED", StandaloneServerStateRecovery.refusedStateName(
            StandaloneServerStateRecovery.refusalMessage(refusal(2))));
        assertEquals("STARTING", StandaloneServerStateRecovery.refusedStateName(
            StandaloneServerStateRecovery.refusalMessage(refusal(1))));
        assertEquals("STOPPING", StandaloneServerStateRecovery.refusedStateName(
            StandaloneServerStateRecovery.refusalMessage(refusal(3))));
        assertEquals("UNKNOWN", StandaloneServerStateRecovery.refusedStateName(
            StandaloneServerStateRecovery.refusalMessage(refusal(0))));
    }

    @Test
    public void testUnreadableStateIsNotInvented()
    {
        assertNull(StandaloneServerStateRecovery.refusedStateName(null));
        assertNull("a message without the marker names no state",
            StandaloneServerStateRecovery.refusedStateName("Server start attempt failed."));
        assertNull("a marker with no number following it names no state",
            StandaloneServerStateRecovery.refusedStateName(
                "Can only start server that is stopped but current server state is unclear"));
    }

    @Test
    public void testErrorForTheStuckServerNamesWhatWasDoneAndWhatIsLeft()
    {
        String refusal = StandaloneServerStateRecovery.refusalMessage(refusal(2));
        String message = StandaloneServerStateRecovery.staleStateError("ServerApplication.Test",
            refusal, StandaloneServerStateRecovery.Recovery.stopped(), "ports 8429, 8420 are busy");
        assertTrue("names the application", message.contains("ServerApplication.Test"));
        assertTrue("names the state EDT refused on", message.contains("STARTED"));
        assertTrue("reports that the retry happened", message.contains("retried once"));
        assertTrue("carries the retry's own reason", message.contains("ports 8429, 8420 are busy"));
        assertFalse("never renders as the literal null", message.contains("null"));
    }

    @Test
    public void testErrorWhenTheServerCouldNotBeStoppedTellsTheUserWhatToDo()
    {
        String refusal = StandaloneServerStateRecovery.refusalMessage(refusal(2));
        String message = StandaloneServerStateRecovery.staleStateError("ServerApplication.Test",
            refusal, StandaloneServerStateRecovery.Recovery.failed("the EDT application manager "
                + "is not available"), null);
        assertTrue(message.contains("the EDT application manager is not available"));
        assertTrue("points at the manual way out", message.contains("Servers view"));
        assertFalse("nothing was retried, so it must not claim it was",
            message.contains("retried once"));
    }

    @Test
    public void testATransientStateIsReportedAsSuchAndNotAsAStuckServer()
    {
        // STARTING/STOPPING belong to an operation still in flight. Advising a stop there would
        // tell the caller to break someone else's start.
        String refusal = StandaloneServerStateRecovery.refusalMessage(refusal(1));
        String message = StandaloneServerStateRecovery.staleStateError("ServerApplication.Test",
            refusal, StandaloneServerStateRecovery.Recovery.failed("unused"), null);
        assertTrue(message.contains("STARTING"));
        assertTrue("says to wait it out", message.contains("retry"));
        assertFalse("must not claim EDT lost the launch", message.contains("outlives the launch"));
        assertFalse("must not leak the unused recovery detail", message.contains("unused"));
    }

    @Test
    public void testStopWithoutATargetFailsInsteadOfThrowing()
    {
        StandaloneServerStateRecovery.Recovery recovery =
            StandaloneServerStateRecovery.stopStaleServer(null, null);
        assertFalse(recovery.recovered());
        assertNotNull("a failed recovery always says why", recovery.detail());
    }

    @Test
    public void testStateNameFallsBackToTheNumberForAStateWstDoesNotDefine()
    {
        assertEquals("9", StandaloneServerStateRecovery.stateName(9));
    }
}

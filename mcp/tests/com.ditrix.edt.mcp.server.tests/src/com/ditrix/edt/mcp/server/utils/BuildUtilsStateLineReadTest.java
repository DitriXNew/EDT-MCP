/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the accumulation limit on the bounded read of a module's state line back from a project's {@code .mdo}
 * (#743 review). The read runs in a job the caller stops waiting for at its deadline, and a read the deadline cut off
 * keeps that job until the file system answers - so while such a read of a project is out, no second one may start
 * until its claim lapses, or repeated writes would pile up one hung job each. The limit is the marker read's, on a key
 * of its own, and it has the same two failure modes worth pinning: letting a second read through, and shutting the
 * project out forever.
 */
public class BuildUtilsStateLineReadTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final String OTHER = "OtherProject"; //$NON-NLS-1$
    private static final long DEADLINE_MS = 60_000L;

    @Before
    public void reset()
    {
        BuildUtils.forgetExportWaits();
    }

    @Test
    public void testASecondReadIsRefusedWhileTheFirstHasNotReturned()
    {
        assertNotNull("the first read must be allowed to start", //$NON-NLS-1$
            BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS));
        assertNull("a second read must not start while the first is still out", //$NON-NLS-1$
            BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS));
        assertNull("and it must keep being refused, not just the first time", //$NON-NLS-1$
            BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS));
    }

    @Test
    public void testTheSlotReopensOnceTheReadReturns()
    {
        AtomicBoolean first = BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS);
        assertNotNull(first);
        assertNull(BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS));

        // What the read's job does when the file system finally answers.
        first.set(true);

        AtomicBoolean second = BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS);
        assertNotNull("a returned read must reopen the slot, or one slow read would refuse every later one", second); //$NON-NLS-1$
        assertNotSame("each read needs its own flag", first, second); //$NON-NLS-1$
    }

    @Test
    public void testAClaimThatIsNeverReturnedStillLapses() throws Exception
    {
        // A read that never comes back - a file system that never answers - must not shut the project's reads out for
        // the rest of the session. The hold is a multiple of the timeout, so a near-zero one lapses at once; the flag
        // is deliberately never set.
        AtomicBoolean abandoned = BuildUtils.beginStateLineRead(PROJECT, 1L);
        assertNotNull(abandoned);
        assertNull("still held while the (tiny) hold lasts", BuildUtils.beginStateLineRead(PROJECT, 1L)); //$NON-NLS-1$

        Thread.sleep(25);

        AtomicBoolean next = BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS);
        assertNotNull("an expired claim must not keep refusing: nothing would ever reopen it", next); //$NON-NLS-1$
        assertNotSame(abandoned, next);
    }

    @Test
    public void testTheLimitIsPerProject()
    {
        // A hung read in one project must not refuse the reads of another: one outstanding read per PROJECT.
        assertNotNull(BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS));
        assertNull(BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS));

        assertNotNull("an unrelated project must still be allowed to read", //$NON-NLS-1$
            BuildUtils.beginStateLineRead(OTHER, DEADLINE_MS));
    }

    @Test
    public void testTheReadHasAKeyOfItsOwn()
    {
        // The export wait, the validation wait and the marker read of the same project, all outstanding, do not refuse
        // the state-line read...
        assertNotNull(BuildUtils.beginExportWait(PROJECT, DEADLINE_MS));
        assertNotNull(BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS));
        assertNotNull(BuildUtils.beginMarkerRead(PROJECT, DEADLINE_MS));
        assertNotNull("an outstanding export wait, validation wait or marker read must not refuse the read", //$NON-NLS-1$
            BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS));

        // ...and an outstanding state-line read refuses none of them.
        BuildUtils.forgetExportWaits();
        assertNotNull(BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS));
        assertNotNull("nor may the read refuse the export wait", BuildUtils.beginExportWait(PROJECT, DEADLINE_MS)); //$NON-NLS-1$
        assertNotNull("or the validation wait", BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS)); //$NON-NLS-1$
        assertNotNull("or the marker read", BuildUtils.beginMarkerRead(PROJECT, DEADLINE_MS)); //$NON-NLS-1$
        assertNull("while a second state-line read is still refused", //$NON-NLS-1$
            BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS));
    }

    @Test
    public void testTheHoldLeftIsReadOffTheProjectsUnreturnedClaim() throws Exception
    {
        // The claim's size is its caller's choice - three times the timeout it passes - and a pin on that size needs it
        // read off the slot: waiting it out would take minutes.
        assertEquals("no claim, nothing held", 0L, BuildUtils.stateLineReadHoldLeftMs(PROJECT)); //$NON-NLS-1$
        AtomicBoolean claim = BuildUtils.beginStateLineRead(PROJECT, DEADLINE_MS);
        assertNotNull(claim);
        long left = BuildUtils.stateLineReadHoldLeftMs(PROJECT);
        assertTrue("an unreturned claim holds for three times its timeout: " + left + "ms left", //$NON-NLS-1$ //$NON-NLS-2$
            left > 3 * DEADLINE_MS - 10_000L && left <= 3 * DEADLINE_MS);
        assertEquals("another project holds nothing", 0L, BuildUtils.stateLineReadHoldLeftMs(OTHER)); //$NON-NLS-1$

        claim.set(true); // the read returned
        assertEquals("a returned claim holds nothing", 0L, BuildUtils.stateLineReadHoldLeftMs(PROJECT)); //$NON-NLS-1$

        assertNotNull(BuildUtils.beginMarkerRead(PROJECT, DEADLINE_MS));
        assertEquals("the marker read's claim is not the state-line read's", 0L, //$NON-NLS-1$
            BuildUtils.stateLineReadHoldLeftMs(PROJECT));

        assertNotNull(BuildUtils.beginStateLineRead(PROJECT, 1L));
        Thread.sleep(25);
        assertEquals("a lapsed claim holds nothing", 0L, BuildUtils.stateLineReadHoldLeftMs(PROJECT)); //$NON-NLS-1$
    }
}

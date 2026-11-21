/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * #662 review: a dcs write waits for its export only what is left of its call budget (down to 5 s), but
 * an export wait that never returns must still hold its project's slot as long as a full 60 s wait
 * would - a shorter caller deadline must not let a wedged pipeline collect unstoppable jobs faster.
 */
public class BuildUtilsExportSlotHoldTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$

    private final AtomicLong nanos = new AtomicLong(1_000L);
    private LongSupplier previous;

    @Before
    public void setUp()
    {
        BuildUtils.forgetExportWaits();
        previous = BuildUtils.useSlotClock(nanos::get);
    }

    @After
    public void tearDown()
    {
        BuildUtils.useSlotClock(previous);
        BuildUtils.forgetExportWaits();
    }

    private void advanceSeconds(long seconds)
    {
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    @Test
    public void testAShortCallerDeadlineStillHoldsTheSlotForTheFullWait()
    {
        assertNotNull(BuildUtils.beginExportWait(PROJECT, 5_000L, 60_000L));
        advanceSeconds(20);
        assertNull("a 5 s caller deadline must not reopen the slot after 15 s", //$NON-NLS-1$
            BuildUtils.beginExportWait(PROJECT, 5_000L, 60_000L));
        advanceSeconds(161);
        assertNotNull("the claim still lapses on the full wait's hold", //$NON-NLS-1$
            BuildUtils.beginExportWait(PROJECT, 5_000L, 60_000L));
    }

    @Test
    public void testWithoutAHoldBasisTheClaimLapsesOnTheCallersOwnDeadline()
    {
        assertNotNull(BuildUtils.beginExportWait(PROJECT, 5_000L));
        advanceSeconds(20);
        assertNotNull("the existing two-argument claim keeps its own pace", //$NON-NLS-1$
            BuildUtils.beginExportWait(PROJECT, 5_000L));
    }
}

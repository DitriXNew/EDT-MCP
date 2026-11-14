/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.reference.ReferenceSearchBound;

/**
 * Issue #931: the tool hands the reference search a REAL bound - the clamped {@code limit} and a time
 * budget counted from the start of the call, so the pre-scan readiness wait is paid out of the same
 * budget instead of extending the call past the client's transport timeout.
 */
public class FindReferencesToolBoundTest
{
    private static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(10);

    private static final class Probe
    {
        final List<ReferenceSearchBound> bounds = new ArrayList<>();
        final AtomicLong now = new AtomicLong(TimeUnit.SECONDS.toNanos(1000));

        FindReferencesTool tool()
        {
            return new FindReferencesTool((name, budget) -> {
                now.addAndGet(SETTLE_NANOS);
                return null;
            }, Runnable::run, () -> false, (projectName, objectFqn, bound) -> {
                bounds.add(bound);
                return "ok"; //$NON-NLS-1$
            }, now::get);
        }

        ReferenceSearchBound run(String limit)
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "P"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("objectFqn", "Catalog.X"); //$NON-NLS-1$ //$NON-NLS-2$
            if (limit != null)
            {
                params.put("limit", limit); //$NON-NLS-1$
            }
            assertEquals("ok", tool().execute(params)); //$NON-NLS-1$
            return bounds.get(bounds.size() - 1);
        }
    }

    @Test
    public void theSearchGetsABoundNotAnOpenEndedScan()
    {
        ReferenceSearchBound bound = new Probe().run("50"); //$NON-NLS-1$
        assertTrue(bound.isBounded());
        assertEquals(50, bound.limit());
        assertEquals(ReferenceSearchBound.TIME_BUDGET_MS, bound.budgetMillis());
    }

    @Test
    public void theLimitIsClampedToOneThroughFiveHundredWithADefaultOfOneHundred()
    {
        Probe probe = new Probe();
        assertEquals(100, probe.run(null).limit());
        assertEquals(500, probe.run("9999").limit()); //$NON-NLS-1$
        assertEquals(1, probe.run("0").limit()); //$NON-NLS-1$
        assertEquals(1, probe.run("-3").limit()); //$NON-NLS-1$
    }

    /** The budget starts before the readiness wait, so the wait is spent out of it. */
    @Test
    public void theTimeBudgetCountsFromTheStartOfTheCallIncludingTheSettle()
    {
        Probe probe = new Probe();
        long start = probe.now.get();
        ReferenceSearchBound bound = probe.run("10"); //$NON-NLS-1$
        long budget = TimeUnit.MILLISECONDS.toNanos(ReferenceSearchBound.TIME_BUDGET_MS);

        probe.now.set(start + budget - 1);
        assertFalse(bound.shouldStop(0));
        probe.now.set(start + budget);
        assertTrue(bound.shouldStop(0));
        assertEquals(ReferenceSearchBound.StopReason.TIME_BUDGET, bound.stopReason());
    }
}

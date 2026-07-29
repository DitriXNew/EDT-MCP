/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link AsyncLaunchOutcomes} — the registry that lets a FIRE-AND-FORGET launch report
 * what happened after {@code debug_launch} already answered {@code status: "launching"}.
 *
 * <p>What matters here is that the registry stays a small, bounded window into the recent past: it
 * must never grow into a log, and it must never swallow the newest failure — that is the one the
 * caller is asking about.
 */
public class AsyncLaunchOutcomesTest
{
    @Before
    public void clear()
    {
        AsyncLaunchOutcomes.clearForTest();
    }

    @Test
    public void testRecordsTheFailureWithItsConfigurationAndMessage()
    {
        AsyncLaunchOutcomes.record("Trade YAxUnit", "app-uuid", "the update wrote nothing"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<AsyncLaunchOutcomes.Outcome> recent = AsyncLaunchOutcomes.recent();
        assertEquals(1, recent.size());
        assertEquals("Trade YAxUnit", recent.get(0).launchConfiguration()); //$NON-NLS-1$
        assertEquals("the update wrote nothing", recent.get(0).message()); //$NON-NLS-1$
        assertEquals("app-uuid", recent.get(0).applicationId()); //$NON-NLS-1$
        assertTrue(recent.get(0).ageSeconds() >= 0);
    }

    @Test
    public void testKeepsTheNewestEntriesWithinTheCap()
    {
        for (int i = 0; i < AsyncLaunchOutcomes.MAX_ENTRIES + 5; i++)
        {
            AsyncLaunchOutcomes.record("config-" + i, "app", "failure " + i); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        List<AsyncLaunchOutcomes.Outcome> recent = AsyncLaunchOutcomes.recent();
        assertEquals(AsyncLaunchOutcomes.MAX_ENTRIES, recent.size());
        // The NEWEST failure is the one a caller is asking about — it must never be the one dropped.
        assertEquals("failure " + (AsyncLaunchOutcomes.MAX_ENTRIES + 4), //$NON-NLS-1$
            recent.get(recent.size() - 1).message());
        assertEquals("failure 5", recent.get(0).message()); //$NON-NLS-1$
    }

    @Test
    public void testIgnoresAnEmptyMessageAndKeepsANullConfigurationName()
    {
        // A launch started by name only carries no configuration object; the message is what makes
        // the entry useful, so an empty one is not worth reporting at all.
        AsyncLaunchOutcomes.record("config", "app", null); //$NON-NLS-1$ //$NON-NLS-2$
        AsyncLaunchOutcomes.record("config", "app", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(AsyncLaunchOutcomes.recent().isEmpty());

        AsyncLaunchOutcomes.record(null, null, "the update wrote nothing"); //$NON-NLS-1$
        assertEquals(1, AsyncLaunchOutcomes.recent().size());
        assertNull(AsyncLaunchOutcomes.recent().get(0).launchConfiguration());
    }

    @Test
    public void testAnExpiredEntryIsDroppedBeforeTheCapEvictsALiveOne()
    {
        // The cap is the last line of defence: an entry nobody can report any more must not be what
        // pushes a live one out of the window a caller is about to poll.
        for (int i = 0; i < AsyncLaunchOutcomes.MAX_ENTRIES; i++)
        {
            AsyncLaunchOutcomes.record("config-" + i, "app", "failure " + i); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        AsyncLaunchOutcomes.record("newest", "app", "the one being asked about"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        java.util.List<AsyncLaunchOutcomes.Outcome> recent = AsyncLaunchOutcomes.recent();
        assertEquals(AsyncLaunchOutcomes.MAX_ENTRIES, recent.size());
        assertEquals("the one being asked about", recent.get(recent.size() - 1).message()); //$NON-NLS-1$
    }

    @Test
    public void testRecentIsASnapshotTheCallerCannotCorrupt()
    {
        AsyncLaunchOutcomes.record("config", "app", "first"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        List<AsyncLaunchOutcomes.Outcome> snapshot = AsyncLaunchOutcomes.recent();
        snapshot.clear();

        assertEquals(1, AsyncLaunchOutcomes.recent().size());
    }
}

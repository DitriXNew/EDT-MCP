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

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests the per-object validation wait's slot and timeout guards (issue #643). The wait itself
 * needs a live derived-data pipeline; what is pinned here is the accumulation limit and the
 * EDT 2026.1.1 linkage rule that the platform never receives a non-positive timeout.
 */
public class BuildUtilsValidationWaitTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final long DEADLINE_MS = 10_000L;

    @Before
    public void reset()
    {
        BuildUtils.forgetExportWaits();
    }

    @Test
    public void testValidationAndExportSlotsOfOneProjectDoNotBlockEachOther()
    {
        assertNotNull(BuildUtils.beginExportWait(PROJECT, DEADLINE_MS));
        assertNotNull("an outstanding export wait must not refuse the validation wait of the same write", //$NON-NLS-1$
            BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS));
        assertNull("but a second validation wait is refused", //$NON-NLS-1$
            BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS));
        assertNull("and the export slot is still held", BuildUtils.beginExportWait(PROJECT, DEADLINE_MS)); //$NON-NLS-1$
    }

    @Test
    public void testTheValidationSlotReopensOnReturn()
    {
        AtomicBoolean first = BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS);
        assertNotNull(first);
        first.set(true);

        AtomicBoolean second = BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS);
        assertNotNull(second);
        assertNotSame(first, second);
    }

    @Test
    public void testAnUnreturnedValidationClaimLapses() throws Exception
    {
        assertNotNull(BuildUtils.beginValidationWait(PROJECT, 1L));
        assertNull(BuildUtils.beginValidationWait(PROJECT, 1L));

        Thread.sleep(25);

        assertNotNull("an expired claim must not keep refusing", //$NON-NLS-1$
            BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS));
    }

    @Test
    public void testThePlatformNeverGetsANonPositiveTimeout()
    {
        assertEquals(1L, BuildUtils.platformTimeoutMs(0L));
        assertEquals(1L, BuildUtils.platformTimeoutMs(-1L));
        assertEquals(1L, BuildUtils.platformTimeoutMs(Long.MIN_VALUE));
        assertEquals(DEADLINE_MS, BuildUtils.platformTimeoutMs(DEADLINE_MS));
    }

    @Test
    public void testAnEmptyScopeIsUnobservableWithoutAskingThePlatform()
    {
        assertEquals(BuildUtils.ValidationState.UNOBSERVABLE,
            BuildUtils.waitForObjectValidation(null, Collections.<Long> emptyList(), DEADLINE_MS));
        assertNotNull("no slot was taken", BuildUtils.beginValidationWait(PROJECT, DEADLINE_MS)); //$NON-NLS-1$
    }
}

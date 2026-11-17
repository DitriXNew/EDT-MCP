// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server;

import static org.junit.Assert.*;

import java.time.Duration;

import org.junit.Test;

/** The idle-timeout property is bounded by what {@link HeadlessLifetime} can schedule, with a named refusal. */
public class StdioHostIdleBoundTest
{
    @Test public void theLargestAcceptedTimeoutIsOneTheLifetimeCanArm()
    {
        Duration idle = StdioHostApplication.idleDuration(Long.toString(StdioHostApplication.MAX_IDLE_MINUTES));
        assertEquals(153_722_867L, StdioHostApplication.MAX_IDLE_MINUTES);
        assertEquals(StdioHostApplication.MAX_IDLE_MINUTES * 60_000_000_000L, idle.toNanos());
        try (HeadlessLifetime lifetime = new HeadlessLifetime(idle, (task, delay) -> () -> { }, () -> { })) {
            assertNotNull(lifetime.tool());
        }
    }

    @Test public void oneMinuteMoreIsRefusedByNameBeforeTheHostStarts()
    {
        String tooLong = Long.toString(StdioHostApplication.MAX_IDLE_MINUTES + 1);
        try
        {
            StdioHostApplication.idleDuration(tooLong);
            fail("an idle timeout past the nanosecond range must be refused");
        }
        catch (IllegalArgumentException expected)
        {
            assertEquals("edt.mcp.headlessIdleMinutes must be at most 153722867 (0 disables the idle shutdown)",
                expected.getMessage());
        }
    }

    @Test public void aValuePastTheMinuteRangeGetsTheSameRefusal()
    {
        try
        {
            StdioHostApplication.idleDuration("999999999999999999");
            fail("a value past Duration's minute range must be refused");
        }
        catch (IllegalArgumentException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("edt.mcp.headlessIdleMinutes must be at most "));
        }
    }
}

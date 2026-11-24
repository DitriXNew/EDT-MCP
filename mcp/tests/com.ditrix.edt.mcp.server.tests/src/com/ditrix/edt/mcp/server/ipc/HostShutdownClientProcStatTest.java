// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.ipc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HostShutdownClientProcStatTest
{
    // Linux stat fields 4 through 52 are space-separated decimal numbers.
    private static final String FIELDS_BEFORE_THREAD_COUNT = " 1 -1" + " 0".repeat(14);
    private static final String FIELDS_AFTER_THREAD_COUNT = " 0".repeat(32) + "\n";
    private static final String FIELDS = FIELDS_BEFORE_THREAD_COUNT + " 1" + FIELDS_AFTER_THREAD_COUNT;

    @Test public void findsTheStateAfterTheWholeProcessName()
    {
        assertTrue(HostShutdownClient.isExitedLinuxStat(123, "123 (a) R (b\nc)) Z" + FIELDS));
        assertTrue(HostShutdownClient.isExitedLinuxStat(123, "123 (java) X" + FIELDS));
        assertFalse(HostShutdownClient.isExitedLinuxStat(123, "123 (fake) Z (name) S" + FIELDS));
    }

    @Test public void zombieLeaderDoesNotMeanAllThreadsExited()
    {
        assertFalse(HostShutdownClient.isExitedLinuxStat(123,
            "123 (java) Z" + FIELDS_BEFORE_THREAD_COUNT + " 2" + FIELDS_AFTER_THREAD_COUNT));
    }

    @Test public void refusesToInferExitFromMalformedOrMismatchedStat()
    {
        assertFalse(HostShutdownClient.isExitedLinuxStat(123, "456 (java) Z" + FIELDS));
        assertFalse(HostShutdownClient.isExitedLinuxStat(123, "123 java) Z" + FIELDS));
        assertFalse(HostShutdownClient.isExitedLinuxStat(123, "123 (java) ZZ" + FIELDS));
        assertFalse(HostShutdownClient.isExitedLinuxStat(123, "123 (java) Z 1\n"));
        assertFalse(HostShutdownClient.isExitedLinuxStat(123, "123 (java) Z" + FIELDS.stripTrailing()));
        assertFalse(HostShutdownClient.isExitedLinuxStat(123, "123 (java) Z" + FIELDS.replace("-1", "?")));
    }
}

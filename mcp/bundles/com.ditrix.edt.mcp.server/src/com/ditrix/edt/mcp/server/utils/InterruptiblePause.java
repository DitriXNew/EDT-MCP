// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

/** A short pause that honours a caller interrupt instead of swallowing it. */
public final class InterruptiblePause
{
    private InterruptiblePause()
    {
        // Utility class
    }

    /**
     * Sleeps for the given time.
     *
     * @param millis the pause length in milliseconds
     * @return {@code true} when the full pause elapsed; {@code false} when the thread was
     *         interrupted (the interrupt flag is restored, never cleared)
     */
    public static boolean pause(long millis)
    {
        try
        {
            Thread.sleep(millis);
            return true;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

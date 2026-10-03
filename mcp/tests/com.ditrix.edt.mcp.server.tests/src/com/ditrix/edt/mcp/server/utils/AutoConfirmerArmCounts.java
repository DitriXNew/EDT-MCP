/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

/**
 * Reaches the arm counters of {@link LaunchUpdateDialogAutoConfirmer} for tests that live outside
 * this package.
 *
 * <p>The seams are package-private so production code cannot raise a count without a display. The
 * test fragment shares the package with the bundle, so a helper here exposes them to a tool's test
 * without widening them for the bundle.
 */
public final class AutoConfirmerArmCounts
{
    private AutoConfirmerArmCounts()
    {
        // Utility class
    }

    /**
     * Takes one arm of each selected matcher, as a concurrent launch whose arm took effect would.
     *
     * @param updateDialog take an update-matcher arm
     * @param sessionDialog take a session-matcher arm
     * @param restructureDialog take a restructure-matcher arm
     */
    public static void arm(boolean updateDialog, boolean sessionDialog, boolean restructureDialog)
    {
        LaunchUpdateDialogAutoConfirmer.armMatchersForTest(updateDialog, sessionDialog,
            restructureDialog);
    }

    /**
     * @return the outstanding arms of the update, session and restructure matchers, in that order
     */
    public static int[] counts()
    {
        return LaunchUpdateDialogAutoConfirmer.armCountsForTest();
    }
}

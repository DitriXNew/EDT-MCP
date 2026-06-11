/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * Tests {@link GuideLoader}'s payload hygiene: the served guide body must be LF-normalized no matter
 * how the bundled {@code guides/*.md} file was committed (CRLF working trees put {@code \r} into
 * every wire payload and generated doc otherwise), and a missing guide degrades to {@code ""}.
 */
public class GuideLoaderTest
{
    @Test
    public void testLoadedGuideHasNoCarriageReturns()
    {
        GuideLoader.clearCache();
        String guide = GuideLoader.load("create_metadata"); //$NON-NLS-1$
        assertFalse("the create_metadata guide must resolve in the test runtime", guide.isEmpty()); //$NON-NLS-1$
        assertEquals("the served guide body must be LF-normalized (no \\r)", //$NON-NLS-1$
            -1, guide.indexOf('\r'));
    }

    @Test
    public void testMissingGuideDegradesToEmpty()
    {
        assertEquals("", GuideLoader.load("no_such_tool_zz")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", GuideLoader.load(null)); //$NON-NLS-1$
    }
}

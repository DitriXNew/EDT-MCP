/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Behavior of the runtime {@link ToolsetState}: core is always visible and not
 * toggleable; a non-core toolset is hidden until enabled and hidden again after
 * disable; unknown ids are ignored.
 */
public class ToolsetStateTest
{
    @Before
    @After
    public void reset()
    {
        ToolsetState.getInstance().reset();
    }

    @Test
    public void coreIsAlwaysVisibleAndCannotBeToggled()
    {
        ToolsetState state = ToolsetState.getInstance();
        assertTrue(state.isVisible(Toolsets.CORE));
        assertFalse("enabling core is a no-op", state.enable(Toolsets.CORE)); //$NON-NLS-1$
        assertFalse("disabling core is a no-op", state.disable(Toolsets.CORE)); //$NON-NLS-1$
        assertTrue(state.isVisible(Toolsets.CORE));
    }

    @Test
    public void nonCoreToolsetHiddenUntilEnabled()
    {
        ToolsetState state = ToolsetState.getInstance();
        assertFalse(state.isVisible(Toolsets.DEBUG));
        assertTrue("first enable changes the set", state.enable(Toolsets.DEBUG)); //$NON-NLS-1$
        assertTrue(state.isVisible(Toolsets.DEBUG));
        assertFalse("re-enable is idempotent (no change)", state.enable(Toolsets.DEBUG)); //$NON-NLS-1$
    }

    @Test
    public void disableHidesAgain()
    {
        ToolsetState state = ToolsetState.getInstance();
        state.enable(Toolsets.CODE);
        assertTrue(state.isVisible(Toolsets.CODE));
        assertTrue("disable changes the set", state.disable(Toolsets.CODE)); //$NON-NLS-1$
        assertFalse(state.isVisible(Toolsets.CODE));
        assertFalse("re-disable is idempotent", state.disable(Toolsets.CODE)); //$NON-NLS-1$
    }

    @Test
    public void unknownIdIsIgnored()
    {
        ToolsetState state = ToolsetState.getInstance();
        assertFalse(state.enable("no_such_toolset_zzz")); //$NON-NLS-1$
        assertFalse(state.isVisible("no_such_toolset_zzz")); //$NON-NLS-1$
    }

    @Test
    public void resetClearsRevealedToolsets()
    {
        ToolsetState state = ToolsetState.getInstance();
        state.enable(Toolsets.METADATA);
        state.enable(Toolsets.TAGS);
        assertFalse(state.getEnabledToolsetIds().isEmpty());
        state.reset();
        assertTrue(state.getEnabledToolsetIds().isEmpty());
        assertFalse(state.isVisible(Toolsets.METADATA));
    }
}

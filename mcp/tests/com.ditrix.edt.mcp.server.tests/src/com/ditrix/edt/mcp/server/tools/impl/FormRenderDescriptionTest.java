/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Render requirements in tool descriptions must agree with the guides. */
public class FormRenderDescriptionTest
{
    @Test
    public void testLayoutSnapshotDoesNotRequireBufferedFlag()
    {
        String description = new GetFormLayoutSnapshotTool().getDescription();

        assertFalse(description, description.contains("nativeFormBufferedLayoutRender")); //$NON-NLS-1$
        assertFalse(description, description.contains("Requires EDT launched")); //$NON-NLS-1$
    }

    @Test
    public void testScreenshotFlagRequirementIsWindowsOnly()
    {
        String description = new GetFormScreenshotTool().getDescription();

        assertTrue(description, description.contains("-DnativeFormBufferedLayoutRender=true on Windows")); //$NON-NLS-1$
        assertTrue(description, description.contains("offscreen buffer is unavailable")); //$NON-NLS-1$
    }
}

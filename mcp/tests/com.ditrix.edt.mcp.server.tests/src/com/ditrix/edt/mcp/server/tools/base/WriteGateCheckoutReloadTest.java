/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * #922 (Codex P1 on #928): the writers on the STRICT gate ({@code delete_metadata}, {@code apply_quick_fix}) are
 * refused after this server's branch switch exactly like the writers on the model gate.
 */
public class WriteGateCheckoutReloadTest
{
    private static final String RELOAD = "reload"; //$NON-NLS-1$

    @Test
    public void aStrictWriterAsksTheCheckoutReloadFirst()
    {
        assertEquals(RELOAD, AbstractMetadataWriteTool.writeGateError(true, () -> RELOAD, () -> null, () -> "model")); //$NON-NLS-1$
    }

    @Test
    public void aStrictWriterWithoutAReloadUsesTheStrictGate()
    {
        assertEquals("strict", AbstractMetadataWriteTool.writeGateError(true, () -> null, () -> "strict", //$NON-NLS-1$ //$NON-NLS-2$
            () -> "model")); //$NON-NLS-1$
        assertNull(AbstractMetadataWriteTool.writeGateError(true, () -> null, () -> null, () -> "model")); //$NON-NLS-1$
    }

    /** The model gate carries the reload check itself; it is not asked twice. */
    @Test
    public void aModelGateWriterUsesTheModelGateOnly()
    {
        assertEquals("model", AbstractMetadataWriteTool.writeGateError(false, () -> { //$NON-NLS-1$
            throw new AssertionError("asked twice"); //$NON-NLS-1$
        }, () -> "strict", () -> "model")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.function.Consumer;

import org.eclipse.core.runtime.IStatus;

/**
 * Lets a test outside this package observe what {@link Refusals#log} emits.
 */
public final class RefusalsTestAccess
{
    private RefusalsTestAccess()
    {
    }

    /**
     * @param sink receives every status {@link Refusals#log} emits; {@code null} restores the platform log
     */
    public static void setSink(Consumer<IStatus> sink)
    {
        Refusals.setSink(sink);
    }
}

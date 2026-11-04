/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

/** Chooses the client configuration from the pending checkbox and live HTTP state. */
public enum ConnectionMode
{
    HTTP, STDIO;

    public static ConnectionMode resolve(boolean httpEnabled, boolean stdioOnly, boolean httpRunning)
    {
        return !httpEnabled || (stdioOnly && !httpRunning) ? STDIO : HTTP;
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server;

/** Host capability is selected by the Eclipse application, never by display availability. */
public enum HostKind
{
    WINDOWED, HEADLESS;

    public static final String APPLICATION_ID = "com.ditrix.edt.mcp.server.stdioHost";

    public static HostKind current()
    {
        return APPLICATION_ID.equals(System.getProperty("eclipse.application")) ? HEADLESS : WINDOWED;
    }
}

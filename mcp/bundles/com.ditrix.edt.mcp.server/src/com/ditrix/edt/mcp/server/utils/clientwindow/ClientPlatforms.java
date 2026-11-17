// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.Locale;

/** OS selection is explicit; unsupported hosts never silently launch normally. */
public final class ClientPlatforms
{
    public enum Os { WINDOWS, LINUX, MAC }
    public static final class UnsupportedHostException extends IllegalArgumentException
    {
        private static final long serialVersionUID = 1L;
        UnsupportedHostException(String name)
        {
            super("Window modes are unsupported on '" + name + "'; use windowMode 'normal' on this OS.");
        }
    }
    private ClientPlatforms() { }
    public static Os os(String name)
    {
        String value = name.toLowerCase(Locale.ROOT);
        if (value.startsWith("windows")) { return Os.WINDOWS; }
        if (value.equals("linux")) { return Os.LINUX; }
        if (value.startsWith("mac") || value.equals("darwin")) { return Os.MAC; }
        throw new UnsupportedHostException(name);
    }

    public static WindowController controller(Os os, WindowCommands commands)
    {
        return switch (os)
        {
            case WINDOWS -> new WindowsWindowController(new WindowsNative());
            case LINUX -> new LinuxWindowController(commands);
            case MAC -> new MacWindowController(commands);
        };
    }

    public static IsolatedClientLauncher isolated(Os os, WindowCommands commands)
    {
        return switch (os)
        {
            case WINDOWS -> WindowsIsolatedClient::start;
            case LINUX -> new LinuxIsolatedClient(commands);
            case MAC -> new MacIsolatedClient(commands);
        };
    }
}

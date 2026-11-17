// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.List;

@FunctionalInterface
public interface IsolatedClientLauncher
{
    OwnedClient start(List<String> command) throws Exception;

    interface OwnedClient extends AutoCloseable
    {
        long pid();
        boolean alive();
        int exitCode();
        List<WindowController.Window> windows() throws Exception;
        default String limitation() { return null; }
        @Override void close();
    }
}

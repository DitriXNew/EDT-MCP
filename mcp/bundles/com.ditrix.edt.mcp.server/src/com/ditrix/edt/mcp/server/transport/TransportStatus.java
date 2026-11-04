/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import java.util.function.BooleanSupplier;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;

/** Shared listener snapshot for diagnostics and workbench UI. */
public record TransportStatus(boolean http, boolean ipc)
{
    public static TransportStatus read()
    {
        Activator plugin = Activator.getDefault();
        return read(plugin == null ? null : plugin.getMcpServer(), StdioPluginSupport::isIpcRunning);
    }

    public static TransportStatus read(McpServer server, BooleanSupplier ipcRunning)
    {
        return new TransportStatus(server != null && server.isRunning(), ipcRunning.getAsBoolean());
    }

    public boolean running()
    {
        return http || ipc;
    }
}

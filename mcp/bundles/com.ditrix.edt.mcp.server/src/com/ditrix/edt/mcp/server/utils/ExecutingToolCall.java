// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Read-only probe of the tool call executing on the MCP server. Shared by the tools that must
 * not repeat work once the caller was already answered (a cancelled or timed-out call whose
 * worker thread is still running).
 */
public final class ExecutingToolCall
{
    private ExecutingToolCall()
    {
        // Utility class
    }

    /**
     * Whether the response of the currently executing tool call was already sent.
     *
     * @return {@code true} when a call is executing and its response was delivered; {@code false}
     *         when it was not, or when no activator, server or call is available (null-safe)
     */
    public static boolean isResponseDelivered()
    {
        var activator = Activator.getDefault();
        var server = activator == null ? null : activator.getMcpServer();
        var call = server == null ? null : server.getExecutingToolCall();
        return call != null && call.isResponseDelivered();
    }
}

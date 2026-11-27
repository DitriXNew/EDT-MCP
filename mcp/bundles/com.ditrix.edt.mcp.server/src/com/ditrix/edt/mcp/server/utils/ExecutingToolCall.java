// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.function.BooleanSupplier;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.ActiveToolCall;

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
        return delivered(current());
    }

    /**
     * Captures the call executing on the calling (MCP executor) thread now. The lookup is
     * thread-local to that executor, so work queued to another thread (the UI thread) must take
     * its probe here, before the hand-off.
     *
     * @return a probe that may be called from any thread: {@code true} once the captured call's
     *         response was delivered; always {@code false} when no call was executing at capture
     *         time (null-safe)
     */
    public static BooleanSupplier capture()
    {
        ActiveToolCall call = current();
        return () -> delivered(call);
    }

    private static ActiveToolCall current()
    {
        var activator = Activator.getDefault();
        var server = activator == null ? null : activator.getMcpServer();
        return server == null ? null : server.getExecutingToolCall();
    }

    private static boolean delivered(ActiveToolCall call)
    {
        return call != null && call.isResponseDelivered();
    }
}

// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonParser;

/** An isolated launch has already started the client when the call returns. */
public class ClientWindowLaunchMessageTest
{
    @Test
    public void runningIsolatedClientIsReportedAsStarted()
    {
        String message = ClientWindowLaunch.isolatedMessage(true, 4242, false);
        assertTrue(message, message.contains("pid 4242") && message.contains("started on an isolated desktop"));
        assertTrue(message, message.contains("debug_status") && message.contains("terminate_launch"));
        assertFalse(message, message.contains("asynchronously"));
        assertFalse(message, message.contains("wait_for_break"));
    }

    @Test
    public void debugIsolatedClientPointsAtWaitForBreak()
    {
        assertTrue(ClientWindowLaunch.isolatedMessage(true, 1, true).contains("wait_for_break"));
    }

    @Test
    public void exitedIsolatedClientIsNotReportedAsStarted()
    {
        String message = ClientWindowLaunch.isolatedMessage(false, 7, false);
        assertTrue(message, message.contains("exited right after it started"));
        assertFalse(message, message.contains("was started on an isolated desktop"));
    }

    @Test
    public void decorationReplacesTheAsynchronousMessage()
    {
        var execution = new ClientWindowLaunch.Execution(null, Map.of("message", "started", "status", "running"));
        var json = JsonParser.parseString(execution.decorate(
            "{\"success\":true,\"status\":\"launching\",\"message\":\"starting asynchronously\"}")).getAsJsonObject();
        assertEquals("started", json.get("message").getAsString());
        assertEquals("running", json.get("status").getAsString());
    }
}

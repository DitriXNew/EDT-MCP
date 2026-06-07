/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * Tests the server-initiated SSE broadcast mechanism (the channel that delivers
 * {@code notifications/tools/list_changed}). Exercised without HTTP: a registered
 * {@link java.io.OutputStream} receives the SSE frame; a stream that fails to write
 * is dropped. The full client round-trip (open a GET SSE stream, receive the push
 * after enable_toolset) is verified in the live loop with progressive disclosure on.
 */
public class SseStreamRegistryTest
{
    @Test
    public void notifyWritesToolsListChangedAsAnSseEventFrame()
    {
        SseStreamRegistry reg = SseStreamRegistry.getInstance();
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        SseStreamRegistry.SseStream stream = reg.register(sink);
        try
        {
            int delivered = reg.notifyToolsListChanged();
            assertTrue("at least the registered stream is notified", delivered >= 1); //$NON-NLS-1$

            String frame = sink.toString(StandardCharsets.UTF_8);
            assertTrue("must be an SSE event frame", frame.contains("event: message")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("must carry an event id", frame.contains("id: ")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("payload on a data line", frame.contains("data: ")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("the JSON-RPC method is the list_changed notification", //$NON-NLS-1$
                frame.contains("notifications/tools/list_changed")); //$NON-NLS-1$
            assertTrue("frame is terminated by a blank line", frame.endsWith("\n\n")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        finally
        {
            reg.unregister(stream);
        }
    }

    @Test
    public void deadStreamIsDroppedOnBroadcastFailure()
    {
        SseStreamRegistry reg = SseStreamRegistry.getInstance();
        OutputStream throwing = new OutputStream()
        {
            @Override
            public void write(int b) throws IOException
            {
                throw new IOException("client gone"); //$NON-NLS-1$
            }
        };
        SseStreamRegistry.SseStream stream = reg.register(throwing);
        int before = reg.activeStreamCount();
        try
        {
            reg.broadcast("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}"); //$NON-NLS-1$
            // The failing stream is removed; the count drops by exactly that one.
            assertEquals("a stream that fails to accept the write is dropped", //$NON-NLS-1$
                before - 1, reg.activeStreamCount());
        }
        finally
        {
            reg.unregister(stream); // idempotent if already removed
        }
    }

    @Test
    public void broadcastWithNoStreamsOrNullMessageDeliversNothing()
    {
        SseStreamRegistry reg = SseStreamRegistry.getInstance();
        assertEquals(0, reg.broadcast(null));
    }
}

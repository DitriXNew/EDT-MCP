/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry of the open server-initiated SSE streams (the standalone {@code GET}
 * {@code text/event-stream} connections per the MCP Streamable HTTP transport),
 * plus a {@link #broadcast} that pushes a JSON-RPC message to all of them.
 * <p>
 * This is how the server delivers notifications it originates — currently
 * {@code notifications/tools/list_changed} when {@code enable_toolset} changes the
 * visible toolset under progressive disclosure. A client only receives it if it
 * keeps a GET SSE stream open; clients that don't fall back to re-requesting
 * {@code tools/list} (the pull path that {@code enable_toolset} points them to).
 * <p>
 * Thread-safe: streams are added/removed from a concurrent set, and each stream's
 * writes (heartbeat from its own SSE thread, broadcasts from a request thread) are
 * serialized by the per-stream lock in {@link SseStream}.
 */
public final class SseStreamRegistry // NOSONAR intentional singleton (Eclipse service / getInstance); a single instance is by design
{
    private static final SseStreamRegistry INSTANCE = new SseStreamRegistry();

    private final Set<SseStream> streams = ConcurrentHashMap.newKeySet();
    private final AtomicLong eventId = new AtomicLong(0);

    private SseStreamRegistry()
    {
        // Singleton
    }

    public static SseStreamRegistry getInstance()
    {
        return INSTANCE;
    }

    /**
     * Registers an open SSE stream's output. The transport calls this when a GET SSE
     * stream opens and {@link #unregister} when it closes.
     *
     * @param out the SSE stream's output stream
     * @return the registered handle (used for heartbeat writes and unregister)
     */
    public SseStream register(OutputStream out)
    {
        SseStream stream = new SseStream(out);
        streams.add(stream);
        return stream;
    }

    /**
     * Removes a stream from the registry (on disconnect / close).
     *
     * @param stream the handle returned by {@link #register}
     */
    public void unregister(SseStream stream)
    {
        if (stream != null)
        {
            streams.remove(stream);
        }
    }

    /** Number of currently-open server-initiated SSE streams. */
    public int activeStreamCount()
    {
        return streams.size();
    }

    /**
     * Pushes a JSON-RPC message to every open SSE stream as an {@code event: message}
     * frame. Streams that fail to accept the write (client gone) are dropped. Returns
     * how many streams received it.
     *
     * @param jsonRpcMessage the serialized JSON-RPC notification/request
     * @return the number of streams the message was written to
     */
    public int broadcast(String jsonRpcMessage)
    {
        if (jsonRpcMessage == null || streams.isEmpty())
        {
            return 0;
        }
        long id = eventId.incrementAndGet();
        String frame = "event: message\nid: " + id + "\ndata: " + jsonRpcMessage + "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int delivered = 0;
        for (SseStream stream : streams)
        {
            try
            {
                stream.write(frame);
                delivered++;
            }
            catch (IOException e)
            {
                // Client disconnected mid-write: drop the stream (its own heartbeat
                // loop will also break and unregister, which is idempotent).
                streams.remove(stream);
            }
        }
        return delivered;
    }

    /**
     * Pushes {@code notifications/tools/list_changed} to all open SSE streams, telling
     * clients the {@code tools/list} surface changed (e.g. a toolset was revealed).
     *
     * @return the number of streams notified
     */
    public int notifyToolsListChanged()
    {
        return broadcast("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}"); //$NON-NLS-1$
    }

    /**
     * One open SSE stream. Writes (heartbeat + broadcast) are serialized by the
     * per-instance lock so frames never interleave.
     */
    public static final class SseStream
    {
        private final OutputStream out;
        private final Object lock = new Object();

        SseStream(OutputStream out)
        {
            this.out = out;
        }

        /**
         * Writes a raw SSE chunk (an {@code event:} frame or a {@code :} heartbeat
         * comment) and flushes. Serialized against concurrent writers.
         *
         * @param chunk the SSE text to write
         * @throws IOException if the client has disconnected
         */
        public void write(String chunk) throws IOException
        {
            byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
            synchronized (lock)
            {
                out.write(bytes);
                out.flush();
            }
        }
    }
}

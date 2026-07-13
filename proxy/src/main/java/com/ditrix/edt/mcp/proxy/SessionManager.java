/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the proxy's OWN client-facing MCP sessions.
 *
 * <p>The proxy terminates the MCP session layer itself: an {@code initialize} request is
 * answered by the proxy with a fresh proxy-issued {@code Mcp-Session-Id}, independent of any
 * backend session (each {@code Backend} maintains its own handshake with its EDT instance).
 * This mirrors the plugin's transport, where the session id is minted by
 * {@code McpHttpHandler} on initialize.</p>
 *
 * <p>Thread-safe: backed by a concurrent set, so transport threads can create, validate and
 * close sessions concurrently.</p>
 */
public final class SessionManager
{
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new client session.
     *
     * @return the freshly issued random UUID session id, never {@code null}
     */
    public String create()
    {
        String sessionId = UUID.randomUUID().toString();
        sessions.add(sessionId);
        return sessionId;
    }

    /**
     * Checks whether a session id identifies an open session.
     *
     * @param sessionId the session id from the {@code Mcp-Session-Id} header (may be {@code null})
     * @return {@code true} when the id belongs to an open session
     */
    public boolean isValid(String sessionId)
    {
        return sessionId != null && sessions.contains(sessionId);
    }

    /**
     * Closes a session. Unknown or {@code null} ids are ignored (closing is idempotent).
     *
     * @param sessionId the session id to close (may be {@code null})
     */
    public void close(String sessionId)
    {
        if (sessionId != null)
        {
            sessions.remove(sessionId);
        }
    }

    /**
     * The number of currently open client sessions.
     *
     * @return the open session count
     */
    public int activeCount()
    {
        return sessions.size();
    }
}

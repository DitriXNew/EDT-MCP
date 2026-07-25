/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.util.Map;
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
 * <p>Each session also remembers whether ITS client accepts {@code structuredContent}. The proxy
 * answers {@code initialize} itself, so the backends never see that capability and cannot apply
 * their own gate - and because several clients can be connected to one proxy at once, the answer
 * must be per session rather than per server.</p>
 *
 * <p>Thread-safe: backed by a concurrent map, so transport threads can create, validate and
 * close sessions concurrently.</p>
 *
 * <p><b>Session cap (issue #253 hardening).</b> {@link #create()} refuses to grow the set past
 * {@value #MAX_SESSIONS} open sessions, returning {@code null} instead - an unbounded set of
 * abandoned sessions (a client that never sends {@code DELETE /mcp}) would otherwise grow
 * forever. This is deliberately a hard cap only, with no idle-eviction sweep: a well-behaved
 * client closes its session, and {@code MAX_SESSIONS} is generous enough that hitting it in
 * practice means sessions are leaking and worth investigating.</p>
 */
public final class SessionManager
{
    /** Hard cap on concurrently open sessions; {@link #create()} returns {@code null} past it. */
    static final int MAX_SESSIONS = 10_000;

    /** Session id -> whether that client accepts {@code structuredContent}. */
    private final Map<String, Boolean> sessions = new ConcurrentHashMap<>();

    /**
     * Creates a new client session, unless the proxy already holds {@value #MAX_SESSIONS} open
     * sessions.
     *
     * @return the freshly issued random UUID session id, or {@code null} when the session cap
     *         ({@value #MAX_SESSIONS}) has been reached
     */
    public String create()
    {
        return create(true);
    }

    /**
     * Creates a new client session that remembers the client's {@code structuredContent}
     * capability, unless the proxy already holds {@value #MAX_SESSIONS} open sessions.
     *
     * @param allowsStructuredContent whether this client accepts {@code structuredContent}
     * @return the freshly issued random UUID session id, or {@code null} when the session cap
     *         ({@value #MAX_SESSIONS}) has been reached
     */
    public String create(boolean allowsStructuredContent)
    {
        if (sessions.size() >= MAX_SESSIONS)
        {
            return null;
        }
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, Boolean.valueOf(allowsStructuredContent));
        return sessionId;
    }

    /**
     * Whether the client behind a session accepts {@code structuredContent}. An unknown or
     * {@code null} session answers {@code true}: only an EXPLICIT opt-out at {@code initialize}
     * suppresses the field, so anything else keeps the long-standing permissive behaviour.
     *
     * @param sessionId the session id from the {@code Mcp-Session-Id} header (may be {@code null})
     * @return {@code false} only when that session's client explicitly opted out
     */
    public boolean allowsStructuredContent(String sessionId)
    {
        return !Boolean.FALSE.equals(capabilityOf(sessionId));
    }

    /**
     * Looks a session up ONCE, answering both "is it open?" and "does its client accept
     * {@code structuredContent}?" - so a concurrent {@code DELETE} cannot land between the two
     * questions and turn an opted-out session into a permissive unknown one.
     *
     * @param sessionId the session id from the {@code Mcp-Session-Id} header (may be {@code null})
     * @return {@code TRUE}/{@code FALSE} for an open session, or {@code null} when the id is unknown
     */
    public Boolean capabilityOf(String sessionId)
    {
        if (sessionId == null)
        {
            return null;
        }
        return sessions.get(sessionId);
    }

    /**
     * Checks whether a session id identifies an open session.
     *
     * @param sessionId the session id from the {@code Mcp-Session-Id} header (may be {@code null})
     * @return {@code true} when the id belongs to an open session
     */
    public boolean isValid(String sessionId)
    {
        return sessionId != null && sessions.containsKey(sessionId);
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

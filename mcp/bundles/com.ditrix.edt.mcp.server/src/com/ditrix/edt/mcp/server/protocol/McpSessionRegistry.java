/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The MCP sessions this server has issued.
 *
 * <p>{@code initialize} mints a session and its id travels back in {@code Mcp-Session-Id}; every
 * later request on that connection must carry the id back, and the transport
 * ({@code McpHttpHandler}) refuses one that does not. Before this registry existed the header was
 * generated fresh per {@code initialize} response and then never read again: an id the server had
 * never issued, an id from a terminated session, and no id at all were all accepted, and
 * {@code DELETE /mcp} answered 200 while terminating nothing.</p>
 *
 * <p>Each session also remembers the {@link ClientCapabilities} ITS client declared, because the
 * capabilities are per client and the server can hold more than one at a time (an IDE plus an
 * agent, or two editor windows). Keeping them in one server-wide slot meant the last
 * {@code initialize} decided how everyone else's {@code tools/call} was formatted - structured
 * content sent to a client that opted out of it, and the reverse.</p>
 *
 * <p>Deliberately the same shape as the proxy's {@code SessionManager}, down to the session cap
 * and the "unknown session is permissive" rule for capabilities: the two components implement the
 * same MCP session layer, and where they disagreed the client saw the difference.</p>
 *
 * <p>Thread-safe: backed by a concurrent map, so transport threads can create, validate and close
 * sessions concurrently.</p>
 */
public final class McpSessionRegistry
{
    /**
     * Hard cap on concurrently open sessions; {@link #create(ClientCapabilities)} returns
     * {@code null} past it. Deliberately a cap only, with no idle-eviction sweep - a well-behaved
     * client sends {@code DELETE /mcp}, and reaching this number means sessions are leaking and
     * is worth investigating rather than papering over. Sessions do not survive a server
     * stop/start: the registry belongs to the running listener, and a client whose session
     * vanished is told to initialize again.
     */
    public static final int MAX_SESSIONS = 10_000;

    /** Session id -> the capabilities that session's client declared at initialize. */
    private final Map<String, ClientCapabilities> sessions = new ConcurrentHashMap<>();

    /**
     * Opens a session for a client that has just initialized.
     *
     * @param capabilities the capabilities the client declared; {@code null} is stored as
     *            {@link ClientCapabilities#ABSENT} (the permissive default)
     * @return the freshly issued random UUID session id, or {@code null} when {@link #MAX_SESSIONS}
     *         sessions are already open
     */
    public String create(ClientCapabilities capabilities)
    {
        if (sessions.size() >= MAX_SESSIONS)
        {
            return null;
        }
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, capabilities == null ? ClientCapabilities.ABSENT : capabilities);
        return sessionId;
    }

    /**
     * Looks a session up ONCE, answering both "is it open?" and "what did its client declare?" -
     * so a concurrent {@code DELETE} cannot land between the two questions and turn a session that
     * opted out of a feature into a permissive unknown one.
     *
     * @param sessionId the id from the {@code Mcp-Session-Id} header (may be {@code null})
     * @return that session's capabilities, or {@code null} when the id is absent or unknown
     */
    public ClientCapabilities capabilitiesOf(String sessionId)
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
     * @param sessionId the id from the {@code Mcp-Session-Id} header (may be {@code null})
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
     * @return {@code true} when a session was actually open and is now closed
     */
    public boolean close(String sessionId)
    {
        return sessionId != null && sessions.remove(sessionId) != null;
    }

    /**
     * The number of currently open sessions.
     *
     * @return the open session count
     */
    public int activeCount()
    {
        return sessions.size();
    }
}

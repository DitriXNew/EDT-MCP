/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link SessionManager}: session lifecycle ({@code create}/{@code isValid}/
 * {@code close}), rejection of unknown or {@code null} ids, idempotent close, and the
 * active-session count.
 */
public class SessionManagerTest
{
    @Test
    public void testCreateIssuesAValidSession()
    {
        SessionManager sessions = new SessionManager();

        String id = sessions.create();

        assertNotNull(id);
        assertTrue(sessions.isValid(id));
        assertEquals(1, sessions.activeCount());
    }

    @Test
    public void testCreateIssuesDistinctIds()
    {
        SessionManager sessions = new SessionManager();

        String first = sessions.create();
        String second = sessions.create();

        assertNotEquals(first, second);
        assertEquals(2, sessions.activeCount());
    }

    @Test
    public void testCloseInvalidatesTheSession()
    {
        SessionManager sessions = new SessionManager();
        String id = sessions.create();

        sessions.close(id);

        assertFalse(sessions.isValid(id));
        assertEquals(0, sessions.activeCount());
    }

    @Test
    public void testCloseIsIdempotent()
    {
        SessionManager sessions = new SessionManager();
        String id = sessions.create();

        sessions.close(id);
        sessions.close(id); // must not throw and must not go negative

        assertFalse(sessions.isValid(id));
        assertEquals(0, sessions.activeCount());
    }

    @Test
    public void testCloseNullIsIgnored()
    {
        SessionManager sessions = new SessionManager();
        sessions.create();

        sessions.close(null); // must not throw

        assertEquals(1, sessions.activeCount());
    }

    @Test
    public void testCloseUnknownIdIsIgnored()
    {
        SessionManager sessions = new SessionManager();
        sessions.create();

        sessions.close("never-issued"); //$NON-NLS-1$

        assertEquals(1, sessions.activeCount());
    }

    @Test
    public void testIsValidRejectsNullAndUnknownIds()
    {
        SessionManager sessions = new SessionManager();
        sessions.create();

        assertFalse(sessions.isValid(null));
        assertFalse(sessions.isValid("not-a-real-session")); //$NON-NLS-1$
    }

    @Test
    public void testActiveCountReflectsMultipleSessionsIndependently()
    {
        SessionManager sessions = new SessionManager();
        String a = sessions.create();
        String b = sessions.create();
        String c = sessions.create();

        sessions.close(b);

        assertEquals(2, sessions.activeCount());
        assertTrue(sessions.isValid(a));
        assertFalse(sessions.isValid(b));
        assertTrue(sessions.isValid(c));
    }

    @Test
    public void testActiveCountStartsAtZero()
    {
        SessionManager sessions = new SessionManager();

        assertEquals(0, sessions.activeCount());
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.ReadResult;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;

/** Tests parsing and the structural readable-empty versus unreachable invariant. */
public class InfobaseSessionSupportTest
{
    @Test
    public void parsesIbcmdBlocksAndMarksDesignerAgent()
    {
        String output = "session: 11111111-1111-1111-1111-111111111111\n" //$NON-NLS-1$
            + "session-id: 7\n" //$NON-NLS-1$
            + "app-id: Designer\n" //$NON-NLS-1$
            + "user-name: Agent\n" //$NON-NLS-1$
            + "host: edt-host\n" //$NON-NLS-1$
            + "started-at: 2026-01-01T10:00:00\n" //$NON-NLS-1$
            + "last-active-at: 2026-01-01T10:01:00\n\n" //$NON-NLS-1$
            + "session: 22222222-2222-2222-2222-222222222222\n" //$NON-NLS-1$
            + "session-id: 42\n" //$NON-NLS-1$
            + "app-id: 1CV8C\n" //$NON-NLS-1$
            + "user-name: User\n" //$NON-NLS-1$
            + "host: desk\n"; //$NON-NLS-1$

        List<SessionInfo> sessions = InfobaseSessionSupport.parseSessions(output).sessions();

        assertEquals(2, sessions.size());
        assertEquals(Long.valueOf(7), sessions.get(0).sessionNumber());
        assertEquals("Designer", sessions.get(0).applicationKind()); //$NON-NLS-1$
        assertTrue(sessions.get(0).edtAgent());
        assertEquals(Long.valueOf(42), sessions.get(1).sessionNumber());
        assertFalse(sessions.get(1).edtAgent());
        assertEquals("", sessions.get(1).lastActiveAt()); //$NON-NLS-1$
    }

    @Test
    public void parsesTheColumnPaddedOutputARealServerEmits()
    {
        String output = "session                          : fe63b824-630f-4c3e-b5e9-08a9e5edae74\n" //$NON-NLS-1$
            + "session-id                       : 5\n" //$NON-NLS-1$
            + "infobase                         : 8ef869af-9785-4d3c-9094-e1c434068afe\n" //$NON-NLS-1$
            + "connection                       : \n" //$NON-NLS-1$
            + "process                          : \n" //$NON-NLS-1$
            + "user-name                        : DefUser\n" //$NON-NLS-1$
            + "host                             : \n" //$NON-NLS-1$
            + "app-id                           : Designer\n" //$NON-NLS-1$
            + "locale                           : ru\n" //$NON-NLS-1$
            + "started-at                       : 2026-09-11T19:30:35\n" //$NON-NLS-1$
            + "last-active-at                   : 2026-09-11T21:40:35\n" //$NON-NLS-1$
            + "hibernate                        : no\n" //$NON-NLS-1$
            + "db-proc-took-at                  : 0001-01-01T00:00:00\n"; //$NON-NLS-1$

        List<SessionInfo> sessions = InfobaseSessionSupport.parseSessions(output).sessions();

        assertEquals("padded keys must still yield one session", 1, sessions.size()); //$NON-NLS-1$
        assertEquals("fe63b824-630f-4c3e-b5e9-08a9e5edae74", sessions.get(0).sessionId()); //$NON-NLS-1$
        assertEquals(Long.valueOf(5), sessions.get(0).sessionNumber());
        assertEquals("Designer", sessions.get(0).applicationKind()); //$NON-NLS-1$
        assertTrue("the live agent session must be recognised as the EDT agent", //$NON-NLS-1$
            sessions.get(0).edtAgent());
        assertEquals("a timestamp value must keep the time after its first colon", //$NON-NLS-1$
            "2026-09-11T19:30:35", sessions.get(0).startedAt()); //$NON-NLS-1$
    }

    @Test
    public void readableEmptyIsNotUnreachable()
    {
        ReadResult readable = ReadResult.readable(List.of());
        ReadResult unreachable = ReadResult.unreachable("The standalone server is not running."); //$NON-NLS-1$

        assertTrue(readable.isReadable());
        assertTrue(readable.sessions().isEmpty());
        assertNull(readable.unreachableReason());
        assertFalse(unreachable.isReadable());
        assertNull(unreachable.sessions());
        assertEquals("The standalone server is not running.", unreachable.unreachableReason()); //$NON-NLS-1$
    }

    @Test
    public void partialParseSanitizesDiagnosticAndNamesTheUnreadableBlockCount()
    {
        String output = "session: 11111111-1111-1111-1111-111111111111\n" //$NON-NLS-1$
            + "app-id: 1CV8C\n" //$NON-NLS-1$
            + "user-name: Ivanov\n" //$NON-NLS-1$
            + "host: WKS-01\n" //$NON-NLS-1$
            + "future-owner: Petrov\n\n" //$NON-NLS-1$
            + "session-id: 9\n" //$NON-NLS-1$
            + "app-id: 1CV8C\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);
        String reason = result.unreachableReason();

        assertFalse(result.isReadable());
        assertNull(result.sessions());
        assertTrue(reason.contains("1 unreadable session block")); //$NON-NLS-1$
        assertTrue(reason.contains("session: 11111111-1111-1111-1111-111111111111")); //$NON-NLS-1$
        assertTrue(reason.contains("app-id: 1CV8C")); //$NON-NLS-1$
        assertTrue(reason.contains("user-name: ***")); //$NON-NLS-1$
        assertTrue(reason.contains("host: ***")); //$NON-NLS-1$
        assertTrue(reason.contains("future-owner: ***")); //$NON-NLS-1$
        assertFalse(reason.contains("Ivanov")); //$NON-NLS-1$
        assertFalse(reason.contains("WKS-01")); //$NON-NLS-1$
        assertFalse(reason.contains("Petrov")); //$NON-NLS-1$
    }

    @Test
    public void twoUsableBlocksAreReadableWithBothSessions()
    {
        String output = "session: 11111111-1111-1111-1111-111111111111\n\n" //$NON-NLS-1$
            + "session: 22222222-2222-2222-2222-222222222222\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertTrue(result.isReadable());
        assertEquals(2, result.sessions().size());
    }

    @Test
    public void trailingBlankLineDoesNotCreateAnUnreadableBlock()
    {
        ReadResult result = InfobaseSessionSupport.readSessionsOutput(
            "session: 11111111-1111-1111-1111-111111111111\n"); //$NON-NLS-1$

        assertTrue(result.isReadable());
        assertEquals(1, result.sessions().size());
    }

    @Test
    public void whollyUnrecognizedOutputRemainsUnreachable()
    {
        ReadResult result = InfobaseSessionSupport.readSessionsOutput(
            "ibcmd emitted an unknown response"); //$NON-NLS-1$

        assertFalse(result.isReadable());
        assertNull(result.sessions());
        assertTrue(result.unreachableReason().contains("unrecognized output")); //$NON-NLS-1$
        assertTrue(result.unreachableReason().contains("ibcmd emitted an unknown response")); //$NON-NLS-1$
    }

    @Test(expected = IllegalArgumentException.class)
    public void unreadableResultCannotCarryAnEmptyList()
    {
        new ReadResult(InfobaseSessionSupport.Reachability.UNREACHABLE, List.of(), "offline"); //$NON-NLS-1$
    }
}

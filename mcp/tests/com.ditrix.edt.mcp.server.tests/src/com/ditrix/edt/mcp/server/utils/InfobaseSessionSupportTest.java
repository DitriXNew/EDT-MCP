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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
    public void preservesTheOriginalSessionNumberTokenForErrorValidation()
    {
        String output = "session: 22222222-2222-2222-2222-222222222222\n" //$NON-NLS-1$
            + "session-id: +42\n"; //$NON-NLS-1$

        SessionInfo session = InfobaseSessionSupport.parseSessions(output).sessions().get(0);

        assertEquals(Long.valueOf(42), session.sessionNumber());
        assertEquals("+42", session.sessionNumberText()); //$NON-NLS-1$
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
    public void partialParseWithholdsRawOutputAndNamesTheUnreadableBlockCount()
    {
        String output = "user-name: Ivanov\n" //$NON-NLS-1$
            + "host: WKS-01\n" //$NON-NLS-1$
            + "client-ip: 10.0.0.5\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);
        String reason = result.unreachableReason();

        assertFalse(result.isReadable());
        assertNull(result.sessions());
        assertEquals("ibcmd session list returned 1 unreadable session block. " //$NON-NLS-1$
            + "The raw output was written to the EDT log.", reason); //$NON-NLS-1$
        assertFalse(reason.contains("Ivanov")); //$NON-NLS-1$
        assertFalse(reason.contains("WKS-01")); //$NON-NLS-1$
        assertFalse(reason.contains("10.0.0.5")); //$NON-NLS-1$
        assertFalse(reason.contains("user-name")); //$NON-NLS-1$
        assertFalse(reason.contains("host")); //$NON-NLS-1$
        assertFalse(reason.contains("client-ip")); //$NON-NLS-1$
    }

    @Test
    public void nonzeroExitWithholdsRawCommandOutput() throws Exception
    {
        Class<?> commandType = Class.forName(
            InfobaseSessionSupport.class.getName() + "$CommandExecution"); //$NON-NLS-1$
        Constructor<?> constructor = commandType.getDeclaredConstructor(int.class, String.class,
            String.class, boolean.class);
        constructor.setAccessible(true); // NOSONAR production command state remains private
        Object command = constructor.newInstance(23, "stdout secret", //$NON-NLS-1$
            "user-name: Ivanov\nhost: WKS-01\nclient-ip: 10.0.0.5", false); //$NON-NLS-1$
        Method commandFailure = InfobaseSessionSupport.class.getDeclaredMethod("commandFailure", //$NON-NLS-1$
            String.class, commandType);
        commandFailure.setAccessible(true); // NOSONAR production failure handling remains private

        String reason = (String)commandFailure.invoke(null, "list", command); //$NON-NLS-1$

        assertEquals("ibcmd session list failed with exit code 23. " //$NON-NLS-1$
            + "The raw output was written to the EDT log.", reason); //$NON-NLS-1$
        assertFalse(reason.contains("stdout secret")); //$NON-NLS-1$
        assertFalse(reason.contains("Ivanov")); //$NON-NLS-1$
        assertFalse(reason.contains("WKS-01")); //$NON-NLS-1$
        assertFalse(reason.contains("10.0.0.5")); //$NON-NLS-1$
        assertFalse(reason.contains("user-name")); //$NON-NLS-1$
        assertFalse(reason.contains("host")); //$NON-NLS-1$
        assertFalse(reason.contains("client-ip")); //$NON-NLS-1$
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
    public void validSessionPlusColonlessRecordIsUnreachable()
    {
        String output = "session: 11111111-1111-1111-1111-111111111111\n" //$NON-NLS-1$
            + "app-id: Designer\n\n" //$NON-NLS-1$
            + "foreign client record\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertFalse(result.isReadable());
        assertNull(result.sessions());
        assertEquals("ibcmd session list returned 1 unreadable session block. " //$NON-NLS-1$
            + "The raw output was written to the EDT log.", result.unreachableReason()); //$NON-NLS-1$
        assertFalse(result.unreachableReason().contains("foreign client record")); //$NON-NLS-1$
    }

    /** Output that parses to nothing at all must still be UNREACHABLE and leak none of itself. */
    @Test
    public void whollyUnrecognizedOutputIsUnreachableAndWithholdsItself()
    {
        String output = "ibcmd: could not reach the server for user Ivanov at WKS-01\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertFalse(result.isReadable());
        assertNull(result.sessions());
        assertFalse(result.unreachableReason().contains("Ivanov")); //$NON-NLS-1$
        assertFalse(result.unreachableReason().contains("WKS-01")); //$NON-NLS-1$
        assertFalse(result.unreachableReason().contains("could not reach the server")); //$NON-NLS-1$
    }

    @Test
    public void wellFormedOutputWithTrailingBlankLinesRemainsReadable()
    {
        String output = "session: 11111111-1111-1111-1111-111111111111\n\n" //$NON-NLS-1$
            + "session: 22222222-2222-2222-2222-222222222222\n\n\n"; //$NON-NLS-1$

        ReadResult result = InfobaseSessionSupport.readSessionsOutput(output);

        assertTrue(result.isReadable());
        assertEquals(2, result.sessions().size());
        assertNull(result.unreachableReason());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unreadableResultCannotCarryAnEmptyList()
    {
        new ReadResult(InfobaseSessionSupport.Reachability.UNREACHABLE, List.of(), "offline"); //$NON-NLS-1$
    }
}

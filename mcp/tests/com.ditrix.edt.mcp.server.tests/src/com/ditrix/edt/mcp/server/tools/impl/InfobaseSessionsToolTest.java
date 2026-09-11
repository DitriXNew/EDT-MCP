/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Pins the public session-tool contract and its pure target-selection safety rules. */
public class InfobaseSessionsToolTest
{
    private static final SessionInfo DESIGNER = new SessionInfo(
        "11111111-1111-1111-1111-111111111111", 1L, "Designer", "agent", "host", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "2026-01-01T10:00:00", "2026-01-01T10:01:00", true); //$NON-NLS-1$ //$NON-NLS-2$

    private static final SessionInfo CLIENT = new SessionInfo(
        "22222222-2222-2222-2222-222222222222", 42L, "1CV8C", "User", "desk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "2026-01-01T10:02:00", "2026-01-01T10:03:00", false); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void metadataDeclaresJsonInfobaseDataTool()
    {
        InfobaseSessionsTool tool = new InfobaseSessionsTool();
        assertEquals("infobase_sessions", tool.getName()); //$NON-NLS-1$
        assertEquals(InfobaseSessionsTool.NAME, tool.getName());
        assertEquals(ResponseType.JSON, tool.getResponseType());
        assertTrue(tool.returnsInfobaseData());
        assertTrue(tool.getDescription().contains("get_tool_guide('infobase_sessions')")); //$NON-NLS-1$
        assertTrue(tool.getDescription().contains("Designer")); //$NON-NLS-1$
    }

    @Test
    public void inputSchemaDeclaresOnlyProjectAsRequired()
    {
        JsonObject schema = JsonParser.parseString(new InfobaseSessionsTool().getInputSchema())
            .getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        for (String property : List.of("projectName", "applicationId", "action", "sessionId", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "all", "confirm", "message")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue(property, properties.has(property));
        }
        JsonArray required = schema.getAsJsonArray("required"); //$NON-NLS-1$
        assertEquals(1, required.size());
        assertEquals("projectName", required.get(0).getAsString()); //$NON-NLS-1$
        assertEquals("list", properties.getAsJsonObject("action").getAsJsonArray("enum") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get(0).getAsString());
        assertEquals("terminate", properties.getAsJsonObject("action").getAsJsonArray("enum") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .get(1).getAsString());
    }

    @Test
    public void outputSchemaNamesReachabilityAndSessionPayloads()
    {
        String schema = new InfobaseSessionsTool().getOutputSchema();
        assertNotNull(schema);
        for (String key : List.of("reachable", "unreachableReason", "sessions", "count", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "terminatedCount", "verification", "verificationReason", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "verified", "mismatched", "not_verifiable")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue(key, schema.contains("\"" + key + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void guideExplainsUnreachableAndExactClearingCall()
    {
        String guide = new InfobaseSessionsTool().getGuide();
        assertTrue("the guide must name the unreachable outcome", //$NON-NLS-1$
            guide.contains("`reachable=false` with `unreachableReason`")); //$NON-NLS-1$
        assertTrue("the guide must forbid reading unreachable as an empty list", //$NON-NLS-1$
            guide.contains("Never interpret `reachable=false` as an empty session list")); //$NON-NLS-1$
        assertTrue("the guide must state that an empty readable list IS proof", //$NON-NLS-1$
            guide.contains("proves there are no sessions")); //$NON-NLS-1$
        assertTrue("the guide must spell out the exact clearing call", //$NON-NLS-1$
            guide.contains("all=true, confirm=true")); //$NON-NLS-1$
        assertTrue("the guide must document the protected agent session", //$NON-NLS-1$
            guide.contains("app-id: Designer")); //$NON-NLS-1$
        assertTrue("the guide must say a terminate is verified by a re-read, not assumed", //$NON-NLS-1$
            guide.contains("Termination is verified, not assumed")); //$NON-NLS-1$
        assertTrue("the guide must warn that ibcmd exits 0 for a session that is already gone", //$NON-NLS-1$
            guide.contains("exits 0 even for a session UUID that no longer exists")); //$NON-NLS-1$
    }

    @Test
    public void terminationReadBackMatchesTheSessionUuidCaseInsensitively()
    {
        SessionInfo upperCase = new SessionInfo(
            "024CDB6E-D089-45D5-B9EC-9ACB561CD9F1", 10L, "1CV8C", "User", "desk", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "2026-01-01T10:02:00", "2026-01-01T10:03:00", false); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a UUID differing only in case is the SAME live session", //$NON-NLS-1$
            InfobaseSessionsTool.containsSessionId(List.of(upperCase),
                "024cdb6e-d089-45d5-b9ec-9acb561cd9f1")); //$NON-NLS-1$
        assertFalse("an absent UUID must read as gone, which is what lets a terminate succeed", //$NON-NLS-1$
            InfobaseSessionsTool.containsSessionId(List.of(upperCase),
                "00000000-0000-0000-0000-000000000000")); //$NON-NLS-1$
        assertFalse("an empty list reports every targeted session as gone", //$NON-NLS-1$
            InfobaseSessionsTool.containsSessionId(List.of(),
                "024cdb6e-d089-45d5-b9ec-9acb561cd9f1")); //$NON-NLS-1$
    }

    @Test
    public void terminateRequiresConfirmationAndExactlyOneSelector()
    {
        assertTrue(InfobaseSessionsTool.validate("terminate", "42", false, false) //$NON-NLS-1$ //$NON-NLS-2$
            .contains("confirm=true")); //$NON-NLS-1$
        assertTrue(InfobaseSessionsTool.validate("terminate", null, false, true) //$NON-NLS-1$
            .contains("exactly one")); //$NON-NLS-1$
        assertTrue(InfobaseSessionsTool.validate("terminate", "42", true, true) //$NON-NLS-1$ //$NON-NLS-2$
            .contains("exactly one")); //$NON-NLS-1$
        assertTrue(InfobaseSessionsTool.validate("terminate", "not-an-id", false, true) //$NON-NLS-1$ //$NON-NLS-2$
            .contains("full UUID")); //$NON-NLS-1$
        assertNull(InfobaseSessionsTool.validate("terminate", "42", false, true)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(InfobaseSessionsTool.validate("terminate", null, true, true)); //$NON-NLS-1$
    }

    @Test
    public void numericSelectorResolvesClientButRefusesDesigner()
    {
        InfobaseSessionsTool.Selection client = InfobaseSessionsTool.selectSessions(
            List.of(DESIGNER, CLIENT), "42", false); //$NON-NLS-1$
        assertNull(client.error);
        assertEquals(List.of(CLIENT), client.sessions);

        InfobaseSessionsTool.Selection designer = InfobaseSessionsTool.selectSessions(
            List.of(DESIGNER, CLIENT), "1", false); //$NON-NLS-1$
        assertTrue(designer.sessions.isEmpty());
        assertTrue(designer.error.contains("must not be terminated")); //$NON-NLS-1$
    }

    @Test
    public void allAlwaysExcludesDesigner()
    {
        InfobaseSessionsTool.Selection selection = InfobaseSessionsTool.selectSessions(
            List.of(DESIGNER, CLIENT), null, true);
        assertNull(selection.error);
        assertEquals(List.of(CLIENT), selection.sessions);
        assertFalse(selection.sessions.get(0).edtAgent());
    }
}

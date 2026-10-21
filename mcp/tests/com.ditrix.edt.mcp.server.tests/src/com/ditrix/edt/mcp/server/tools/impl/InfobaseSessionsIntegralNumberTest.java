/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Numeric normalization must feed both the selector gate and the safe session selection. */
public class InfobaseSessionsIntegralNumberTest
{
    private static final SessionInfo DESIGNER = new SessionInfo(
        "11111111-1111-1111-1111-111111111111", 1L, "Designer", "agent", "host",
        "2026-01-01T10:00:00", "2026-01-01T10:01:00", true);

    private static final SessionInfo CLIENT = new SessionInfo(
        "22222222-2222-2222-2222-222222222222", 42L, "1CV8C", "User", "desk",
        "2026-01-01T10:02:00", "2026-01-01T10:03:00", false);

    @Test
    public void canonicalSelectorChangesOnlyWholeNumberRenderings() throws Exception
    {
        assertEquals("42", canonical(" 42.0 "));
        assertEquals("42", canonical("4.2E1"));
        assertEquals(CLIENT.sessionId(), canonical(CLIENT.sessionId()));
        assertEquals("42.5", canonical("42.5"));
        assertNull(canonical(null));
    }

    @Test
    public void numericSelectorValidatesAndResolvesClient() throws Exception
    {
        String selector = canonical("42.0");
        assertNull(InfobaseSessionsTool.validate("terminate", selector, false, true, null));
        InfobaseSessionsTool.Selection selected = InfobaseSessionsTool.selectSessions(
            List.of(DESIGNER, CLIENT), selector, false);
        assertNull(selected.error);
        assertEquals(List.of(CLIENT), selected.sessions);
    }

    @Test
    public void designerStillRequiresExactUuid() throws Exception
    {
        InfobaseSessionsTool.Selection selected = InfobaseSessionsTool.selectSessions(
            List.of(DESIGNER, CLIENT), canonical("1.0"), false);
        assertTrue(selected.error, selected.error.contains("exact full UUID"));
    }

    @Test
    public void unmatchedNumericSelectorEchoesCanonicalDigits() throws Exception
    {
        InfobaseSessionsTool.Selection selected = InfobaseSessionsTool.selectSessions(
            List.of(CLIENT), canonical("99.0"), false);
        assertTrue(selected.error, selected.error.contains("Session '99' was not found"));
        assertFalse(selected.error, selected.error.contains("99.0"));
    }

    @Test
    public void fractionalSelectorStillFailsValidation() throws Exception
    {
        assertEquals("sessionId must be a full UUID or the numeric session-id returned by list.",
            InfobaseSessionsTool.validate("terminate", canonical("42.5"), false, true, null));
    }

    @Test
    public void executeNormalizesBeforeValidationWithoutPlatformAccess()
    {
        // The NUL-message guard follows selector validation and stops before any platform access.
        JsonObject result = JsonParser.parseString(new InfobaseSessionsTool().execute(Map.of(
            "projectName", "NoSuchProject_796", "action", "terminate", "confirm", "true",
            "sessionId", "42.0", "message", String.valueOf((char)0)))).getAsJsonObject();
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        assertTrue(result.toString(), result.get("error").getAsString().contains("message must not contain a NUL"));
    }

    private static String canonical(String raw) throws Exception
    {
        // Reflection also lets the entire pre-fix class compile during the red-on-base run.
        Method method = InfobaseSessionsTool.class.getDeclaredMethod("canonicalSessionSelector", String.class);
        method.setAccessible(true);
        return (String)method.invoke(null, raw);
    }
}

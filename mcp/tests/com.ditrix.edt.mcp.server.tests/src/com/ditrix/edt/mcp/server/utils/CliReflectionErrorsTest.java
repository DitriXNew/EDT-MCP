/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.InvocationTargetException;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests {@link CliReflectionErrors#toErrorJson} — the shared error mapper used by the
 * reflection-wrapped CLI/LanguageTool tools. Verifies the three dispatch branches
 * produce the exact actionable message each tool used to build inline, so the shared
 * helper cannot silently drift the wire-visible error text.
 */
public class CliReflectionErrorsTest
{
    private static String error(String json)
    {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertFalse("error result must not report success", obj.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        return obj.get("error").getAsString(); //$NON-NLS-1$
    }

    /** InvocationTargetException → "<actionLabel> failed: <cause message>" (the API itself threw). */
    @Test
    public void invocationTargetUnwrapsCauseUnderActionLabel()
    {
        Exception e = new InvocationTargetException(new IllegalStateException("boom")); //$NON-NLS-1$
        assertEquals("Export failed: boom", //$NON-NLS-1$
            error(CliReflectionErrors.toErrorJson(e, "Export", "CLI"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** InvocationTargetException with no cause falls back to the exception itself (no NPE). */
    @Test
    public void invocationTargetWithoutCauseDoesNotCrash()
    {
        String msg = error(CliReflectionErrors.toErrorJson(
            new InvocationTargetException(null), "Translate configuration", "LanguageTool")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Translate configuration failed: null", msg); //$NON-NLS-1$
    }

    /** NoSuchMethodException → "<apiLabel> API mismatch: <message>" (CLI shape differs). */
    @Test
    public void noSuchMethodIsAnApiMismatchUnderApiLabel()
    {
        assertEquals("CLI API mismatch: exportToFiles", //$NON-NLS-1$
            error(CliReflectionErrors.toErrorJson(
                new NoSuchMethodException("exportToFiles"), "Export", "CLI"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** IllegalAccessException also routes to the API-mismatch branch. */
    @Test
    public void illegalAccessIsAnApiMismatchUnderApiLabel()
    {
        assertEquals("LanguageTool API mismatch: denied", //$NON-NLS-1$
            error(CliReflectionErrors.toErrorJson(
                new IllegalAccessException("denied"), "Generate translation strings", "LanguageTool"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Any other exception → the raw message, unprefixed. */
    @Test
    public void unexpectedExceptionReturnsRawMessage()
    {
        assertEquals("weird", //$NON-NLS-1$
            error(CliReflectionErrors.toErrorJson(new RuntimeException("weird"), "Import", "CLI"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}

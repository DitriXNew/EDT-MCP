/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.UserSignal.SignalType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link UserSignal}.
 * Verifies signal creation, types, JSON serialization, and default messages.
 */
public class UserSignalTest
{
    @Test
    public void testConstructor()
    {
        UserSignal signal = new UserSignal(SignalType.CANCEL, "Cancelled by user");
        assertEquals(SignalType.CANCEL, signal.getType());
        assertEquals("Cancelled by user", signal.getMessage());
        assertTrue("Timestamp should be recent", signal.getTimestamp() > 0);
    }

    @Test
    public void testTimestampIsCurrentTime()
    {
        long before = System.currentTimeMillis();
        UserSignal signal = new UserSignal(SignalType.RETRY, "retry");
        long after = System.currentTimeMillis();
        assertTrue(signal.getTimestamp() >= before);
        assertTrue(signal.getTimestamp() <= after);
    }

    @Test
    public void testAllSignalTypes()
    {
        SignalType[] types = SignalType.values();
        assertTrue("Should have at least 5 signal types", types.length >= 5);

        // Verify all expected types exist
        assertNotNull(SignalType.valueOf("CANCEL"));
        assertNotNull(SignalType.valueOf("RETRY"));
        assertNotNull(SignalType.valueOf("BACKGROUND"));
        assertNotNull(SignalType.valueOf("EXPERT"));
        assertNotNull(SignalType.valueOf("CUSTOM"));
    }

    @Test
    public void testToJson()
    {
        UserSignal signal = new UserSignal(SignalType.CANCEL, "User cancelled");
        String json = signal.toJson();
        assertNotNull(json);

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(parsed.get("userSignal").getAsBoolean());
        assertEquals("CANCEL", parsed.get("signalType").getAsString());
        assertEquals("User cancelled", parsed.get("message").getAsString());
    }

    @Test
    public void testToJsonRetryType()
    {
        UserSignal signal = new UserSignal(SignalType.RETRY, "Please retry");
        String json = signal.toJson();
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("RETRY", parsed.get("signalType").getAsString());
    }

    @Test
    public void testToJsonExpertType()
    {
        UserSignal signal = new UserSignal(SignalType.EXPERT, "Need expert");
        String json = signal.toJson();
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("EXPERT", parsed.get("signalType").getAsString());
    }

    @Test
    public void testGetDefaultMessageCancel()
    {
        String msg = UserSignal.getDefaultMessage(SignalType.CANCEL);
        assertNotNull(msg);
        assertFalse(msg.isEmpty());
        assertTrue(msg.toLowerCase().contains("cancel"));
    }

    @Test
    public void testGetDefaultMessageRetry()
    {
        String msg = UserSignal.getDefaultMessage(SignalType.RETRY);
        assertNotNull(msg);
        assertFalse(msg.isEmpty());
        assertTrue(msg.toLowerCase().contains("retry"));
    }

    @Test
    public void testGetDefaultMessageBackground()
    {
        String msg = UserSignal.getDefaultMessage(SignalType.BACKGROUND);
        assertNotNull(msg);
        assertFalse(msg.isEmpty());
    }

    @Test
    public void testGetDefaultMessageExpert()
    {
        String msg = UserSignal.getDefaultMessage(SignalType.EXPERT);
        assertNotNull(msg);
        assertFalse(msg.isEmpty());
        assertTrue(msg.toLowerCase().contains("expert"));
    }

    @Test
    public void testGetDefaultMessageCustom()
    {
        String msg = UserSignal.getDefaultMessage(SignalType.CUSTOM);
        assertNotNull(msg);
        // Custom default message is empty
        assertEquals("", msg);
    }
}

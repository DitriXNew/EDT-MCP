/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link McpOriginValidator}.
 * Locks the Origin allow-list policy that was extracted from {@code McpServer}.
 */
public class McpOriginValidatorTest
{
    // === Allowed origins ===

    @Test
    public void testLocalhostHttpAllowed()
    {
        assertTrue(McpOriginValidator.isValidOrigin("http://localhost"));
        assertTrue(McpOriginValidator.isValidOrigin("http://localhost:8765"));
    }

    @Test
    public void testLocalhostHttpsAllowed()
    {
        assertTrue(McpOriginValidator.isValidOrigin("https://localhost"));
        assertTrue(McpOriginValidator.isValidOrigin("https://localhost:8765"));
    }

    @Test
    public void testLoopbackIpAllowed()
    {
        assertTrue(McpOriginValidator.isValidOrigin("http://127.0.0.1"));
        assertTrue(McpOriginValidator.isValidOrigin("http://127.0.0.1:8765"));
        assertTrue(McpOriginValidator.isValidOrigin("https://127.0.0.1:8765"));
    }

    @Test
    public void testFileOriginAllowed()
    {
        assertTrue(McpOriginValidator.isValidOrigin("file:///C:/page.html"));
    }

    @Test
    public void testNullLiteralAllowed()
    {
        // Local HTML files send the literal string "null" as Origin
        assertTrue(McpOriginValidator.isValidOrigin("null"));
    }

    @Test
    public void testVscodeWebviewAllowed()
    {
        assertTrue(McpOriginValidator.isValidOrigin("vscode-webview://abc123"));
    }

    // === Rejected origins ===

    @Test
    public void testRemoteHttpRejected()
    {
        assertFalse(McpOriginValidator.isValidOrigin("http://example.com"));
        assertFalse(McpOriginValidator.isValidOrigin("https://evil.example.com"));
    }

    @Test
    public void testUnrelatedSchemeRejected()
    {
        // A scheme/host that does not begin with any allowed prefix is rejected.
        assertFalse(McpOriginValidator.isValidOrigin("ftp://localhost"));
        assertFalse(McpOriginValidator.isValidOrigin("chrome-extension://abc"));
    }

    @Test
    public void testEmptyStringRejected()
    {
        assertFalse(McpOriginValidator.isValidOrigin(""));
    }

    @Test
    public void testLookAlikeHostRejected()
    {
        // A naive startsWith check accepted these look-alikes; exact-host matching
        // (prefix exactly, or prefix immediately followed by ':') must reject them.
        assertFalse(McpOriginValidator.isValidOrigin("http://localhost.attacker.com"));
        assertFalse(McpOriginValidator.isValidOrigin("http://127.0.0.1.attacker.com"));
        assertFalse(McpOriginValidator.isValidOrigin("https://localhost.evil.example"));
        assertFalse(McpOriginValidator.isValidOrigin("http://localhostx"));
        assertFalse(McpOriginValidator.isValidOrigin("http://127.0.0.1.attacker.com:8765"));
    }
}

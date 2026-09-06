/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonObject;

/**
 * Tests for {@link McpSessionRegistry}: the sessions the plugin issues, validates and closes.
 * The transport wiring (which status code each refusal produces) is asserted end-to-end by the
 * e2e suite; this locks the registry's own contract.
 */
public class McpSessionRegistryTest
{
    @Test
    public void testACreatedSessionIsValidAndCarriesItsCapabilities()
    {
        McpSessionRegistry registry = new McpSessionRegistry();
        ClientCapabilities declared = optedOutOfStructuredContent();

        String sessionId = registry.create(declared);

        assertNotNull("initialize must mint a session id", sessionId); //$NON-NLS-1$
        assertTrue("the id it just issued must validate", registry.isValid(sessionId)); //$NON-NLS-1$
        assertSame("the session must carry the capabilities THAT client declared", //$NON-NLS-1$
            declared, registry.capabilitiesOf(sessionId));
        assertEquals(1, registry.activeCount());
    }

    @Test
    public void testTwoClientsKeepTheirOwnCapabilities()
    {
        // The defect this registry exists for: one server-wide slot meant the LAST initialize
        // decided how everyone's tools/call was formatted.
        McpSessionRegistry registry = new McpSessionRegistry();
        ClientCapabilities optedOut = optedOutOfStructuredContent();

        String pickyClient = registry.create(optedOut);
        String defaultClient = registry.create(ClientCapabilities.ABSENT);

        assertNotEquals("each client gets its own session", pickyClient, defaultClient); //$NON-NLS-1$
        assertFalse("the opted-out client keeps its opt-out after a later initialize", //$NON-NLS-1$
            registry.capabilitiesOf(pickyClient).allowsStructuredContent());
        assertTrue("and the other client is unaffected by it", //$NON-NLS-1$
            registry.capabilitiesOf(defaultClient).allowsStructuredContent());
    }

    @Test
    public void testNullCapabilitiesAreStoredAsThePermissiveDefault()
    {
        McpSessionRegistry registry = new McpSessionRegistry();

        String sessionId = registry.create(null);

        assertSame(ClientCapabilities.ABSENT, registry.capabilitiesOf(sessionId));
    }

    @Test
    public void testAnUnknownOrAbsentIdIsNotASession()
    {
        McpSessionRegistry registry = new McpSessionRegistry();
        registry.create(ClientCapabilities.ABSENT);

        assertFalse("an id this registry never issued is not open", //$NON-NLS-1$
            registry.isValid("11111111-2222-3333-4444-555555555555")); //$NON-NLS-1$
        assertFalse("and neither is no id at all", registry.isValid(null)); //$NON-NLS-1$
        // null, not ABSENT: the transport tells these apart from an open session and answers 404.
        assertNull(registry.capabilitiesOf("11111111-2222-3333-4444-555555555555")); //$NON-NLS-1$
        assertNull(registry.capabilitiesOf(null));
    }

    @Test
    public void testCloseTerminatesTheSessionAndIsIdempotent()
    {
        McpSessionRegistry registry = new McpSessionRegistry();
        String sessionId = registry.create(ClientCapabilities.ABSENT);

        assertTrue("the first DELETE closes a session that was open", registry.close(sessionId)); //$NON-NLS-1$
        assertFalse("after which it no longer validates", registry.isValid(sessionId)); //$NON-NLS-1$
        assertNull("and its capabilities are gone with it", registry.capabilitiesOf(sessionId)); //$NON-NLS-1$
        assertFalse("a repeated DELETE closes nothing, but is not an error", //$NON-NLS-1$
            registry.close(sessionId));
        assertFalse("nor is a DELETE with no session id", registry.close(null)); //$NON-NLS-1$
        assertEquals(0, registry.activeCount());
    }

    @Test
    public void testClosingOneSessionLeavesTheOthersOpen()
    {
        McpSessionRegistry registry = new McpSessionRegistry();
        String first = registry.create(ClientCapabilities.ABSENT);
        String second = registry.create(ClientCapabilities.ABSENT);

        registry.close(first);

        assertFalse(registry.isValid(first));
        assertTrue("closing one client's session must not disconnect another", //$NON-NLS-1$
            registry.isValid(second));
        assertEquals(1, registry.activeCount());
    }

    @Test
    public void testTheSessionCapRefusesRatherThanGrowingForever()
    {
        McpSessionRegistry registry = new McpSessionRegistry();
        for (int i = 0; i < McpSessionRegistry.MAX_SESSIONS; i++)
        {
            assertNotNull("session " + i + " must be issued", registry.create(ClientCapabilities.ABSENT)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        assertNull("past the cap, initialize is refused instead of growing the map", //$NON-NLS-1$
            registry.create(ClientCapabilities.ABSENT));
        assertEquals(McpSessionRegistry.MAX_SESSIONS, registry.activeCount());
    }

    @Test
    public void testClosingASessionMakesRoomAgain()
    {
        McpSessionRegistry registry = new McpSessionRegistry();
        String first = null;
        for (int i = 0; i < McpSessionRegistry.MAX_SESSIONS; i++)
        {
            String created = registry.create(ClientCapabilities.ABSENT);
            first = first == null ? created : first;
        }
        assertNull(registry.create(ClientCapabilities.ABSENT));

        registry.close(first);

        assertNotNull("a DELETE must let the next client in - the cap is not a one-way door", //$NON-NLS-1$
            registry.create(ClientCapabilities.ABSENT));
    }

    /** A client that explicitly declared it cannot accept {@code structuredContent}. */
    private static ClientCapabilities optedOutOfStructuredContent()
    {
        JsonObject experimental = new JsonObject();
        experimental.addProperty("structuredContent", false); //$NON-NLS-1$
        JsonObject capabilities = new JsonObject();
        capabilities.add("experimental", experimental); //$NON-NLS-1$
        ClientCapabilities parsed = ClientCapabilities.from(capabilities);
        assertFalse("fixture guard: this must be a real opt-out", parsed.allowsStructuredContent()); //$NON-NLS-1$
        return parsed;
    }
}

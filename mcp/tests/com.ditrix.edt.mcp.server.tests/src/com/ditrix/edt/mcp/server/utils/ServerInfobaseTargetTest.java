/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

public class ServerInfobaseTargetTest
{
    @Test
    public void validServerRegistrationNeedsNoFile()
    {
        assertNull(ServerInfobaseTarget.validationError("localhost:1541", "TestInfobase", null, true, false)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ServerInfobaseTarget.validationError(null, null, "C:/file", true, false)); //$NON-NLS-1$
    }

    @Test
    public void blanksAreNotACompleteServerTarget()
    {
        assertNull(ServerInfobaseTarget.validationError("", "", null, true, false)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ServerInfobaseTarget.validationError("", " ", "C:/file", true, false)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNotNull(ServerInfobaseTarget.validationError("localhost", " ", null, true, false)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void serverEntriesHaveHostsAndValidPorts()
    {
        for (String server : new String[] {"host", "host:1", "host:65535", "host:1541,other:1542", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "[::1]", "[2001:db8::1]:1541,host", "host.example.", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "[::ffff:192.0.2.1]:1541", "[::ffff:c000:201]"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertNull(server, ServerInfobaseTarget.validationError(server, "base", null, true, false)); //$NON-NLS-1$
        }
        for (String server : new String[] {"host:99999", "host:", "host:abc", ":1541", "local host", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "host:0", "host:-1", "host,,other", "host,", "::1", "[gg::1]", "[host]", "[::1]:65536", "[::1]extra"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$ //$NON-NLS-9$
        {
            String error = ServerInfobaseTarget.validationError(server, "base", null, true, false); //$NON-NLS-1$
            assertNotNull(server, error);
            assertTrue(error, error.contains("infobaseServer")); //$NON-NLS-1$
            assertFalse(error, error.contains("infobaseRef")); //$NON-NLS-1$
        }
    }

    @Test
    public void fieldErrorsNameOnlyTheBadFieldAndMaskPotentialSecrets()
    {
        for (String ref : new String[] {"https://host/base", "a\\b", "base;Pwd=secret", "base\"", " base", "base\n"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            String error = ServerInfobaseTarget.validationError("host", ref, null, true, false); //$NON-NLS-1$
            assertNotNull(ref, error);
            assertTrue(error, error.contains("infobaseRef")); //$NON-NLS-1$
            assertFalse(error, error.contains("infobaseServer")); //$NON-NLS-1$
            assertFalse(error, error.contains("secret")); //$NON-NLS-1$
        }
        assertTrue(ServerInfobaseTarget.validationError(" host", "base", null, true, false) //$NON-NLS-1$ //$NON-NLS-2$
            .contains("whitespace")); //$NON-NLS-1$
    }

    @Test
    public void urlsConnectionStringsAndControlsAreRejected()
    {
        for (String server : new String[] {"https://host/base", "host;Usr=secret", "host\n", " host"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertNotNull(ServerInfobaseTarget.validationError(server, "TestInfobase", null, true, false)); //$NON-NLS-1$
        }
        assertNotNull(ServerInfobaseTarget.validationError("host", "base\";Pwd=secret", null, true, false)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void typedReferenceKeepsAddressAndDisplayName()
    {
        ServerInfobaseTarget target = new ServerInfobaseTarget("localhost:1541", "TestInfobase"); //$NON-NLS-1$ //$NON-NLS-2$
        InfobaseReference reference = target.reference("Server test"); //$NON-NLS-1$
        assertNotNull(reference.getUuid());
        assertEquals("Server test", reference.getName()); //$NON-NLS-1$
        String connection = reference.getConnectionString().asConnectionString();
        assertTrue(connection.contains("localhost:1541")); //$NON-NLS-1$
        assertTrue(connection.contains("TestInfobase")); //$NON-NLS-1$
        assertEquals("localhost:1541/TestInfobase", target.presentation()); //$NON-NLS-1$
    }
}

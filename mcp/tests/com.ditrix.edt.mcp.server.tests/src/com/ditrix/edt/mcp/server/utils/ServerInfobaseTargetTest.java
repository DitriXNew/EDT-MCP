/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
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
        assertNotNull(ServerInfobaseTarget.validationError("", "", null, true, false)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(ServerInfobaseTarget.validationError("localhost", " ", null, true, false)); //$NON-NLS-1$ //$NON-NLS-2$
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

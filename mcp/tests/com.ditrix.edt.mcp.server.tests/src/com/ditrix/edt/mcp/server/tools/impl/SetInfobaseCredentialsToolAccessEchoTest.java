/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport.StoreResult;
import com.google.gson.JsonParser;

/** Pins the canonical access values in the real success payload (issue #615). */
public class SetInfobaseCredentialsToolAccessEchoTest
{
    @Test
    public void infobaseAccessUsesCanonicalEnumConstantName()
    {
        String json = successJson(InfobaseAccess.INFOBASE);

        assertEquals("INFOBASE", JsonParser.parseString(json).getAsJsonObject() //$NON-NLS-1$
            .get("access").getAsString()); //$NON-NLS-1$
        assertFalse("The access field must not expose EDT's mixed-case enum name", //$NON-NLS-1$
            json.contains("\"access\":\"Infobase\"")); //$NON-NLS-1$
    }

    @Test
    public void osAccessKeepsCanonicalEnumConstantName()
    {
        String json = successJson(InfobaseAccess.OS);

        assertEquals("OS", JsonParser.parseString(json).getAsJsonObject() //$NON-NLS-1$
            .get("access").getAsString()); //$NON-NLS-1$
    }

    private static String successJson(InfobaseAccess access)
    {
        InfobaseReference ref = mock(InfobaseReference.class);
        when(ref.getName()).thenReturn("Main infobase"); //$NON-NLS-1$
        when(ref.getUuid()).thenReturn(UUID.fromString("eb53270d-489b-4f58-9f22-ae7fd9426bc9")); //$NON-NLS-1$
        return SetInfobaseCredentialsTool.buildSuccess("TestProject", "app1", "Main infobase", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "Admin", true, access, StoreResult.verified(ref), null, null); //$NON-NLS-1$
    }
}

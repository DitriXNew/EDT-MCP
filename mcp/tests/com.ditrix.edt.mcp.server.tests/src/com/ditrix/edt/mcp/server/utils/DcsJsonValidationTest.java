// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Pins the shared validator contract captured from all three original DCS writers. */
public class DcsJsonValidationTest
{
    @Test public void unknownMembersPreserveInputOrderAcceptedOrderAndExactError() throws Exception
    {
        JsonObject body = JsonParser.parseString("{\"second\":null,\"first\":false}").getAsJsonObject();
        assertEquals("shared DCS JSON validation", "Unknown member 'second' in body.items[2]. Accepted members: b, a. "
            + "Remove 'second' or use one of them.", JsonUtils.unknownMemberError(body, "body.items[2]", new String[] {"b", "a", "b"}));
        assertNull(JsonUtils.unknownMemberError(body, "body", new String[] {"second", "first"}));
        assertNull(JsonUtils.unknownMemberError(new JsonObject(), "body", new String[0]));
        assertEquals("Unknown member 'second' in body. Accepted members: . Remove 'second' or use one of them.",
            JsonUtils.unknownMemberError(body, "body", new String[0]));
    }
}

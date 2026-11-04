/* Copyright (C) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.EdtDialogs;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class LaunchToolDialogTest
{
    @Test
    public void dispatchedLaunchNamesDialogFromBoundedWatch()
    {
        EdtDialogs dialogs = mock(EdtDialogs.class);
        JsonArray observed = JsonParser.parseString("[{\"dialogId\":\"one-shell\",\"title\":\"Application update\","
            + "\"message\":\"Update?\",\"buttons\":[\"Update then run\"],\"answerable\":true}]").getAsJsonArray();
        when(dialogs.awaitDialogs(true, 750)).thenReturn(observed);
        {
            JsonObject result = JsonParser.parseString(LaunchTool.reportLaunchDialogs(
                "{\"success\":true,\"status\":\"launching\"}", LaunchTool.shouldWatchDialogs(false, false,
                    ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED), dialogs)).getAsJsonObject();
            assertEquals(observed, result.get("edtDialogs"));
            assertEquals("launching", result.get("status").getAsString());
            verify(dialogs).awaitDialogs(true, 750);
        }
    }

    @Test
    public void upToDateLaunchHasNoWatchDelayOrPayloadChange()
    {
        EdtDialogs dialogs = new EdtDialogs();
        {
            String original = "{\"success\":true,\"status\":\"launching\"}";
            long started = System.nanoTime();
            assertSame(original, LaunchTool.reportLaunchDialogs(original,
                LaunchTool.shouldWatchDialogs(false, false, ApplicationUpdateState.UPDATED), dialogs));
            assertTrue("no 750ms watch for up-to-date launch", System.nanoTime() - started < 200_000_000L);
        }
    }

    @Test
    public void updatingAndAttachRoutesKeepTheirExistingArmingPolicy()
    {
        assertFalse(LaunchTool.shouldWatchDialogs(true, false, ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED));
        assertFalse(LaunchTool.shouldWatchDialogs(false, true, ApplicationUpdateState.INCREMENTAL_UPDATE_REQUIRED));
        assertTrue(LaunchTool.shouldWatchDialogs(false, false, ApplicationUpdateState.UNKNOWN));
    }
}

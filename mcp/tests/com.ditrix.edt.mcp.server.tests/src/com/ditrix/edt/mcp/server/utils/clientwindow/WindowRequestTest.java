// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class WindowRequestTest
{
    @Test public void defaultAndAllModes()
    {
        assertEquals(WindowRequest.Mode.NORMAL, WindowRequest.parse(null, null).mode());
        for (WindowRequest.Mode mode : WindowRequest.Mode.values())
        {
            assertEquals(mode, WindowRequest.parse(mode.token(), null).mode());
        }
        assertEquals(Integer.valueOf(2), WindowRequest.parse("otherScreen", "2.0").screen());
    }
    @Test public void rejectInvalidModesAndScreens()
    {
        for (String mode : List.of("", "Normal", "hidden"))
        {
            assertThrows(IllegalArgumentException.class, () -> WindowRequest.parse(mode, null));
        }
        for (String screen : List.of("0", "-1", "1.5", "many", "2147483648", ""))
        {
            assertThrows(IllegalArgumentException.class, () -> WindowRequest.parse("otherScreen", screen));
        }
        assertThrows(IllegalArgumentException.class, () -> WindowRequest.parse("minimized", "1"));
    }
    @Test public void monitorOrderAndDefault()
    {
        var primary = new WindowController.Screen(0, 0, 1920, 1080, true);
        var other = new WindowController.Screen(-1600, 100, 1600, 900, false);
        assertSame(other, WindowController.selectScreen(List.of(primary, other), null));
        assertSame(primary, WindowController.selectScreen(List.of(primary, other), 1));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> WindowController.selectScreen(List.of(primary), null)).getMessage().contains("two monitors"));
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> WindowController.selectScreen(List.of(primary, other), 3)).getMessage().contains("1..2"));
    }
    @Test public void explicitPlatformSelection()
    {
        assertEquals(ClientPlatforms.Os.WINDOWS, ClientPlatforms.os("Windows 11"));
        assertEquals(ClientPlatforms.Os.LINUX, ClientPlatforms.os("Linux"));
        assertEquals(ClientPlatforms.Os.MAC, ClientPlatforms.os("Mac OS X"));
        assertEquals(ClientPlatforms.Os.MAC, ClientPlatforms.os("Darwin"));
        assertThrows(IllegalArgumentException.class, () -> ClientPlatforms.os("FreeBSD"));
    }

    @Test public void wireSchemaKeepsTheDefaultAndMonitorIndexContract()
    {
        var schema = com.ditrix.edt.mcp.server.protocol.InputSchemaCompactor.compact("launch",
            com.google.gson.JsonParser.parseString(new com.ditrix.edt.mcp.server.tools.impl.LaunchTool().getInputSchema()))
            .getAsJsonObject().getAsJsonObject("properties");
        assertEquals("normal", schema.getAsJsonObject("windowMode").get("default").getAsString());
        assertTrue(schema.getAsJsonObject("screen").get("description").getAsString().contains("1-based"));
        assertTrue(schema.getAsJsonObject("screen").get("description").getAsString().contains("non-primary"));
    }
}

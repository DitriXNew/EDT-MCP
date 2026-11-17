// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils.clientwindow;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class PlatformWindowControllerTest
{
    static final class Commands implements WindowCommands
    {
        final List<List<String>> calls = new ArrayList<>();
        String absent;
        Answer answer = new Answer(0, "true\n", "");
        @Override public boolean available(String command) { return !command.equals(absent); }
        @Override public Answer run(List<String> command) { calls.add(command); return answer; }
    }

    @Test public void xrandrPreservesEnumerationPrimaryAndNegativeCoordinates()
    {
        var screens = LinuxWindowController.parseScreens("Monitors: 2\n"
            + " 0: +*DP-1 1920/530x1080/300+0+0 DP-1\n 1: +HDMI-1 1600/450x900/250-1600+100 HDMI-1\n");
        assertEquals(2, screens.size());
        assertTrue(screens.get(0).primary());
        assertEquals(new WindowController.Screen(-1600, 100, 1600, 900, false), screens.get(1));
        assertSame(screens.get(1), WindowController.selectScreen(screens, null));
    }

    @Test public void missingLinuxToolsNameTheirPackages()
    {
        Commands commands = new Commands();
        commands.absent = "xdotool";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new LinuxWindowController(commands))
            .getMessage().contains("install the xdotool package"));
        commands.absent = "Xvfb";
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> ClientPlatforms.isolated(ClientPlatforms.Os.LINUX, commands)).getMessage().contains("xvfb"));
        commands.absent = "setsid";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new LinuxIsolatedClient(commands))
            .getMessage().contains("util-linux"));
    }

    @Test public void macUsesPidAndReadsBackMinimizedState() throws Exception
    {
        Commands commands = new Commands();
        var controller = new MacWindowController(commands);
        assertTrue(controller.adjust(new WindowController.Window("42:1", 42, "", ""), WindowRequest.Mode.MINIMIZED, null));
        String source = commands.calls.get(0).get(2);
        assertTrue(source.contains("unix id is 42"));
        assertTrue(source.contains("AXMinimized"));
        assertFalse(source.contains("activate"));
    }

    @Test public void linuxUsesManagedTopLevelWindowsIncludingMinimizedOnes() throws Exception
    {
        Commands commands = new Commands();
        commands.answer = new WindowCommands.Answer(0, "0x00002a 0 42 host Client\n0x00002b 0 99 host Other app\n", "");
        var controller = new LinuxWindowController(commands);
        var windows = controller.windows(Set.of(42L));
        assertEquals(1, windows.size());
        assertEquals("42", windows.get(0).id());
        assertEquals("Client", windows.get(0).title());
        commands.answer = new WindowCommands.Answer(0, "window state: Iconic\n", "");
        assertTrue(controller.adjust(windows.get(0), WindowRequest.Mode.MINIMIZED, null));
        assertEquals(List.of("xdotool", "windowminimize", "--sync", "42"), commands.calls.get(1));
        commands.answer = new WindowCommands.Answer(0, "window state: Normal\n", "");
        assertFalse(controller.adjust(windows.get(0), WindowRequest.Mode.MINIMIZED, null));
    }

    @Test public void macOtherScreenSetsExactDisplayBoundsWithoutActivation() throws Exception
    {
        Commands commands = new Commands();
        assertTrue(new MacWindowController(commands).adjust(new WindowController.Window("42:2", 42, "", ""),
            WindowRequest.Mode.OTHER_SCREEN, new WindowController.Screen(-1600, 100, 1600, 900, false)));
        String source = commands.calls.get(0).get(2);
        assertTrue(source.contains("set position to {-1600, 100}"));
        assertTrue(source.contains("set size to {1600, 900}"));
        assertTrue(source.contains("window 2"));
        assertFalse(source.contains("activate"));
    }

    @Test public void linuxMaximizesOnTheSelectedMonitorAndAllowsFrameBorders() throws Exception
    {
        List<List<String>> calls = new ArrayList<>();
        WindowCommands commands = new WindowCommands()
        {
            @Override public boolean available(String command) { return true; }
            @Override public Answer run(List<String> command)
            {
                calls.add(command);
                String output = command.contains("getwindowgeometry") ? "X=1912\nY=-8\nWIDTH=1936\nHEIGHT=1096\n"
                    : "_NET_WM_STATE_MAXIMIZED_VERT, _NET_WM_STATE_MAXIMIZED_HORZ";
                return new Answer(0, output, "");
            }
        };
        var controller = new LinuxWindowController(commands);
        assertTrue(controller.adjust(new WindowController.Window("42", 99, "", ""), WindowRequest.Mode.OTHER_SCREEN,
            new WindowController.Screen(1920, 0, 1920, 1080, false)));
        assertTrue(calls.contains(List.of("xdotool", "windowmove", "--sync", "42", "1920", "0")));
        assertTrue(calls.contains(List.of("wmctrl", "-i", "-r", "0x2a", "-b", "add,maximized_vert,maximized_horz")));
    }

    @Test public void macAccessibilityRefusalIsActionable() throws Exception
    {
        Commands commands = new Commands();
        commands.answer = new WindowCommands.Answer(0, "EDT_MCP_OSERROR\t-1743\tNot authorized", "");
        var controller = new MacWindowController(commands);
        String error = assertThrows(IllegalStateException.class, () -> controller.windows(Set.of(42L))).getMessage();
        assertTrue(error.contains("Accessibility and Automation"));
        assertTrue(error.contains("System Settings"));
    }

    @Test public void macBoundsAndHiddenBundleLaunchAreExplicit() throws Exception
    {
        Commands commands = new Commands();
        commands.answer = new WindowCommands.Answer(0, "0\t0\t1920\t1080\ttrue\n-1600\t100\t1600\t900\tfalse\n", "");
        var screens = new MacWindowController(commands).screens();
        assertEquals(2, screens.size());
        assertEquals(-1600, screens.get(1).x());
        assertTrue(MacIsolatedClient.appBundle("/Applications/1cv8c.app/Contents/MacOS/1cv8c").toString().endsWith("1cv8c.app"));
        assertThrows(IllegalArgumentException.class, () -> MacIsolatedClient.appBundle("/usr/bin/1cv8c"));
        assertTrue(ClientPlatforms.isolated(ClientPlatforms.Os.MAC, commands) instanceof MacIsolatedClient);
        assertTrue(ClientPlatforms.isolated(ClientPlatforms.Os.LINUX, commands) instanceof LinuxIsolatedClient);
    }

    @Test public void macWindowListingReportsTheActualTitleAndAccessibilityRole() throws Exception
    {
        WindowCommands commands = new WindowCommands()
        {
            @Override public boolean available(String command) { return true; }
            @Override public Answer run(List<String> command)
            {
                String source = command.get(2);
                String value = source.contains("count of windows") ? "1"
                    : source.contains("return name") ? "Blocking modal, with a title" : "AXDialog";
                return new Answer(0, value + "\n", "");
            }
        };
        var windows = new MacWindowController(commands).windows(Set.of(42L));
        assertEquals(1, windows.size());
        assertEquals("Blocking modal, with a title", windows.get(0).title());
        assertEquals("AXDialog", windows.get(0).windowClass());
    }
}

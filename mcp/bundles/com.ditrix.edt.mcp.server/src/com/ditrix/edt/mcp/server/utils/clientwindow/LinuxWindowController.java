// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** X11 window adjustment; Wayland's native windows cannot be controlled by these helpers. */
public final class LinuxWindowController implements WindowController
{
    private static final Pattern MONITOR = Pattern.compile(
        "^\\s*\\d+:\\s+(\\S+)\\s+(\\d+)/\\d+x(\\d+)/\\d+([+-]\\d+)([+-]\\d+).*$");
    private final WindowCommands commands;

    public LinuxWindowController(WindowCommands commands)
    {
        this.commands = commands;
        commands.require("xdotool", "xdotool");
        commands.require("wmctrl", "wmctrl");
        commands.require("xprop", "x11-utils (or xprop)");
    }

    @Override public List<Screen> screens() throws Exception
    {
        commands.require("xrandr", "x11-xserver-utils (or xrandr)");
        return parseScreens(commands.checked(List.of("xrandr", "--listmonitors")));
    }

    static List<Screen> parseScreens(String output)
    {
        List<Screen> result = new ArrayList<>();
        for (String line : output.split("\\R"))
        {
            Matcher match = MONITOR.matcher(line);
            if (match.matches())
            {
                result.add(new Screen(Integer.parseInt(match.group(4)), Integer.parseInt(match.group(5)),
                    Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)), match.group(1).contains("*")));
            }
        }
        return result;
    }

    @Override public List<Window> windows(Set<Long> pids) throws Exception
    {
        List<Window> result = new ArrayList<>();
        String listing = commands.checked(List.of("wmctrl", "-lp"));
        for (String line : listing.split("\\R"))
        {
            String[] fields = line.strip().split("\\s+", 5);
            if (fields.length < 4 || !fields[2].matches("[0-9]+")) { continue; }
            long pid = Long.parseLong(fields[2]);
            if (!pids.contains(pid)) { continue; }
            String id = Long.toUnsignedString(Long.parseUnsignedLong(fields[0].substring(2), 16));
            result.add(new Window(id, pid, fields.length == 5 ? fields[4] : "", ""));
        }
        return result;
    }

    @Override public boolean adjust(Window window, WindowRequest.Mode mode, Screen screen) throws Exception
    {
        String id = window.id();
        String hex = "0x" + Long.toHexString(Long.parseLong(id));
        if (mode == WindowRequest.Mode.MINIMIZED)
        {
            commands.checked(List.of("xdotool", "windowminimize", "--sync", id));
            return commands.checked(List.of("xprop", "-id", id, "WM_STATE")).contains("Iconic");
        }
        commands.checked(List.of("wmctrl", "-i", "-r", hex, "-b", "remove,maximized_vert,maximized_horz"));
        commands.checked(List.of("xdotool", "windowmove", "--sync", id, "" + screen.x(), "" + screen.y()));
        commands.checked(List.of("wmctrl", "-i", "-r", hex, "-b", "add,maximized_vert,maximized_horz"));
        long deadline = System.nanoTime() + 500_000_000;
        do
        {
            String state = commands.checked(List.of("xprop", "-id", id, "_NET_WM_STATE"));
            String geometry = commands.checked(List.of("xdotool", "getwindowgeometry", "--shell", id));
            int width = coordinate(geometry, "WIDTH");
            int height = coordinate(geometry, "HEIGHT");
            long centerX = (long)coordinate(geometry, "X") + width / 2;
            long centerY = (long)coordinate(geometry, "Y") + height / 2;
            if (state.contains("_NET_WM_STATE_MAXIMIZED_VERT") && state.contains("_NET_WM_STATE_MAXIMIZED_HORZ")
                && width > 0 && height > 0 && centerX >= screen.x() && centerX < screen.x() + screen.width()
                && centerY >= screen.y() && centerY < screen.y() + screen.height())
            {
                return true;
            }
            Thread.sleep(25);
        }
        while (System.nanoTime() < deadline);
        return false;
    }

    private static int coordinate(String geometry, String name)
    {
        Matcher value = Pattern.compile("(?m)^" + name + "=(-?\\d+)$").matcher(geometry);
        return value.find() ? Integer.parseInt(value.group(1)) : Integer.MIN_VALUE;
    }
}

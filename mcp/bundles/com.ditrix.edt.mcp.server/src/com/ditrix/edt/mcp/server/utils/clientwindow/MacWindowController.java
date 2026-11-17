// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** System Events controls only the process selected by its Unix PID. */
public final class MacWindowController implements WindowController
{
    private final WindowCommands commands;
    public MacWindowController(WindowCommands commands)
    {
        this.commands = commands;
        commands.require("osascript", "macOS osascript");
    }

    private String script(String source) throws Exception
    {
        String guarded = "try\ntell application \"System Events\"\n"
            + "if UI elements enabled is false then return \"EDT_MCP_OSERROR\" & tab & -25211 & tab & \"Accessibility disabled\"\n"
            + "end tell\n" + source + "\non error detail number code\n"
            + "return \"EDT_MCP_OSERROR\" & tab & code & tab & detail\nend try";
        WindowCommands.Answer answer = commands.run(List.of("osascript", "-e", guarded));
        if (answer.code() != 0)
        {
            if (answer.code() == -1)
            {
                throw new IllegalStateException("macOS window control timed out; allow Accessibility and Automation for EDT "
                    + "in System Settings > Privacy & Security, then retry.");
            }
            throw new IllegalStateException("macOS osascript could not control the client window: " + answer.error().strip());
        }
        if (answer.output().startsWith("EDT_MCP_OSERROR\t"))
        {
            String[] failure = answer.output().strip().split("\t", 3);
            int code = Integer.parseInt(failure[1]);
            if (code == -1743 || code == -25211 || code == -10004)
            {
                throw new IllegalStateException("macOS refused window access; allow Accessibility and Automation for EDT "
                    + "in System Settings > Privacy & Security (System Events code " + code + ").");
            }
            throw new IllegalStateException("System Events could not adjust the client window (code " + code + "): "
                + (failure.length > 2 ? failure[2] : "inspect the client window and retry"));
        }
        return answer.output();
    }

    @Override public List<Screen> screens() throws Exception
    {
        String source = "ObjC.import('AppKit'); var ss=$.NSScreen.screens; var h=ss.objectAtIndex(0).frame.size.height;"
            + " var out=[]; for(var i=0;i<ss.count;i++){var f=ss.objectAtIndex(i).frame;"
            + "out.push([f.origin.x,h-f.origin.y-f.size.height,f.size.width,f.size.height,i===0].join('\\t'));} out.join('\\n');";
        String output = commands.checked(List.of("osascript", "-l", "JavaScript", "-e", source));
        List<Screen> result = new ArrayList<>();
        for (String line : output.strip().split("\\R"))
        {
            String[] values = line.split("\t");
            if (values.length != 5) { throw new IllegalStateException("Cannot read macOS display bounds."); }
            result.add(new Screen((int)Double.parseDouble(values[0]), (int)Double.parseDouble(values[1]),
                (int)Double.parseDouble(values[2]), (int)Double.parseDouble(values[3]), Boolean.parseBoolean(values[4])));
        }
        return result;
    }

    @Override public List<Window> windows(Set<Long> pids) throws Exception
    {
        List<Window> result = new ArrayList<>();
        for (long pid : pids)
        {
            String count = script("tell application \"System Events\"\n"
                + "if not (exists first application process whose unix id is " + pid + ") then return 0\n"
                + "tell (first application process whose unix id is " + pid + ") to return count of windows\nend tell").strip();
            for (int i = 1; i <= Integer.parseInt(count); i++)
            {
                String target = "tell application \"System Events\" to tell "
                    + "(first application process whose unix id is " + pid + ") to tell window " + i + "\n";
                String title = script(target + "return name\nend tell").strip();
                String role = script(target + "return value of attribute \"AXSubrole\"\nend tell").strip();
                result.add(new Window(pid + ":" + i, pid, title, role));
            }
        }
        return result;
    }

    @Override public boolean adjust(Window window, WindowRequest.Mode mode, Screen screen) throws Exception
    {
        int index = Integer.parseInt(window.id().split(":")[1]);
        String action = mode == WindowRequest.Mode.MINIMIZED
            ? "set value of attribute \"AXMinimized\" to true\nreturn value of attribute \"AXMinimized\""
            : "set position to {" + screen.x() + ", " + screen.y() + "}\nset size to {" + screen.width() + ", "
                + screen.height() + "}\nreturn (position is {" + screen.x() + ", " + screen.y()
                + "}) and (size is {" + screen.width() + ", " + screen.height() + "})";
        return "true".equals(script("tell application \"System Events\" to tell "
            + "(first application process whose unix id is " + window.pid() + ") to tell window "
            + index + "\n" + action + "\nend tell").strip());
    }
}

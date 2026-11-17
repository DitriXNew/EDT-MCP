// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.ditrix.edt.mcp.server.utils.ExternalProcess;

/** Launch Services' hidden, non-activating launch; macOS has no public private-desktop API. */
public final class MacIsolatedClient implements IsolatedClientLauncher
{
    private final WindowCommands commands;
    private final java.util.function.LongFunction<ProcessHandle> handles;
    public MacIsolatedClient(WindowCommands commands)
    {
        this(commands, pid -> ProcessHandle.of(pid).orElseThrow(() ->
            new IllegalStateException("The hidden macOS client exited before it could be tracked.")));
    }

    MacIsolatedClient(WindowCommands commands, java.util.function.LongFunction<ProcessHandle> handles)
    {
        this.commands = commands;
        this.handles = handles;
        commands.require("osascript", "macOS osascript");
    }

    static Path appBundle(String executable)
    {
        for (Path path = Path.of(executable).toAbsolutePath(); path != null; path = path.getParent())
        {
            if (path.toString().endsWith(".app")) { return path; }
        }
        throw new IllegalArgumentException("macOS isolated mode requires a 1C .app bundle; select a bundled thin-client runtime installation.");
    }

    @Override public OwnedClient start(List<String> command) throws Exception
    {
        Gson json = new Gson();
        String source = "ObjC.import('AppKit'); var cfg=$.NSMutableDictionary.dictionary;"
            + "cfg.setObjectForKey($(" + json.toJson(command.subList(1, command.size()))
            + "), $.NSWorkspaceLaunchConfigurationArguments);"
            + "var app=$.NSWorkspace.sharedWorkspace.launchApplicationAtURLOptionsConfigurationError("
            + "$.NSURL.fileURLWithPath(" + json.toJson(appBundle(command.get(0)).toString()) + "),"
            + "$.NSWorkspaceLaunchNewInstance | $.NSWorkspaceLaunchWithoutActivation | $.NSWorkspaceLaunchAndHide | $.NSWorkspaceLaunchAsync, cfg, null);"
            + "if(!app) throw Error('Launch Services refused the bundled client');"
            + "var until=Date.now()+5000; while((app.active || !app.hidden) && Date.now()<until){app.hide();"
            + "$.NSRunLoop.currentRunLoop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(0.05));}"
            + "JSON.stringify({pid:Number(app.processIdentifier),hidden:Boolean(app.hidden),active:Boolean(app.active)});";
        WindowCommands.Answer answer = commands.run(List.of("osascript", "-l", "JavaScript", "-e", source), 10_000);
        if (answer.code() != 0)
        {
            // osascript diagnostics can reproduce source containing credentials.
            throw new IllegalStateException("macOS Launch Services could not start the hidden 1C app; verify the .app runtime installation and Automation permission for EDT.");
        }
        var state = JsonParser.parseString(answer.output()).getAsJsonObject();
        long pid = state.get("pid").getAsLong();
        ProcessHandle process = handles.apply(pid);
        OwnedClient owner = new OwnedClient()
        {
            @Override public long pid() { return pid; }
            @Override public boolean alive() { return process.isAlive(); }
            @Override public int exitCode()
            {
                if (alive()) { throw new IllegalThreadStateException("The hidden client is still running."); }
                return 0;
            }
            @Override public String limitation()
            {
                return "macOS has no public isolated-desktop API: the client window exists, hidden and without activation, on the current desktop.";
            }
            @Override public List<WindowController.Window> windows() throws Exception
            {
                return new MacWindowController(commands).windows(Set.of(pid));
            }
            @Override public void close()
            {
                ExternalProcess.destroyTree(process);
            }
        };
        if (!state.get("hidden").getAsBoolean() || state.get("active").getAsBoolean())
        {
            owner.close();
            throw new IllegalStateException("macOS did not confirm a hidden, non-activated client; the launched process was terminated.");
        }
        return owner;
    }
}

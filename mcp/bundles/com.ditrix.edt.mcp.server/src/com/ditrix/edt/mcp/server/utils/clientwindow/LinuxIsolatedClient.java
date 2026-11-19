// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

import com.ditrix.edt.mcp.server.utils.ExternalProcess;

/** One Xvfb and one new process group per owned client. */
public final class LinuxIsolatedClient implements IsolatedClientLauncher
{
    public interface Backend
    {
        Process startDisplay() throws Exception;
        boolean acceptsConnections(int number);
        Process startClient(List<String> command, String display) throws Exception;
    }

    private final WindowCommands commands;
    private final Backend backend;
    private final long readinessMillis;
    public LinuxIsolatedClient(WindowCommands commands)
    {
        this(commands, new Backend()
        {
            @Override public Process startDisplay() throws Exception
            {
                // Xvfb reserves a free display atomically and writes its number to this private pipe.
                return new ProcessBuilder("Xvfb", "-displayfd", "1", "-screen", "0", "1920x1080x24", "-nolisten", "tcp")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            }
            @Override public boolean acceptsConnections(int number)
            {
                try (SocketChannel connection = SocketChannel.open(UnixDomainSocketAddress.of("/tmp/.X11-unix/X" + number)))
                {
                    return connection.isConnected();
                }
                catch (java.io.IOException e) { return false; }
            }
            @Override public Process startClient(List<String> command, String display) throws Exception
            {
                List<String> grouped = new ArrayList<>(List.of("setsid"));
                grouped.addAll(command);
                ProcessBuilder builder = new ProcessBuilder(grouped)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
                configureDisplay(builder.environment(), display);
                return builder.start();
            }
        }, 5000);
    }

    static void configureDisplay(java.util.Map<String, String> environment, String display)
    {
        environment.put("DISPLAY", display);
        environment.remove("WAYLAND_DISPLAY");
        environment.put("QT_QPA_PLATFORM", "xcb");
        environment.put("GDK_BACKEND", "x11");
        environment.put("XDG_SESSION_TYPE", "x11");
    }

    LinuxIsolatedClient(WindowCommands commands, Backend backend, long readinessMillis)
    {
        this.commands = commands; this.backend = backend; this.readinessMillis = readinessMillis;
        commands.require("Xvfb", "xvfb");
        commands.require("setsid", "util-linux");
        commands.require("kill", "procps (or util-linux)");
    }

    @Override public OwnedClient start(List<String> command) throws Exception
    {
        Process xvfb = backend.startDisplay();
        boolean transferred = false;
        java.util.concurrent.FutureTask<Integer> number = new java.util.concurrent.FutureTask<>(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                xvfb.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII)))
            {
                String line = reader.readLine();
                if (line == null || !line.matches("\\d+"))
                {
                    throw new IllegalStateException("Xvfb did not allocate a private display; verify the xvfb package supports -displayfd.");
                }
                return Integer.parseInt(line);
            }
        });
        Thread reader = new Thread(number, "EDT-MCP Xvfb display allocation");
        reader.setDaemon(true);
        reader.start();
        try
        {
            long deadline = System.nanoTime() + readinessMillis * 1_000_000;
            int displayNumber = number.get(readinessMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            while (xvfb.isAlive() && System.nanoTime() < deadline)
            {
                if (backend.acceptsConnections(displayNumber))
                {
                    Process client = backend.startClient(command, ":" + displayNumber);
                    transferred = true;
                    return new LinuxOwner(client, xvfb, ":" + displayNumber, commands);
                }
                Thread.sleep(50);
            }
            throw new IllegalStateException("The private Xvfb display did not accept connections; verify the xvfb package and X11 socket permissions.");
        }
        finally
        {
            number.cancel(true);
            if (!transferred) { ExternalProcess.destroyTree(xvfb); }
        }
    }

    static final class LinuxOwner implements OwnedClient
    {
        private final Process client;
        private final Process xvfb;
        private final String display;
        private final WindowCommands commands;
        private boolean closed;
        LinuxOwner(Process client, Process xvfb, String display, WindowCommands commands)
        {
            this.client = client; this.xvfb = xvfb; this.display = display; this.commands = commands;
        }
        @Override public long pid() { return client.pid(); }
        @Override public boolean alive() { return !closed && client.isAlive(); }
        @Override public int exitCode() { return client.exitValue(); }
        @Override public List<WindowController.Window> windows() throws Exception
        {
            commands.require("xwininfo", "x11-utils (or xwininfo), for isolated window listing");
            String output = commands.checked(List.of("xwininfo", "-display", display, "-root", "-children"));
            List<WindowController.Window> result = new ArrayList<>();
            java.util.regex.Matcher window = java.util.regex.Pattern.compile(
                "(?m)^\\s+(0x[0-9a-fA-F]+) (?:\"(.*)\"|\\(has no name\\)):\\s+\\((.*)\\).*$").matcher(output);
            while (window.find())
            {
                // This display is private to this launch, including its child-process modals.
                String state = commands.checked(List.of("xwininfo", "-display", display, "-id", window.group(1), "-stats"));
                if (!state.contains("Map State: IsViewable")) { continue; }
                result.add(new WindowController.Window(window.group(1), pid(), window.group(2) == null ? "" : window.group(2), window.group(3)));
            }
            return result;
        }
        @Override public synchronized void close()
        {
            if (closed) { return; }
            try
            {
                WindowCommands.Answer result = commands.run(List.of("kill", "-KILL", "--", "-" + client.pid()));
                if (result.code() != 0 && client.isAlive())
                {
                    throw new IllegalStateException("Cannot terminate the isolated client process group: " + result.error());
                }
            }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException("Cannot terminate the isolated client process group.", e); }
            finally
            {
                ExternalProcess.destroyTree(client);
                ExternalProcess.destroyTree(xvfb);
                closed = true;
            }
        }
    }
}

/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** WMI creates the GUI outside the client's process tree and Job Object. No system Java or PATH. */
public final class WindowsEdtStarter implements StartupCoordinator.Starter
{
    public static final class InstallationException extends IOException
    {
        private static final long serialVersionUID = 1L;
        private InstallationException(String message, Exception cause) { super(message, cause); }
    }

    private final Path install;
    private final Path workspace;
    private final Path configuration;
    private final boolean headless;
    public WindowsEdtStarter(Path install, Path workspace, Path configuration)
    { this(install, workspace, configuration, false); }

    public WindowsEdtStarter(Path install, Path workspace, Path configuration, boolean headless)
    { this.install = install; this.workspace = workspace; this.configuration = configuration; this.headless = headless; }

    public static void checkRuntime(Path install) throws IOException
    {
        if (Runtime.version().feature() < 17) { throw new IOException("EDT MCP requires Java 17 or newer; use the EDT launcher or -vm."); }
        if (!System.getProperty("os.name").startsWith("Windows")) { return; }
        int required = 17;
        Path ini = install.resolve("1cedt.ini");
        String version = null;
        try
        {
            for (String line : Files.readAllLines(ini))
            {
                if (line.startsWith("-Dosgi.requiredJavaVersion="))
                {
                    version = line.substring(line.indexOf('=') + 1);
                    required = Integer.parseInt(version.split("\\.", 2)[0]);
                }
            }
        }
        catch (IOException e) { throw new InstallationException("Cannot read " + ini + ".", e); }
        catch (NumberFormatException e)
        { throw new InstallationException("Invalid -Dosgi.requiredJavaVersion value '" + version + "' in " + ini + ".", e); }
        if (Runtime.version().feature() < required)
        { throw new IOException("This EDT install requires Java " + required + "; select it with the EDT launcher -vm option."); }
    }

    @Override public StartupCoordinator.Started start() throws Exception
    {
        checkRuntime(install);
        return createDetached(command(), install, headless);
    }

    public List<String> command()
    {
        List<String> command = new ArrayList<>(List.of(install.resolve(headless ? "1cedtc.exe" : "1cedt.exe").toString(),
            "--launcher.ini", install.resolve("1cedt.ini").toString(), "--launcher.suppressErrors", "-nosplash",
            "-vm", Path.of(System.getProperty("java.home"), "bin", headless ? "java.exe" : "javaw.exe").toString(),
            "-install", install.toString(), "-data", workspace.toString()));
        if (configuration != null) { command.addAll(List.of("-configuration", configuration.toString())); }
        if (headless) { command.addAll(List.of("-application", "com.ditrix.edt.mcp.server.stdioHost")); }
        // Explicit launch-only override. Never changes the user's HTTP auto-start preference.
        command.addAll(List.of("--launcher.appendVmargs", "-vmargs", "-Dedt.mcp.stdioOnly=true"));
        return List.copyOf(command);
    }

    @Override public String notReadyMessage()
    { return headless ? "Headless EDT is running but IPC is not ready. Ask the user to inspect the EDT workspace log."
        : StartupCoordinator.Starter.super.notReadyMessage(); }

    @Override public boolean supportsStartup() { return System.getProperty("os.name").startsWith("Windows"); }

    public static StartupCoordinator.Started createDetached(List<String> command, Path workingDirectory) throws Exception
    { return createDetached(command, workingDirectory, false); }

    private static StartupCoordinator.Started createDetached(List<String> command, Path workingDirectory, boolean headless) throws Exception
    {
        if (!System.getProperty("os.name").startsWith("Windows")) { throw new IOException(StartupCoordinator.OPEN_WORKSPACE); }
        String commandLine = command.stream().map(WindowsEdtStarter::quote).collect(java.util.stream.Collectors.joining(" "));
        String script = "$ErrorActionPreference='Stop';$s=([wmiclass]'Win32_ProcessStartup').CreateInstance();"
            + "$s.ShowWindow=" + (headless ? "0" : "1") + ";$s.CreateFlags=16777216;$r=([wmiclass]'Win32_Process').Create("
            + literal(commandLine) + "," + literal(workingDirectory.toString()) + ",$s);"
            + "if($r.ReturnValue -ne 0){throw ('WMI Create failed: '+$r.ReturnValue)};[Console]::Out.Write($r.ProcessId)";
        Path powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        Process provider = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
            "-WindowStyle", "Hidden", "-EncodedCommand", Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
            .redirectErrorStream(true).start();
        if (!provider.waitFor(15, TimeUnit.SECONDS))
        { provider.destroyForcibly(); throw new IOException(headless ? "WMI launch timed out; inspect the EDT workspace log before retrying"
                : "WMI launch timed out; inspect the EDT window before retrying"); }
        String result = new String(provider.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (provider.exitValue() != 0)
        {
            System.err.println("EDT MCP WMI launch: " + result);
            throw new IOException("Windows WMI refused to start EDT (exit " + provider.exitValue()
                + "). Ask the user to open this workspace in EDT and retry the tool call");
        }
        long pid;
        try { pid = Long.parseLong(result); }
        catch (NumberFormatException e) { throw new IOException("Invalid WMI launch response", e); }
        Instant start = ProcessHandle.of(pid).flatMap(p -> p.info().startInstant()).orElseThrow(
            () -> new IOException("The EDT launcher exited before its process identity could be recorded"));
        return new StartupCoordinator.Started(pid, start.toString());
    }

    static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    public static String quote(String value)
    {
        // Windows CommandLineToArgvW quoting, including trailing backslashes and quotes.
        StringBuilder out = new StringBuilder("\"");
        int slashes = 0;
        for (char c : value.toCharArray())
        {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') { out.append("\\".repeat(slashes * 2 + 1)).append(c); }
            else { out.append("\\".repeat(slashes)).append(c); }
            slashes = 0;
        }
        return out.append("\\".repeat(slashes * 2)).append('"').toString();
    }
}

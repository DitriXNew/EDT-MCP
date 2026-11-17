// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Function;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IProcess;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.ExternalInfobaseChangesPolicy;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchOverrides;
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com._1c.g5.v8.dt.debug.core.launchconfigurations.DtLaunch;
import com._1c.g5.v8.dt.debug.core.model.IDebugMonitoringManager;
import com._1c.g5.wiring.ServiceAccess;

/** Thin LaunchTool integration: dispatch normally, or prepare EDT then own an isolated process. */
public final class ClientWindowLaunch
{
    public record Execution(String error, Map<String, Object> fields)
    {
        public String decorate(String response)
        {
            if (fields.isEmpty()) { return response; }
            var json = JsonParser.parseString(response).getAsJsonObject();
            Gson gson = new Gson();
            fields.forEach((key, value) -> json.add(key, gson.toJsonTree(value)));
            return ToolResult.toJsonStatic(json);
        }
    }
    /** Dispatch result; window polling must run only after the operation lease is released. */
    public record Started(Execution result, Supplier<Execution> adjustment)
    {
        public Started(Execution result) { this(result, null); }

        public Execution finish() { return adjustment == null ? result : adjustment.get(); }
    }

    private ClientWindowLaunch() { }

    /** Refuse invalid screens and missing packages before an existing client can be terminated. */
    public static String preflight(WindowRequest request)
    {
        if (request.mode() == WindowRequest.Mode.NORMAL) { return null; }
        try
        {
            ClientPlatforms.Os os = ClientPlatforms.os(System.getProperty("os.name"));
            if (request.mode() == WindowRequest.Mode.ISOLATED)
            {
                ClientPlatforms.isolated(os, WindowCommands.system());
            }
            else
            {
                if (os == ClientPlatforms.Os.LINUX && "wayland".equals(System.getenv("XDG_SESSION_TYPE")))
                {
                    return "Window adjustment requires X11; use an X11 session or windowMode 'isolated' with xvfb on Wayland.";
                }
                WindowController controller = ClientPlatforms.controller(os, WindowCommands.system());
                if (request.mode() == WindowRequest.Mode.OTHER_SCREEN)
                {
                    WindowController.selectScreen(controller.screens(), request.screen());
                }
            }
        }
        catch (ClientPlatforms.UnsupportedHostException e)
        {
            return request.mode() == WindowRequest.Mode.ISOLATED ? e.getMessage() : null;
        }
        catch (IllegalArgumentException e) { return e.getMessage(); }
        catch (Exception | LinkageError e)
        {
            if (request.mode() == WindowRequest.Mode.ISOLATED) { return message(e); }
        }
        return null;
    }

    public static String configurationRefusal(ILaunchConfiguration config, WindowRequest request)
    {
        if (request.mode() == WindowRequest.Mode.NORMAL) { return null; }
        if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(config)))
        {
            return ToolResult.error("windowMode '" + request.mode().token()
                + "' requires a runtime-client configuration; Attach and standalone-server configurations do not start a client.").toJson();
        }
        return null;
    }

    public static Started start(ILaunchConfiguration config, WindowRequest request, String mode,
        boolean update, ExternalInfobaseChangesPolicy policy, StandaloneServerPortConflictPolicy portPolicy,
        LaunchOverrides.Prepared overrides,
        Function<ILaunchConfiguration, String> normalDispatch)
    {
        if (request.mode() == WindowRequest.Mode.NORMAL)
        {
            return new Started(new Execution(normalDispatch.apply(config), Map.of()));
        }
        ClientPlatforms.Os os;
        try { os = ClientPlatforms.os(System.getProperty("os.name")); }
        catch (IllegalArgumentException e)
        {
            if (request.mode() == WindowRequest.Mode.ISOLATED) { return new Started(error(e.getMessage())); }
            String failure = normalDispatch.apply(config);
            return new Started(new Execution(failure, fields(request, new WindowAdjustment.Outcome(false, e.getMessage()), null)));
        }
        if (request.mode() == WindowRequest.Mode.ISOLATED)
        {
            return new Started(isolated(config, request, mode, update, policy, portPolicy, overrides, os));
        }
        WindowController controller = null;
        WindowController.Screen screen = null;
        String unavailable = null;
        try
        {
            controller = ClientPlatforms.controller(os, WindowCommands.system());
            if (request.mode() == WindowRequest.Mode.OTHER_SCREEN)
            {
                screen = WindowController.selectScreen(controller.screens(), request.screen());
            }
        }
        catch (IllegalArgumentException e) { return new Started(error(e.getMessage())); }
        catch (Exception | LinkageError e) { unavailable = message(e); }

        var manager = LaunchConfigUtils.getLaunchManager();
        Set<ILaunch> previous = manager == null ? Set.of() : new HashSet<>(Arrays.asList(manager.getLaunches()));
        ClientWindowRegistry.Tracking tracking;
        try { tracking = ClientWindowRegistry.track(config, request); }
        catch (Exception e)
        {
            return new Started(new Execution(normalDispatch.apply(config), fields(request,
                new WindowAdjustment.Outcome(false, "Cannot track the window request: " + message(e)), null)));
        }
        String failure;
        try { failure = normalDispatch.apply(tracking.config()); }
        catch (RuntimeException | Error e) { tracking.cancel(); throw e; }
        if (failure != null) { tracking.cancel(); return new Started(new Execution(failure, Map.of())); }
        WindowController resolvedController = controller;
        WindowController.Screen resolvedScreen = screen;
        String adjustmentUnavailable = unavailable;
        return new Started(null, () -> {
            Supplier<ILaunch> launched = () -> findNewLaunch(tracking.config(), previous);
            WindowAdjustment.Outcome outcome = adjustmentUnavailable != null
                ? new WindowAdjustment.Outcome(false, adjustmentUnavailable)
                : WindowAdjustment.apply(resolvedController, request, resolvedScreen, () -> pids(launched.get()), 20_000);
            ILaunch launch = launched.get();
            Set<Long> ids = pids(launch);
            Long pid = ids.isEmpty() ? null : ids.iterator().next();
            tracking.finish(outcome, launch, pid);
            return new Execution(null, fields(request, outcome, pid));
        });
    }

    private static Execution isolated(ILaunchConfiguration config, WindowRequest request, String mode,
        boolean update, ExternalInfobaseChangesPolicy policy, StandaloneServerPortConflictPolicy portPolicy,
        LaunchOverrides.Prepared overrides, ClientPlatforms.Os os)
    {
        IsolatedClientLauncher.OwnedClient owner = null;
        OwnedClientProcess process = null;
        ILaunch launch = null;
        var manager = LaunchConfigUtils.getLaunchManager();
        try
        {
            // Resolve prerequisites before the update changes the infobase.
            IsolatedClientLauncher launcher = ClientPlatforms.isolated(os, WindowCommands.system());
            IDebugMonitoringManager monitoring = ServiceAccess.get(IDebugMonitoringManager.class);
            if (monitoring == null || manager == null)
            {
                return error("EDT's debug launch services are unavailable; enable the EDT debugger and retry.");
            }
            launch = ownedLaunch(config, mode, monitoring);
            DebugSessionRegistry.get().ensureListenerRegistered();
            manager.addLaunch(launch);
            InfobaseAuthDialogSuppressor.markActivityStart();
            EdtClientCommand.Command command;
            try { command = EdtClientPreparation.prepare(config, launch, update, policy, portPolicy, overrides); }
            finally { InfobaseAuthDialogSuppressor.markActivityEnd(); }
            owner = launcher.start(command.arguments());
            process = new OwnedClientProcess(owner);
            ClientWindowRegistry.owned(launch, request, owner, process);
            DebugPlugin.newProcess(launch, process, "1C client (isolated)", Map.of(IProcess.ATTR_PROCESS_ID, "" + owner.pid()));
            Map<String, Object> fields = fields(request, new WindowAdjustment.Outcome(true, owner.limitation()), owner.pid());
            fields.put("status", process.isAlive() ? "running" : "terminated");
            return new Execution(null, fields);
        }
        catch (Exception | LinkageError e)
        {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            String reason = "Could not launch the isolated client: " + message(e);
            try
            {
                if (process != null) { process.destroy(); }
                else if (owner != null) { owner.close(); }
            }
            catch (RuntimeException cleanup) { reason += ". Process cleanup failed: " + message(cleanup); }
            if (launch != null)
            {
                // An attach target may have existed before this call; failed client startup must not terminate it.
                for (var target : launch.getDebugTargets()) { launch.removeDebugTarget(target); }
                try { launch.terminate(); }
                catch (Exception cleanup) { reason += ". EDT lifecycle cleanup failed: " + message(cleanup); }
            }
            try { if (manager != null && launch != null) { manager.removeLaunch(launch); } }
            catch (RuntimeException cleanup) { reason += ". Launch cleanup failed: " + message(cleanup); }
            return error(reason);
        }
    }

    static ILaunch ownedLaunch(ILaunchConfiguration config, String mode, IDebugMonitoringManager monitoring)
    {
        return new DtLaunch(config, mode, "Client", monitoring);
    }

    private static Execution error(String message) { return new Execution(message, Map.of()); }
    private static String message(Throwable error)
    {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    private static Map<String, Object> fields(WindowRequest request, WindowAdjustment.Outcome outcome, Long pid)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowMode", request.mode().token());
        result.put("windowModeApplied", outcome.applied());
        if (outcome.reason() != null) { result.put("windowModeReason", outcome.reason()); }
        if (pid != null) { result.put("pid", pid); }
        return result;
    }

    private static ILaunch findNewLaunch(ILaunchConfiguration config, Set<ILaunch> previous)
    {
        var manager = LaunchConfigUtils.getLaunchManager();
        if (manager == null) { return null; }
        for (ILaunch launch : manager.getLaunches())
        {
            if (previous.contains(launch) || launch.getLaunchConfiguration() == null) { continue; }
            if (ClientWindowRegistry.sameRequest(config, launch.getLaunchConfiguration()))
            {
                return launch;
            }
        }
        return null;
    }

    static Set<Long> pids(ILaunch launch)
    {
        Set<Long> result = new java.util.LinkedHashSet<>();
        if (launch == null) { return result; }
        for (IProcess process : launch.getProcesses())
        {
            if (process.isTerminated()) { continue; }
            String value = process.getAttribute(IProcess.ATTR_PROCESS_ID);
            if (value == null || !value.matches("[0-9]+")) { continue; }
            long pid = Long.parseLong(value);
            result.add(pid);
            ProcessHandle.of(pid).ifPresent(handle -> handle.descendants().forEach(child -> result.add(child.pid())));
        }
        return result;
    }
}

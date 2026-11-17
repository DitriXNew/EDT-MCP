// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils.clientwindow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchesListener2;

import com.ditrix.edt.mcp.server.utils.ListenerDisposables;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/** Adds window information to Eclipse's launches; process ownership stays with each IProcess. */
public final class ClientWindowRegistry
{
    private static final Map<ILaunch, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final String REQUEST_ID = "com.ditrix.edt.mcp.clientWindowRequestId";
    private static final Map<String, Tracking> PENDING = new ConcurrentHashMap<>();
    private static boolean listening;
    private record Entry(WindowRequest request, WindowAdjustment.Outcome outcome, Long pid,
        IsolatedClientLauncher.OwnedClient owner, OwnedClientProcess process) { }
    private ClientWindowRegistry() { }

    private static synchronized void ensureListening()
    {
        if (listening) { return; }
        var plugin = DebugPlugin.getDefault();
        if (plugin == null) { return; }
        var manager = plugin.getLaunchManager();
        ILaunchesListener2 listener = new ILaunchesListener2()
        {
            @Override public void launchesRemoved(ILaunch[] launches)
            {
                for (ILaunch launch : launches)
                {
                    Entry entry = ENTRIES.remove(launch);
                    if (entry != null && entry.process() != null) { entry.process().destroy(); }
                }
            }
            @Override public void launchesAdded(ILaunch[] launches)
            {
                for (ILaunch launch : launches)
                {
                    ILaunchConfiguration config = launch.getLaunchConfiguration();
                    if (config == null) { continue; }
                    Tracking tracking = PENDING.remove(key(config));
                    if (tracking != null)
                    {
                        synchronized (tracking)
                        {
                            tracking.launch = launch;
                            adjusted(launch, tracking.request, tracking.outcome, null);
                        }
                    }
                }
            }
            @Override public void launchesChanged(ILaunch[] launches) { }
            @Override public void launchesTerminated(ILaunch[] launches) { }
        };
        manager.addLaunchListener(listener);
        listening = true;
        ListenerDisposables.register(() -> {
            manager.removeLaunchListener(listener);
            try { closeOwned(); }
            finally { synchronized (ClientWindowRegistry.class) { listening = false; } }
        });
    }

    private static String key(ILaunchConfiguration config)
    {
        if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(config))) { return ""; }
        return LaunchConfigUtils.readAttribute(config, REQUEST_ID, "");
    }

    static boolean sameRequest(ILaunchConfiguration expected, ILaunchConfiguration actual)
    {
        String request = key(expected);
        return !request.isEmpty() && request.equals(key(actual));
    }

    /** A late EDT launch still receives the honest deadline outcome after the call returns. */
    public static Tracking track(ILaunchConfiguration config, WindowRequest request) throws org.eclipse.core.runtime.CoreException
    {
        ensureListening();
        var copy = config.getWorkingCopy();
        String id = java.util.UUID.randomUUID().toString();
        copy.setAttribute(REQUEST_ID, id);
        Tracking tracking = new Tracking(id, request, copy);
        PENDING.put(tracking.key, tracking);
        return tracking;
    }

    public static final class Tracking
    {
        private final String key;
        private final WindowRequest request;
        private final ILaunchConfiguration config;
        private ILaunch launch;
        private WindowAdjustment.Outcome outcome = new WindowAdjustment.Outcome(false,
            "Waiting for EDT to start the client window.");
        private Tracking(String key, WindowRequest request, ILaunchConfiguration config)
        {
            this.key = key; this.request = request; this.config = config;
        }
        public ILaunchConfiguration config() { return config; }
        public synchronized void finish(WindowAdjustment.Outcome result, ILaunch observed, Long pid)
        {
            outcome = result;
            if (observed != null) { launch = observed; PENDING.remove(key, this); }
            if (launch != null) { adjusted(launch, request, outcome, pid); }
        }
        public void cancel() { PENDING.remove(key, this); }
    }

    public static void adjusted(ILaunch launch, WindowRequest request, WindowAdjustment.Outcome outcome, Long pid)
    {
        ensureListening();
        ENTRIES.put(launch, new Entry(request, outcome, pid, null, null));
    }

    public static void owned(ILaunch launch, WindowRequest request, IsolatedClientLauncher.OwnedClient owner,
        OwnedClientProcess process)
    {
        ensureListening();
        ListenerDisposables.register(process::destroy);
        ENTRIES.put(launch, new Entry(request, new WindowAdjustment.Outcome(true, owner.limitation()), owner.pid(), owner, process));
    }

    public static Map<String, Object> describe(ILaunch launch)
    {
        Entry entry = ENTRIES.get(launch);
        if (entry == null) { return Map.of(); }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("windowMode", entry.request().mode().token());
        result.put("windowModeApplied", entry.outcome().applied());
        if (entry.outcome().reason() != null) { result.put("windowModeReason", entry.outcome().reason()); }
        if (entry.pid() != null) { result.put("pid", entry.pid()); }
        if (entry.owner() != null)
        {
            result.put("state", entry.process().isAlive() ? "running" : "terminated");
            try
            {
                result.put("windows", entry.owner().windows().stream().map(window ->
                    Map.of("title", window.title(), "class", window.windowClass(), "pid", window.pid())).toList());
                result.put("windowListingAvailable", true);
            }
            catch (Exception e)
            {
                result.put("windowListingAvailable", false);
                result.put("windowListingReason", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        return result;
    }

    static void closeOwned()
    {
        RuntimeException failure = null;
        for (Entry entry : ENTRIES.values())
        {
            if (entry.process() == null) { continue; }
            try { entry.process().destroy(); }
            catch (RuntimeException e)
            {
                if (failure == null) { failure = e; }
                else { failure.addSuppressed(e); }
            }
        }
        ENTRIES.clear();
        PENDING.clear();
        if (failure != null) { throw failure; }
    }
}

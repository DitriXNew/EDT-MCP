/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;

import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * The start of a 1C client launch: between EDT registering the launch and the client starting, the launch has
 * neither a process nor a debug target, and EDT may spend minutes synchronizing the configuration with the
 * infobase. Interrupting that start (terminating or cancelling the launch) breaks the synchronization and leaves
 * an EDT error dialog, so the tools report the phase and refuse to interrupt it unless asked to.
 */
public final class StartingLaunches
{
    /** Where a live launch is in its life. */
    public enum Phase
    {
        /** A client process or debug target exists. */
        RUNNING("running"), //$NON-NLS-1$
        /** EDT is still preparing the launch (an infobase update, or a dialog it waits on). */
        STARTING("starting"), //$NON-NLS-1$
        /** EDT is loading the configuration into the infobase before starting the client. */
        SYNCHRONIZING("synchronizing"), //$NON-NLS-1$
        /** The launch delegate returned without a client: the start failed or was cancelled. */
        ABANDONED("abandoned"); //$NON-NLS-1$

        private final String token;

        Phase(String token)
        {
            this.token = token;
        }

        /** The value reported in tool responses. */
        public String token()
        {
            return token;
        }

        /** True while EDT is still starting the launch. */
        public boolean starting()
        {
            return this == STARTING || this == SYNCHRONIZING;
        }
    }

    /** How long a phase read may wait for the application's update state. */
    private static final long UPDATE_STATE_READ_MS = 300;

    /** Launches whose delegate returned inside this plugin's launch job. */
    private static final Set<ILaunch> DELEGATE_RETURNED =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private StartingLaunches()
    {
    }

    /** Records that the launch delegate of {@code launch} has returned. */
    public static void markDelegateReturned(ILaunch launch)
    {
        if (launch != null)
        {
            DELEGATE_RETURNED.add(launch);
        }
    }

    /** Whether the delegate of {@code launch} returned inside this plugin's launch job. */
    public static boolean delegateReturned(ILaunch launch)
    {
        return launch != null && DELEGATE_RETURNED.contains(launch);
    }

    /** Whether this plugin's launch job for the launch's configuration is still scheduled or running. */
    public static boolean startJobActive(ILaunch launch)
    {
        ILaunchConfiguration config = launch == null ? null : launch.getLaunchConfiguration();
        return config != null && Job.getJobManager().find(config).length > 0;
    }

    /** The phase of a live launch. */
    public static Phase phase(ILaunch launch)
    {
        return phase(launch, StartingLaunches::applicationBeingUpdated);
    }

    /**
     * The phase of a live launch, reading the synchronization signal through {@code beingUpdated}.
     *
     * @param launch the launch (not terminated)
     * @param beingUpdated whether the launch's application is being updated right now
     * @return the phase, never {@code null}
     */
    public static Phase phase(ILaunch launch, Predicate<ILaunch> beingUpdated)
    {
        if (!LaunchLifecycleUtils.emptyLaunch(launch))
        {
            return Phase.RUNNING;
        }
        if (delegateReturned(launch) && !startJobActive(launch))
        {
            return Phase.ABANDONED;
        }
        return beingUpdated.test(launch) ? Phase.SYNCHRONIZING : Phase.STARTING;
    }

    /** What an agent should do about a launch in {@code phase}; {@code null} for a running one. */
    public static String note(Phase phase)
    {
        switch (phase)
        {
            case SYNCHRONIZING:
                return "EDT is loading the configuration into the infobase before it starts the 1C client; " //$NON-NLS-1$
                    + "on a large configuration this takes minutes. Keep polling debug_status until the state " //$NON-NLS-1$
                    + "is running. Do not terminate_launch or relaunch with restartIfRunning=true meanwhile: " //$NON-NLS-1$
                    + "that interrupts the load and leaves an EDT error dialog."; //$NON-NLS-1$
            case STARTING:
                return "EDT has not started the 1C client yet (an infobase update, or an EDT dialog - check " //$NON-NLS-1$
                    + "edt_dialog action=list). Keep polling debug_status until the state is running; " //$NON-NLS-1$
                    + "terminating it now interrupts the start."; //$NON-NLS-1$
            case ABANDONED:
                return "EDT finished this launch without starting a 1C client (see recentLaunchFailures and " //$NON-NLS-1$
                    + "edt_dialog action=list). launch with restartIfRunning=true or terminate_launch removes it."; //$NON-NLS-1$
            default:
                return null;
        }
    }

    /** How cancelling a starting launch ended. */
    public enum CancelOutcome
    {
        /** The launch is gone. */
        REMOVED,
        /** The launch job did not stop within the wait. */
        JOB_STILL_RUNNING,
        /** The launch was not started by this plugin's launch job; it was left alone. */
        FOREIGN,
        /** A client the launch started did not confirm termination. */
        CLIENT_NOT_STOPPED
    }

    /**
     * Ends a launch that has no client yet: this plugin's launch job for its configuration is cancelled and
     * awaited (the delegate checks the monitor before starting the client, and Eclipse removes a cancelled
     * launch even when a client did start), a client started past that check is terminated, and the launch is
     * removed. An empty launch whose delegate has not returned in this plugin's job may still be starting
     * elsewhere and is left alone.
     *
     * @param launch the launch to end
     * @param applicationId the id its debug state is registered under, or {@code null}
     * @param waitMs how long to wait for the cancelled job
     * @return the outcome, never {@code null}
     */
    public static CancelOutcome cancel(ILaunch launch, String applicationId, long waitMs)
    {
        ILaunchConfiguration config = launch.getLaunchConfiguration();
        IJobManager jobs = Job.getJobManager();
        if (config != null && jobs.find(config).length > 0)
        {
            jobs.cancel(config);
            long deadline = System.nanoTime() + waitMs * 1_000_000L;
            while (jobs.find(config).length > 0)
            {
                if (System.nanoTime() >= deadline)
                {
                    return CancelOutcome.JOB_STILL_RUNNING;
                }
                try
                {
                    Thread.sleep(100);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return CancelOutcome.JOB_STILL_RUNNING;
                }
            }
        }
        if (!delegateReturned(launch))
        {
            return CancelOutcome.FOREIGN;
        }
        if (!LaunchLifecycleUtils.emptyLaunch(launch)
            && !LaunchLifecycleUtils.terminateExistingLaunchAndWait(launch, applicationId))
        {
            return CancelOutcome.CLIENT_NOT_STOPPED;
        }
        DebugPlugin debug = DebugPlugin.getDefault();
        if (debug != null)
        {
            debug.getLaunchManager().removeLaunch(launch);
        }
        if (applicationId != null && !applicationId.isEmpty())
        {
            DebugSessionRegistry.get().forgetApplication(applicationId);
        }
        return CancelOutcome.REMOVED;
    }

    /**
     * Whether the application the launch targets is being updated: its configuration's bound application, else
     * the project's default one, as EDT's launch delegate resolves it. Any read that does not answer in time
     * reads as "not being updated", so the phase degrades to {@link Phase#STARTING}.
     */
    static boolean applicationBeingUpdated(ILaunch launch)
    {
        ILaunchConfiguration config = launch == null ? null : launch.getLaunchConfiguration();
        Activator activator = Activator.getDefault();
        IApplicationManager manager = activator == null ? null : activator.getApplicationManager();
        if (config == null || manager == null)
        {
            return false;
        }
        String projectName = LaunchConfigUtils.readAttribute(config, LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty() || !ProjectContext.of(projectName).isOpen())
        {
            return false;
        }
        IProject project = ProjectContext.of(projectName).project();
        String bound = Objects.toString(
            LaunchConfigUtils.readAttribute(config, LaunchConfigUtils.ATTR_APPLICATION_ID, ""), ""); //$NON-NLS-1$ //$NON-NLS-2$
        try
        {
            var read = ApplicationSupport.readBounded("Read launch update state", projectName, //$NON-NLS-1$
                UPDATE_STATE_READ_MS, () -> {
                    var application = bound.isEmpty() ? manager.getDefaultApplication(project)
                        : manager.getApplication(project, bound);
                    return application.map(manager::getUpdateState).orElse(ApplicationUpdateState.UNKNOWN);
                });
            return read.concluded() && read.valueOrRethrow() == ApplicationUpdateState.BEING_UPDATED;
        }
        catch (Exception e) // A phase read only enriches a report; it never fails it.
        {
            return false;
        }
    }
}

/**
 * MCP Server for EDT
 * Copyright (C) 2025 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Shared helpers for the "prepare for a fresh launch" sequence used by tools
 * that spawn 1С clients via {@code workingCopy.launch()} (YAXUnit run/debug
 * tools, etc.).
 *
 * <p>The sequence is needed because EDT's launch delegate brings up its
 * interactive "Update database before launch?" dialog whenever the
 * configuration has unsynced changes, and that dialog blocks the MCP call
 * indefinitely — especially if a 1С client is already holding the infobase in
 * exclusive use, in which case even pressing OK fails on the lock.
 *
 * <p>By terminating any live launch of the target configuration first and
 * then running {@link IApplicationManager#update} programmatically, we leave
 * the IB in {@link ApplicationUpdateState#UPDATED} state — at which point the
 * launch delegate skips the dialog entirely.
 */
public final class LaunchLifecycleUtils
{
    /**
     * Per-{@code project + applicationId} locks that serialise the auto-chain
     * (terminate-and-update) across MCP tools. Without this, two concurrent
     * MCP calls targeting the same IB could race on {@code terminateAndWait}
     * and {@code appManager.update()}. Keys are interned tuples and never
     * removed — the set of (project, applicationId) pairs is finite in
     * practice (one per EDT launch configuration).
     */
    private static final ConcurrentMap<String, Object> KEY_LOCKS = new ConcurrentHashMap<>();

    /**
     * Set of {@link ILaunch} instances that any MCP tool currently owns via
     * the auto-chain. The auto-chain will refuse to terminate any launch in
     * this set — instead it returns an error suggesting the caller wait or
     * call {@code terminate_launch} explicitly. Both {@code run_yaxunit_tests}
     * and {@code debug_yaxunit_tests} register their spawned launches here so
     * concurrent calls to either tool against the same IB protect each other.
     *
     * <p>Identity-equals is intentional: Eclipse {@code Launch} (the default
     * {@link ILaunch} implementation) inherits {@code Object.equals}, and we
     * register exactly the instance returned by {@code workingCopy.launch()}.
     * Terminated entries are pruned lazily inside {@link #prepareForFreshLaunch}.
     */
    private static final Set<ILaunch> OWNED_LAUNCHES = ConcurrentHashMap.newKeySet();

    private LaunchLifecycleUtils()
    {
        // Utility class
    }

    /**
     * Registers a launch as owned by the auto-chain. After this call,
     * {@link #prepareForFreshLaunch} will refuse to terminate it — pass the
     * launch through {@code terminate_launch} explicitly to stop it.
     *
     * <p>Callers must invoke this with the exact {@link ILaunch} reference
     * returned by {@code workingCopy.launch()} so identity-equals lookups
     * inside the auto-chain work.
     */
    public static void registerOwnedLaunch(ILaunch launch)
    {
        if (launch != null)
        {
            OWNED_LAUNCHES.add(launch);
        }
    }

    /**
     * Removes a launch from the owned registry. Optional — terminated launches
     * are pruned automatically on the next {@link #prepareForFreshLaunch} call.
     */
    public static void unregisterOwnedLaunch(ILaunch launch)
    {
        if (launch != null)
        {
            OWNED_LAUNCHES.remove(launch);
        }
    }

    /**
     * Returns the monitor object used to serialise {@link #prepareForFreshLaunch}
     * for the given {@code project + applicationId} pair. Callers that also
     * need the same lock around their own pre-launch steps (e.g. updating a
     * working copy) can synchronise on the returned object.
     *
     * <p>The internal key uses a NUL ({@code \u0000}) separator because
     * Eclipse project names may contain spaces and other printable characters,
     * which would otherwise allow keys to collide (e.g. {@code project="My",
     * appId="Project x"} vs {@code project="My Project", appId="x"}).
     */
    public static Object lockFor(String projectName, String applicationId)
    {
        String key = (projectName != null ? projectName : "") //$NON-NLS-1$
            + "\u0000" + (applicationId != null ? applicationId : ""); //$NON-NLS-1$ //$NON-NLS-2$
        return KEY_LOCKS.computeIfAbsent(key, k -> new Object());
    }

    /**
     * Result of {@link #prepareForFreshLaunch}. Either {@code ok=true} (caller
     * may proceed with the launch) or {@code ok=false} with a populated
     * {@link #error} (caller must abort).
     */
    public static final class PreLaunchResult
    {
        private final boolean ok;
        private final int terminatedCount;
        private final String error;

        private PreLaunchResult(boolean ok, int terminatedCount, String error)
        {
            this.ok = ok;
            this.terminatedCount = terminatedCount;
            this.error = error;
        }

        public boolean isOk()
        {
            return ok;
        }

        public int getTerminatedCount()
        {
            return terminatedCount;
        }

        public String getError()
        {
            return error;
        }

        /**
         * Returns a single-line human-readable summary, suitable for inclusion in
         * a Markdown report or a JSON {@code preLaunch} field.
         */
        public String summary()
        {
            if (!ok)
            {
                return "failed: " + error; //$NON-NLS-1$
            }
            if (terminatedCount == 0)
            {
                return "no-op (no live launch held the lock; DB ready)"; //$NON-NLS-1$
            }
            return "terminated " + terminatedCount //$NON-NLS-1$
                + " live launch" + (terminatedCount == 1 ? "" : "es") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "; DB ready"; //$NON-NLS-1$
        }
    }

    /**
     * Polls until {@link ILaunch#isTerminated} or the timeout elapses.
     *
     * @return {@code true} if the launch is terminated after the call
     */
    public static boolean waitForTerminated(ILaunch launch, long maxMillis)
    {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline)
        {
            if (launch.isTerminated())
            {
                return true;
            }
            try
            {
                Thread.sleep(LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return launch.isTerminated();
            }
        }
        return launch.isTerminated();
    }

    /**
     * Politely terminates the given launch and waits up to {@code timeoutSeconds}
     * for it to complete. Does NOT escalate to {@code IProcess.terminate()};
     * callers that need force-kill semantics should use {@code terminate_launch}
     * directly.
     *
     * @return {@code true} if the launch is terminated after the call
     */
    public static boolean terminateAndWait(ILaunch launch, int timeoutSeconds)
    {
        if (launch == null)
        {
            return true;
        }
        if (launch.isTerminated())
        {
            return true;
        }
        try
        {
            launch.terminate();
        }
        catch (DebugException e)
        {
            Activator.logError("Error initiating launch termination", e); //$NON-NLS-1$
            return false;
        }
        return waitForTerminated(launch, Math.max(1, timeoutSeconds) * 1000L);
    }

    /**
     * Brings the given application's database to {@link ApplicationUpdateState#UPDATED}
     * if needed, using the same programmatic path as {@code update_database}.
     * Skips the work when the state is already {@code UPDATED} or {@code BEING_UPDATED}.
     *
     * <p>Returns {@code Optional.empty()} on success (or no-op); on failure,
     * returns a populated error message.
     */
    public static Optional<String> updateApplicationIfNeeded(IProject project, String applicationId,
            IApplicationManager appManager)
    {
        if (appManager == null)
        {
            return Optional.of("IApplicationManager service is not available"); //$NON-NLS-1$
        }
        if (project == null || !project.exists() || !project.isOpen())
        {
            return Optional.of("Project is not available: " //$NON-NLS-1$
                + (project != null ? project.getName() : "<null>")); //$NON-NLS-1$
        }
        try
        {
            Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
            if (!appOpt.isPresent())
            {
                return Optional.of("Application not found: " + applicationId); //$NON-NLS-1$
            }
            IApplication application = appOpt.get();
            ApplicationUpdateState state = appManager.getUpdateState(application);
            if (state == ApplicationUpdateState.UPDATED)
            {
                return Optional.empty();
            }
            if (state == ApplicationUpdateState.BEING_UPDATED)
            {
                // Another caller (or a UI gesture) is already updating this IB.
                // Wait for it to settle into UPDATED — if we proceeded to launch
                // now, the delegate could still hit a locked / half-updated IB.
                long maxMillis = getDefaultTerminateTimeoutSeconds() * 1000L;
                state = waitForUpdateSettled(appManager, application, maxMillis);
                if (state == ApplicationUpdateState.UPDATED)
                {
                    return Optional.empty();
                }
                return Optional.of("Another update is in progress and did not " //$NON-NLS-1$
                    + "settle within " + (maxMillis / 1000) + "s (final state: " //$NON-NLS-1$ //$NON-NLS-2$
                    + state + "). Retry shortly, or increase the " //$NON-NLS-1$
                    + "`terminate_launch.timeoutSeconds` preference if your IB " //$NON-NLS-1$
                    + "updates take longer.");
            }
            ExecutionContext context = new ExecutionContext();
            Shell shell = grabActiveShell();
            if (shell != null)
            {
                context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
            }
            Activator.logInfo("Pre-launch DB update: application=" + applicationId //$NON-NLS-1$
                + ", stateBefore=" + state); //$NON-NLS-1$
            ApplicationUpdateState after = appManager.update(application,
                ApplicationUpdateType.INCREMENTAL, context, new NullProgressMonitor());
            Activator.logInfo("Pre-launch DB update completed: stateAfter=" + after); //$NON-NLS-1$
            // Post-condition gate: the auto-chain promises the IB will be in
            // UPDATED state before workingCopy.launch() runs, otherwise the
            // launch delegate would still see "DB needs update" and pop its
            // modal dialog — which is exactly what we are trying to avoid.
            if (after == ApplicationUpdateState.UPDATED)
            {
                return Optional.empty();
            }
            if (after == ApplicationUpdateState.BEING_UPDATED)
            {
                // appManager.update() returned but the state machine still
                // reports an update in progress (async path). Wait for it.
                long maxMillis = getDefaultTerminateTimeoutSeconds() * 1000L;
                after = waitForUpdateSettled(appManager, application, maxMillis);
                if (after == ApplicationUpdateState.UPDATED)
                {
                    return Optional.empty();
                }
            }
            return Optional.of("Database update finished with state " + after //$NON-NLS-1$
                + " (expected UPDATED). Inspect the EDT problems view, " //$NON-NLS-1$
                + "or call `update_database` with `fullUpdate=true` / " //$NON-NLS-1$
                + "`autoRestructure=true` to handle restructurization, then retry."); //$NON-NLS-1$
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error during pre-launch DB update", e); //$NON-NLS-1$
            return Optional.of("Database update failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Polls {@link IApplicationManager#getUpdateState} until it leaves
     * {@link ApplicationUpdateState#BEING_UPDATED} or the deadline elapses.
     * Used when another caller is mid-update — we must not start a launch
     * against an IB whose update is still running.
     */
    private static ApplicationUpdateState waitForUpdateSettled(IApplicationManager appManager,
            IApplication application, long maxMillis)
    {
        long deadline = System.currentTimeMillis() + maxMillis;
        ApplicationUpdateState state = ApplicationUpdateState.BEING_UPDATED;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                state = appManager.getUpdateState(application);
            }
            catch (ApplicationException e)
            {
                Activator.logError("Error polling update state", e); //$NON-NLS-1$
                return state;
            }
            if (state != ApplicationUpdateState.BEING_UPDATED)
            {
                return state;
            }
            try
            {
                Thread.sleep(LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return state;
            }
        }
        return state;
    }

    /**
     * Auto-chain executed before {@code workingCopy.launch()} when the caller
     * wants to bypass EDT's interactive "Update database?" dialog:
     * <ol>
     *   <li>Find every live launch matching {@code project + applicationId};</li>
     *   <li>Politely terminate each and wait — aborts on timeout (the IB would
     *       still be locked, defeating the purpose);</li>
     *   <li>Run {@link #updateApplicationIfNeeded} to settle the IB in
     *       {@code UPDATED} state — the launch delegate then skips its dialog.</li>
     * </ol>
     *
     * <p>If any step fails, the result has {@code ok=false} and the caller must
     * abort instead of falling through to a launch that would hang on a modal.
     *
     * @param launchManager           Eclipse launch manager
     * @param project                 target project
     * @param applicationId           target {@code ATTR_APPLICATION_ID}
     * @param appManager              EDT application manager
     * @param terminateTimeoutSeconds polite-wait window per live launch
     */
    public static PreLaunchResult prepareForFreshLaunch(ILaunchManager launchManager,
            IProject project, String applicationId, IApplicationManager appManager,
            int terminateTimeoutSeconds)
    {
        if (launchManager == null)
        {
            return new PreLaunchResult(false, 0, "Launch manager is not available"); //$NON-NLS-1$
        }
        if (project == null)
        {
            return new PreLaunchResult(false, 0, "Project is null"); //$NON-NLS-1$
        }

        // Per-key lock prevents concurrent run_yaxunit_tests / debug_yaxunit_tests
        // calls targeting the same IB from racing on terminate + update. Other
        // (project, applicationId) pairs are unaffected. Java synchronized is
        // reentrant, so callers may already hold this monitor when wrapping
        // their full spawn+register sequence around the auto-chain.
        synchronized (lockFor(project.getName(), applicationId))
        {
            // Prune terminated entries so a stale registration cannot
            // permanently block future auto-chains.
            OWNED_LAUNCHES.removeIf(ILaunch::isTerminated);

            int terminated = 0;
            for (ILaunch live : LaunchConfigUtils.getAllLiveLaunches(launchManager,
                project.getName()))
            {
                if (!applicationId.equals(LaunchConfigUtils.getApplicationIdFor(live)))
                {
                    continue;
                }
                String name = live.getLaunchConfiguration() != null
                    ? live.getLaunchConfiguration().getName() : "<unknown>"; //$NON-NLS-1$
                // Identity-equals lookup against the OWNED registry — relies on
                // the invariant that callers pass the exact ILaunch instance
                // returned by workingCopy.launch() to registerOwnedLaunch.
                if (OWNED_LAUNCHES.contains(live))
                {
                    return new PreLaunchResult(false, terminated,
                        "Another test run is already in progress for this IB " //$NON-NLS-1$
                            + "(launch '" + name + "'). Wait for it to finish " //$NON-NLS-1$ //$NON-NLS-2$
                            + "(call this tool again later with the same arguments), " //$NON-NLS-1$
                            + "or call `terminate_launch` to stop it first."); //$NON-NLS-1$
                }
                boolean done = terminateAndWait(live, terminateTimeoutSeconds);
                if (!done)
                {
                    return new PreLaunchResult(false, terminated,
                        "Could not terminate previous launch '" + name //$NON-NLS-1$
                            + "' within " + terminateTimeoutSeconds //$NON-NLS-1$
                            + "s. Call `terminate_launch` with `force=true` to kill " //$NON-NLS-1$
                            + "the stuck process, then retry."); //$NON-NLS-1$
                }
                terminated++;
            }

            Optional<String> updateErr = updateApplicationIfNeeded(project, applicationId,
                appManager);
            if (updateErr.isPresent())
            {
                return new PreLaunchResult(false, terminated, updateErr.get());
            }
            return new PreLaunchResult(true, terminated, null);
        }
    }

    /**
     * Convenience: returns the configured default terminate timeout (in seconds)
     * shared with the {@code terminate_launch} tool, so the auto-chain honours
     * the same preference.
     */
    public static int getDefaultTerminateTimeoutSeconds()
    {
        return ToolParameterSettings.getInstance()
            .getParameterValue("terminate_launch", "timeoutSeconds", 10); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Shell grabActiveShell()
    {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed())
        {
            return null;
        }
        final Shell[] holder = new Shell[1];
        display.syncExec(() -> {
            holder[0] = display.getActiveShell();
            if (holder[0] == null)
            {
                Shell[] shells = display.getShells();
                if (shells.length > 0)
                {
                    holder[0] = shells[0];
                }
            }
        });
        return holder[0];
    }
}

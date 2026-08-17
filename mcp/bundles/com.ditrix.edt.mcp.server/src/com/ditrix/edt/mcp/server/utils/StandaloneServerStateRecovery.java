/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.swt.widgets.Shell;

import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Recovers the one standalone-server state EDT cannot recover from on its own: a server whose
 * WST state is still {@code STARTED} while the launch that owned it has ended.
 *
 * <h2>What goes wrong</h2>
 * EDT starts a standalone server only from the {@code STOPPED} state — its behaviour delegate
 * refuses anything else with the literal sentence this class matches. The state is set back to
 * {@code STOPPED} by a handler that runs when the {@code ibsrv} PROCESS is confirmed gone, and
 * that confirmation is bounded: EDT polls the process handle for a few seconds and, if the
 * process is still listed (a slow teardown, a loaded machine) or the waiting thread is
 * interrupted (a cancelled operation), it gives up WITHOUT running the handler. The launch,
 * meanwhile, reports itself terminated the moment the process object dies, and a WST server
 * hands out only a live launch — so from that point on:
 * <ul>
 *   <li>the server state says {@code STARTED};</li>
 *   <li>the server's launch is gone, so EDT's "it is already running, nothing to do" shortcut
 *       does not apply;</li>
 *   <li>every start attempt — a launch, or the publish inside a database update — reaches the
 *       delegate and is refused.</li>
 * </ul>
 * Nothing clears it by itself: EVERY subsequent launch/update of that application fails, with a
 * message ("Can only start server that is stopped but current server state is 2") that names an
 * internal state number and no action. Stopping the server explicitly is the documented way out,
 * and that is what {@link #stopStaleServer(IProject, String)} does through EDT's own API.
 *
 * <h2>Why a stop is safe here</h2>
 * The server being stopped is one EDT has already lost track of: it holds no live launch, so no
 * debug session or client is attached to it through EDT. The stop mutates no infobase data — it
 * only returns EDT's own bookkeeping to {@code STOPPED} so the operation the caller asked for can
 * proceed. A server process that outlived EDT's bookkeeping may still be holding the configured
 * ports; that surfaces as the ordinary port-conflict answer on the retry, which names the ports.
 */
public final class StandaloneServerStateRecovery
{
    /**
     * EDT's refusal, verbatim and NOT localized (it is a hardcoded {@code IllegalStateException}
     * message in the standalone-server behaviour delegate, not a message bundle entry), so
     * matching it is stable across EDT's UI languages.
     */
    private static final String REFUSAL_MARKER =
        "Can only start server that is stopped but current server state is"; //$NON-NLS-1$

    /**
     * How long the recovery stop may take before the caller stops waiting. EDT's stop terminates
     * whatever the launch still owns and waits for the process to disappear (its own wait is ~6
     * seconds), so a normal stop is far below this; the bound exists so a wedged platform call
     * cannot hold an unattended MCP request open.
     */
    private static final long STOP_TIMEOUT_MS = 60_000L;

    /**
     * The state whose refusal this class recovers from. Only a server EDT believes is RUNNING can
     * be stuck forever: {@code STARTING}/{@code STOPPING} are states a concurrent operation is
     * legitimately holding for a moment, and stopping a server somebody else is starting would
     * break that operation instead of this one.
     */
    private static final String RECOVERABLE_STATE = "STARTED"; //$NON-NLS-1$

    private StandaloneServerStateRecovery()
    {
        // Utility class
    }

    /**
     * EDT's refusal sentence when this failure IS a stale-state refusal.
     *
     * <p>The whole failure is searched, not just its headline: the refusal arrives wrapped in a
     * generic "An internal error occurred during: "Starting Standalone server for X"" status,
     * with the sentence itself in the {@code IllegalStateException} several hops down.
     *
     * @param failure the failure to inspect (may be {@code null})
     * @return the refusal message, or {@code null} when the failure is something else
     */
    public static String refusalMessage(Throwable failure)
    {
        return PlatformFailures.firstMessageMatching(failure,
            message -> message.contains(REFUSAL_MARKER));
    }

    /**
     * Whether a failure is EDT refusing to start a standalone server because its state is not
     * {@code STOPPED}.
     *
     * @param failure the failure to inspect (may be {@code null})
     * @return {@code true} for the stale-state refusal
     */
    public static boolean isStaleServerState(Throwable failure)
    {
        return refusalMessage(failure) != null;
    }

    /**
     * The server state EDT named in its refusal, translated to the WST name.
     *
     * @param refusal a refusal message from {@link #refusalMessage(Throwable)} (may be
     *     {@code null})
     * @return the state name, or {@code null} when the message carries no readable state
     */
    public static String refusedStateName(String refusal)
    {
        if (refusal == null)
        {
            return null;
        }
        int marker = refusal.indexOf(REFUSAL_MARKER);
        if (marker < 0)
        {
            return null;
        }
        int digits = 0;
        int index = marker + REFUSAL_MARKER.length();
        while (index < refusal.length() && refusal.charAt(index) == ' ')
        {
            index++;
        }
        int start = index;
        while (index < refusal.length() && Character.isDigit(refusal.charAt(index)))
        {
            index++;
            digits++;
        }
        if (digits == 0 || digits > 2)
        {
            return null;
        }
        return stateName(Integer.parseInt(refusal.substring(start, index)));
    }

    /**
     * The WST server-state name for a state number, as EDT prints it.
     *
     * @param state the numeric state ({@code org.eclipse.wst.server.core.IServer.STATE_*})
     * @return the name, or the number itself for a state WST does not define
     */
    static String stateName(int state)
    {
        switch (state)
        {
        case 0:
            return "UNKNOWN"; //$NON-NLS-1$
        case 1:
            return "STARTING"; //$NON-NLS-1$
        case 2:
            return "STARTED"; //$NON-NLS-1$
        case 3:
            return "STOPPING"; //$NON-NLS-1$
        case 4:
            return "STOPPED"; //$NON-NLS-1$
        default:
            return String.valueOf(state);
        }
    }

    /**
     * Starts a launch configuration, recovering ONCE from a standalone server EDT left in a stale
     * {@code STARTED} state.
     *
     * <p>The failing configuration is the caller's own launch, so the recovery is scoped exactly
     * to what the caller asked for: the server that refused belongs to the application this
     * configuration targets.
     *
     * @param config the launch configuration to start (never {@code null})
     * @param mode the launch mode
     * @param monitor the progress monitor (may be {@code null})
     * @return the started launch
     * @throws CoreException if the launch failed for any other reason, if the server could not be
     *     stopped, or if the retried launch failed too — in the latter two cases with an
     *     actionable message that names the state and the way out
     */
    public static ILaunch launchWithRecovery(ILaunchConfiguration config, String mode,
        IProgressMonitor monitor) throws CoreException
    {
        try
        {
            return config.launch(mode, monitor);
        }
        catch (CoreException | RuntimeException e)
        {
            String refusal = refusalMessage(e);
            if (refusal == null)
            {
                throw e;
            }
            return relaunchAfterStop(config, mode, monitor, e, refusal);
        }
    }

    /**
     * Stops the stale server and starts the launch again, once.
     *
     * @param config the launch configuration
     * @param mode the launch mode
     * @param monitor the progress monitor (may be {@code null})
     * @param failure the refused first attempt
     * @param refusal EDT's refusal message
     * @return the launch started by the retry
     * @throws CoreException when the server could not be stopped or the retry failed too
     */
    private static ILaunch relaunchAfterStop(ILaunchConfiguration config, String mode,
        IProgressMonitor monitor, Exception failure, String refusal) throws CoreException
    {
        String applicationId = null;
        IProject project = null;
        try
        {
            String projectName = config.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            applicationId = LaunchLifecycleUtils.resolveDelegateApplicationId(config, projectName);
            ProjectContext ctx = ProjectContext.of(projectName);
            project = ctx.isOpen() ? ctx.project() : null;
        }
        catch (CoreException e) // NOSONAR an unreadable config only costs the recovery, not the report
        {
            Activator.logError("Stale standalone server: cannot read the launch configuration", e); //$NON-NLS-1$
        }
        Recovery recovery = stopServerForRefusal(project, applicationId, refusal);
        if (!recovery.recovered())
        {
            throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                staleStateError(applicationId, refusal, recovery, null), failure));
        }
        try
        {
            return config.launch(mode, monitor);
        }
        catch (CoreException | RuntimeException retry)
        {
            throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                staleStateError(applicationId, refusal, recovery, PlatformFailures.describe(retry)),
                retry));
        }
    }

    /**
     * Runs EDT's application update, recovering ONCE from a standalone server left in a stale
     * {@code STARTED} state. A server application publishes THROUGH its server, so the update
     * starts it first and meets the same refusal a launch does.
     *
     * @param manager the application manager (never {@code null})
     * @param project the project owning the application
     * @param application the application to update
     * @param applicationId the application id (for the message)
     * @param updateType the update type
     * @param context the execution context
     * @param monitor the progress monitor
     * @return the update state EDT reported
     * @throws ApplicationException if the update failed for any other reason, if the server could
     *     not be stopped, or if the retried update failed too
     */
    public static ApplicationUpdateState updateWithRecovery(IApplicationManager manager, // NOSONAR every argument is EDT's own update signature plus what the recovery needs
        IProject project, IApplication application, String applicationId,
        ApplicationUpdateType updateType, ExecutionContext context, IProgressMonitor monitor)
    {
        try
        {
            return manager.update(application, updateType, context, monitor);
        }
        catch (RuntimeException e)
        {
            String refusal = refusalMessage(e);
            if (refusal == null)
            {
                throw e;
            }
            Recovery recovery = stopServerForRefusal(project, applicationId, refusal);
            if (!recovery.recovered())
            {
                throw new ApplicationException(
                    staleStateError(applicationId, refusal, recovery, null), e);
            }
            try
            {
                return manager.update(application, updateType, context, monitor);
            }
            catch (RuntimeException retry)
            {
                throw new ApplicationException(staleStateError(applicationId, refusal, recovery,
                    PlatformFailures.describe(retry)), retry);
            }
        }
    }

    /**
     * The recovery stop, but only for the one state that cannot resolve itself. A refusal naming
     * {@code STARTING}/{@code STOPPING} belongs to an operation still in flight — reported, never
     * interfered with.
     *
     * @param project the project owning the application (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @param refusal EDT's refusal message
     * @return the outcome, never {@code null}
     */
    private static Recovery stopServerForRefusal(IProject project, String applicationId,
        String refusal)
    {
        String state = refusedStateName(refusal);
        if (!RECOVERABLE_STATE.equals(state))
        {
            return Recovery.failed("the server is " //$NON-NLS-1$
                + (state == null ? "in a state EDT did not name" : state) //$NON-NLS-1$
                + ", which another start or stop is holding right now"); //$NON-NLS-1$
        }
        return stopStaleServer(project, applicationId);
    }

    /**
     * Stops the standalone server of an application through EDT's own application lifecycle
     * ({@code IApplicationManager.cleanup}, which for a server application stops its server),
     * returning its bookkeeping to {@code STOPPED}.
     *
     * <p>Bounded by a background job: a platform stop that never returns must not hold an
     * unattended MCP request open.
     *
     * @param project the project owning the application (may be {@code null})
     * @param applicationId the application id, e.g. {@code ServerApplication.<name>} (may be
     *     {@code null})
     * @return the outcome, never {@code null}
     */
    public static Recovery stopStaleServer(IProject project, String applicationId)
    {
        if (project == null || applicationId == null)
        {
            return Recovery.failed("the project or application id is unknown"); //$NON-NLS-1$
        }
        Activator activator = Activator.getDefault();
        IApplicationManager manager = activator == null ? null : activator.getApplicationManager();
        if (manager == null)
        {
            return Recovery.failed("the EDT application manager is not available"); //$NON-NLS-1$
        }
        IApplication application;
        try
        {
            application = manager.getApplication(project, applicationId).orElse(null);
        }
        catch (Exception e) // NOSONAR the recovery reports every failure, it never adds one
        {
            Activator.logError("Stale standalone server: cannot resolve application " //$NON-NLS-1$
                + applicationId, e);
            return Recovery.failed("the application could not be resolved: " //$NON-NLS-1$
                + PlatformFailures.describe(e));
        }
        if (application == null)
        {
            return Recovery.failed("application '" + applicationId //$NON-NLS-1$
                + "' was not found in project " + project.getName()); //$NON-NLS-1$
        }
        return runStop(manager, application, applicationId);
    }

    /**
     * Runs the bounded stop and classifies its outcome.
     *
     * @param manager the application manager
     * @param application the application whose server is stopped
     * @param applicationId the application id (for the job name and the log)
     * @return the outcome, never {@code null}
     */
    private static Recovery runStop(IApplicationManager manager, IApplication application,
        String applicationId)
    {
        ExecutionContext context = new ExecutionContext();
        Shell shell = LaunchLifecycleUtils.grabActiveShell();
        if (shell != null)
        {
            context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
        }
        Activator.logInfo("Stale standalone server: stopping it so the operation can proceed: " //$NON-NLS-1$
            + applicationId);
        BoundedJob.Result result = BoundedJob.run("Stopping standalone server: " + applicationId, //$NON-NLS-1$
            STOP_TIMEOUT_MS, monitor -> manager.cleanup(application, context, monitor));
        if (result.isSuccess())
        {
            Activator.logInfo("Stale standalone server: stopped: " + applicationId); //$NON-NLS-1$
            return Recovery.stopped();
        }
        if (result.getFailure() != null)
        {
            Activator.logError("Stale standalone server: stopping it failed: " + applicationId, //$NON-NLS-1$
                result.getFailure());
            return Recovery.failed("stopping it failed: " //$NON-NLS-1$
                + PlatformFailures.describe(result.getFailure()));
        }
        Activator.logError("Stale standalone server: stopping it did not finish (" //$NON-NLS-1$
            + result.getOutcome() + "): " + applicationId, null); //$NON-NLS-1$
        return Recovery.failed("stopping it did not finish within " //$NON-NLS-1$
            + (STOP_TIMEOUT_MS / 1000) + "s"); //$NON-NLS-1$
    }

    /**
     * The message reported when the operation could not be completed despite the recovery: what
     * EDT refused, what was done about it, and what the caller can do next.
     *
     * @param applicationId the application the server belongs to
     * @param refusal EDT's refusal message (may be {@code null})
     * @param recovery the outcome of the recovery stop (never {@code null})
     * @param retryFailure the failure of the retried operation, or {@code null} when the
     *     recovery itself failed and nothing was retried
     * @return the actionable message
     */
    public static String staleStateError(String applicationId, String refusal, Recovery recovery,
        String retryFailure)
    {
        String state = refusedStateName(refusal);
        StringBuilder message = new StringBuilder();
        message.append("EDT refused to start the standalone server of application ") //$NON-NLS-1$
            .append(applicationId == null ? "this launch targets" : "'" + applicationId + "'") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .append(": it starts only a STOPPED server, and this one is ") //$NON-NLS-1$
            .append(state == null ? "in another state" : state) //$NON-NLS-1$
            .append('.');
        if (!RECOVERABLE_STATE.equals(state))
        {
            // Not the stuck case: a start or stop of that server is genuinely in flight, and
            // stopping it from here would break THAT operation rather than fix this one.
            return message.append(" Another start or stop of that server is in flight - retry ") //$NON-NLS-1$
                .append("once it settles; if it never does, stop the server in EDT (Servers ") //$NON-NLS-1$
                .append("view) or restart EDT.") //$NON-NLS-1$
                .toString();
        }
        message.append(" That state outlives the launch that owned it whenever EDT cannot ") //$NON-NLS-1$
            .append("confirm in time that the server process died, and nothing clears it by ") //$NON-NLS-1$
            .append("itself."); //$NON-NLS-1$
        if (recovery.recovered())
        {
            message.append(" The server was stopped and the operation retried once, which failed too: ") //$NON-NLS-1$
                .append(retryFailure == null ? "no reason reported" : retryFailure) //$NON-NLS-1$
                .append(". A server process left over from the previous run may still be holding ") //$NON-NLS-1$
                .append("the configured ports - stop it, or restart EDT, and retry."); //$NON-NLS-1$
        }
        else
        {
            message.append(" Stopping it automatically was not possible (") //$NON-NLS-1$
                .append(recovery.detail())
                .append("). Stop the standalone server in EDT (Servers view) or restart EDT, ") //$NON-NLS-1$
                .append("then retry."); //$NON-NLS-1$
        }
        return message.toString();
    }

    /**
     * The outcome of the recovery stop: whether EDT's server bookkeeping was returned to
     * {@code STOPPED}, and — when it was not — why not.
     */
    public static final class Recovery
    {
        private final boolean recovered;
        private final String detail;

        private Recovery(boolean recovered, String detail)
        {
            this.recovered = recovered;
            this.detail = detail;
        }

        /**
         * @return a successful recovery
         */
        static Recovery stopped()
        {
            return new Recovery(true, null);
        }

        /**
         * @param detail why the stop did not happen, as a sentence fragment
         * @return a failed recovery
         */
        static Recovery failed(String detail)
        {
            return new Recovery(false, detail);
        }

        /** @return {@code true} when the server was stopped and the operation may be retried */
        public boolean recovered()
        {
            return recovered;
        }

        /** @return why the stop did not happen, or {@code null} when it did */
        public String detail()
        {
            return detail;
        }
    }
}

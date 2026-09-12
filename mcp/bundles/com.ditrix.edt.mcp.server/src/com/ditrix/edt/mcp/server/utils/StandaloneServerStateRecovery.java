/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
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
 *
 * <h2>Two moments, one repair</h2>
 * The same stop is applied at two moments, and both are needed:
 * <ul>
 *   <li>{@link #ensureStartable(IProject, IApplication, String)} runs BEFORE the operation and
 *       is what keeps the failure from happening at all — including the workbench-log stack
 *       trace EDT writes even when the retry succeeds (the refusal escapes a WST job, and the
 *       job framework logs it);</li>
 *   <li>{@link #launchWithRecovery} / {@link #updateWithRecovery} keep the reactive repair for
 *       what a pre-flight cannot see: a state that goes stale between the check and the start,
 *       and any refusal reaching a path the pre-flight could not resolve.</li>
 * </ul>
 */
public final class StandaloneServerStateRecovery
{
    private static final int ABANDONED_LAUNCH_STATUS_CODE = 0x4C4143;

    /**
     * EDT's refusal, verbatim and NOT localized (it is a hardcoded {@code IllegalStateException}
     * message in the standalone-server behaviour delegate, not a message bundle entry), so
     * matching it is stable across EDT's UI languages.
     */
    private static final String REFUSAL_MARKER =
        "Can only start server that is stopped but current server state is"; //$NON-NLS-1$

    /**
     * The state whose refusal this class recovers from. Only a server EDT believes is RUNNING can
     * be stuck forever: {@code STARTING}/{@code STOPPING} are states a concurrent operation is
     * legitimately holding for a moment, and stopping a server somebody else is starting would
     * break that operation instead of this one.
     */
    private static final String RECOVERABLE_STATE = "STARTED"; //$NON-NLS-1$

    /**
     * WST server states, as {@code org.eclipse.wst.server.core.IServer} numbers them. Mirrored
     * as constants rather than imported: the plugin carries no dependency on the WST server
     * bundles (see {@link StandaloneServerSupport}), so the state is read reflectively and
     * compared against these.
     */
    private static final int STATE_STARTING = 1;

    /** @see #STATE_STARTING */
    private static final int STATE_STARTED = 2;

    /** @see #STATE_STARTING */
    private static final int STATE_STOPPING = 3;

    /** @see #STATE_STARTING */
    private static final int STATE_STOPPED = 4;

    /**
     * How long the pre-flight waits for a server another operation is starting or stopping right
     * now. Comfortably above a normal {@code ibsrv} start, and bounded because an unattended MCP
     * request must not be held open by somebody else's operation.
     */
    private static final long SETTLE_TIMEOUT_MS = 30_000L;

    /** How often the settle wait re-reads the server state. */
    private static final long SETTLE_POLL_MS = 250L;

    /**
     * Guards that serialize stale-server recovery actions, one per project+application.
     *
     * <p>Deliberately NOT {@link LaunchLifecycleUtils#lockFor}: that monitor is held across a
     * whole {@code update_database} publish, and waiting on it inside a bounded caller (the
     * {@code build_external_objects} job has a deadline, and a thread parked in
     * {@code synchronized} cannot be cancelled) would trade one hang for another. This lock is
     * acquired with a deadline and held only across "re-read the state, then stop or restore";
     * both actions are bounded.
     *
     * <p>What the long lock would have bought is bought by the RE-READ instead: an operation that
     * holds the application (an update publishing through the server, a launch that owns it)
     * leaves the server either STARTING/STOPPING or STARTED-with-a-live-launch, and neither is a
     * state this stop acts on. The only state it does act on - STARTED with the launch gone - is
     * by construction one that nobody owns.
     */
    private static final Map<String, RecoveryGuard> STOP_LOCKS = new ConcurrentHashMap<>();

    /** The server a successful recovery stop changes during the current operation. */
    private static final ThreadLocal<OperationStop> OPERATION_STOP = new ThreadLocal<>();

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
        return launchWithRecovery(config, mode, monitor,
            () -> "Launch of '" + config.getName() + "' was abandoned by EDT " //$NON-NLS-1$ //$NON-NLS-2$
                + "(the launch delegate cancelled it); no reason was logged. " //$NON-NLS-1$
                + "Check the EDT error log."); //$NON-NLS-1$
    }

    /** Starts a launch and classifies abandonment while its stopped server can still be restored. */
    public static ILaunch launchWithRecovery(ILaunchConfiguration config, String mode,
        IProgressMonitor monitor, Supplier<String> abandonedMessage) throws CoreException
    {
        Target target = resolveTarget(config);
        beginOperation();
        try
        {
            try
            {
                ensureStartable(target.project, null, target.applicationId);
            }
            catch (ApplicationException abort)
            {
                // The launch path reports the pre-flight refusal in the same CoreException shape.
                throw new CoreException(
                    new Status(IStatus.ERROR, Activator.PLUGIN_ID, abort.getMessage(), abort));
            }
            try
            {
                return launchOnce(config, mode, monitor, abandonedMessage);
            }
            catch (CoreException | RuntimeException e)
            {
                if (e instanceof CoreException && isAbandonedLaunch((CoreException)e))
                {
                    throw e;
                }
                String refusal = refusalMessage(e);
                if (refusal == null)
                {
                    throw e;
                }
                return relaunchAfterStop(config, mode, monitor, abandonedMessage, e, refusal,
                    target);
            }
        }
        catch (CoreException failure)
        {
            String restored = appendRestoration(PlatformFailures.describe(failure), target.project);
            if (restored == null)
            {
                throw failure;
            }
            throw new CoreException(failureStatus(restored, failure));
        }
        catch (RuntimeException failure)
        {
            String restored = appendRestoration(PlatformFailures.describe(failure), target.project);
            if (restored == null)
            {
                throw failure;
            }
            throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, restored,
                failure));
        }
        finally
        {
            endOperation();
        }
    }

    /** Runs one synchronous delegate call and rejects only a cancellation attributable to it. */
    private static ILaunch launchOnce(ILaunchConfiguration config, String mode,
        IProgressMonitor monitor, Supplier<String> abandonedMessage) throws CoreException
    {
        AttributableCancel attributable = new AttributableCancel(monitor);
        ILaunch launch = config.launch(mode, attributable);
        if (attributable.wasCanceledByLaunchingThread())
        {
            String message = abandonedMessage == null ? null : abandonedMessage.get();
            if (message == null || message.isEmpty())
            {
                message = "The launch delegate cancelled the launch."; //$NON-NLS-1$
            }
            throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                ABANDONED_LAUNCH_STATUS_CODE, message, null));
        }
        return launch;
    }

    /**
     * Stops the stale server and starts the launch again, once.
     *
     * @param config the launch configuration
     * @param mode the launch mode
     * @param monitor the progress monitor (may be {@code null})
     * @param failure the refused first attempt
     * @param refusal EDT's refusal message
     * @param target the launch's project and application id, resolved once by
     *     {@link #resolveTarget(ILaunchConfiguration)} before the first attempt
     * @return the launch started by the retry
     * @throws CoreException when the server could not be stopped or the retry failed too
     */
    private static ILaunch relaunchAfterStop(ILaunchConfiguration config, String mode,
        IProgressMonitor monitor, Supplier<String> abandonedMessage, Exception failure,
        String refusal, Target target)
        throws CoreException
    {
        String applicationId = target.applicationId;
        IProject project = target.project;
        Recovery recovery = stopServerForRefusal(project, null, applicationId,
            applicationManager(), refusal);
        if (!recovery.recovered())
        {
            throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                staleStateError(applicationId, refusal, recovery, null), failure));
        }
        try
        {
            return launchOnce(config, mode, monitor, abandonedMessage);
        }
        catch (CoreException | RuntimeException retry)
        {
            String message = staleStateError(applicationId, refusal, recovery,
                PlatformFailures.describe(retry));
            throw new CoreException(failureStatus(message, retry));
        }
    }

    /** Whether a failure represents a normal delegate return after its own cancellation. */
    public static boolean isAbandonedLaunch(CoreException failure)
    {
        return failure != null && failure.getStatus() != null
            && Activator.PLUGIN_ID.equals(failure.getStatus().getPlugin())
            && failure.getStatus().getCode() == ABANDONED_LAUNCH_STATUS_CODE;
    }

    /** Preserves the abandonment classification while adding recovery detail. */
    private static IStatus failureStatus(String message, Throwable failure)
    {
        int code = failure instanceof CoreException && isAbandonedLaunch((CoreException)failure)
            ? ABANDONED_LAUNCH_STATUS_CODE : 0;
        return new Status(IStatus.ERROR, Activator.PLUGIN_ID, code, message, failure);
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
        beginOperation();
        try
        {
            ensureStartable(project, application, applicationId, manager);
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
                Recovery recovery = stopServerForRefusal(project, application, applicationId,
                    manager, refusal);
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
        catch (RuntimeException failure)
        {
            String restored = appendRestoration(PlatformFailures.describe(failure), project);
            if (restored == null)
            {
                throw failure;
            }
            throw new ApplicationException(restored, failure);
        }
        finally
        {
            endOperation();
        }
    }

    /**
     * The recovery stop, but only for the one state that cannot resolve itself. A refusal naming
     * {@code STARTING}/{@code STOPPING} belongs to an operation still in flight — reported, never
     * interfered with.
     *
     * @param project the project owning the application (may be {@code null})
     * @param application the application when already resolved (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @param manager the application manager (may be {@code null})
     * @param refusal EDT's refusal message
     * @return the outcome, never {@code null}
     */
    private static Recovery stopServerForRefusal(IProject project, IApplication application,
        String applicationId, IApplicationManager manager, String refusal)
    {
        String state = refusedStateName(refusal);
        if (!RECOVERABLE_STATE.equals(state))
        {
            return Recovery.failed("the server is " //$NON-NLS-1$
                + (state == null ? "in a state EDT did not name" : state) //$NON-NLS-1$
                + ", which another start or stop is holding right now"); //$NON-NLS-1$
        }
        // The SAME guarded stop the pre-flight uses. The refusal proves the server was stale when
        // EDT answered, not that it still is: two operations refused at the same moment would
        // otherwise both stop it, and the second would stop the server the first had already
        // recovered and started.
        return stopStaleServerGuarded(project, application, applicationId, manager);
    }

    /**
     * Stops a stale standalone server, having re-established UNDER A LOCK that it is still stale.
     *
     * <p>The decision and the stop must not be separated by a window in which somebody revives the
     * server, or the stop lands on a HEALTHY one: two operations can reach this point together
     * (both pre-flighted the same stale server, or both were refused by EDT), the first stops it,
     * starts a fresh one, and the second would stop THAT. Serializing the stop and re-reading the
     * state inside the lock closes it - the second sees a live launch and does nothing.
     *
     * @param project the project owning the application (may be {@code null})
     * @param application the application when already resolved (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @param manager the application manager (may be {@code null})
     * <p>Nothing is stopped on state that cannot be re-read: a server that will not resolve gives
     * no evidence that stopping it is right NOW, and the refusal (or the earlier read) that sent
     * us here describes a moment that has passed.
     *
     * @return the outcome, never {@code null}; {@link Recovery#recovered()} is also true when the
     *     server stopped being stale on its own, because the caller's next step - proceed, or
     *     retry what EDT refused - is then exactly the same
     */
    private static Recovery stopStaleServerGuarded(IProject project, IApplication application,
        String applicationId, IApplicationManager manager)
    {
        return stopStaleServerGuarded(project, application, applicationId, manager,
            StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS);
    }

    /** Same guarded stale stop constrained by the caller's remaining operation deadline. */
    private static Recovery stopStaleServerGuarded(IProject project, IApplication application,
        String applicationId, IApplicationManager manager, long timeoutMs)
    {
        if (project == null || applicationId == null)
        {
            return Recovery.failed("the project or application id is unknown"); //$NON-NLS-1$
        }
        if (manager == null)
        {
            return Recovery.failed("the EDT application manager is not available"); //$NON-NLS-1$
        }
        long deadline = recoveryDeadline(timeoutMs);
        RecoveryGuard guard = stopLockFor(project, applicationId);
        if (!tryRecoveryLock(guard, timeoutMs, null))
        {
            return Recovery.failedInFlight(recoveryLockFailure());
        }
        try
        {
            if (guard.startClaim.get() != null)
            {
                return Recovery.failed("another standalone start currently claims the server"); //$NON-NLS-1$
            }
            IApplication resolvedApplication = application;
            if (resolvedApplication == null)
            {
                long remainingMs = remainingRecoveryTimeMs(deadline);
                if (remainingMs <= 0L)
                {
                    return Recovery.failed(
                        "the operation deadline elapsed before the application lookup began"); //$NON-NLS-1$
                }
                StandaloneServerSupport.ApplicationLookup lookup =
                    StandaloneServerSupport.lookupApplicationBounded(manager, project,
                        applicationId, remainingMs);
                if (lookup.failure() != null)
                {
                    return Recovery.failed(lookup.failure());
                }
                resolvedApplication = lookup.application();
            }
            Object server = resolveServer(resolvedApplication);
            if (server == null)
            {
                // No server to read means no evidence that stopping is the right thing to do NOW.
                // The refusal that sent us here (or the state the pre-flight read) is a statement
                // about a moment that has passed, and somebody else may have recovered and
                // restarted the server since. Stopping on unverifiable state would take theirs
                // down, so this reports instead - the caller already turns it into a message that
                // names the manual way out.
                Activator.logError("Standalone server: its state could not be re-read, so it was " //$NON-NLS-1$
                    + "NOT stopped: " + applicationId, null); //$NON-NLS-1$
                return Recovery.failed("its server could not be resolved, so stopping it would " //$NON-NLS-1$
                    + "have acted on a state nothing could confirm"); //$NON-NLS-1$
            }
            if (decide(serverState(server), hasLiveLaunch(server)) != Preflight.STOP_STALE)
            {
                Activator.logInfo("Standalone server: it is no longer stale (somebody else " //$NON-NLS-1$
                    + "recovered it); leaving it alone: " + applicationId); //$NON-NLS-1$
                return Recovery.stopped();
            }
            long remainingMs = remainingRecoveryTimeMs(deadline);
            if (remainingMs <= 0L)
            {
                return Recovery.failed("the operation deadline elapsed before the stop began"); //$NON-NLS-1$
            }
            return runStop(manager, resolvedApplication, applicationId, remainingMs);
        }
        finally
        {
            guard.lock.unlock();
        }
    }

    /** Same locked recheck, with cleanup executed by the caller's enclosing bounded Job. */
    private static Recovery stopStaleServerGuardedWithinBound(IProject project,
        IApplication application, String applicationId, IApplicationManager manager,
        IProgressMonitor monitor, Runnable stopStarting, Runnable stopCompleted)
    {
        if (project == null || application == null || applicationId == null || manager == null)
        {
            return Recovery.failed("the stale-server target is incomplete"); //$NON-NLS-1$
        }
        RecoveryGuard guard = stopLockFor(project, applicationId);
        if (!tryRecoveryLock(guard, StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS, monitor))
        {
            return Recovery.failedInFlight(recoveryLockFailure());
        }
        try
        {
            if (guard.startClaim.get() != null)
            {
                return Recovery.failed("another standalone start currently claims the server"); //$NON-NLS-1$
            }
            Object server = resolveServer(application);
            if (server == null)
            {
                Activator.logError("Standalone server: its state could not be re-read, so it was " //$NON-NLS-1$
                    + "NOT stopped: " + applicationId, null); //$NON-NLS-1$
                return Recovery.failed("its server could not be resolved, so stopping it would " //$NON-NLS-1$
                    + "have acted on a state nothing could confirm"); //$NON-NLS-1$
            }
            if (decide(serverState(server), hasLiveLaunch(server)) != Preflight.STOP_STALE)
            {
                Activator.logInfo("Standalone server: it is no longer stale (somebody else " //$NON-NLS-1$
                    + "recovered it); leaving it alone: " + applicationId); //$NON-NLS-1$
                return Recovery.stopped();
            }
            if (monitor != null && monitor.isCanceled())
            {
                return Recovery.failed(
                    "the bounded pre-flight was cancelled before the stop began"); //$NON-NLS-1$
            }
            if (stopStarting != null)
            {
                stopStarting.run();
            }
            return runStopWithinBound(manager, application, applicationId, monitor, stopCompleted);
        }
        finally
        {
            guard.lock.unlock();
        }
    }

    /** Returns EDT's application manager when its plugin is available. */
    private static IApplicationManager applicationManager()
    {
        Activator activator = Activator.getDefault();
        return activator == null ? null : activator.getApplicationManager();
    }

    /**
     * The lock and start claim serializing recovery for one application.
     *
     * @param project the project owning the application (never {@code null})
     * @param applicationId the application id (never {@code null})
     * @return the guard, never {@code null}
     */
    private static RecoveryGuard stopLockFor(IProject project, String applicationId)
    {
        // NUL separator for the same reason LaunchLifecycleUtils.lockFor uses one: project names
        // and application ids both contain spaces, so any printable separator can collide.
        return STOP_LOCKS.computeIfAbsent(project.getName() + "\u0000" + applicationId, //$NON-NLS-1$
            k -> new RecoveryGuard());
    }

    /** Acquires one recovery lock without allowing platform work to block the caller forever. */
    private static boolean tryRecoveryLock(RecoveryGuard guard, long timeoutMs,
        IProgressMonitor monitor)
    {
        long remainingNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        long deadline = System.nanoTime() + remainingNanos;
        do
        {
            if (monitor != null && monitor.isCanceled())
            {
                return false;
            }
            long waitNanos = Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(100L));
            try
            {
                if (guard.lock.tryLock(waitNanos, TimeUnit.NANOSECONDS))
                {
                    return true;
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
            remainingNanos = deadline - System.nanoTime();
        }
        while (remainingNanos > 0L);
        return false;
    }

    /** Monotonic deadline shared by the guarded steps of one recovery operation. */
    private static long recoveryDeadline(long timeoutMs)
    {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
    }

    /** Whole milliseconds still available to bounded work under a recovery deadline. */
    private static long remainingRecoveryTimeMs(long deadline)
    {
        long remainingNanos = deadline - System.nanoTime();
        return remainingNanos <= 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(remainingNanos);
    }

    /** Shared diagnosis for a recovery guard that could not be acquired by its deadline. */
    private static String recoveryLockFailure()
    {
        return "its recovery guard did not become available before the deadline, so the server's " //$NON-NLS-1$
            + "ownership could not be confirmed"; //$NON-NLS-1$
    }

    /** Claims an ordinary start before it leaves the caller's bounded preparation phase. */
    public static StartClaim claimStartWithinBound(IProject project, String applicationId,
        IProgressMonitor monitor)
    {
        return claimStartWithinBound(project, applicationId,
            StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS, monitor);
    }

    /** Testable deadline form of the start claim. */
    static StartClaim claimStartWithinBound(IProject project, String applicationId,
        long lockTimeoutMs, IProgressMonitor monitor)
    {
        if (project == null || applicationId == null)
        {
            return null;
        }
        RecoveryGuard guard = stopLockFor(project, applicationId);
        if (!tryRecoveryLock(guard, lockTimeoutMs, monitor))
        {
            return null;
        }
        try
        {
            if (guard.startClaim.get() != null)
            {
                return null;
            }
            StartClaim claim = new StartClaim(guard);
            guard.startClaim.set(claim);
            return claim;
        }
        finally
        {
            guard.lock.unlock();
        }
    }

    /** Runs test coordination while holding the production recovery lock. */
    static void holdRecoveryLockForTest(IProject project, String applicationId, Runnable action)
    {
        RecoveryGuard guard = stopLockFor(project, applicationId);
        if (!tryRecoveryLock(guard, 1_000L, null))
        {
            throw new IllegalStateException("The test recovery lock could not be acquired"); //$NON-NLS-1$
        }
        try
        {
            action.run();
        }
        finally
        {
            guard.lock.unlock();
        }
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
        IApplicationManager manager = applicationManager();
        if (manager == null)
        {
            return Recovery.failed("the EDT application manager is not available"); //$NON-NLS-1$
        }
        StandaloneServerSupport.ApplicationLookup lookup =
            StandaloneServerSupport.lookupApplicationBounded(manager, project, applicationId,
                StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS);
        if (lookup.failure() != null)
        {
            return Recovery.failed(lookup.failure());
        }
        IApplication application = lookup.application();
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
        return runStop(manager, application, applicationId,
            StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS);
    }

    /** Same bounded cleanup constrained by a caller's remaining operation deadline. */
    private static Recovery runStop(IApplicationManager manager, IApplication application,
        String applicationId, long timeoutMs)
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
            timeoutMs, monitor -> manager.cleanup(application, context, monitor));
        if (result.isSuccess())
        {
            recordStoppedServer(applicationId);
            Activator.logInfo("Stale standalone server: stopped: " + applicationId); //$NON-NLS-1$
            return Recovery.stopped();
        }
        // The OUTCOME is classified before the failure, not after it: an INTERRUPTED run carries
        // its InterruptedException in getFailure(), so a failure-first branch would answer
        // "nothing is in flight" for the very outcome that means the opposite - the job was only
        // ASKED to stop and may still be running.
        BoundedJob.Outcome outcome = result.getOutcome();
        if (outcome == BoundedJob.Outcome.TIMED_OUT || outcome == BoundedJob.Outcome.INTERRUPTED)
        {
            Activator.logError("Stale standalone server: stopping it did not finish (" //$NON-NLS-1$
                + outcome + "): " + applicationId, result.getFailure()); //$NON-NLS-1$
            return Recovery.failedInFlight(outcome == BoundedJob.Outcome.INTERRUPTED
                ? "the wait for it was interrupted" //$NON-NLS-1$
                : "stopping it did not finish within " //$NON-NLS-1$
                    + (timeoutMs / 1000) + "s"); //$NON-NLS-1$
        }
        if (result.getFailure() != null)
        {
            Activator.logError("Stale standalone server: stopping it failed: " + applicationId, //$NON-NLS-1$
                result.getFailure());
            return Recovery.failed("stopping it failed: " //$NON-NLS-1$
                + PlatformFailures.describe(result.getFailure()));
        }
        Activator.logError("Stale standalone server: stopping it did not run (" //$NON-NLS-1$
            + outcome + "): " + applicationId, null); //$NON-NLS-1$
        return Recovery.failed("it never ran (" + outcome + ")"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Executes cleanup directly when an enclosing bounded Job already owns the deadline. */
    private static Recovery runStopWithinBound(IApplicationManager manager,
        IApplication application, String applicationId, IProgressMonitor monitor,
        Runnable stopCompleted)
    {
        ExecutionContext context = new ExecutionContext();
        Shell shell = LaunchLifecycleUtils.grabActiveShell();
        if (shell != null)
        {
            context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
        }
        Activator.logInfo("Stale standalone server: stopping it so the operation can proceed: " //$NON-NLS-1$
            + applicationId);
        try
        {
            manager.cleanup(application, context, monitor);
            if (stopCompleted != null)
            {
                stopCompleted.run();
            }
            Activator.logInfo("Stale standalone server: stopped: " + applicationId); //$NON-NLS-1$
            return Recovery.stopped();
        }
        catch (Exception failure) // NOSONAR the enclosing bounded Job owns timeout classification
        {
            Activator.logError("Stale standalone server: stopping it failed: " + applicationId, //$NON-NLS-1$
                failure);
            return Recovery.failed("stopping it failed: " + PlatformFailures.describe(failure)); //$NON-NLS-1$
        }
    }

    /** Starts a fresh operation-local stop record. */
    public static void beginOperation()
    {
        OPERATION_STOP.set(new OperationStop());
    }

    /** Clears the current operation-local stop record. */
    public static void endOperation()
    {
        OPERATION_STOP.remove();
    }

    /** Marks that this operation completed the stop of the named server. */
    static void recordStoppedServer(String applicationId)
    {
        OperationStop operation = OPERATION_STOP.get();
        if (operation != null)
        {
            operation.applicationId = applicationId;
        }
    }

    /** Transfers a successful stop from bounded preparation to the caller's operation record. */
    public static void retainPreparedStop(String applicationId)
    {
        recordStoppedServer(applicationId);
    }

    /** Appends one restore outcome when the current operation stopped a server. */
    private static String appendRestoration(String original, IProject project)
    {
        OperationStop stopped = OPERATION_STOP.get();
        if (stopped == null || stopped.applicationId == null)
        {
            return null;
        }
        RestorationAttempt attempt = restoreStoppedServer(project, stopped.applicationId, true);
        return appendRestorationOutcome(original, attempt.launchConfigurationName,
            applicationId -> attempt.outcome);
    }

    /** Restores an operation-local stop, using the caller's known launch configuration name. */
    public static String appendRestoration(String original, IProject project,
        String launchConfigurationName)
    {
        return appendRestorationOutcome(original, launchConfigurationName,
            applicationId -> restoreStoppedServer(project, applicationId));
    }

    /** Explains why an operation-local stop is not restored while the original start is in flight. */
    public static String appendInconclusiveStartNotice(String original, String launchConfigurationName)
    {
        OperationStop stopped = OPERATION_STOP.get();
        if (stopped == null || stopped.applicationId == null)
        {
            return null;
        }
        String separator = original.endsWith(".") || original.endsWith("!") //$NON-NLS-1$ //$NON-NLS-2$
            || original.endsWith("?") ? " " : ". "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return original + separator + "The standalone server '" + stopped.applicationId //$NON-NLS-1$
            + "' was stopped for this operation and was left stopped instead of scheduling a " //$NON-NLS-1$
            + "second start because the original start may still be running."; //$NON-NLS-1$
    }

    /** Shared next step for a start whose bounded caller returned before the start finished. */
    public static String inconclusiveStartGuidance(String launchConfigurationName)
    {
        String name = launchConfigurationName == null || launchConfigurationName.isEmpty()
            ? "<standalone launch configuration>" : launchConfigurationName; //$NON-NLS-1$
        return "Wait for the in-flight start to settle, then check debug_status and EDT's Servers " //$NON-NLS-1$
            + "view before starting anything. Only if the server is stopped, call " //$NON-NLS-1$
            + "launch(launchConfigurationName='" + name + "')."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Testable composition of the operation record, restore attempt, and exact message. */
    static String appendRestoration(String original, String launchConfigurationName,
        Restarter restarter)
    {
        return appendRestorationOutcome(original, launchConfigurationName,
            applicationId -> new RestorationStartOutcome(restarter.restore(applicationId), true));
    }

    /** Same composition while retaining whether the restoration start is still in flight. */
    static String appendRestorationOutcome(String original, String launchConfigurationName,
        RestorationRestarter restarter)
    {
        OperationStop stopped = OPERATION_STOP.get();
        if (stopped == null || stopped.applicationId == null)
        {
            return null;
        }
        String applicationId = stopped.applicationId;
        RestorationStartOutcome restoration = restarter.restore(applicationId);
        String restoreFailure = restoration.failure;
        String separator = original.endsWith(".") || original.endsWith("!") //$NON-NLS-1$ //$NON-NLS-2$
            || original.endsWith("?") ? " " : ". "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (restoration.skippedMessage != null)
        {
            return original + separator + "The standalone server '" + applicationId + "' " //$NON-NLS-1$ //$NON-NLS-2$
                + restoration.skippedMessage;
        }
        if (restoreFailure == null)
        {
            return original + separator + "The standalone server '" + applicationId //$NON-NLS-1$
                + "' was stopped for this operation and has been started again."; //$NON-NLS-1$
        }
        String reason = restoreFailure.trim();
        if (reason.endsWith(".")) //$NON-NLS-1$
        {
            reason = reason.substring(0, reason.length() - 1);
        }
        String name = launchConfigurationName == null || launchConfigurationName.isEmpty()
            ? applicationId : launchConfigurationName;
        if (!restoration.conclusive)
        {
            return original + separator + "The standalone server '" + applicationId //$NON-NLS-1$
                + "' was stopped for this operation, but its restoration start did not finish " //$NON-NLS-1$
                + "conclusively: " + reason + ". " + inconclusiveStartGuidance(name); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return original + separator + "The standalone server '" + applicationId //$NON-NLS-1$
            + "' was stopped for this operation and could NOT be started again: " //$NON-NLS-1$
            + reason + ". Start it with launch(launchConfigurationName='" + name + "')."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Starts the stopped server once and retains whether a failed start may still be running. */
    private static RestorationStartOutcome restoreStoppedServer(IProject project, String applicationId)
    {
        return restoreStoppedServer(project, applicationId, false).outcome;
    }

    /** Resolves every restoration prerequisite, optionally including its launch name, together. */
    private static RestorationAttempt restoreStoppedServer(IProject project, String applicationId,
        boolean resolveLaunchConfigurationName)
    {
        AtomicReference<String> launchConfigurationName = new AtomicReference<>(applicationId);
        return runRestorationAttempt(launchConfigurationName,
            () -> restoreStoppedServerAttempt(project, applicationId,
                resolveLaunchConfigurationName, launchConfigurationName));
    }

    /** Keeps restoration scheduling trouble subordinate to the operation's original failure. */
    static RestorationAttempt runRestorationAttempt(
        AtomicReference<String> launchConfigurationName, Supplier<RestorationAttempt> attempt)
    {
        try
        {
            return attempt.get();
        }
        catch (Exception failure) // NOSONAR restoration must not hide the original failure
        {
            return new RestorationAttempt(launchConfigurationName.get(),
                new RestorationStartOutcome(PlatformFailures.describe(failure), true));
        }
    }

    /** Complete bounded preparation and separately bounded start for one restoration. */
    private static RestorationAttempt restoreStoppedServerAttempt(IProject project,
        String applicationId, boolean resolveLaunchConfigurationName,
        AtomicReference<String> launchConfigurationName)
    {
        AtomicReference<RestorationPreparation> prepared = new AtomicReference<>();
        AtomicReference<RestorationPreparationStage> stage =
            new AtomicReference<>(RestorationPreparationStage.PHASE);
        BoundedJob.Result bounded = BoundedJob.run(
            "Preparing standalone-server restoration: " + applicationId, //$NON-NLS-1$
            StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS, monitor -> {
            if (project == null)
            {
                prepared.set(RestorationPreparation.failed("the project is unknown")); //$NON-NLS-1$
                return;
            }
            if (resolveLaunchConfigurationName)
            {
                stage.set(RestorationPreparationStage.LAUNCH_CONFIGURATION);
                try
                {
                    launchConfigurationName.set(
                        standaloneLaunchConfigurationName(project, applicationId));
                }
                catch (Exception failure) // NOSONAR discovery must not prevent restoration
                {
                    Activator.logError(
                        "Standalone server: cannot resolve its launch configuration", failure); //$NON-NLS-1$
                }
                if (monitor.isCanceled())
                {
                    return;
                }
            }
            stage.set(RestorationPreparationStage.PHASE);
            Activator activator = Activator.getDefault();
            IApplicationManager manager = activator == null ? null : activator.getApplicationManager();
            if (monitor.isCanceled())
            {
                return;
            }
            if (manager == null)
            {
                prepared.set(RestorationPreparation.failed(
                    "the EDT application manager is not available")); //$NON-NLS-1$
                return;
            }
            stage.set(RestorationPreparationStage.APPLICATION);
            StandaloneServerSupport.ApplicationLookup lookup =
                StandaloneServerSupport.lookupApplication(manager, project, applicationId);
            if (monitor.isCanceled())
            {
                return;
            }
            IApplication application = lookup.application();
            if (application == null)
            {
                prepared.set(RestorationPreparation.failed(
                    "the application could not be resolved")); //$NON-NLS-1$
                return;
            }
            Object server = lookup.server();
            if (server == null)
            {
                prepared.set(RestorationPreparation.failed(
                    "the application's standalone server could not be resolved")); //$NON-NLS-1$
                return;
            }
            stage.set(RestorationPreparationStage.SERVICE);
            Object service = StandaloneServerSupport.acquireService();
            if (service == null)
            {
                prepared.set(RestorationPreparation.failed(
                    "the EDT standalone-server service is not available")); //$NON-NLS-1$
                return;
            }
            prepared.set(RestorationPreparation.ready(manager, service, lookup));
        });

        RestorationPreparation preparation = prepared.get();
        if (BoundedJob.isInconclusive(bounded.getOutcome()) && preparation != null
            && preparation.failure != null)
        {
            return new RestorationAttempt(launchConfigurationName.get(),
                new RestorationStartOutcome(preparation.failure, true));
        }
        if (!bounded.isSuccess())
        {
            String failure;
            if (bounded.getFailure() != null
                && bounded.getOutcome() == BoundedJob.Outcome.COMPLETED)
            {
                failure = stage.get() == RestorationPreparationStage.APPLICATION
                    ? "the application could not be resolved: " //$NON-NLS-1$
                        + PlatformFailures.describe(bounded.getFailure())
                    : PlatformFailures.describe(bounded.getFailure());
            }
            else if (stage.get() == RestorationPreparationStage.APPLICATION)
            {
                failure = StandaloneServerSupport.applicationLookupFailure(applicationId,
                    StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS, bounded);
            }
            else
            {
                String target;
                if (stage.get() == RestorationPreparationStage.SERVICE)
                {
                    target = "the EDT standalone-server service lookup"; //$NON-NLS-1$
                }
                else if (stage.get() == RestorationPreparationStage.LAUNCH_CONFIGURATION)
                {
                    target = "the standalone-server launch-configuration lookup"; //$NON-NLS-1$
                }
                else
                {
                    target = "the standalone-server restoration precondition phase"; //$NON-NLS-1$
                }
                failure = StandaloneServerSupport.boundedPhaseFailure(target,
                    StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS, bounded);
            }
            return new RestorationAttempt(launchConfigurationName.get(),
                new RestorationStartOutcome(failure, true));
        }
        if (preparation == null)
        {
            return new RestorationAttempt(launchConfigurationName.get(),
                new RestorationStartOutcome(
                    "the standalone-server restoration precondition phase produced no result", //$NON-NLS-1$
                    true));
        }
        if (preparation.failure != null)
        {
            return new RestorationAttempt(launchConfigurationName.get(),
                new RestorationStartOutcome(preparation.failure, true));
        }
        RestorationStartOutcome outcome = restoreIfStillUnownedClaimed(project,
            preparation.lookup.server(), applicationId,
            StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS,
            remainingMs -> stopStaleServerGuarded(project, preparation.lookup.application(),
                applicationId, preparation.manager, remainingMs),
            (claim, remainingMs) -> startRestorationWithPortGuardOutcome(preparation.service,
                preparation.lookup.server(), applicationId, preparation.lookup.infobaseName(),
                preparation.lookup.serverName(), remainingMs,
                StandaloneServerSupport.INCONCLUSIVE_START_GUARD_CAP_MS, claim));
        return new RestorationAttempt(launchConfigurationName.get(), outcome);
    }

    /** Revalidates current ownership under the same guard used by the stale stop. */
    static RestorationStartOutcome restoreIfStillUnowned(IProject project, Object server,
        String applicationId, Supplier<RestorationStartOutcome> restarter)
    {
        return restoreIfStillUnowned(project, server, applicationId,
            StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS, restarter);
    }

    /** Testable deadline form of the guarded ownership revalidation. */
    static RestorationStartOutcome restoreIfStillUnowned(IProject project, Object server,
        String applicationId, long timeoutMs, Supplier<RestorationStartOutcome> restarter)
    {
        return restoreIfStillUnownedClaimed(project, server, applicationId, timeoutMs,
            remainingMs -> stopStaleServerGuarded(project, null, applicationId,
                applicationManager(), remainingMs),
            (claim, remainingMs) -> restarter.get());
    }

    /** Test seam that exercises the real stale-stop machinery with already-resolved EDT objects. */
    static RestorationStartOutcome restoreIfStillUnowned(IProject project, IApplication application,
        Object server, String applicationId, IApplicationManager manager, long timeoutMs,
        Supplier<RestorationStartOutcome> restarter)
    {
        return restoreIfStillUnownedClaimed(project, server, applicationId, timeoutMs,
            remainingMs -> stopStaleServerGuarded(project, application, applicationId, manager,
                remainingMs),
            (claim, remainingMs) -> restarter.get());
    }

    /** Test seam exposing the actual allowances handed to normalization and restoration. */
    static RestorationStartOutcome restoreIfStillUnowned(IProject project, Object server,
        String applicationId, long timeoutMs, StaleStateNormalizer normalizer,
        TimedRestarter restarter)
    {
        return restoreIfStillUnownedClaimed(project, server, applicationId, timeoutMs, normalizer,
            (claim, remainingMs) -> restarter.restore(remainingMs));
    }

    /** Normalizes stale state, then claims and dispatches restoration under the remaining wait. */
    private static RestorationStartOutcome restoreIfStillUnownedClaimed(IProject project,
        Object server, String applicationId, long timeoutMs, StaleStateNormalizer normalizer,
        ClaimedRestarter restarter)
    {
        long deadline = recoveryDeadline(timeoutMs);
        RecoveryGuard guard = stopLockFor(project, applicationId);
        if (!tryRecoveryLock(guard, timeoutMs, null))
        {
            return RestorationStartOutcome.skipped(
                "was not restored because " + recoveryLockFailure() + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            if (guard.startClaim.get() != null)
            {
                return RestorationStartOutcome.skipped(
                    "is claimed by another launch that has not started it yet, so restoration " //$NON-NLS-1$
                        + "was skipped."); //$NON-NLS-1$
            }
            Integer state = serverState(server);
            Boolean liveLaunch = hasLiveLaunch(server);
            if (Boolean.TRUE.equals(liveLaunch))
            {
                return RestorationStartOutcome.skipped(
                    "is already running under another launch, so restoration was skipped."); //$NON-NLS-1$
            }
            if (state == null)
            {
                return RestorationStartOutcome.skipped(
                    "was not restored because its current state could not be confirmed."); //$NON-NLS-1$
            }
            boolean normalizationAttempted = false;
            if (state.intValue() == STATE_STARTED && Boolean.FALSE.equals(liveLaunch))
            {
                long remainingMs = remainingRecoveryTimeMs(deadline);
                if (remainingMs <= 0L)
                {
                    return RestorationStartOutcome.skipped(
                        "was not restored because its stale state could not be normalized before " //$NON-NLS-1$
                            + "the operation deadline."); //$NON-NLS-1$
                }
                // Re-entry stays on this thread. The reused stop's bounded Job executes only EDT
                // cleanup; it never waits on the recovery lock held here. Give it the full
                // remainder so a stop that fits the caller's deadline can confirm STOPPED.
                Recovery normalization = normalizer.normalize(remainingMs);
                if (!normalization.recovered())
                {
                    return RestorationStartOutcome.skipped(
                        "was not restored because its stale state could not be normalized to " //$NON-NLS-1$
                            + "STOPPED: " + normalization.detail() + "."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                normalizationAttempted = true;
                state = serverState(server);
                liveLaunch = hasLiveLaunch(server);
                if (Boolean.TRUE.equals(liveLaunch))
                {
                    return RestorationStartOutcome.skipped(
                        "is already running under another launch, so restoration was skipped."); //$NON-NLS-1$
                }
                if (state == null)
                {
                    return RestorationStartOutcome.skipped(
                        "was not restored because its current state could not be confirmed."); //$NON-NLS-1$
                }
            }
            if (state.intValue() != STATE_STOPPED)
            {
                if (normalizationAttempted)
                {
                    return RestorationStartOutcome.skipped(
                        "was not restored because its stale state could not be normalized to " //$NON-NLS-1$
                            + "STOPPED; its current state is " + stateName(state.intValue()) + "."); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return RestorationStartOutcome.skipped(
                    "was not restored because its current state is " + stateName(state.intValue()) //$NON-NLS-1$
                        + ", not STOPPED."); //$NON-NLS-1$
            }
            if (liveLaunch == null)
            {
                return RestorationStartOutcome.skipped(
                    "was not restored because its owning launch could not be confirmed."); //$NON-NLS-1$
            }
            long remainingMs = remainingRecoveryTimeMs(deadline);
            // A confirmed STOPPED server is always handed to the guarded start. Its bounded wait
            // gets only this remainder; an inconclusive start retains the claim through cleanup.
            StartClaim claim = new StartClaim(guard);
            guard.startClaim.set(claim);
            try
            {
                return restarter.restore(claim, remainingMs);
            }
            finally
            {
                claim.closeIfNotHandedOff();
            }
        }
        finally
        {
            guard.lock.unlock();
        }
    }

    /** Starts a restoration while refusing any port rewrite and retaining its specific failure. */
    static String startRestorationWithPortGuard(Object service, Object server, String applicationId,
        String infobaseName, String serverName)
    {
        return startRestorationWithPortGuardOutcome(service, server, applicationId, infobaseName,
            serverName).failure;
    }

    /** Testable deadline form of the real guarded restoration path. */
    static String startRestorationWithPortGuard(Object service, Object server, String applicationId,
        String infobaseName, String serverName, long timeoutMs, long cleanupCapMs)
    {
        return startRestorationWithPortGuardOutcome(service, server, applicationId, infobaseName,
            serverName, timeoutMs, cleanupCapMs, null).failure;
    }

    /** Same guarded restoration while preserving whether its start is still in flight. */
    private static RestorationStartOutcome startRestorationWithPortGuardOutcome(Object service,
        Object server, String applicationId, String infobaseName, String serverName)
    {
        return startRestorationWithPortGuardOutcome(service, server, applicationId, infobaseName,
            serverName, StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS,
            StandaloneServerSupport.INCONCLUSIVE_START_GUARD_CAP_MS, null);
    }

    /** Same production restoration with its exclusive start claim. */
    private static RestorationStartOutcome startRestorationWithPortGuardOutcome(Object service,
        Object server, String applicationId, String infobaseName, String serverName,
        StartClaim startClaim)
    {
        return startRestorationWithPortGuardOutcome(service, server, applicationId, infobaseName,
            serverName, StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS,
            StandaloneServerSupport.INCONCLUSIVE_START_GUARD_CAP_MS, startClaim);
    }

    /** Runs restoration through the same bounded, composite-guard helper as the direct start. */
    private static RestorationStartOutcome startRestorationWithPortGuardOutcome(Object service,
        Object server, String applicationId, String infobaseName, String serverName,
        long timeoutMs, long cleanupCapMs, StartClaim startClaim)
    {
        StandaloneServerPortConflictPolicy portPolicy = StandaloneServerPortConflictPolicy.CANCEL;
        Runnable claimCleanup = startClaim == null ? null : startClaim::close;
        Runnable claimHandoff = startClaim == null ? null : startClaim::handoff;
        StandaloneServerSupport.GuardedStartResult result =
            StandaloneServerSupport.startServerGuarded(service, server,
                "Restoring standalone server: " + applicationId, ILaunchManager.DEBUG_MODE, //$NON-NLS-1$
                infobaseName, serverName, portPolicy, timeoutMs, cleanupCapMs, claimCleanup,
                claimHandoff);
        return new RestorationStartOutcome(result.failure(), result.conclusive());
    }

    /** Failure plus whether the underlying restoration start has definitely ended. */
    static final class RestorationStartOutcome
    {
        private final String failure;
        private final boolean conclusive;
        private final String skippedMessage;

        RestorationStartOutcome(String failure, boolean conclusive)
        {
            this(failure, conclusive, null);
        }

        private RestorationStartOutcome(String failure, boolean conclusive, String skippedMessage)
        {
            this.failure = failure;
            this.conclusive = conclusive;
            this.skippedMessage = skippedMessage;
        }

        static RestorationStartOutcome skipped(String message)
        {
            return new RestorationStartOutcome(null, true, message);
        }
    }

    /** One application's bounded recovery lock and its current ordinary-start claim. */
    private static final class RecoveryGuard
    {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicReference<StartClaim> startClaim = new AtomicReference<>();
    }

    /** Exclusive intention to start a server whose WST state may still read as STOPPED. */
    public static final class StartClaim implements AutoCloseable
    {
        private final RecoveryGuard guard;
        private final AtomicBoolean handedOff = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private StartClaim(RecoveryGuard guard)
        {
            this.guard = guard;
        }

        /** Records that cleanup is now protected by the bounded start lifecycle. */
        public void handoff()
        {
            handedOff.set(true);
        }

        /** Releases a preparation claim that never reached the bounded start. */
        public void closeIfNotHandedOff()
        {
            if (!handedOff.get())
            {
                close();
            }
        }

        /** Releases this exact claim without consuming a newer operation's claim. */
        @Override
        public void close()
        {
            if (released.compareAndSet(false, true))
            {
                guard.startClaim.compareAndSet(this, null);
            }
        }
    }

    /** Step active when a bounded restoration preparation stops answering. */
    private enum RestorationPreparationStage
    {
        PHASE,
        LAUNCH_CONFIGURATION,
        APPLICATION,
        SERVICE
    }

    /** Name resolved under the preparation deadline plus the separately bounded start outcome. */
    static final class RestorationAttempt
    {
        final String launchConfigurationName;
        final RestorationStartOutcome outcome;

        RestorationAttempt(String launchConfigurationName, RestorationStartOutcome outcome)
        {
            this.launchConfigurationName = launchConfigurationName;
            this.outcome = outcome;
        }
    }

    /** Values resolved together before the separately bounded restoration start. */
    private static final class RestorationPreparation
    {
        private final IApplicationManager manager;
        private final Object service;
        private final StandaloneServerSupport.ApplicationLookup lookup;
        private final String failure;

        private RestorationPreparation(IApplicationManager manager, Object service,
            StandaloneServerSupport.ApplicationLookup lookup, String failure)
        {
            this.manager = manager;
            this.service = service;
            this.lookup = lookup;
            this.failure = failure;
        }

        static RestorationPreparation ready(IApplicationManager manager, Object service,
            StandaloneServerSupport.ApplicationLookup lookup)
        {
            return new RestorationPreparation(manager, service, lookup, null);
        }

        static RestorationPreparation failed(String failure)
        {
            return new RestorationPreparation(null, null, null, failure);
        }
    }

    /** Finds the standalone configuration that addresses this project application. */
    private static String standaloneLaunchConfigurationName(IProject project, String applicationId)
    {
        if (project == null || applicationId == null)
        {
            return applicationId;
        }
        ILaunchManager launchManager = LaunchConfigUtils.getLaunchManager();
        if (launchManager == null)
        {
            return applicationId;
        }
        ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(
            LaunchConfigUtils.STANDALONE_SERVER_LAUNCH_CONFIG_TYPE_ID);
        if (type == null)
        {
            return applicationId;
        }
        ILaunchConfiguration exact = LaunchConfigUtils.findLaunchConfig(launchManager, type,
            project.getName(), applicationId);
        if (exact != null)
        {
            return exact.getName();
        }
        try
        {
            for (ILaunchConfiguration config : launchManager.getLaunchConfigurations(type))
            {
                String projectName = LaunchConfigUtils.readAttribute(config,
                    LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                if (project.getName().equals(projectName)
                    && applicationId.equals(
                        LaunchLifecycleUtils.resolveDelegateApplicationId(config, projectName)))
                {
                    return config.getName();
                }
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Standalone server: cannot find its launch configuration", e); //$NON-NLS-1$
        }
        return applicationId;
    }

    /** One operation's successfully stopped application. */
    private static final class OperationStop
    {
        String applicationId;
    }

    /** Normalizes one stale STARTED/no-live-launch tuple inside the remaining deadline. */
    @FunctionalInterface
    interface StaleStateNormalizer
    {
        Recovery normalize(long timeoutMs);
    }

    /** Starts restoration with the portion of the shared deadline still available. */
    @FunctionalInterface
    interface TimedRestarter
    {
        RestorationStartOutcome restore(long timeoutMs);
    }

    /** Starts restoration while owning its persistent claim and remaining deadline. */
    @FunctionalInterface
    private interface ClaimedRestarter
    {
        RestorationStartOutcome restore(StartClaim claim, long timeoutMs);
    }

    /** Performs one conclusive restoration; {@code null} means it succeeded. */
    @FunctionalInterface
    interface Restarter
    {
        String restore(String applicationId);
    }

    /** Performs one restoration while preserving whether a failed start is still in flight. */
    @FunctionalInterface
    interface RestorationRestarter
    {
        RestorationStartOutcome restore(String applicationId);
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
     * Returns a standalone server EDT can no longer start to a state it CAN start from, BEFORE the
     * operation that starts it runs.
     *
     * <h2>Why this exists next to the recovery</h2>
     * {@link #launchWithRecovery} and {@link #updateWithRecovery} repair the stale state AFTER EDT
     * refuses, and the operation then succeeds. What they cannot repair is the noise: the refusal
     * is thrown inside WST's start job, whose {@code startImpl} catches only {@link CoreException},
     * so the {@link IllegalStateException} escapes the job and the Eclipse job framework writes the
     * whole stack into the workbench log — even when the retry that follows succeeds. An unattended
     * run accumulates ERROR entries that describe nothing the caller can act on. Deciding BEFORE
     * the start means the refusal never happens, so nothing is logged.
     *
     * <p>The check mirrors EDT's OWN "it is already running, nothing to do" condition
     * ({@code StandaloneServerService.startServer}): a server counts as running only when its state
     * is {@code STARTED} AND it still holds a live launch. That is deliberate — a server whose
     * launch is gone is exactly the one EDT refuses, and a server that still holds one is exactly
     * the one EDT leaves alone.
     *
     * <p>It never fails an operation for its own reasons: any failure to READ the state (an EDT
     * without the standalone-server feature, a changed API, a server that cannot be resolved)
     * leaves the call to proceed exactly as before, where the reactive recovery still covers it.
     * The single exception is deliberate - a stop that MAY STILL BE RUNNING, where proceeding
     * would start a server the lingering stop can take down again.
     *
     * @param project the project owning the application (may be {@code null} — no-op)
     * @param application the application, when the caller already holds it (may be {@code null} —
     *     it is then resolved from {@code applicationId})
     * @param applicationId the application id; anything but a standalone-server id
     *     ({@code ServerApplication.<name>}) is a no-op
     * @throws ApplicationException when a stale server had to be stopped and that stop did not
     *     finish - it may still be running, so the operation must not start the server now
     */
    public static void ensureStartable(IProject project, IApplication application,
        String applicationId)
    {
        ensureStartable(project, application, applicationId, applicationManager());
    }

    /** Same pre-flight with the manager already held by the caller. */
    public static void ensureStartable(IProject project, IApplication application,
        String applicationId, IApplicationManager manager)
    {
        if (project == null || !DebugServerTargetSupport.isServerApplicationId(applicationId))
        {
            return;
        }
        try
        {
            IApplication resolvedApplication = application;
            if (resolvedApplication == null)
            {
                if (manager == null)
                {
                    return;
                }
                StandaloneServerSupport.ApplicationLookup lookup =
                    StandaloneServerSupport.lookupApplicationBounded(manager, project,
                        applicationId, StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS);
                if (lookup.failure() != null)
                {
                    Activator.logError("Standalone server: the pre-flight application lookup " //$NON-NLS-1$
                        + "did not complete: " + applicationId + ": " + lookup.failure(), null); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                resolvedApplication = lookup.application();
            }
            Object server = resolveServer(resolvedApplication);
            if (server == null)
            {
                return;
            }
            Preflight decision = decide(serverState(server), hasLiveLaunch(server));
            if (decision == Preflight.WAIT_SETTLE)
            {
                decision = decide(awaitSettled(server, applicationId), hasLiveLaunch(server));
            }
            if (decision == Preflight.STOP_STALE)
            {
                stopStaleServerBeforeStart(project, resolvedApplication, applicationId, manager);
            }
        }
        catch (ApplicationException abort)
        {
            // The one deliberate abort: a stop that may STILL BE RUNNING (see
            // stopStaleServerBeforeStart). Swallowing it here would let the caller start a server
            // that the lingering stop can take down again.
            throw abort;
        }
        catch (Exception e) // NOSONAR every other pre-flight failure must not break an operation that would otherwise run
        {
            Activator.logError("Standalone server: the pre-flight state check failed for " //$NON-NLS-1$
                + applicationId, e);
        }
    }

    /** Runs the same pre-flight directly inside a deadline already owned by the caller. */
    public static void ensureStartableWithinBound(IProject project, IApplication application,
        Object server, String applicationId, IApplicationManager manager, IProgressMonitor monitor,
        Runnable stopStarting, Runnable stopCompleted)
    {
        if (project == null || application == null || manager == null
            || !DebugServerTargetSupport.isServerApplicationId(applicationId)
            || (monitor != null && monitor.isCanceled()))
        {
            return;
        }
        try
        {
            if (server == null)
            {
                return;
            }
            if (monitor != null && monitor.isCanceled())
            {
                return;
            }
            Preflight decision = decide(serverState(server), hasLiveLaunch(server));
            if (decision == Preflight.WAIT_SETTLE)
            {
                decision = decide(awaitSettled(server, applicationId, monitor),
                    hasLiveLaunch(server));
            }
            if (decision == Preflight.STOP_STALE)
            {
                stopStaleServerBeforeStartWithinBound(project, application, applicationId,
                    manager, monitor, stopStarting, stopCompleted);
            }
        }
        catch (ApplicationException abort)
        {
            throw abort;
        }
        catch (Exception e) // NOSONAR every other pre-flight failure remains best-effort
        {
            Activator.logError("Standalone server: the pre-flight state check failed for " //$NON-NLS-1$
                + applicationId, e);
            return;
        }
    }

    /**
     * The pre-flight for a caller that names no application: EDT prepares the project's DEFAULT
     * application (that is what the external-object dump does), so that is the server whose state
     * has to be settled.
     *
     * @param project the project whose default application will be prepared (may be {@code null} —
     *     no-op)
     */
    public static void ensureDefaultApplicationStartable(IProject project)
    {
        if (project == null)
        {
            return;
        }
        Activator activator = Activator.getDefault();
        IApplicationManager manager = activator == null ? null : activator.getApplicationManager();
        if (manager == null)
        {
            return;
        }
        ensureStartable(project, null,
            LaunchLifecycleUtils.resolveDefaultApplicationId(project, null, manager), manager);
    }

    /** What the pre-flight decided to do about the server's current state. */
    enum Preflight
    {
        /** Nothing to do — EDT will start it, or leave it alone, by itself. */
        PROCEED,
        /** {@code STARTED} with no live launch: the stuck state, stop it before starting. */
        STOP_STALE,
        /** A start or stop is in flight: wait for it to finish before deciding. */
        WAIT_SETTLE
    }

    /**
     * The pre-flight decision for one server state, kept pure so the whole rule is testable
     * without an EDT runtime.
     *
     * @param state the WST server state, or {@code null} when it could not be read
     * @param liveLaunch whether the server still holds a live launch, or {@code null} when that
     *     could not be determined
     * @return the decision, never {@code null}
     */
    static Preflight decide(Integer state, Boolean liveLaunch)
    {
        if (state == null)
        {
            return Preflight.PROCEED;
        }
        if (state == STATE_STARTING || state == STATE_STOPPING)
        {
            return Preflight.WAIT_SETTLE;
        }
        // Only a server EDT believes is RUNNING can be stuck, and only when the launch that owned
        // it is gone. An UNREADABLE launch (null) is not a dead one: stopping a healthy server
        // because its launch could not be inspected would break the very operation this check
        // exists to protect.
        return state == STATE_STARTED && Boolean.FALSE.equals(liveLaunch) ? Preflight.STOP_STALE
            : Preflight.PROCEED;
    }

    /**
     * Waits, bounded, for a server another operation is starting or stopping right now.
     *
     * <p>Waiting is what makes a transitional state usable instead of fatal: the recovery refuses
     * to touch {@code STARTING}/{@code STOPPING} (stopping a server somebody else is starting
     * would break THAT operation), so without a wait a concurrent start turns into an error the
     * caller can only answer by retrying by hand.
     *
     * @param server the WST server object
     * @param applicationId the application id (for the log)
     * @return the state the server settled in, the transitional state when it did not settle in
     *     time, or {@code null} when the state could not be read
     */
    private static Integer awaitSettled(Object server, String applicationId)
    {
        return awaitSettled(server, applicationId, null);
    }

    /** Same settling wait, also respecting an enclosing bounded phase's cancellation. */
    private static Integer awaitSettled(Object server, String applicationId,
        IProgressMonitor monitor)
    {
        long deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS;
        Integer state = serverState(server);
        while (isTransitional(state) && System.currentTimeMillis() < deadline
            && (monitor == null || !monitor.isCanceled()))
        {
            try
            {
                Thread.sleep(SETTLE_POLL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return state;
            }
            state = serverState(server);
        }
        if (isTransitional(state) && (monitor == null || !monitor.isCanceled()))
        {
            Activator.logInfo("Standalone server: it is still " + stateName(state.intValue()) //$NON-NLS-1$
                + " after " + (SETTLE_TIMEOUT_MS / 1000) //$NON-NLS-1$
                + "s; proceeding and letting EDT decide: " + applicationId); //$NON-NLS-1$
        }
        return state;
    }

    /** Whether a state is one a concurrent start/stop is holding right now. */
    private static boolean isTransitional(Integer state)
    {
        return state != null && (state == STATE_STARTING || state == STATE_STOPPING);
    }

    /**
     * The pre-flight stop: the same stop the reactive recovery performs, run before the refusal
     * instead of after it.
     *
     * @param project the project owning the application
     * @param application the already-resolved application
     * @param applicationId the application id
     * @param manager the application manager that resolved the application
     * @throws ApplicationException when the stop did not finish and MAY STILL BE RUNNING - the
     *     caller must not start a server that a lingering stop can take down again
     */
    private static void stopStaleServerBeforeStart(IProject project, IApplication application,
        String applicationId, IApplicationManager manager)
    {
        Activator.logInfo("Standalone server: EDT still has it STARTED while the launch that " //$NON-NLS-1$
            + "owned it is gone; stopping it so the operation is not refused: " + applicationId); //$NON-NLS-1$
        Recovery recovery = stopStaleServerGuarded(project, application, applicationId, manager);
        finishPreflightStop(applicationId, recovery);
    }

    /** Performs the stale stop on the enclosing bounded Job and reports whether it completed. */
    private static boolean stopStaleServerBeforeStartWithinBound(IProject project,
        IApplication application, String applicationId, IApplicationManager manager,
        IProgressMonitor monitor, Runnable stopStarting, Runnable stopCompleted)
    {
        Activator.logInfo("Standalone server: EDT still has it STARTED while the launch that " //$NON-NLS-1$
            + "owned it is gone; stopping it so the operation is not refused: " + applicationId); //$NON-NLS-1$
        Recovery recovery = stopStaleServerGuardedWithinBound(project, application, applicationId,
            manager, monitor, stopStarting, stopCompleted);
        return finishPreflightStop(applicationId, recovery);
    }

    /** Applies the existing pre-flight outcome contract and returns whether this call stopped it. */
    private static boolean finishPreflightStop(String applicationId, Recovery recovery)
    {
        if (recovery.recovered())
        {
            return true;
        }
        if (recovery.stopStillInFlight())
        {
            // The stop was neither completed nor abandoned: BoundedJob cancels the job but cannot
            // preempt it, so it may finish later - and stop whatever server is running by then,
            // including the one this operation is about to start. Refusing here costs the caller a
            // retry; proceeding would cost them a server that dies under them.
            throw new ApplicationException(preflightStopInFlightFailure(applicationId,
                recovery.detail()));
        }
        // The stop never ran (it was refused outright): nothing is in flight, so the operation
        // still runs, meets EDT's refusal, and the reactive recovery answers it with the same
        // actionable message it always did.
        Activator.logError("Standalone server: the pre-flight stop did not happen (" //$NON-NLS-1$
            + recovery.detail() + "); the operation proceeds and may be refused: " //$NON-NLS-1$
            + applicationId, null);
        return false;
    }

    /** Existing refusal text for a pre-flight stop whose cleanup may still be running. */
    public static String preflightStopInFlightFailure(String applicationId, String detail)
    {
        return "The standalone server of application '" //$NON-NLS-1$
            + applicationId + "' is in a state EDT cannot start from, and stopping it " //$NON-NLS-1$
            + "did not finish (" + detail + "). That stop may still be running, so " //$NON-NLS-1$ //$NON-NLS-2$
            + "starting " //$NON-NLS-1$
            + "the server now could be undone by it. Wait for it to finish (Servers view in " //$NON-NLS-1$
            + "EDT), or restart EDT, then retry."; //$NON-NLS-1$
    }

    /**
     * The WST {@code IServer} behind a standalone-server application, or {@code null} when this is
     * not a standalone-server application or the server cannot be resolved.
     *
     * <p>Resolved ONLY through the application's own {@code IServerApplication.getServer()} - see
     * the comment in the body for why the by-module-name scan {@code delete_infobase} falls back
     * to must not be used for a decision that can stop a server.
     *
     * @param application the already-resolved application
     * @return the WST server object (address it reflectively), or {@code null}
     */
    private static Object resolveServer(IApplication application)
    {
        if (application == null)
        {
            return null;
        }
        String typeId = application.getType() != null ? application.getType().getId() : null;
        if (!StandaloneServerSupport.WST_SERVER_APP_TYPE.equals(typeId))
        {
            // The id looked like a standalone server's but the application is something else —
            // there is no WST server to inspect, and nothing to do.
            return null;
        }
        // ONLY the application's own accessor. delete_infobase additionally falls back to a scan
        // for a server whose MODULE NAME matches, but a module name is not scoped to a project:
        // two projects may hold standalone applications with the same display name, and reading
        // the state of the wrong server would then decide the fate of this one. A decision that
        // can stop a server must be made from the server that provably belongs to it, so when the
        // accessor gives nothing the pre-flight simply does not run.
        return StandaloneServerSupport.serverOfApplication(application);
    }

    /**
     * Reflective {@code IServer.getServerState()}. Reflective for the same reason the rest of the
     * standalone-server access is (see {@link StandaloneServerSupport}): the plugin carries no
     * dependency on the WST server bundles, so it must stay loadable on an EDT without them.
     *
     * @param server the WST server object (never {@code null})
     * @return the state, or {@code null} when it could not be read
     */
    static Integer serverState(Object server)
    {
        try
        {
            Object value = server.getClass().getMethod("getServerState").invoke(server); //$NON-NLS-1$
            return (value instanceof Integer) ? (Integer)value : null;
        }
        catch (Throwable t) // NOSONAR deliberate catch-all at a reflective boundary
        {
            Activator.logError("Standalone server: IServer.getServerState() refl failed", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Whether the server still holds a live launch — the second half of EDT's own "already
     * running" condition.
     *
     * @param server the WST server object (never {@code null})
     * @return {@code TRUE} when a non-terminated launch is attached, {@code FALSE} when there is
     *     none, and {@code null} when the answer could not be determined (an unknown launch type,
     *     a reflective failure) — which the decision treats as "do not touch it"
     */
    static Boolean hasLiveLaunch(Object server)
    {
        try
        {
            Object launch = server.getClass().getMethod("getLaunch").invoke(server); //$NON-NLS-1$
            if (launch == null)
            {
                return Boolean.FALSE;
            }
            if (!(launch instanceof ILaunch))
            {
                return null;
            }
            return Boolean.valueOf(!((ILaunch)launch).isTerminated());
        }
        catch (Throwable t) // NOSONAR deliberate catch-all at a reflective boundary
        {
            Activator.logError("Standalone server: IServer.getLaunch() refl failed", t); //$NON-NLS-1$
            return null;
        }
    }

    /** The project and application id a launch configuration targets. */
    private static final class Target
    {
        /** The launch's project, or {@code null} when it is unknown or not open. */
        final IProject project;
        /** The delegate-resolved application id, or {@code null} when it could not be read. */
        final String applicationId;

        Target(IProject project, String applicationId)
        {
            this.project = project;
            this.applicationId = applicationId;
        }
    }

    /**
     * The project and application a launch configuration targets, read ONCE per launch and shared
     * by the pre-flight and the recovery (both need exactly this pair, and the recovery must not
     * re-read a configuration whose launch has already failed).
     *
     * @param config the launch configuration (never {@code null})
     * @return the target, never {@code null}; its fields are {@code null} when the configuration
     *     could not be read
     */
    private static Target resolveTarget(ILaunchConfiguration config)
    {
        try
        {
            String projectName = config.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String applicationId =
                LaunchLifecycleUtils.resolveDelegateApplicationId(config, projectName);
            ProjectContext ctx = ProjectContext.of(projectName);
            return new Target(ctx.isOpen() ? ctx.project() : null, applicationId);
        }
        catch (Exception e) // NOSONAR an unreadable config costs the pre-flight and the recovery, never the launch
        {
            Activator.logError("Standalone server: cannot read the launch configuration", e); //$NON-NLS-1$
            return new Target(null, null);
        }
    }

    /**
     * The outcome of the recovery stop: whether EDT's server bookkeeping was returned to
     * {@code STOPPED}, and — when it was not — why not.
     */
    public static final class Recovery
    {
        private final boolean recovered;
        private final String detail;
        private final boolean stopStillInFlight;

        private Recovery(boolean recovered, String detail, boolean stopStillInFlight)
        {
            this.recovered = recovered;
            this.detail = detail;
            this.stopStillInFlight = stopStillInFlight;
        }

        /**
         * @return a successful recovery
         */
        static Recovery stopped()
        {
            return new Recovery(true, null, false);
        }

        /**
         * @param detail why the stop did not happen, as a sentence fragment
         * @return a failed recovery
         */
        static Recovery failed(String detail)
        {
            return new Recovery(false, detail, false);
        }

        /**
         * A stop that was neither completed nor abandoned - it was asked to stop and may still be
         * running. Separate from {@link #failed} because the caller's next move differs: nothing
         * is in flight after a plain failure, so the operation may proceed; after this one it may
         * not, or the lingering stop can take its freshly started server down.
         *
         * @param detail why the stop did not finish, as a sentence fragment
         * @return a failed recovery whose stop may still be running
         */
        static Recovery failedInFlight(String detail)
        {
            return new Recovery(false, detail, true);
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

        /**
         * @return {@code true} when the stop was asked to stop but may still be running, so
         *     starting the server now can be undone by it
         */
        public boolean stopStillInFlight()
        {
            return stopStillInFlight;
        }
    }
}

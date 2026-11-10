/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
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
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.Acquisition;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.LaunchLock;
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
    private static final int ABANDONED_LAUNCH_WITH_RECOVERY_STATUS_CODE = 0x4C4152;

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

    private static final long STOP_SHELL_TIMEOUT_MS = 250L;

    /** Log subject of a recovery stop (a stale STARTED server). */
    private static final String STALE_STOP_LOG = "Stale standalone server"; //$NON-NLS-1$

    /** Log subject of an explicit {@code standalone_server} stop. */
    private static final String EXPLICIT_STOP_LOG = "Standalone server (explicit stop)"; //$NON-NLS-1$

    /** How long an explicit start waits to observe the server RUNNING after EDT reported OK. */
    private static final long START_OBSERVE_TIMEOUT_MS = 5_000L;

    /**
     * Cap on an explicit start's unattended dialog guards after an inconclusive wait. Its start
     * CLAIM is not capped: it is released only when the start Job really ends. Mutable only so
     * tests can shrink it.
     */
    static volatile long explicitStartGuardCapMs =
        StandaloneServerSupport.INCONCLUSIVE_START_GUARD_CAP_MS;

    /** Test seam for a UI thread that never answers the bounded shell probe. */
    static volatile LongFunction<LaunchLifecycleUtils.ShellProbe> stopShellProbe =
        LaunchLifecycleUtils::grabActiveShellWithin;

    /**
     * A start we cannot observe arms global dialog guards until the cleanup cap, so it is worth
     * dispatching only when it has a real chance to conclude inside our remaining wait.
     */
    private static final long MIN_RESTORATION_START_WAIT_MS = 5_000L;

    /**
     * Hard cap on how long one application's {@link StopInFlight} record survives without the
     * Job manager's terminal notification. It exists only so a broken Job lifecycle cannot refuse
     * every later start of that application until EDT restarts - the same bound, for the same
     * reason, as {@link StandaloneServerSupport#INCONCLUSIVE_START_GUARD_CAP_MS}.
     *
     * <p>Mutable only so tests can shrink it; production code never reassigns it.
     */
    static volatile long stopInFlightCapMs =
        StandaloneServerSupport.INCONCLUSIVE_START_GUARD_CAP_MS;

    /**
     * Guards that serialize stale-server recovery actions, one per project+application.
     *
     * <p>Deliberately NOT {@link LaunchLifecycleUtils#lockFor}: that lock is held across a whole
     * {@code update_database} publish, so a bounded caller (the {@code build_external_objects} job
     * has a deadline) waiting on it would spend its whole budget there. That lock is itself
     * acquired with a deadline, but a publish-sized one, not this caller's. This lock is acquired
     * with this caller's deadline and held only across "re-read the state, then stop or restore".
     *
     * <p>Only the WAIT for those actions is bounded. {@link BoundedJob} cancels its job but cannot
     * preempt it, so a cleanup the guarded stop dispatched can still be running when the lock is
     * released; the guard therefore also carries a {@link StopInFlight} record of it, which
     * outlives the unlock and is what a later start consults.
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
    public static String stateName(int state)
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
                + "(the launch delegate cancelled it); no reason was logged."); //$NON-NLS-1$
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
        Recovery recovery;
        try
        {
            recovery = stopForRetry(project, applicationId,
                () -> stopServerForRefusal(project, null, applicationId, applicationManager(),
                    refusal));
        }
        catch (ApplicationException held)
        {
            // Same CoreException shape as the pre-flight's refusal in launchWithRecovery.
            throw new CoreException(
                new Status(IStatus.ERROR, Activator.PLUGIN_ID, held.getMessage(), held));
        }
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
            && (failure.getStatus().getCode() == ABANDONED_LAUNCH_STATUS_CODE
                || failure.getStatus().getCode() == ABANDONED_LAUNCH_WITH_RECOVERY_STATUS_CODE);
    }

    /** Whether cancellation has no appended recovery or restoration outcome. */
    public static boolean isPlainAbandonedLaunch(CoreException failure)
    {
        return isAbandonedLaunch(failure) && failure.getStatus().getCode() == ABANDONED_LAUNCH_STATUS_CODE;
    }

    /** Preserves broad abandonment while marking appended recovery detail as non-plain. */
    private static IStatus failureStatus(String message, Throwable failure)
    {
        int code = failure instanceof CoreException && isAbandonedLaunch((CoreException)failure)
            ? ABANDONED_LAUNCH_WITH_RECOVERY_STATUS_CODE : 0;
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
        return updateWithRecovery(manager, project, application, applicationId, updateType, context,
            monitor, null);
    }

    /**
     * {@link #updateWithRecovery(IApplicationManager, IProject, IApplication, String,
     * ApplicationUpdateType, ExecutionContext, IProgressMonitor)} with a guard consulted immediately
     * before EVERY update attempt, the recovery retry included. A guard refusal is thrown as
     * {@link AttemptRefusedException} and is never retried.
     *
     * @param guard the pre-attempt check, or {@code null} for none
     * @return the update state EDT reported
     */
    public static ApplicationUpdateState updateWithRecovery(IApplicationManager manager, // NOSONAR every argument is EDT's own update signature plus what the recovery needs
        IProject project, IApplication application, String applicationId,
        ApplicationUpdateType updateType, ExecutionContext context, IProgressMonitor monitor,
        AttemptGuard guard)
    {
        beginOperation();
        try
        {
            // Before the pre-flight: a refused target must not have its server stopped or restarted.
            checkGuard(guard);
            ensureStartable(project, application, applicationId, manager);
            return attemptUpdates(() -> com.ditrix.edt.mcp.server.HostKind.current()
                == com.ditrix.edt.mcp.server.HostKind.HEADLESS
                    ? HeadlessInfobaseUpdate.update(application, updateType, context, monitor)
                    : manager.update(application, updateType, context, monitor), guard,
                refusal -> stopForRetry(project, applicationId,
                    () -> stopServerForRefusal(project, application, applicationId, manager, refusal)),
                applicationId);
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
     * The attempt sequence of {@link #updateWithRecovery}: guard, update, and on a stale-state
     * refusal one recovery stop, guard again, one retry. Package-private for unit tests.
     */
    static ApplicationUpdateState attemptUpdates(Supplier<ApplicationUpdateState> update,
        AttemptGuard guard, Function<String, Recovery> recover, String applicationId)
    {
        checkGuard(guard);
        try
        {
            return enter(update, guard);
        }
        catch (RuntimeException e)
        {
            String refusal = refusalMessage(e);
            if (refusal == null)
            {
                throw e;
            }
            Recovery recovery = recover.apply(refusal);
            if (!recovery.recovered())
            {
                throw new ApplicationException(
                    staleStateError(applicationId, refusal, recovery, null), e);
            }
            // The stop can take long enough for the caller's precondition to change.
            checkGuard(guard);
            try
            {
                return enter(update, guard);
            }
            catch (RuntimeException retry)
            {
                throw new ApplicationException(staleStateError(applicationId, refusal, recovery,
                    PlatformFailures.describe(retry)), retry);
            }
        }
    }

    private static void checkGuard(AttemptGuard guard)
    {
        String refusal;
        try
        {
            refusal = guard == null ? null : guard.refusalOrNull();
        }
        catch (RuntimeException e)
        {
            // A check that could not run proves nothing; the attempt must not proceed.
            throw new AttemptRefusedException("The pre-update check failed: " //$NON-NLS-1$
                + PlatformFailures.describe(e) + ". Nothing was updated."); //$NON-NLS-1$
        }
        if (refusal != null)
        {
            throw new AttemptRefusedException(refusal);
        }
    }

    private static ApplicationUpdateState enter(Supplier<ApplicationUpdateState> update, AttemptGuard guard)
    {
        if (guard != null)
        {
            guard.updateEntered();
        }
        return update.get();
    }

    /** A check run immediately before each update attempt; a non-null answer refuses the attempt. */
    @FunctionalInterface
    public interface AttemptGuard
    {
        /**
         * @return the refusal text, or {@code null} when the attempt may run
         */
        String refusalOrNull();

        /** Called immediately before the platform update is entered (after a passing check). */
        default void updateEntered()
        {
        }
    }

    /** Thrown when an {@link AttemptGuard} refuses an update attempt; nothing was attempted. */
    public static final class AttemptRefusedException extends ApplicationException
    {
        private static final long serialVersionUID = 1L;

        AttemptRefusedException(String message)
        {
            super(message);
        }
    }

    /**
     * The reactive stop before a retry, followed by the explicit-stop hold check: a hold installed
     * after the pre-flight passed must still keep the retry from starting the server.
     *
     * @param project the project owning the application (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @param stop the recovery stop
     * @return the stop's outcome when the retry may proceed
     * @throws ApplicationException when the server is now held stopped
     */
    static Recovery stopForRetry(IProject project, String applicationId, Supplier<Recovery> stop)
    {
        Recovery recovery = stop.get();
        if (recovery.recovered())
        {
            refuseWhileHeld(project, applicationId, null);
        }
        return recovery;
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
        return stopGuarded(project, application, applicationId, manager, timeoutMs,
            StopPolicy.STALE_ONLY);
    }

    /** Which server states a guarded stop acts on. */
    public enum StopPolicy
    {
        /**
         * Only the stuck state: {@code STARTED} with the owning launch gone. Anything else is
         * somebody's healthy server and is left alone (the recovery's contract).
         */
        STALE_ONLY,
        /**
         * An explicit stop ({@code standalone_server}): any server that is not {@code STOPPED},
         * with or without a live launch; a start or stop in flight is waited out first. It also
         * installs the stop hold BEFORE the cleanup is dispatched.
         */
        RUNNING,
        /**
         * Hold a server the caller observed STOPPED, without stopping anything: the explicit
         * stop of an already stopped server, which asks no consent. Under the guard it holds the
         * server only when it is still STOPPED; a server somebody started meanwhile is refused,
         * because ending its sessions would need a fresh preview and consent.
         */
        HOLD_ONLY
    }

    /** What a guarded stop decided to do about one server state. */
    enum StopDecision
    {
        /** Dispatch EDT's cleanup. */
        STOP,
        /** A start or stop is in flight: wait for it to settle, then decide again. */
        WAIT_SETTLE,
        /** Nothing to stop. */
        NOOP,
        /** The state could not be read; stopping would act on nothing anybody confirmed. */
        UNREADABLE,
        /** A hold-only request found the server no longer STOPPED; nothing may be stopped. */
        NOT_STOPPED
    }

    /**
     * The guarded-stop decision for one server state under a policy, kept pure so the whole rule
     * is testable without an EDT runtime. {@link StopPolicy#STALE_ONLY} is exactly
     * {@link #decide(Integer, Boolean)}.
     *
     * @param state the WST server state, or {@code null} when it could not be read
     * @param liveLaunch whether the server holds a live launch, or {@code null} when unknown
     * @param policy which states the stop acts on
     * @return the decision, never {@code null}
     */
    static StopDecision decideStop(Integer state, Boolean liveLaunch, StopPolicy policy)
    {
        if (policy == StopPolicy.STALE_ONLY)
        {
            return switch (decide(state, liveLaunch))
            {
                case STOP_STALE -> StopDecision.STOP;
                case WAIT_SETTLE -> StopDecision.WAIT_SETTLE;
                case PROCEED -> StopDecision.NOOP;
            };
        }
        if (state == null)
        {
            return StopDecision.UNREADABLE;
        }
        if (isTransitional(state))
        {
            return StopDecision.WAIT_SETTLE;
        }
        if (state == STATE_STOPPED)
        {
            return StopDecision.NOOP;
        }
        // EDT's own stop does nothing for a STOPPED server; every other state - RUNNING with or
        // without a live launch, or one WST names UNKNOWN - is what an explicit stop is for. A
        // hold-only request never stops anything.
        return policy == StopPolicy.HOLD_ONLY ? StopDecision.NOT_STOPPED : StopDecision.STOP;
    }

    /**
     * Stops the standalone server of an application through EDT's own application lifecycle,
     * deciding under the application's recovery guard whether to act at all.
     *
     * <p>{@link StopPolicy#STALE_ONLY} is the recovery's stop, unchanged.
     * {@link StopPolicy#RUNNING} is an explicit stop: it waits out a start or stop in flight
     * ({@link #SETTLE_TIMEOUT_MS}), refuses an unreadable state, and installs the stop hold
     * BEFORE dispatching the cleanup, so a plugin-driven start is refused from that instant on.
     * The hold is kept when the cleanup may still be running and when the server stopped; a
     * cleanup that conclusively failed restores the hold that was there before.
     *
     * @param project the project owning the application (may be {@code null})
     * @param application the application when already resolved (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @param manager the application manager (may be {@code null})
     * @param timeoutMs the caller's remaining deadline
     * @param policy which states the stop acts on
     * @return the outcome, never {@code null}; {@link Recovery#alreadyStopped()} marks a server
     *     an explicit stop found already stopped
     */
    public static Recovery stopGuarded(IProject project, IApplication application,
        String applicationId, IApplicationManager manager, long timeoutMs, StopPolicy policy)
    {
        return stopGuarded(project, application, applicationId, manager, timeoutMs, policy, null);
    }

    /**
     * {@link #stopGuarded(IProject, IApplication, String, IApplicationManager, long, StopPolicy)}
     * that hands the server to {@code beforeDispatch} UNDER the guard, after the final state
     * re-check and immediately before EDT's cleanup is dispatched - so whatever the caller reads
     * from it (the ibsrv process to verify) belongs to the server this call actually stops.
     *
     * @param beforeDispatch called with the WST server right before the cleanup, or {@code null};
     *     not called when nothing is dispatched
     * @return the outcome, never {@code null}
     */
    public static Recovery stopGuarded(IProject project, IApplication application,
        String applicationId, IApplicationManager manager, long timeoutMs, StopPolicy policy,
        Consumer<Object> beforeDispatch)
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
        Acquisition acquisition = guard.lock.tryAcquire(timeoutMs, null);
        if (!acquisition.acquired())
        {
            return Recovery.guardNotAcquired(acquisition);
        }
        try
        {
            if (guard.startClaim.get() != null)
            {
                return Recovery.failed("another standalone start currently claims the server"); //$NON-NLS-1$
            }
            StopInFlight running = guard.stopInFlight.get();
            if (running != null)
            {
                // A cleanup dispatched earlier has not reported finishing. A second one would
                // race it, and neither could then say which server the later one stopped.
                return Recovery.failedInFlight(stopInFlightDetail(running));
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
            StopDecision decision = decideStop(serverState(server), hasLiveLaunch(server), policy);
            if (policy == StopPolicy.STALE_ONLY)
            {
                if (decision != StopDecision.STOP)
                {
                    Activator.logInfo("Standalone server: it is no longer stale (somebody else " //$NON-NLS-1$
                        + "recovered it); leaving it alone: " + applicationId); //$NON-NLS-1$
                    return Recovery.stopped();
                }
            }
            else
            {
                if (decision == StopDecision.WAIT_SETTLE)
                {
                    Integer settled = awaitSettled(server, applicationId);
                    decision = decideStop(settled, hasLiveLaunch(server), policy);
                    if (decision == StopDecision.WAIT_SETTLE)
                    {
                        return Recovery.failed("it is " + stateName(settled.intValue()) //$NON-NLS-1$
                            + " and did not settle within " + (SETTLE_TIMEOUT_MS / 1000) //$NON-NLS-1$
                            + "s: another start or stop holds it"); //$NON-NLS-1$
                    }
                }
                if (decision == StopDecision.UNREADABLE)
                {
                    return Recovery.failed("its state could not be read, so stopping it would " //$NON-NLS-1$
                        + "have acted on a state nothing could confirm"); //$NON-NLS-1$
                }
                if (decision == StopDecision.NOT_STOPPED)
                {
                    Integer now = serverState(server);
                    return Recovery.failed("it is no longer STOPPED (it is " //$NON-NLS-1$
                        + (now == null ? "in an unreadable state" : stateName(now.intValue())) //$NON-NLS-1$
                        + "), and holding it would need its sessions ended first"); //$NON-NLS-1$
                }
                if (decision == StopDecision.NOOP)
                {
                    guard.hold.compareAndSet(null, new StopHold());
                    return Recovery.foundStopped();
                }
            }
            long remainingMs = remainingRecoveryTimeMs(deadline);
            if (remainingMs <= 0L)
            {
                return Recovery.failed("the operation deadline elapsed before the stop began"); //$NON-NLS-1$
            }
            // The cleanup runs on its own job and can outlive the unlock below, so the guard takes
            // ownership of it BEFORE it is dispatched - and, for an explicit stop, before the hold
            // is installed, so nothing can observe the hold without also seeing the stop in flight
            // (a hold is never released while a stop is in flight). Every non-null write happens
            // under this lock; the release is identity-scoped and needs none.
            StopInFlight stop = new StopInFlight(guard);
            guard.stopInFlight.set(stop);
            // An explicit stop holds the server stopped from BEFORE its cleanup is dispatched, so
            // no plugin-driven start can slip in between the stop and the caller's next step.
            StopHold previousHold = null;
            StopHold newHold = null;
            if (policy == StopPolicy.RUNNING)
            {
                newHold = new StopHold();
                previousHold = guard.hold.getAndSet(newHold);
            }
            Recovery outcome = null;
            try
            {
                if (beforeDispatch != null)
                {
                    try
                    {
                        beforeDispatch.accept(server);
                    }
                    catch (RuntimeException e) // NOSONAR a failed read must not leave the in-flight record without its stop
                    {
                        Activator.logError("Standalone server: the pre-dispatch read failed; the stop " //$NON-NLS-1$
                            + "proceeds without it: " + applicationId, e); //$NON-NLS-1$
                    }
                    catch (Error e)
                    {
                        // Nothing was dispatched, so nothing may stay recorded as in flight.
                        stop.release();
                        throw e;
                    }
                }
                outcome = runStop(manager, resolvedApplication, applicationId, remainingMs,
                    stop, policy == StopPolicy.RUNNING ? EXPLICIT_STOP_LOG : STALE_STOP_LOG);
            }
            finally
            {
                if (newHold != null)
                {
                    settleExplicitHold(guard, server, newHold, previousHold, outcome);
                }
            }
            return outcome;
        }
        finally
        {
            guard.lock.unlock();
        }
    }

    /**
     * Decides, after an explicit stop's cleanup, whether its hold stands. Called under the guard.
     *
     * <ul>
     *   <li>stopped: the hold stands - and is put back if anything cleared it meanwhile;</li>
     *   <li>may still be running: the hold stands (the in-flight record refuses starts anyway);</li>
     *   <li>conclusively failed, or the stop threw (its Job has ended - see {@link #runStop}):
     *       the hold stands only when the server reads STOPPED now; otherwise the previous hold
     *       is restored, because the server may well be running and nobody stopped it.</li>
     * </ul>
     */
    private static void settleExplicitHold(RecoveryGuard guard, Object server, StopHold newHold,
        StopHold previousHold, Recovery outcome)
    {
        if (outcome != null && outcome.recovered())
        {
            guard.hold.compareAndSet(null, newHold);
            return;
        }
        if (outcome != null && outcome.stopStillInFlight())
        {
            return;
        }
        Integer state = serverState(server);
        if (state != null && state.intValue() == STATE_STOPPED)
        {
            guard.hold.compareAndSet(null, newHold);
            return;
        }
        guard.hold.compareAndSet(newHold, previousHold);
    }

    /**
     * Installs a hold directly, without the guard's checks. Production code holds a server only
     * through {@link #stopGuarded} ({@link StopPolicy#RUNNING} or {@link StopPolicy#HOLD_ONLY});
     * this exists for tests that need a held server without a stop. The existing hold, if any,
     * keeps its timestamp.
     *
     * @param project the project owning the application (never {@code null})
     * @param applicationId the application id (never {@code null})
     */
    public static void holdStopped(IProject project, String applicationId)
    {
        stopLockFor(project, applicationId).hold.compareAndSet(null, new StopHold());
    }

    /**
     * Clears an explicit stop's hold unconditionally. Production code releases a hold only by a
     * successful {@link #startExplicitly} or by {@link #releaseHoldIfStartedOutside}; this exists
     * for test cleanup. Idempotent.
     *
     * @param project the project owning the application (may be {@code null} - no-op)
     * @param applicationId the application id (may be {@code null} - no-op)
     * @return {@code true} when a hold was released
     */
    public static boolean releaseHold(IProject project, String applicationId)
    {
        RecoveryGuard guard = guardIfPresent(project, applicationId);
        return guard != null && guard.hold.getAndSet(null) != null;
    }

    /** How long a guarded hold release waits for the recovery guard before keeping the hold. */
    private static final long HOLD_RELEASE_LOCK_TIMEOUT_MS = 2_000L;

    /**
     * Releases the hold when somebody started the server outside this plugin (EDT's Servers view):
     * it reads RUNNING with a live launch. Decided under the recovery guard, never while a stop of
     * it is in flight, and only for the hold observed before the guard was taken - a newer hold
     * is never cleared. A guard that does not become available within a short bound keeps the
     * hold: the guard is held by a stop or a start whose outcome this read cannot know.
     *
     * @param project the project owning the application (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @param server the application's WST server (may be {@code null} - nothing is released)
     * @return {@code true} when this call released a hold
     */
    public static boolean releaseHoldIfStartedOutside(IProject project, String applicationId,
        Object server)
    {
        RecoveryGuard guard = guardIfPresent(project, applicationId);
        StopHold observed = guard == null ? null : guard.hold.get();
        if (observed == null || server == null)
        {
            return false;
        }
        Acquisition acquisition = guard.lock.tryAcquire(HOLD_RELEASE_LOCK_TIMEOUT_MS, null);
        if (!acquisition.acquired())
        {
            return false;
        }
        try
        {
            // A claimed start is not "somebody else's" start: an explicit start that timed out
            // keeps its claim while it may still run, and only its own conclusive end releases.
            if (guard.stopInFlight.get() != null || guard.startClaim.get() != null
                || !runningWithLiveLaunch(server) || !guard.hold.compareAndSet(observed, null))
            {
                return false;
            }
            Activator.logInfo("Standalone server: started outside standalone_server, so its " //$NON-NLS-1$
                + "stop hold is released: " + applicationId); //$NON-NLS-1$
            return true;
        }
        finally
        {
            guard.lock.unlock();
        }
    }

    /**
     * When the explicit-stop hold on this application was installed.
     *
     * @param project the project owning the application (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @return the ISO-8601 instant, or {@code null} when the server is not held
     */
    public static String heldSince(IProject project, String applicationId)
    {
        RecoveryGuard guard = guardIfPresent(project, applicationId);
        StopHold hold = guard == null ? null : guard.hold.get();
        return hold == null ? null : hold.since.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * The refusal a plugin-driven start meets while an explicit stop holds the server.
     *
     * @param projectName the project owning the application
     * @param applicationId the application id
     * @param since when the hold was installed (ISO-8601)
     * @return the actionable refusal
     */
    public static String heldStoppedFailure(String projectName, String applicationId, String since)
    {
        return "The standalone server of application '" + applicationId //$NON-NLS-1$
            + "' is held stopped by standalone_server(action='stop') since " + since //$NON-NLS-1$
            + ". Nothing was started. Release it with standalone_server(action='start', " //$NON-NLS-1$
            + "projectName='" + projectName + "', applicationId='" + applicationId //$NON-NLS-1$ //$NON-NLS-2$
            + "'), or inspect it with action='status'. A start from EDT's Servers view also " //$NON-NLS-1$
            + "releases the hold."; //$NON-NLS-1$
    }

    /**
     * Refuses a plugin-driven start while an explicit stop holds the server, unless the server is
     * already RUNNING with a live launch - somebody started it from EDT, which releases the hold.
     *
     * @param project the project owning the application (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @param server the WST server when the caller has it, or {@code null} (the hold then
     *     refuses: whether somebody started the server cannot be read)
     * @throws ApplicationException while the hold stands
     */
    private static void refuseWhileHeld(IProject project, String applicationId, Object server)
    {
        RecoveryGuard guard = guardIfPresent(project, applicationId);
        StopHold hold = guard == null ? null : guard.hold.get();
        if (hold == null)
        {
            return;
        }
        if (releaseHoldIfStartedOutside(project, applicationId, server))
        {
            return;
        }
        StopHold current = guard.hold.get();
        if (current == null)
        {
            // Released by a concurrent explicit start or observer; nothing holds it any more.
            return;
        }
        throw new ApplicationException(heldStoppedFailure(project.getName(), applicationId,
            current.since.truncatedTo(ChronoUnit.SECONDS).toString()));
    }

    /**
     * {@link #refuseWhileHeld} for a caller that has no server: resolves it from the application,
     * or by a bounded lookup of {@code project + applicationId} (never by a bare server name),
     * only when a hold exists. A server that cannot be resolved keeps the hold refusing.
     */
    private static void refuseWhileHeldResolving(IProject project, IApplication application,
        String applicationId, IApplicationManager manager)
    {
        RecoveryGuard guard = guardIfPresent(project, applicationId);
        if (guard == null || guard.hold.get() == null)
        {
            return;
        }
        Object server = null;
        if (application != null)
        {
            server = serverQuietly(application);
        }
        else if (manager != null)
        {
            try
            {
                StandaloneServerSupport.ApplicationLookup lookup =
                    StandaloneServerSupport.lookupApplicationBounded(manager, project,
                        applicationId, StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS);
                server = lookup.failure() != null ? null : serverQuietly(lookup.application());
            }
            catch (RuntimeException e) // NOSONAR an unresolvable server keeps the hold refusing
            {
                server = null;
            }
        }
        refuseWhileHeld(project, applicationId, server);
    }

    /** Whether the server is STARTED and still holds a live launch (EDT's "already running"). */
    public static boolean runningWithLiveLaunch(Object server)
    {
        Integer state = serverState(server);
        return state != null && state.intValue() == STATE_STARTED
            && Boolean.TRUE.equals(hasLiveLaunch(server));
    }

    /** The server behind an application, or {@code null} on any failure. */
    private static Object serverQuietly(IApplication application)
    {
        try
        {
            return resolveServer(application);
        }
        catch (RuntimeException e) // NOSONAR the hold check must not fail an operation by itself
        {
            return null;
        }
    }

    /** How an explicit start ended. */
    public enum StartKind
    {
        /** EDT reported the start OK; the hold is released. */
        STARTED,
        /** The server was already RUNNING with a live launch; the hold is released. */
        ALREADY_RUNNING,
        /** Nothing was dispatched; {@link StartOutcome#detail()} says why. */
        REFUSED,
        /** The start was dispatched and did not succeed (or did not finish); the hold stays. */
        FAILED
    }

    /** Outcome of {@link #startExplicitly}. */
    public static final class StartOutcome
    {
        private final StartKind kind;
        private final String detail;
        private final boolean conclusive;
        private final boolean portsReassigned;
        private final boolean portReassignmentOutcomeUnknown;

        private StartOutcome(StartKind kind, String detail, boolean conclusive,
            boolean portsReassigned, boolean portReassignmentOutcomeUnknown)
        {
            this.kind = kind;
            this.detail = detail;
            this.conclusive = conclusive;
            this.portsReassigned = portsReassigned;
            this.portReassignmentOutcomeUnknown = portReassignmentOutcomeUnknown;
        }

        static StartOutcome refused(String detail)
        {
            return new StartOutcome(StartKind.REFUSED, detail, true, false, false);
        }

        /** @return how the start ended */
        public StartKind kind()
        {
            return kind;
        }

        /** @return why it was refused or failed, or {@code null} on success */
        public String detail()
        {
            return detail;
        }

        /** @return {@code false} when a dispatched start may still be running */
        public boolean conclusive()
        {
            return conclusive;
        }

        /** @return whether EDT moved the server to other ports (policy reassign) */
        public boolean portsReassigned()
        {
            return portsReassigned;
        }

        /** @return whether an unfinished reassign start may still rewrite the configuration */
        public boolean portReassignmentOutcomeUnknown()
        {
            return portReassignmentOutcomeUnknown;
        }
    }

    /**
     * Starts the standalone server of an application on an explicit request
     * ({@code standalone_server}), under the same recovery guard and start-claim lifecycle the
     * restoration start uses, and releases the explicit-stop hold once EDT reports the start OK.
     *
     * <p>A server already RUNNING with a live launch is left alone (and released); a start or stop
     * in flight is waited out; a stale {@code STARTED} server whose launch is gone is first
     * returned to {@code STOPPED} by the recovery's own stop. A failed or unfinished start keeps
     * the hold.
     *
     * @param project the project owning the application
     * @param application the resolved application
     * @param applicationId the application id
     * @param manager the application manager
     * @param service the EDT standalone-server service (reflective)
     * @param portPolicy the answer to EDT's port-conflict prompt
     * @param timeoutMs the bound on the whole start
     * @return the outcome, never {@code null}
     */
    public static StartOutcome startExplicitly(IProject project, IApplication application,
        String applicationId, IApplicationManager manager, Object service,
        StandaloneServerPortConflictPolicy portPolicy, long timeoutMs)
    {
        if (project == null || application == null || applicationId == null || manager == null)
        {
            return StartOutcome.refused("the start target is incomplete"); //$NON-NLS-1$
        }
        if (service == null)
        {
            return StartOutcome.refused("the EDT standalone-server service is not available"); //$NON-NLS-1$
        }
        long deadline = recoveryDeadline(timeoutMs);
        RecoveryGuard guard = stopLockFor(project, applicationId);
        Acquisition acquisition = guard.lock.tryAcquire(timeoutMs, null);
        if (!acquisition.acquired())
        {
            return StartOutcome.refused(recoveryLockFailure(acquisition));
        }
        try
        {
            if (guard.startClaim.get() != null)
            {
                return StartOutcome.refused("another standalone start currently claims the server"); //$NON-NLS-1$
            }
            StopInFlight running = guard.stopInFlight.get();
            if (running != null)
            {
                return StartOutcome.refused(stopInFlightDetail(running));
            }
            Object server = resolveServer(application);
            if (server == null)
            {
                return StartOutcome.refused("EDT could not resolve the application's standalone server"); //$NON-NLS-1$
            }
            if (runningWithLiveLaunch(server))
            {
                guard.hold.set(null);
                return new StartOutcome(StartKind.ALREADY_RUNNING, null, true, false, false);
            }
            Integer state = serverState(server);
            if (isTransitional(state))
            {
                state = awaitSettled(server, applicationId);
                if (isTransitional(state))
                {
                    return StartOutcome.refused("it is " + stateName(state.intValue()) //$NON-NLS-1$
                        + " and did not settle within " + (SETTLE_TIMEOUT_MS / 1000) //$NON-NLS-1$
                        + "s: another start or stop holds it"); //$NON-NLS-1$
                }
                if (runningWithLiveLaunch(server))
                {
                    guard.hold.set(null);
                    return new StartOutcome(StartKind.ALREADY_RUNNING, null, true, false, false);
                }
            }
            Boolean liveLaunch = hasLiveLaunch(server);
            if (state != null && state.intValue() == STATE_STARTED && Boolean.FALSE.equals(liveLaunch))
            {
                long remainingMs = remainingRecoveryTimeMs(deadline);
                if (remainingMs <= 0L)
                {
                    return StartOutcome.refused(
                        "the deadline elapsed before its stale STARTED state could be normalized"); //$NON-NLS-1$
                }
                // Re-entry stays on this thread, exactly as the restoration's normalization does.
                Recovery normalized = stopStaleServerGuarded(project, application, applicationId,
                    manager, remainingMs);
                if (!normalized.recovered())
                {
                    return StartOutcome.refused("its stale STARTED state could not be normalized " //$NON-NLS-1$
                        + "to STOPPED: " + normalized.detail()); //$NON-NLS-1$
                }
                state = serverState(server);
                liveLaunch = hasLiveLaunch(server);
            }
            if (state == null || liveLaunch == null)
            {
                return StartOutcome.refused("its current state could not be confirmed"); //$NON-NLS-1$
            }
            if (state.intValue() != STATE_STOPPED)
            {
                return StartOutcome.refused("its current state is " + stateName(state.intValue()) //$NON-NLS-1$
                    + ", not STOPPED"); //$NON-NLS-1$
            }
            long remainingMs = remainingRecoveryTimeMs(deadline);
            if (remainingMs < MIN_RESTORATION_START_WAIT_MS)
            {
                return StartOutcome.refused("too little time remained to observe a start"); //$NON-NLS-1$
            }
            StartClaim claim = new StartClaim(guard);
            guard.startClaim.set(claim);
            try
            {
                StandaloneServerSupport.GuardedStartResult result =
                    StandaloneServerSupport.startServerGuarded(service, server,
                        "Starting standalone server: " + applicationId, ILaunchManager.DEBUG_MODE, //$NON-NLS-1$
                        LaunchLifecycleUtils.conflictAttributionName(application),
                        StandaloneServerSupport.nameOfServer(server), portPolicy, remainingMs,
                        explicitStartGuardCapMs, claim::close, claim::handoff, true);
                String failure = result.failure();
                if (failure != null)
                {
                    return new StartOutcome(StartKind.FAILED, failure, result.conclusive(),
                        result.portsReassigned(), result.portReassignmentOutcomeUnknown());
                }
                // The observation lives inside the whole start's bound, not after it: with no time
                // left it is one immediate read, and an unobserved STARTED is inconclusive below.
                long observeMs = Math.min(START_OBSERVE_TIMEOUT_MS, remainingRecoveryTimeMs(deadline));
                Integer observed = awaitState(server, STATE_STARTED, observeMs);
                if (observed == null || observed.intValue() != STATE_STARTED)
                {
                    // EDT said OK, but the server does not read STARTED: the start is not proven,
                    // so the hold stays. STARTING (or an unreadable state) may still turn into a
                    // running server; anything else is a start that did not take.
                    boolean mayStillStart = observed == null || observed.intValue() == STATE_STARTING;
                    return new StartOutcome(StartKind.FAILED, "EDT reported the start as done, but the " //$NON-NLS-1$
                        + "server reads " + (observed == null ? "an unreadable state" //$NON-NLS-1$ //$NON-NLS-2$
                            : stateName(observed.intValue()))
                        + " " + observeMs + "ms later", //$NON-NLS-1$ //$NON-NLS-2$
                        !mayStillStart, result.portsReassigned(), false);
                }
                guard.hold.set(null);
                return new StartOutcome(StartKind.STARTED, null, true, result.portsReassigned(),
                    false);
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

    /** Polls, bounded, until the server reaches {@code wanted}; returns the last state read. */
    private static Integer awaitState(Object server, int wanted, long timeoutMs)
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        Integer state = serverState(server);
        while ((state == null || state.intValue() != wanted) && System.nanoTime() - deadline < 0L)
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
        return state;
    }

    /** The guard of an application, read without creating one. */
    private static RecoveryGuard guardIfPresent(IProject project, String applicationId)
    {
        if (project == null || applicationId == null)
        {
            return null;
        }
        return STOP_LOCKS.get(stopLockKey(project, applicationId));
    }

    /**
     * Same locked recheck, with cleanup executed by the caller's enclosing bounded Job.
     *
     * <p>This one registers no {@link StopInFlight} record and needs none: its cleanup runs
     * between the lock and the unlock below, on this very thread, so the lock itself is already
     * the record. Even when the caller's own bounded wait abandons the enclosing Job, that Job
     * keeps the lock until {@code cleanup} returns, and a later start is refused by the lock.
     */
    private static Recovery stopStaleServerGuardedWithinBound(IProject project,
        IApplication application, String applicationId, IApplicationManager manager,
        IProgressMonitor monitor, Runnable stopStarting, Runnable stopCompleted)
    {
        if (project == null || application == null || applicationId == null || manager == null)
        {
            return Recovery.failed("the stale-server target is incomplete"); //$NON-NLS-1$
        }
        RecoveryGuard guard = stopLockFor(project, applicationId);
        Acquisition acquisition = guard.lock.tryAcquire(
            StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS, monitor);
        if (!acquisition.acquired())
        {
            return Recovery.guardNotAcquired(acquisition);
        }
        try
        {
            if (guard.startClaim.get() != null)
            {
                return Recovery.failed("another standalone start currently claims the server"); //$NON-NLS-1$
            }
            StopInFlight running = guard.stopInFlight.get();
            if (running != null)
            {
                return Recovery.failedInFlight(stopInFlightDetail(running));
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
        return STOP_LOCKS.computeIfAbsent(stopLockKey(project, applicationId),
            k -> new RecoveryGuard());
    }

    /**
     * The guard key for one application. NUL separator for the same reason
     * {@code LaunchLifecycleUtils.lockFor} uses one: project names and application ids both
     * contain spaces, so any printable separator can collide.
     *
     * @param project the project owning the application (never {@code null})
     * @param applicationId the application id (never {@code null})
     * @return the key, never {@code null}
     */
    private static String stopLockKey(IProject project, String applicationId)
    {
        return project.getName() + "\u0000" + applicationId; //$NON-NLS-1$
    }

    /**
     * The stop of this application whose cleanup has not reported finishing, read without
     * creating a guard for an application that never had one.
     *
     * @param project the project owning the application (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @return the record, or {@code null} when no dispatched cleanup is outstanding
     */
    private static StopInFlight stopInFlightFor(IProject project, String applicationId)
    {
        if (project == null || applicationId == null)
        {
            return null;
        }
        RecoveryGuard guard = STOP_LOCKS.get(stopLockKey(project, applicationId));
        return guard == null ? null : guard.stopInFlight.get();
    }

    /**
     * How long ago the stop of this application whose cleanup has not reported finishing started.
     *
     * @param project the project owning the application (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @return whole seconds, or {@code null} when no dispatched cleanup is outstanding
     */
    public static Long stopInFlightSeconds(IProject project, String applicationId)
    {
        StopInFlight stop = stopInFlightFor(project, applicationId);
        return stop == null ? null : Long.valueOf(stop.elapsedSeconds());
    }

    /**
     * How long ago the start that currently claims this application began - an explicit start
     * whose Job has not ended keeps its claim until it does.
     *
     * @param project the project owning the application (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @return whole seconds, or {@code null} when no start claims the application
     */
    public static Long startClaimSeconds(IProject project, String applicationId)
    {
        RecoveryGuard guard = guardIfPresent(project, applicationId);
        StartClaim claim = guard == null ? null : guard.startClaim.get();
        return claim == null ? null
            : Long.valueOf((System.currentTimeMillis() - claim.startedAtMs) / 1000L);
    }

    /** Shared diagnosis for an application whose dispatched stop has not reported finishing. */
    private static String stopInFlightDetail(StopInFlight stop)
    {
        return "an earlier stop of it started " + stop.elapsedSeconds() //$NON-NLS-1$
            + "s ago and has not reported finishing"; //$NON-NLS-1$
    }

    /**
     * Refuses a start while a stop of the same application may still be running.
     *
     * <p>Consulted BEFORE the server state is read, because that state cannot answer the
     * question: a cleanup that got as far as STOPPED and then wedged leaves a state every start
     * reads as perfectly startable, and the server it brings up is the one the belatedly
     * finishing cleanup takes down.
     *
     * @param project the project owning the application (may be {@code null})
     * @param applicationId the application id (may be {@code null})
     * @throws ApplicationException when a dispatched cleanup has not reported finishing
     */
    private static void refuseWhileStopInFlight(IProject project, String applicationId)
    {
        StopInFlight stop = stopInFlightFor(project, applicationId);
        if (stop != null)
        {
            throw new ApplicationException(
                preflightStopInFlightFailure(applicationId, stopInFlightDetail(stop)));
        }
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

    /** Names the actual acquisition failure before any guarded ownership check could run. */
    private static String recoveryLockFailure(Acquisition acquisition)
    {
        return switch (acquisition)
        {
            case CONTENDED -> "its recovery guard did not become available before the deadline, " //$NON-NLS-1$
                + "so the server's ownership could not be confirmed"; //$NON-NLS-1$
            case INTERRUPTED -> "the wait for its recovery guard was interrupted, " //$NON-NLS-1$
                + "so ownership was not checked under the guard"; //$NON-NLS-1$
            case CANCELLED -> "acquiring its recovery guard was cancelled, " //$NON-NLS-1$
                + "so ownership was not checked under the guard"; //$NON-NLS-1$
            case ACQUIRED -> throw new IllegalArgumentException("The recovery guard was acquired"); //$NON-NLS-1$
        };
    }

    /**
     * Claims an ordinary start before it leaves the caller's bounded preparation phase.
     * @throws ApplicationException when lock acquisition fails, with its actual reason
     */
    public static StartClaim claimStartWithinBound(IProject project, String applicationId,
        IProgressMonitor monitor)
    {
        return claimStartWithinBound(project, applicationId, null,
            StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS, monitor);
    }

    /**
     * Same claim with the application's WST server, so an explicit-stop hold on a server somebody
     * started from EDT is released instead of refusing the claim.
     */
    public static StartClaim claimStartWithinBound(IProject project, String applicationId,
        Object server, IProgressMonitor monitor)
    {
        return claimStartWithinBound(project, applicationId, server,
            StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS, monitor);
    }

    /** Testable deadline form of the start claim. */
    static StartClaim claimStartWithinBound(IProject project, String applicationId,
        long lockTimeoutMs, IProgressMonitor monitor)
    {
        return claimStartWithinBound(project, applicationId, null, lockTimeoutMs, monitor);
    }

    /** Testable deadline form of the start claim with the server, when the caller has it. */
    static StartClaim claimStartWithinBound(IProject project, String applicationId, Object server,
        long lockTimeoutMs, IProgressMonitor monitor)
    {
        if (project == null || applicationId == null)
        {
            return null;
        }
        RecoveryGuard guard = stopLockFor(project, applicationId);
        Acquisition acquisition = guard.lock.tryAcquire(lockTimeoutMs, monitor);
        if (!acquisition.acquired())
        {
            throw new ApplicationException("The standalone server '" + applicationId //$NON-NLS-1$
                + "' could not be claimed: " + recoveryLockFailure(acquisition) //$NON-NLS-1$
                + ". Check debug_status before retrying launch."); //$NON-NLS-1$
        }
        try
        {
            // An explicit stop's hold refuses outright: returning null would read as "another
            // start owns it" and let the caller proceed without a claim.
            refuseWhileHeld(project, applicationId, server);
            if (guard.startClaim.get() != null)
            {
                return null;
            }
            if (guard.stopInFlight.get() != null)
            {
                // A dispatched cleanup owns this server in the same sense a claim does: it, not
                // this start, decides what state the server ends in.
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
        if (!guard.lock.tryAcquire(1_000L, null).acquired())
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
     * <p>It takes no recovery guard and records no {@link StopInFlight}, so it claims no
     * ownership of the server: a caller that goes on to START one must use the guarded pre-flight
     * ({@link #ensureStartable}) instead.
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
        return runStop(manager, application, applicationId, timeoutMs, null);
    }

    /**
     * Same bounded cleanup while a guard owns the fact that it can outlive the wait for it.
     *
     * <p>{@code stopInFlight} is released the moment the work has DEFINITELY ended, and not
     * before: immediately for a conclusive bounded wait, on the Job manager's terminal
     * notification after a timeout or an interruption, and by {@link #stopInFlightCapMs} if that
     * notification never arrives. The three converge on one exactly-once release.
     *
     * @param manager the application manager
     * @param application the application whose server is stopped
     * @param applicationId the application id (for the job name and the log)
     * @param timeoutMs the caller's remaining deadline
     * @param stopInFlight the guard's record of this cleanup, or {@code null} for a stop no guard
     *     owns
     * @return the outcome, never {@code null}
     */
    private static Recovery runStop(IApplicationManager manager, IApplication application,
        String applicationId, long timeoutMs, StopInFlight stopInFlight)
    {
        return runStop(manager, application, applicationId, timeoutMs, stopInFlight,
            STALE_STOP_LOG);
    }

    /** Same bounded cleanup, logged under the caller's subject (a stale or an explicit stop). */
    private static Recovery runStop(IApplicationManager manager, IApplication application,
        String applicationId, long timeoutMs, StopInFlight stopInFlight, String logSubject)
    {
        BoundedJob.DeferredCleanup ownership;
        if (stopInFlight == null)
        {
            ownership = null;
        }
        else if (EXPLICIT_STOP_LOG.equals(logSubject))
        {
            // An explicit stop keeps its record until EDT's cleanup Job has really ended: a time
            // cap would let a start (and the hold's release) slip in while the belated cleanup can
            // still take the new server down. A Job that never ends keeps refusing starts with a
            // text naming the way out (Servers view, EDT restart); the stale recovery keeps its cap.
            ownership = BoundedJob.DeferredCleanup.untilJobFinished(stopInFlight::release);
        }
        else
        {
            ownership = new BoundedJob.DeferredCleanup(stopInFlight::release, stopInFlightCapMs,
                "Standalone server stop ownership safety cap: " + applicationId); //$NON-NLS-1$
        }
        BoundedJob.Result result = null;
        try
        {
            result = runStopBounded(manager, application, applicationId, timeoutMs, ownership,
                logSubject);
            return classifyStop(result, applicationId, timeoutMs, logSubject);
        }
        finally
        {
            // The concrete outcome is the sole authority on whether ownership may be released
            // now; a null result means BoundedJob rethrew an Error, which it does only after its
            // Job has already terminated.
            if (ownership != null)
            {
                ownership.afterBoundedWait(result == null
                    || !BoundedJob.isInconclusive(result.getOutcome()));
            }
        }
    }

    /** Dispatches EDT's cleanup on its own job under the caller's deadline. */
    private static BoundedJob.Result runStopBounded(IApplicationManager manager,
        IApplication application, String applicationId, long timeoutMs,
        BoundedJob.DeferredCleanup ownership, String logSubject)
    {
        Activator.logInfo(logSubject + ": stopping it" //$NON-NLS-1$
            + (STALE_STOP_LOG.equals(logSubject) ? " so the operation can proceed: " : ": ") //$NON-NLS-1$ //$NON-NLS-2$
            + applicationId);
        return BoundedJob.run("Stopping standalone server: " + applicationId, //$NON-NLS-1$
            timeoutMs, monitor -> manager.cleanup(application, stopContext(timeoutMs), monitor),
            ownership == null ? null : ownership::jobFinished);
    }

    /** EDT's server cleanup does not read the context; a shell is optional. */
    private static ExecutionContext stopContext(long timeoutMs)
    {
        ExecutionContext context = new ExecutionContext();
        Shell shell = stopShellProbe.apply(Math.min(STOP_SHELL_TIMEOUT_MS,
            Math.max(0L, timeoutMs))).shell();
        if (shell != null)
        {
            context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
        }
        return context;
    }

    /** Turns one bounded stop outcome into the recovery contract its callers act on. */
    private static Recovery classifyStop(BoundedJob.Result result, String applicationId,
        long timeoutMs, String logSubject)
    {
        if (result.isSuccess())
        {
            if (STALE_STOP_LOG.equals(logSubject))
            {
                // Only a recovery stop is one the current operation may restore afterwards.
                recordStoppedServer(applicationId);
            }
            Activator.logInfo(logSubject + ": stopped: " + applicationId); //$NON-NLS-1$
            return Recovery.stopped();
        }
        // The OUTCOME is classified before the failure, not after it: an INTERRUPTED run carries
        // its InterruptedException in getFailure(), so a failure-first branch would answer
        // "nothing is in flight" for the very outcome that means the opposite - the job was only
        // ASKED to stop and may still be running.
        BoundedJob.Outcome outcome = result.getOutcome();
        if (outcome == BoundedJob.Outcome.TIMED_OUT || outcome == BoundedJob.Outcome.INTERRUPTED)
        {
            Activator.logError(logSubject + ": stopping it did not finish (" //$NON-NLS-1$
                + outcome + "): " + applicationId, result.getFailure()); //$NON-NLS-1$
            return Recovery.failedInFlight(outcome == BoundedJob.Outcome.INTERRUPTED
                ? "the wait for it was interrupted" //$NON-NLS-1$
                : "stopping it did not finish within " //$NON-NLS-1$
                    + (timeoutMs / 1000) + "s"); //$NON-NLS-1$
        }
        if (result.getFailure() != null)
        {
            Activator.logError(logSubject + ": stopping it failed: " + applicationId, //$NON-NLS-1$
                result.getFailure());
            return Recovery.failedAfterDispatch("stopping it failed: " //$NON-NLS-1$
                + PlatformFailures.describe(result.getFailure()));
        }
        Activator.logError(logSubject + ": stopping it did not run (" //$NON-NLS-1$
            + outcome + "): " + applicationId, null); //$NON-NLS-1$
        return Recovery.failed("it never ran (" + outcome + ")"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Executes cleanup directly when an enclosing bounded Job already owns the deadline. */
    private static Recovery runStopWithinBound(IApplicationManager manager,
        IApplication application, String applicationId, IProgressMonitor monitor,
        Runnable stopCompleted)
    {
        ExecutionContext context = stopContext(STOP_SHELL_TIMEOUT_MS);
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
        Acquisition acquisition = guard.lock.tryAcquire(timeoutMs, null);
        if (!acquisition.acquired())
        {
            return RestorationStartOutcome.skipped(
                "was not restored because " + recoveryLockFailure(acquisition) + "."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            if (guard.startClaim.get() != null)
            {
                return RestorationStartOutcome.skipped(
                    "is claimed by another launch that has not started it yet, so restoration " //$NON-NLS-1$
                        + "was skipped."); //$NON-NLS-1$
            }
            StopInFlight stopping = guard.stopInFlight.get();
            if (stopping != null)
            {
                return RestorationStartOutcome.skipped("was not restored because " //$NON-NLS-1$
                    + stopInFlightDetail(stopping) + "."); //$NON-NLS-1$
            }
            if (guard.hold.get() != null)
            {
                return RestorationStartOutcome.skipped("was not restored because " //$NON-NLS-1$
                    + "standalone_server(action='stop') holds it stopped; start it with " //$NON-NLS-1$
                    + "standalone_server(action='start')."); //$NON-NLS-1$
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
            if (remainingMs < MIN_RESTORATION_START_WAIT_MS)
            {
                return RestorationStartOutcome.skipped(
                    "was stopped for this operation and was left stopped because too little " //$NON-NLS-1$
                        + "time remained to observe a restoration start. Start it again with " //$NON-NLS-1$
                        + "the launch tool."); //$NON-NLS-1$
            }
            // The guarded start gets only this remainder; an inconclusive start retains the
            // claim through cleanup.
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

    /**
     * One application's bounded recovery lock, its current ordinary-start claim, and the stop
     * whose cleanup may still be running.
     *
     * <p>The last two exist for the same reason: the lock is released long before the work it
     * serialized has necessarily ended, so what outlives the unlock has to be recorded on the
     * guard itself rather than inferred from the server's state.
     */
    private static final class RecoveryGuard
    {
        private final LaunchLock lock = new LaunchLock();
        private final AtomicReference<StartClaim> startClaim = new AtomicReference<>();
        private final AtomicReference<StopInFlight> stopInFlight = new AtomicReference<>();
        /** Set by an explicit {@code standalone_server} stop; refuses plugin-driven starts. */
        private final AtomicReference<StopHold> hold = new AtomicReference<>();
    }

    /**
     * An explicit stop's promise that the server stays stopped until an explicit start.
     *
     * <p>In memory only: an EDT restart clears it, and EDT's own UI (the Servers view, a Run
     * button) never consults it. A plugin-driven start that finds the server already RUNNING with
     * a live launch (somebody started it from EDT) releases it instead of refusing.
     */
    private static final class StopHold
    {
        private final Instant since = Instant.now();
    }

    /**
     * A stale-server stop dispatched to a job that can outlive the bounded wait for it.
     *
     * <p>{@link BoundedJob} cancels its job but cannot preempt it, so {@code cleanup} keeps
     * running after the guard is unlocked. Without this record an unrelated later start finds a
     * neutral guard, brings a server up, and the belatedly finishing cleanup stops THAT one.
     *
     * <p>It cannot become immortal: {@link BoundedJob.DeferredCleanup} releases it as soon as the
     * work has definitely ended, on the Job manager's terminal notification (which the platform
     * also fires for a job cancelled before it ever ran), and in any case at
     * {@link #stopInFlightCapMs}.
     */
    private static final class StopInFlight
    {
        private final RecoveryGuard guard;
        private final long startedAtMs = System.currentTimeMillis();
        private final AtomicBoolean released = new AtomicBoolean();

        private StopInFlight(RecoveryGuard guard)
        {
            this.guard = guard;
        }

        /** Whole seconds since the cleanup was dispatched. */
        long elapsedSeconds()
        {
            return (System.currentTimeMillis() - startedAtMs) / 1000L;
        }

        /** Releases this exact record without consuming a newer stop's. */
        void release()
        {
            if (released.compareAndSet(false, true))
            {
                guard.stopInFlight.compareAndSet(this, null);
            }
        }
    }

    /** Exclusive intention to start a server whose WST state may still read as STOPPED. */
    public static final class StartClaim implements AutoCloseable
    {
        private final RecoveryGuard guard;
        private final long startedAtMs = System.currentTimeMillis();
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
     * @throws ApplicationException when recovery ownership could not be checked, or a stop of
     *     that server did not finish and may still be running
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
        // Before the state read, not after it: a foreign cleanup that already reached STOPPED
        // leaves a state this pre-flight would happily proceed on.
        refuseWhileStopInFlight(project, applicationId);
        // Before any early return below: an explicit stop's hold must refuse even a start whose
        // state this pre-flight could not read.
        refuseWhileHeldResolving(project, application, applicationId, manager);
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
        refuseWhileStopInFlight(project, applicationId);
        refuseWhileHeld(project, applicationId, server);
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
        ensureDefaultApplicationStartableThen(project, 0L, null, null, null);
    }

    /**
     * The default-application pre-flight followed by {@code work} (the dump that prepares, and so
     * starts, that application), BOTH under the application's infobase lock
     * ({@link LaunchLifecycleUtils#lockFor}) - the lock an explicit {@code standalone_server}
     * stop takes first. Without it a confirmed stop could land between the pre-flight and the
     * prepare, and the work would start the server the stop just held.
     *
     * <p>When the default application cannot be resolved, the pre-flight is skipped exactly as
     * {@link #ensureDefaultApplicationStartable} always did (logged) and {@code work} runs unlocked:
     * there is no application id to lock, and EDT's own prepare then fails on its own terms.
     *
     * @param project the project whose default application the work prepares (may be {@code null})
     * @param lockTimeoutMs the bound on waiting for the application's lock
     * @param monitor cancellation for the lock wait (may be {@code null})
     * @param nothingDone the sentence appended to a lock refusal, e.g. {@code "Nothing was built."}
     * @param work the work to run, or {@code null} for the pre-flight alone (then no lock is taken)
     * @throws ApplicationException when the lock is not acquired, or the pre-flight refuses
     */
    public static void ensureDefaultApplicationStartableThen(IProject project, long lockTimeoutMs,
        IProgressMonitor monitor, String nothingDone, Runnable work)
    {
        if (project == null)
        {
            runIfPresent(work);
            return;
        }
        Activator activator = Activator.getDefault();
        IApplicationManager manager = activator == null ? null : activator.getApplicationManager();
        if (manager == null)
        {
            runIfPresent(work);
            return;
        }
        LaunchLifecycleUtils.DefaultApplicationLookup defaultApp = LaunchLifecycleUtils
            .resolveDefaultApplication(project, null, manager, ApplicationSupport.LOOKUP_TIMEOUT_MS);
        if (defaultApp.inconclusive())
        {
            // The pre-flight is skipped, and that must not be silent (#622). This method has no
            // channel back to the tool, so the skip otherwise surfaces only much later as EDT's own
            // "can only start a stopped server" refusal — the very refusal the pre-flight exists to
            // prevent — with nothing anywhere saying why the check did not run.
            Activator.logError("Standalone-server pre-flight skipped for project " //$NON-NLS-1$
                + project.getName() + ": the project's default application could not be resolved (" //$NON-NLS-1$
                + defaultApp.failure()
                + "). A stale STARTED server will not be settled before the next start.", null); //$NON-NLS-1$
            runIfPresent(work);
            return;
        }
        defaultApplicationStartableThen(project, manager, defaultApp.id(), lockTimeoutMs, monitor,
            nothingDone, work);
    }

    /** The resolved-default-application half of {@link #ensureDefaultApplicationStartableThen}. */
    static void defaultApplicationStartableThen(IProject project, IApplicationManager manager,
        String applicationId, long lockTimeoutMs, IProgressMonitor monitor, String nothingDone,
        Runnable work)
    {
        if (work == null || applicationId == null || applicationId.isBlank())
        {
            ensureStartable(project, null, applicationId, manager);
            runIfPresent(work);
            return;
        }
        runUnderApplicationLock(project.getName(), applicationId, lockTimeoutMs, monitor,
            nothingDone, () -> {
                ensureStartable(project, null, applicationId, manager);
                work.run();
            });
    }

    /**
     * Runs {@code work} holding {@link LaunchLifecycleUtils#lockFor} for one application, acquired
     * within a bound.
     *
     * @throws ApplicationException naming why the lock was not acquired, plus {@code nothingDone}
     */
    static void runUnderApplicationLock(String projectName, String applicationId, long timeoutMs,
        IProgressMonitor monitor, String nothingDone, Runnable work)
    {
        LaunchLock lock = LaunchLifecycleUtils.lockFor(projectName, applicationId);
        Acquisition acquisition = lock.tryAcquire(timeoutMs, monitor);
        if (!acquisition.acquired())
        {
            throw new ApplicationException(LaunchLifecycleUtils.lockNotAcquiredMessage(acquisition,
                projectName, applicationId, timeoutMs)
                + (nothingDone == null ? "" : " " + nothingDone) //$NON-NLS-1$ //$NON-NLS-2$
                + " Wait for that operation to finish and retry."); //$NON-NLS-1$
        }
        try
        {
            work.run();
        }
        finally
        {
            lock.unlock();
        }
    }

    private static void runIfPresent(Runnable work)
    {
        if (work != null)
        {
            work.run();
        }
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
        if (recovery.guardFailure != null)
        {
            throw new ApplicationException("Recovery did not stop the standalone server of application '" //$NON-NLS-1$
                + applicationId + "': " + recovery.detail() //$NON-NLS-1$
                + ". Check debug_status and the Servers view in EDT before retrying."); //$NON-NLS-1$
        }
        if (recovery.stopStillInFlight())
        {
            // The stop was neither completed nor abandoned: BoundedJob cancels the job but cannot
            // preempt it, so it may finish later - and stop whatever server is running by then,
            // including the one this operation is about to start. Refusing here costs the caller a
            // retry; proceeding would cost them a server that dies under them. The guard keeps
            // refusing on the same grounds until that cleanup reports finishing.
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
    public static Integer serverState(Object server)
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
    public static Boolean hasLiveLaunch(Object server)
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
        private final Acquisition guardFailure;
        private final boolean alreadyStopped;
        private boolean dispatched;

        private Recovery(boolean recovered, String detail, boolean stopStillInFlight)
        {
            this(recovered, detail, stopStillInFlight, null, false);
        }

        private Recovery(boolean recovered, String detail, boolean stopStillInFlight,
            Acquisition guardFailure)
        {
            this(recovered, detail, stopStillInFlight, guardFailure, false);
        }

        private Recovery(boolean recovered, String detail, boolean stopStillInFlight,
            Acquisition guardFailure, boolean alreadyStopped)
        {
            this.recovered = recovered;
            this.detail = detail;
            this.stopStillInFlight = stopStillInFlight;
            this.guardFailure = guardFailure;
            this.alreadyStopped = alreadyStopped;
        }

        /** An explicit stop found the server already STOPPED and dispatched nothing. */
        static Recovery foundStopped()
        {
            return new Recovery(true, null, false, null, true);
        }

        /** @return {@code true} when an explicit stop found nothing to stop */
        public boolean alreadyStopped()
        {
            return alreadyStopped;
        }

        /**
         * A stop EDT ran and that ended with a failure: something was dispatched, and the server
         * may have changed state on the way, unlike a stop refused before dispatch.
         */
        static Recovery failedAfterDispatch(String detail)
        {
            Recovery recovery = new Recovery(false, detail, false, null, false);
            recovery.dispatched = true;
            return recovery;
        }

        /** @return {@code true} when EDT's cleanup ran and failed (not refused before dispatch) */
        public boolean dispatchedAndFailed()
        {
            return dispatched;
        }

        /** No stop was dispatched and ownership could not be checked under the guard. */
        static Recovery guardNotAcquired(Acquisition acquisition)
        {
            return new Recovery(false, recoveryLockFailure(acquisition), false, acquisition);
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

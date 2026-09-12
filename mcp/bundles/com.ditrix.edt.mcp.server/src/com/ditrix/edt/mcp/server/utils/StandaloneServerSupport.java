/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Reflective access to the EDT WST standalone-server feature, shared by the standalone-server
 * paths of {@code create_infobase} and {@code delete_infobase}.
 *
 * <p>Everything here is resolved REFLECTIVELY (OSGi service lookup by class NAME + {@code Method.invoke}),
 * with NO {@code Require-Bundle}/{@code Import-Package} on the standalone-server bundles. A hard
 * dependency on {@code com.e1c.g5.v8.dt.platform.standaloneserver.wst.core} would make the whole MCP
 * plugin fail to resolve on a minimal headless EDT (that bundle pulls a transitive snakeyaml the headless
 * CI does not ship — see {@code CreateInfobaseTool}'s history). Reflection keeps the plugin loadable
 * everywhere; the standalone-server paths degrade gracefully with an actionable error when the feature is
 * absent. All call wrappers return {@code null}/{@code false} (never throw) on a missing bundle/service.
 *
 * <p>Method signatures used here are {@code javap}-verified against EDT 2025.2
 * ({@code com.e1c.g5.v8.dt.platform.standaloneserver.wst.core_3.0.0}):
 * {@code IStandaloneServerService#deleteServer(IServer, IProgressMonitor):IStatus},
 * {@code #getServers():List<IServer>}; {@code IServerApplication#getServer():IServer},
 * {@code #getModule():IModule}; {@code StandaloneServerInfobase#getInfobaseId():UUID}.
 */
public final class StandaloneServerSupport
{
    /** Application type id of a standalone (WST) server application. */
    public static final String WST_SERVER_APP_TYPE = "com.e1c.g5.dt.applications.type.wst-server"; //$NON-NLS-1$

    /** Start and stop share this bound because both are self-contained EDT server operations. */
    public static final long SERVER_OPERATION_TIMEOUT_MS = 60_000L;

    /** Hard cap for retaining a start's targeted dialog guard after an inconclusive wait. */
    public static final long INCONCLUSIVE_START_GUARD_CAP_MS = 300_000L;

    /** Symbolic name of the bundle that owns the standalone-server WST service. */
    private static final String WST_CORE_BUNDLE_ID =
        "com.e1c.g5.v8.dt.platform.standaloneserver.wst.core"; //$NON-NLS-1$

    /** FQN of the standalone-server service interface (looked up by NAME in the OSGi registry). */
    private static final String SERVICE_CLASS =
        "com.e1c.g5.v8.dt.platform.standaloneserver.wst.core.IStandaloneServerService"; //$NON-NLS-1$

    /** Symbolic name of the WST server-core bundle (owns {@code ServerPlugin}/{@code ModuleFactory}). */
    private static final String WST_SERVER_CORE_BUNDLE_ID = "org.eclipse.wst.server.core"; //$NON-NLS-1$

    /** Internal WST class exposing the module-factory registry. */
    private static final String SERVER_PLUGIN_CLASS =
        "org.eclipse.wst.server.core.internal.ServerPlugin"; //$NON-NLS-1$

    /** Module-factory id of the standalone-server infobase registry (owner of infobases.yaml). */
    private static final String MODULE_FACTORY_ID =
        "com.e1c.g5.v8.dt.platform.standaloneserver.moduleFactoryDelegate"; //$NON-NLS-1$

    /** Plugin id used for synthesized error statuses. */
    private static final String PLUGIN_ID = "com.ditrix.edt.mcp.server"; //$NON-NLS-1$

    /** Reflective method name: {@code getId()}, used across several unrelated reflected types. */
    private static final String METHOD_GET_ID = "getId"; //$NON-NLS-1$

    /** Outcome of the best-effort infobases.yaml registry cleanup. */
    public enum RegistryCleanup
    {
        /** The stale entry was found and removed from the registry. */
        REMOVED,
        /** There was nothing to remove (no entry for this infobase / no infobaseId) — not an error. */
        NOT_PRESENT,
        /** The cleanup could not run (reflective failure); the orphan self-heals on the next restart. */
        FAILED
    }

    /**
     * Result of one bounded standalone-application lookup, including every attribution value the
     * following start needs. Keeping the resolved server and names here prevents callers from
     * performing another synchronous {@code IApplicationManager} lookup outside the deadline.
     */
    public static record ApplicationLookup(IApplication application, Object server,
        String infobaseName, String serverName, String failure)
    {
    }

    /** Resolves one complete application snapshot; the caller supplies any required deadline. */
    public static ApplicationLookup lookupApplication(IApplicationManager manager, IProject project,
        String applicationId) throws ApplicationException
    {
        IApplication application = manager.getApplication(project, applicationId).orElse(null);
        if (application == null)
        {
            return new ApplicationLookup(null, null, null, null, null);
        }
        Object server = serverOfApplication(application);
        return new ApplicationLookup(application, server,
            LaunchLifecycleUtils.conflictAttributionName(application), nameOfServer(server), null);
    }

    /** Complete snapshot of one guarded, bounded standalone-server start. */
    public static final class GuardedStartResult
    {
        private final BoundedJob.Result bounded;
        private final IStatus status;
        private final String conflictFailure;
        private final boolean portsReassigned;
        private final boolean portReassignmentOutcomeUnknown;
        private final long accessDialogsBefore;
        private final long accessDialogsAfter;
        private final long timeoutMs;

        private GuardedStartResult(BoundedJob.Result bounded, IStatus status,
            String conflictFailure, boolean portsReassigned,
            boolean portReassignmentOutcomeUnknown, long accessDialogsBefore,
            long accessDialogsAfter, long timeoutMs)
        {
            this.bounded = bounded;
            this.status = status;
            this.conflictFailure = conflictFailure;
            this.portsReassigned = portsReassigned;
            this.portReassignmentOutcomeUnknown = portReassignmentOutcomeUnknown;
            this.accessDialogsBefore = accessDialogsBefore;
            this.accessDialogsAfter = accessDialogsAfter;
            this.timeoutMs = timeoutMs;
        }

        /** Whether the underlying Job has definitely ended. */
        public boolean conclusive()
        {
            return !BoundedJob.isInconclusive(bounded.getOutcome());
        }

        /** Known persistent port rewrite after a conclusive start. */
        public boolean portsReassigned()
        {
            return portsReassigned;
        }

        /** Whether an in-flight REASSIGN start may still rewrite the server configuration. */
        public boolean portReassignmentOutcomeUnknown()
        {
            return portReassignmentOutcomeUnknown;
        }

        /**
         * The start failure, preferring the captured port conflict and appending the repository's
         * established actionable explanation when an access-settings dialog was auto-cancelled.
         */
        public String failure()
        {
            String failure = conflictFailure;
            if (failure == null)
            {
                if (bounded.isSuccess())
                {
                    failure = status == null
                        ? "EDT returned no status from the standalone-server start" //$NON-NLS-1$
                        : status.isOK() ? null : PlatformFailures.describeStatus(status);
                }
                else
                {
                    failure = startFailureReason(bounded, timeoutMs);
                }
            }
            if (failure == null)
            {
                return null;
            }
            String note = InfobaseAuthDialogSuppressor.accessSettingsDialogFailureNote(
                accessDialogsBefore, accessDialogsAfter);
            return note.isEmpty() ? failure : failure + " " + note; //$NON-NLS-1$
        }
    }

    private StandaloneServerSupport()
    {
    }

    /**
     * Acquires the {@code IStandaloneServerService} from the OSGi registry BY CLASS NAME (reflectively).
     * Mirrors {@code CreateInfobaseTool.acquireStandaloneServerService()} so the delete path has no
     * compile/bundle dependency on the standalone-server feature. Returns {@code null} when the bundle or
     * service is unavailable (the caller then fails gracefully with an actionable error).
     *
     * @return the service object (call it reflectively), or {@code null} when unavailable
     */
    public static Object acquireService()
    {
        try
        {
            Bundle bundle = Platform.getBundle(WST_CORE_BUNDLE_ID);
            if (bundle == null)
            {
                Activator.logError("standalone-server: bundle '" + WST_CORE_BUNDLE_ID //$NON-NLS-1$
                    + "' not found — the EDT standalone-server feature is not installed", null); //$NON-NLS-1$
                return null;
            }
            BundleContext context = bundle.getBundleContext();
            if (context == null)
            {
                context = startAndGetContext(bundle);
            }
            if (context == null)
            {
                return null;
            }
            ServiceReference<?> ref = context.getServiceReference(SERVICE_CLASS);
            return ref != null ? context.getService(ref) : null;
        }
        catch (Throwable t)
        {
            Activator.logError("standalone-server: could not acquire the standalone-server service", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Transiently starts the given bundle and returns its (now hopefully available) {@link BundleContext}.
     * A start failure is logged and swallowed (best-effort): the returned context may still be {@code null},
     * which the caller already handles by failing gracefully.
     *
     * @param bundle the standalone-server bundle whose context was not yet available
     * @return the bundle context after the start attempt, or {@code null} when it is still unavailable
     */
    private static BundleContext startAndGetContext(Bundle bundle)
    {
        try
        {
            bundle.start(Bundle.START_TRANSIENT);
            return bundle.getBundleContext();
        }
        catch (Exception startEx)
        {
            Activator.logError("standalone-server: could not start the standalone-server bundle", //$NON-NLS-1$
                startEx);
            return null;
        }
    }

    /**
     * Reflective {@code IStandaloneServerService.deleteServer(IServer, IProgressMonitor) -> IStatus}.
     * This one call stops the server, removes the WST {@code IServer} registration (servers.xml) and
     * deletes the server config folder (the {@code Серверы/…-config} project folder). It does NOT touch
     * the infobases.yaml registry — see {@link #removeFromInfobaseRegistry}.
     *
     * @return the resulting {@link IStatus} (inspect {@code isOK()}); an ERROR status when the
     *         {@code deleteServer} method itself could not be resolved (so a reflective miss is NOT
     *         mistaken for success). May be {@code null} only if the call returned a non-{@code IStatus}.
     * @throws Exception the real failure cause if {@code deleteServer} itself threw
     */
    public static IStatus deleteServer(Object service, Object server, IProgressMonitor monitor) throws Exception
    {
        Method del = findMethod(service.getClass(), "deleteServer", 2); //$NON-NLS-1$
        if (del == null)
        {
            Activator.logError("standalone-server: IStandaloneServerService.deleteServer not found", null); //$NON-NLS-1$
            return new Status(IStatus.ERROR, PLUGIN_ID,
                "IStandaloneServerService.deleteServer(IServer, IProgressMonitor) was not found in this " //$NON-NLS-1$
                    + "EDT — the standalone-server API may have changed."); //$NON-NLS-1$
        }
        try
        {
            Object status = del.invoke(service, server, monitor);
            return (status instanceof IStatus) ? (IStatus)status : null;
        }
        catch (InvocationTargetException ite)
        {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            throw new IllegalStateException(cause != null ? cause : ite);
        }
    }

    /** Reflectively calls the standalone-server service's three-argument start operation. */
    public static IStatus startServer(Object service, Object server, String launchMode,
        IProgressMonitor monitor) throws Exception
    {
        Method start = findMethod(service.getClass(), "startServer", 3); //$NON-NLS-1$
        if (start == null)
        {
            Activator.logError("standalone-server: IStandaloneServerService.startServer not found", null); //$NON-NLS-1$
            return new Status(IStatus.ERROR, PLUGIN_ID,
                "IStandaloneServerService.startServer(IServer, String, IProgressMonitor) was not " //$NON-NLS-1$
                    + "found in this EDT — the standalone-server API may have changed."); //$NON-NLS-1$
        }
        try
        {
            Object status = start.invoke(service, server, launchMode, monitor);
            return (status instanceof IStatus) ? (IStatus)status : null;
        }
        catch (InvocationTargetException ite)
        {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception)
            {
                throw (Exception)cause;
            }
            if (cause instanceof Error)
            {
                throw (Error)cause;
            }
            throw new IllegalStateException(cause != null ? cause : ite);
        }
    }

    /**
     * Resolves an EDT application without letting its synchronous UI-thread hop hold the caller
     * beyond {@code timeoutMs}.
     *
     * <p>Use this when the caller does not already own a larger bounded preparation phase.
     */
    public static ApplicationLookup lookupApplicationBounded(IApplicationManager manager,
        IProject project, String applicationId, long timeoutMs)
    {
        ApplicationLookup[] lookup = new ApplicationLookup[1];
        BoundedJob.Result result = BoundedJob.run(
            "Resolve standalone-server application: " + applicationId, timeoutMs, //$NON-NLS-1$
            monitor -> lookup[0] = lookupApplication(manager, project, applicationId));
        if (result.isSuccess())
        {
            return lookup[0];
        }
        if (result.getFailure() != null && result.getOutcome() == BoundedJob.Outcome.COMPLETED)
        {
            return failedApplicationLookup("the application could not be resolved: " //$NON-NLS-1$
                + PlatformFailures.describe(result.getFailure()));
        }

        return failedApplicationLookup(applicationLookupFailure(applicationId, timeoutMs, result));
    }

    /** Retains the established lookup-timeout wording for callers with a larger bounded phase. */
    public static String applicationLookupFailure(String applicationId, long timeoutMs,
        BoundedJob.Result result)
    {
        String target = "the EDT application lookup for application '" //$NON-NLS-1$
            + applicationId + "'"; //$NON-NLS-1$
        String deadline = timeoutMs % 1000L == 0L
            ? (timeoutMs / 1000L) + "s" : timeoutMs + "ms"; //$NON-NLS-1$ //$NON-NLS-2$
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT)
        {
            return target + " did not finish within " //$NON-NLS-1$
                + deadline + " and may still be running"; //$NON-NLS-1$
        }
        if (result.getOutcome() == BoundedJob.Outcome.INTERRUPTED)
        {
            return "the wait for " + target //$NON-NLS-1$
                + " was interrupted and the lookup may still be running"; //$NON-NLS-1$
        }
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START)
        {
            return target + " did not start within " //$NON-NLS-1$
                + deadline + "; retry when EDT's background Job queue is responsive"; //$NON-NLS-1$
        }
        return target + " never ran (" //$NON-NLS-1$
            + result.getOutcome() + ")"; //$NON-NLS-1$
    }

    /** Describes a larger bounded precondition phase that did not complete. */
    public static String boundedPhaseFailure(String target, long timeoutMs,
        BoundedJob.Result result)
    {
        String deadline = timeoutMs % 1000L == 0L
            ? (timeoutMs / 1000L) + "s" : timeoutMs + "ms"; //$NON-NLS-1$ //$NON-NLS-2$
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT)
        {
            return target + " did not finish within " + deadline //$NON-NLS-1$
                + " and may still be running"; //$NON-NLS-1$
        }
        if (result.getOutcome() == BoundedJob.Outcome.INTERRUPTED)
        {
            return "the wait for " + target + " was interrupted; it may still be running"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START)
        {
            return target + " did not start within " + deadline //$NON-NLS-1$
                + "; retry when EDT's background Job queue is responsive"; //$NON-NLS-1$
        }
        return target + " never ran (" + result.getOutcome() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static ApplicationLookup failedApplicationLookup(String failure)
    {
        return new ApplicationLookup(null, null, null, null, failure);
    }

    /** Best-effort reflective {@code IServer.getName()} for an already-resolved server. */
    private static String nameOfServer(Object server)
    {
        if (server == null)
        {
            return null;
        }
        try
        {
            Object name = server.getClass().getMethod("getName").invoke(server); //$NON-NLS-1$
            if (name == null)
            {
                return null;
            }
            String trimmed = name.toString().trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        catch (Exception | LinkageError e) // NOSONAR best-effort attribution, never a precondition
        {
            return null;
        }
    }

    /**
     * Arms every unattended guard required by a bounded standalone-server start and ties all of
     * them to the underlying Job's lifetime.
     *
     * <p>The port-conflict confirmer, its conflict watch, and the nested infobase-auth suppression
     * count are one lifecycle here. A conclusive result releases them before returning; a timeout
     * or interruption keeps every one armed until {@code completion} reports the Job finished, or
     * until the shared safety cap expires. Keeping this composition in one helper prevents direct
     * starts and restoration starts from drifting apart again.
     */
    public static GuardedStartResult startServerGuarded(Object service, Object server,
        String operationName, String launchMode, String infobaseName, String serverName,
        StandaloneServerPortConflictPolicy portPolicy, long timeoutMs, long cleanupCapMs)
    {
        return startServerGuarded(service, server, operationName, launchMode, infobaseName,
            serverName, portPolicy, timeoutMs, cleanupCapMs, null, null);
    }

    /** Same guarded start with another operation claim tied to the Job's exact lifecycle. */
    public static GuardedStartResult startServerGuarded(Object service, Object server,
        String operationName, String launchMode, String infobaseName, String serverName,
        StandaloneServerPortConflictPolicy portPolicy, long timeoutMs, long cleanupCapMs,
        Runnable operationCleanup, Runnable cleanupInstalled)
    {
        long accessDialogsBefore = InfobaseAuthDialogSuppressor.accessSettingsAutoCancelCount();
        LaunchUpdateDialogAutoConfirmer.ConflictWatch conflicts = portPolicy == null
            ? null : LaunchUpdateDialogAutoConfirmer.beginConflictWatch(infobaseName, serverName);
        boolean autoConfirmerArmed = LaunchUpdateDialogAutoConfirmer.arm(false, false, false, null,
            infobaseName, portPolicy, serverName);
        InfobaseAuthDialogSuppressor.markActivityStart();
        Runnable cleanup = () -> {
            try
            {
                try
                {
                    if (autoConfirmerArmed)
                    {
                        LaunchUpdateDialogAutoConfirmer.disarm(false, false, false, null, infobaseName,
                            portPolicy, serverName);
                    }
                }
                finally
                {
                    try
                    {
                        if (conflicts != null)
                        {
                            conflicts.close();
                        }
                    }
                    finally
                    {
                        InfobaseAuthDialogSuppressor.markActivityEnd();
                    }
                }
            }
            finally
            {
                if (operationCleanup != null)
                {
                    operationCleanup.run();
                }
            }
        };
        BoundedJob.DeferredCleanup deferredCleanup = new BoundedJob.DeferredCleanup(cleanup,
            cleanupCapMs, operationName + " unattended-guard safety cap"); //$NON-NLS-1$
        IStatus[] status = new IStatus[1];
        BoundedJob.Result bounded = null;
        try
        {
            if (cleanupInstalled != null)
            {
                cleanupInstalled.run();
            }
            bounded = BoundedJob.run(operationName, timeoutMs,
                monitor -> status[0] = startServer(service, server, launchMode, monitor),
                deferredCleanup::jobFinished);
            boolean conclusive = !BoundedJob.isInconclusive(bounded.getOutcome());
            String conflictFailure = conflicts != null && conflicts.portConflicted()
                ? LaunchUpdateDialogAutoConfirmer.portConflictError(
                    conflicts.portConflictDetail(), conflicts.portConflictReason())
                : null;
            boolean portsReassigned = conclusive && conflicts != null
                && conflicts.portsReassigned();
            boolean portReassignmentOutcomeUnknown = !conclusive
                && portPolicy == StandaloneServerPortConflictPolicy.REASSIGN;
            return new GuardedStartResult(bounded, status[0], conflictFailure, portsReassigned,
                portReassignmentOutcomeUnknown, accessDialogsBefore,
                InfobaseAuthDialogSuppressor.accessSettingsAutoCancelCount(), timeoutMs);
        }
        finally
        {
            // BoundedJob rethrows Error only after its Job has already terminated. Otherwise its
            // concrete outcome is the sole authority on whether the guards may be released now.
            deferredCleanup.afterBoundedWait(bounded == null
                || !BoundedJob.isInconclusive(bounded.getOutcome()));
        }
    }

    /** Describes an unsuccessful bounded start without hiding an operation still in flight. */
    public static String startFailureReason(BoundedJob.Result result, long timeoutMs)
    {
        BoundedJob.Outcome outcome = result.getOutcome();
        switch (outcome)
        {
        case TIMED_OUT:
            long actualTimeoutMs = Math.max(1L, timeoutMs);
            String deadline = actualTimeoutMs % 1000L == 0L
                ? (actualTimeoutMs / 1000L) + "s" : actualTimeoutMs + "ms"; //$NON-NLS-1$ //$NON-NLS-2$
            return "starting it did not finish within " //$NON-NLS-1$
                + deadline + " and may still be running"; //$NON-NLS-1$
        case TIMED_OUT_BEFORE_START:
            return "the bounded start timed out before it began"; //$NON-NLS-1$
        case INTERRUPTED:
            return "the wait for the standalone-server start was interrupted; " //$NON-NLS-1$
                + "the start may still be running"; //$NON-NLS-1$
        default:
            break;
        }
        if (result.getFailure() != null)
        {
            return PlatformFailures.describe(result.getFailure());
        }
        return "the standalone-server start did not run (" + outcome + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Reflective {@code IServerApplication.getServer()} on a wst-server {@link IApplication} — the WST
     * {@code IServer} backing the application. Returns {@code null} on any failure (the caller then falls
     * back to a name scan via {@link #findServerByModuleName}).
     */
    public static Object serverOfApplication(IApplication app)
    {
        try
        {
            Method m = app.getClass().getMethod("getServer"); //$NON-NLS-1$
            return m.invoke(app);
        }
        catch (Throwable t)
        {
            Activator.logError("standalone-server: IServerApplication.getServer() refl failed", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reflective {@code IServerApplication.getModule()} — the {@code StandaloneServerInfobase} module of a
     * wst-server application (used to read the infobaseId for the infobases.yaml cleanup). May be
     * {@code null}.
     *
     * <p>An application that simply HAS no {@code getModule()} is not a failure and is not logged:
     * callers routinely probe a plain infobase application with this (see
     * {@code InfobaseAccessSupport.resolveInfobaseReference}), and logging that would put an ERROR
     * in the workbench log for an ordinary, expected answer.
     */
    public static Object moduleOfApplication(IApplication app)
    {
        try
        {
            Method m = app.getClass().getMethod("getModule"); //$NON-NLS-1$
            return m.invoke(app);
        }
        catch (NoSuchMethodException e) // NOSONAR not a server application - an expected answer, not a failure
        {
            return null;
        }
        catch (Throwable t)
        {
            Activator.logError("standalone-server: IServerApplication.getModule() refl failed", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Reflective infobase-id read, version-tolerant across the {@code StandaloneServerInfobase}
     * 2025.2 -> 2026.1 API rename — the key under which the entry is stored in infobases.yaml (a raw
     * id STRING on both shapes):
     * <ul>
     *   <li>2025.2: {@code getInfobaseId(): UUID} — read directly, then {@code toString()}'d.</li>
     *   <li>2026.1: {@code getInfobaseId()} was REMOVED with no replacement; the raw id instead lives
     *   at {@code getStandaloneServerConfiguration().getInfobase().getId(): String}, resolved
     *   null-safely at each hop.</li>
     * </ul>
     * Returns {@code null} on failure/absence of BOTH shapes, never throwing; an error is logged only
     * when neither shape resolved (so a single-shape EDT version never logs spuriously).
     */
    public static String infobaseIdOf(Object standaloneServerInfobaseModule)
    {
        try
        {
            Object id = standaloneServerInfobaseModule.getClass().getMethod("getInfobaseId") //$NON-NLS-1$
                .invoke(standaloneServerInfobaseModule);
            return id != null ? id.toString() : null;
        }
        catch (NoSuchMethodException e)
        {
            // 2025.2 shape absent -> try the 2026.1 shape (getInfobaseId() was removed with no
            // direct replacement; the raw id moved under the standalone-server configuration).
            String id = infobaseIdViaConfiguration(standaloneServerInfobaseModule);
            if (id == null)
            {
                Activator.logError("standalone-server: could not read standalone-server infobaseId " //$NON-NLS-1$
                    + "(neither getInfobaseId() nor getStandaloneServerConfiguration()." //$NON-NLS-1$
                    + "getInfobase().getId() resolved)", null); //$NON-NLS-1$
            }
            return id;
        }
        catch (Throwable t) // NOSONAR deliberate catch-all at a reflective/best-effort boundary
        {
            Activator.logError("standalone-server: could not read standalone-server infobaseId", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * 2026.1 fallback shape for {@link #infobaseIdOf}:
     * {@code getStandaloneServerConfiguration().getInfobase().getId(): String}. Null-safe at each hop;
     * returns {@code null} on any missing hop or reflective failure (never throws — the caller decides
     * whether to log).
     */
    private static String infobaseIdViaConfiguration(Object standaloneServerInfobaseModule)
    {
        try
        {
            Object cfg = standaloneServerInfobaseModule.getClass()
                .getMethod("getStandaloneServerConfiguration").invoke(standaloneServerInfobaseModule); //$NON-NLS-1$
            if (cfg == null)
            {
                return null;
            }
            Object infobase = cfg.getClass().getMethod("getInfobase").invoke(cfg); //$NON-NLS-1$
            if (infobase == null)
            {
                return null;
            }
            Object id = infobase.getClass().getMethod(METHOD_GET_ID).invoke(infobase);
            return (id instanceof String) ? (String)id : null;
        }
        catch (Throwable t) // NOSONAR deliberate catch-all at a reflective/best-effort boundary
        {
            return null;
        }
    }

    /**
     * Reflective {@code StandaloneServerInfobase.getStandaloneServerConfiguration().getDatabase()} →, for a
     * FILE-backed standalone server, the on-disk directory of the served database — read via
     * {@code FileDatabase.getConfigDirectory()} (2025.2) or {@code getPath()} (the 2026.1 rename of the
     * same accessor) — (= {@code database.path} in config.yaml = the {@code infobaseFile} that was
     * passed to {@code create_infobase}). This is SEPARATE from the {@code Серверы/…-config} folder that
     * {@code deleteServer} removes — {@code deleteServer} never deletes the served database, so this is how
     * {@code deleteDatabaseFiles=true} finds the directory to remove. Must be read BEFORE {@code deleteServer}
     * (the config is torn down after). Returns {@code null} for an RDBMS-backed server (no local directory)
     * or on any reflective failure.
     */
    public static String databaseDirOf(Object standaloneServerInfobaseModule)
    {
        try
        {
            Object cfg = standaloneServerInfobaseModule.getClass()
                .getMethod("getStandaloneServerConfiguration").invoke(standaloneServerInfobaseModule); //$NON-NLS-1$
            if (cfg == null)
            {
                return null;
            }
            Object db = cfg.getClass().getMethod("getDatabase").invoke(cfg); //$NON-NLS-1$
            if (db == null)
            {
                return null;
            }
            // A FILE database (and its create-template subclass FileCreateTemplateDatabase) carries the
            // on-disk directory in getConfigDirectory() (2025.2), renamed to getPath() on 2026.1; an
            // RDBMS database has neither accessor nor a local directory. Detect the file kind by the
            // PRESENCE of either accessor rather than by the class name: the type is platform-internal
            // and intentionally not imported (no Require-Bundle), so instanceof is impossible, and a
            // name match would be fragile.
            Method dirGetter = findMethod(db.getClass(), "getConfigDirectory", 0); //$NON-NLS-1$
            if (dirGetter == null)
            {
                dirGetter = findMethod(db.getClass(), "getPath", 0); //$NON-NLS-1$
            }
            if (dirGetter == null)
            {
                // Not a file-backed database — nothing on the local disk to resolve.
                return null;
            }
            Object dir = dirGetter.invoke(db);
            return (dir instanceof String) ? (String)dir : null;
        }
        catch (Throwable t) // NOSONAR deliberate catch-all at a reflective/best-effort boundary
        {
            Activator.logError("standalone-server: could not read standalone-server database directory", t); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Fallback lookup: scans {@code IStandaloneServerService.getServers()} for the {@code IServer} whose
     * module display name equals {@code appName}. Used when {@link #serverOfApplication} fails.
     *
     * @return the matching {@code IServer} object, or {@code null} if none matched
     */
    public static Object findServerByModuleName(Object service, String appName)
    {
        try
        {
            Method getServers = findMethod(service.getClass(), "getServers", 0); //$NON-NLS-1$
            if (getServers == null)
            {
                return null;
            }
            Object result = getServers.invoke(service);
            if (!(result instanceof List))
            {
                return null;
            }
            for (Object server : (List<?>)result)
            {
                if (server == null)
                {
                    continue;
                }
                Object modules = server.getClass().getMethod("getModules").invoke(server); //$NON-NLS-1$
                if (modules instanceof Object[])
                {
                    for (Object module : (Object[])modules)
                    {
                        Object name = module.getClass().getMethod("getName").invoke(module); //$NON-NLS-1$
                        if (appName.equals(name))
                        {
                            return server;
                        }
                    }
                }
            }
        }
        catch (Throwable t) // NOSONAR deliberate catch-all at a reflective/best-effort boundary
        {
            Activator.logError("standalone-server: server-by-name scan failed", t); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Best-effort removal of the orphaned infobases.yaml entry that {@code deleteServer} leaves behind
     * (EDT never removes it — a confirmed platform gap). Reflectively reaches the
     * {@code StandaloneServerInfobaseModuleFactoryDelegate} via the WST module-factory registry, removes
     * the entry from its in-memory {@code modules} map, and re-serializes the remaining entries to
     * infobases.yaml exactly as the delegate's own {@code saveModule} does.
     *
     * <p><strong>Version-proof removal (issue #273):</strong> the delegate's map KEY scheme differs per
     * EDT version — 2025.2 keys by the raw {@code getInfobaseId().toString()} uuid (proven live when
     * this cleanup shipped), while 2026.1 keys by {@code StandaloneServerInfobase.getId()}, a PREFIXED
     * module-id string built over the raw config id — so a key-based remove by the raw id silently
     * misses on 2026.1. Guessing key schemes per version is fragile; the primary strategy is therefore
     * removal BY VALUE ({@link #removeModuleEntries}): (1) reference identity with the passed live
     * {@code module} (the map holds the same instance), (2) reflective {@code getId()} String equality,
     * (3) only then the raw-id key fallback that preserves the proven 2025.2 behavior even when the
     * instance differs.
     *
     * <p>NON-FATAL: any failure is logged and ignored — the server itself is already deleted, the app is
     * gone from {@code get_applications}, and the orphan self-heals on the next workbench start (the
     * delegate's {@code initialize()} drops config-less entries from its map).
     *
     * @param module the live {@code StandaloneServerInfobase} module of the deleted server (from
     *            {@link #moduleOfApplication}, read BEFORE deletion); may be {@code null}
     * @param infobaseId the raw infobase id string (from {@link #infobaseIdOf}) — the 2025.2 map key,
     *            kept as the final fallback; may be {@code null}
     * @return {@link RegistryCleanup#REMOVED} if a stale entry was removed, {@link RegistryCleanup#NOT_PRESENT}
     *         when there was nothing to clean (no matching entry), or {@link RegistryCleanup#FAILED} on
     *         a reflective failure / when both {@code module} and {@code infobaseId} are {@code null}
     *         (nothing to target)
     */
    public static RegistryCleanup removeFromInfobaseRegistry(Object module, String infobaseId,
        IProgressMonitor monitor)
    {
        if (module == null && infobaseId == null)
        {
            // We could resolve neither the module nor its raw id, so we cannot target the entry —
            // report FAILED (honest: "could not clean, self-heals on restart") rather than implying
            // there was nothing to clean.
            return RegistryCleanup.FAILED;
        }
        try
        {
            Bundle wst = Platform.getBundle(WST_SERVER_CORE_BUNDLE_ID);
            if (wst == null)
            {
                return RegistryCleanup.FAILED;
            }
            Class<?> serverPlugin = wst.loadClass(SERVER_PLUGIN_CLASS);
            Object[] factories = (Object[])serverPlugin.getMethod("getModuleFactories").invoke(null); //$NON-NLS-1$
            Object factory = null;
            for (Object f : factories)
            {
                Object id = f.getClass().getMethod(METHOD_GET_ID).invoke(f);
                if (MODULE_FACTORY_ID.equals(id))
                {
                    factory = f;
                    break;
                }
            }
            if (factory == null)
            {
                return RegistryCleanup.FAILED;
            }
            Method getDelegate = findMethod(factory.getClass(), "getDelegate", 1); //$NON-NLS-1$
            if (getDelegate == null)
            {
                return RegistryCleanup.FAILED;
            }
            Object delegate = getDelegate.invoke(factory, monitor);
            if (delegate == null)
            {
                return RegistryCleanup.FAILED;
            }

            Field modulesF = findField(delegate.getClass(), "modules"); //$NON-NLS-1$
            Field mapperF = findField(delegate.getClass(), "objectMapper"); //$NON-NLS-1$
            Field locF = findField(delegate.getClass(), "location"); //$NON-NLS-1$
            if (modulesF == null || mapperF == null || locF == null)
            {
                return RegistryCleanup.FAILED;
            }
            modulesF.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            mapperF.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)
            locF.setAccessible(true); // NOSONAR reflective access is required (EDT internals, no Require-Bundle)

            @SuppressWarnings("unchecked")
            Map<Object, Object> modules = (Map<Object, Object>)modulesF.get(delegate);
            if (modules == null)
            {
                return RegistryCleanup.FAILED;
            }
            if (removeModuleEntries(modules, module, infobaseId) == 0)
            {
                // Already gone (e.g. the delegate's initialize() self-healed it) — not an error.
                return RegistryCleanup.NOT_PRESENT;
            }

            Object mapper = mapperF.get(delegate);
            Object location = locF.get(delegate); // java.nio.file.Path
            File file = (File)location.getClass().getMethod("toFile").invoke(location); //$NON-NLS-1$
            List<Object> remaining = new ArrayList<>(modules.values());
            mapper.getClass().getMethod("writeValue", File.class, Object.class) //$NON-NLS-1$
                .invoke(mapper, file, remaining);
            return RegistryCleanup.REMOVED;
        }
        catch (Throwable t)
        {
            Activator.logError("standalone-server: best-effort infobases.yaml cleanup failed " //$NON-NLS-1$
                + "(non-fatal — the server is deleted; the orphan self-heals on restart)", t); //$NON-NLS-1$
            return RegistryCleanup.FAILED;
        }
    }

    /**
     * The map surgery of {@link #removeFromInfobaseRegistry}, extracted as a pure (headless-testable)
     * decision: removes the deleted server's entry/entries from the delegate's {@code modules} map and
     * returns how many were removed. Three strategies, tried in order (each only when the previous
     * removed nothing) because the map's KEY scheme differs per EDT version (2025.2 = raw uuid string,
     * 2026.1 = the prefixed {@code getId()} module-id string):
     * <ol>
     *   <li>VALUE reference identity with {@code module} — version-proof: the live map holds the very
     *   instance {@link #moduleOfApplication} returned, whatever the key scheme;</li>
     *   <li>value {@code getId()} String equality with {@code module}'s own reflective {@code getId()}
     *   — catches a re-created (non-identical) instance for the same module id;</li>
     *   <li>key-based {@code remove(infobaseId)} — the original raw-id removal, preserving the proven
     *   2025.2 behavior even when the instance differs and {@code getId()} is unavailable.</li>
     * </ol>
     *
     * @param modules the delegate's live modules map (never {@code null})
     * @param module the deleted server's module instance; may be {@code null} (strategies 1-2 skipped)
     * @param infobaseId the raw infobase id (the 2025.2 key); may be {@code null} (strategy 3 skipped)
     * @return the number of entries removed (0 = nothing matched)
     */
    static int removeModuleEntries(Map<Object, Object> modules, Object module, String infobaseId)
    {
        int removed = 0;
        if (module != null)
        {
            removed = removeByValue(modules, value -> value == module);
        }
        if (removed == 0 && module != null)
        {
            String moduleId = idOf(module);
            if (moduleId != null)
            {
                removed = removeByValue(modules, value -> moduleId.equals(idOf(value)));
            }
        }
        if (removed == 0 && infobaseId != null && modules.remove(infobaseId) != null)
        {
            removed = 1;
        }
        return removed;
    }

    /** Removes every map entry whose VALUE matches the predicate; returns the removed count. */
    private static int removeByValue(Map<Object, Object> modules, Predicate<Object> valueMatches)
    {
        int removed = 0;
        for (Iterator<Map.Entry<Object, Object>> it = modules.entrySet().iterator(); it.hasNext();)
        {
            if (valueMatches.test(it.next().getValue()))
            {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Reflective {@code getId()} of a registry module as a String (on 2026.1 this is the delegate's
     * map key — the prefixed module-id). Returns {@code null} when the object is {@code null}, the
     * method is absent (possible on older versions) or the read fails — the caller then just skips the
     * id-equality strategy.
     */
    private static String idOf(Object module)
    {
        if (module == null)
        {
            return null;
        }
        try
        {
            Method m = findMethod(module.getClass(), METHOD_GET_ID, 0);
            if (m == null)
            {
                return null;
            }
            Object id = m.invoke(module);
            return id != null ? id.toString() : null;
        }
        catch (Throwable t) // NOSONAR deliberate catch-all at a reflective/best-effort boundary
        {
            return null;
        }
    }

    /** First public method on {@code cls} (incl. inherited) with the given name and parameter count. */
    private static Method findMethod(Class<?> cls, String name, int paramCount)
    {
        for (Method m : cls.getMethods())
        {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount)
            {
                return m;
            }
        }
        return null;
    }

    /** Declared field {@code name} on {@code cls} or any superclass; {@code null} if absent. */
    private static Field findField(Class<?> cls, String name)
    {
        for (Class<?> c = cls; c != null; c = c.getSuperclass())
        {
            try
            {
                return c.getDeclaredField(name);
            }
            catch (NoSuchFieldException e)
            {
                // keep climbing
            }
        }
        return null;
    }
}

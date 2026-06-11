/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetTypeUtil;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Bridges EDT's 1C-native debug-server targets (the ones a breakpoint actually
 * suspends on when code runs <em>server-side</em> during {@code debug_yaxunit_tests},
 * or that a user started from the EDT UI via "Debug As") into the generic Eclipse
 * debug model the MCP debug tools already speak.
 *
 * <h2>Why this exists</h2>
 * When YAXUnit tests run on a file-mode standalone server (rphost) — or a debug
 * session is started directly from the EDT UI — a breakpoint trips on a debuggee
 * that EDT tracks through its own
 * {@code IRuntimeDebugClientTargetManager.listDebugTargets()} view, NOT through
 * the threads of the Eclipse {@link org.eclipse.debug.core.ILaunch} a runtime
 * client started. So {@code launch.getDebugTargets()[*].getThreads()} reports
 * {@code threadCount:0} and {@code suspended:false} even though the server is
 * really suspended on the breakpoint line. The other debug tools, which resolve
 * an {@code applicationId} to an Eclipse {@code ILaunch}, never see the suspend.
 *
 * <h2>The key fact that makes the bridge clean</h2>
 * The 1C-native debug model is <strong>not</strong> a separate model — it
 * implements the standard Eclipse debug interfaces (verified in the EDT 2025.2
 * Javadoc):
 * <ul>
 *   <li>{@code IRuntimeDebugClientTarget extends org.eclipse.debug.core.model.IDebugTarget}
 *       (and {@code ISuspendResume}, {@code ITerminate})</li>
 *   <li>{@code IRuntimeDebugTargetThread extends org.eclipse.debug.core.model.IThread}
 *       (and {@code IStep}, {@code ISuspendResume})</li>
 *   <li>{@code IBslStackFrame extends org.eclipse.debug.core.model.IStackFrame}
 *       (and {@code IStep})</li>
 * </ul>
 * Therefore, once a server target is obtained, its threads/frames can be read,
 * resumed, stepped, inspected ({@code getVariables}) and evaluated against
 * through the very same Eclipse-interface code paths the existing tools use for
 * thin-client launches.
 *
 * <h2>Reflection boundary</h2>
 * Only the <em>discovery</em> of the targets is reflective — obtaining the
 * {@code IRuntimeDebugClientTargetManager} (an OSGi service looked up by class
 * name in {@link Activator#getRuntimeDebugClientTargetManager()}) and calling
 * its {@code listDebugTargets()}. Each returned element is then cast straight to
 * {@link IDebugTarget}; from that point on no reflection is needed.
 */
public final class DebugServerTargetSupport
{
    /**
     * Prefix for the stable, addressable applicationId minted for a debug-server
     * target. Mirrors the {@code ServerApplication.<app>} form so the id the user
     * sees on a server debuggee resolves to the (really suspended) server target.
     */
    public static final String SERVER_APP_ID_PREFIX = "ServerApplication."; //$NON-NLS-1$

    private DebugServerTargetSupport()
    {
        // utility class
    }

    /**
     * Returns {@code true} when {@code applicationId} addresses a 1C STANDALONE-SERVER
     * application — the literal {@code ServerApplication.} prefix that real server
     * application ids carry (and which the synthetic ids minted by
     * {@link #listServerTargets()} mirror on purpose).
     *
     * <p>This is the server-application gate: a server application must NEVER be
     * DB-updated out-of-band by this plugin. {@code IApplicationManager.update} on a
     * {@code ServerApplication} routes through EDT's
     * {@code ServerApplicationBehaviourDelegate.update} →
     * {@code JobBasedServerModulePublisher.publish}, which STARTS the standalone
     * server in RUN mode and caches a live designer-agent connection in the global
     * {@code DesignerSessionPool} singleton. A subsequent debug launch then has to
     * restart the server in DEBUG mode, and the stop's {@code closeDesignerSession}
     * tears down that cached live connection — un-timed SSH exchanges against a
     * shutting-down server under the pool's single global lock wedge the launch.
     * EDT's native flow ({@code ApplicationUiSupport.ensureUpdated}) avoids this by
     * ORDER: it {@code prepare}s the server directly in the target (debug) mode
     * FIRST and only then updates — no restart, no teardown. So for server
     * applications the update is deferred to the launch delegate's coordinated path;
     * its "Application update" dialog (shown only when the IB is stale) is pressed
     * by the armed {@link LaunchUpdateDialogAutoConfirmer}.
     *
     * <p>Deliberately a literal id-prefix test, NOT an {@code instanceof} check
     * against the server-applications bundle — that would add a new bundle
     * dependency for what the id already encodes.
     *
     * @param applicationId the id to test (may be {@code null})
     * @return {@code true} for a {@code ServerApplication.*} id
     */
    public static boolean isServerApplicationId(String applicationId)
    {
        return applicationId != null && applicationId.startsWith(SERVER_APP_ID_PREFIX);
    }

    /**
     * A single debug-server target seen through the Eclipse debug model, with the
     * stable applicationId the MCP tools address it by.
     */
    public static final class ServerTarget
    {
        /** The 1C-native target, viewed as a standard Eclipse {@link IDebugTarget}. */
        public final IDebugTarget target;
        /** Primary stable id: {@code ServerApplication.<appName|url>}. */
        public final String applicationId;
        /** Bare application name (may be {@code null}). */
        public final String application;
        /** Debug server URL (may be {@code null}). */
        public final String debugServerUrl;

        ServerTarget(IDebugTarget target, String applicationId, String application, String debugServerUrl)
        {
            this.target = target;
            this.applicationId = applicationId;
            this.application = application;
            this.debugServerUrl = debugServerUrl;
        }
    }

    /**
     * Enumerates the debug-server targets EDT currently tracks, each viewed as a
     * standard Eclipse {@link IDebugTarget}. Best-effort and fully guarded —
     * returns an empty list (never throws) when the debug-core manager is
     * unavailable (e.g. headless test runtime) or the API shape differs.
     *
     * @return list of server targets (possibly empty)
     */
    public static List<ServerTarget> listServerTargets()
    {
        List<ServerTarget> out = new ArrayList<>();
        Object manager = Activator.getDefault() != null
            ? Activator.getDefault().getRuntimeDebugClientTargetManager()
            : null;
        if (manager == null)
        {
            return out;
        }
        try
        {
            Method listMethod = manager.getClass().getMethod("listDebugTargets"); //$NON-NLS-1$
            listMethod.setAccessible(true);
            Object targets = listMethod.invoke(manager);
            if (!(targets instanceof Iterable<?>))
            {
                return out;
            }
            for (Object t : (Iterable<?>)targets)
            {
                if (!(t instanceof IDebugTarget))
                {
                    // The 1C target implements org.eclipse.debug IDebugTarget; if a
                    // build ever changes that, skip rather than crash.
                    continue;
                }
                IDebugTarget target = (IDebugTarget)t;
                String app = reflectApplicationName(t);
                String url = reflectString(t, "getDebugServerUrl"); //$NON-NLS-1$
                String idKey = app != null && !app.isEmpty() ? app : url;
                if (idKey == null || idKey.isEmpty())
                {
                    idKey = target.getName();
                }
                String appId = SERVER_APP_ID_PREFIX + idKey;
                out.add(new ServerTarget(target, appId, app, url));
            }
        }
        catch (Throwable e)
        {
            Activator.logError("Error enumerating debug-server targets", e); //$NON-NLS-1$
        }
        return out;
    }

    /**
     * Finds a live runtime-client DEBUG target that EDT's launch delegate would
     * consider a duplicate of a launch for {@code (projectName, resolvedAppId)} —
     * the criterion behind the "Debug session already exists" code-1003 modal.
     *
     * <p>This mirrors
     * {@code RuntimeClientLaunchDelegate.checkExistingDebugSessions}: it enumerates
     * EDT's own {@code IRuntimeDebugClientTargetManager.listDebugTargets()} view
     * (the SAME set the delegate scans — which includes UI-started "Debug As" and
     * otherwise-unregistered runtime-client sessions that never surface in
     * {@link org.eclipse.debug.core.ILaunchManager#getLaunches()}, and so are
     * invisible to {@link DebugSessionRegistry#findActiveTarget}/
     * {@link DebugSessionRegistry#findActiveLaunch}), and matches a target whose
     * bound {@code IApplication} has the same project name AND the same application
     * id as the launch about to start.
     *
     * <p>{@code resolvedAppId} MUST already be resolved the delegate's way:
     * {@code ATTR_APPLICATION_ID} if the config has it, else the project's
     * {@code IApplicationManager.getDefaultApplication(project)} id — see
     * {@code DebugLaunchTool} call sites. The synthetic {@code launch:<configName>}
     * id is NOT the delegate's key; passing one here never matches (that mismatch is
     * exactly what the earlier duplicate guard missed).
     *
     * <p><strong>A live CLIENT-typed thread is required.</strong> The
     * application id alone does NOT discriminate a thin-CLIENT debug session from a
     * 1C standalone-SERVER (or profiling) target: both a "Автономный сервер …" server
     * target and a thin client of the same project resolve to the SAME
     * {@code ServerApplication.<proj>} app id. And mere thread <em>liveness</em> is
     * not enough either: a standalone server launched in DEBUG mode (ibsrv with
     * debugging on) carries a LIVE thread typed {@code SERVER} (presented as
     * «Сервер»), so the earlier "a server target carries zero live threads"
     * assumption mis-classified it as a client session — and
     * {@code restartIfRunning=true} then terminated the SERVER session and hung on
     * its restart. The thread's {@code IRuntimeDebugTargetThread.getType()} is the
     * only reliable kind signal (one impl class serves ALL targets, and the thread
     * name is just the localized presentation of the type): a same-app-id+project
     * target matches ONLY when it has at least one non-terminated thread that
     * classifies as CLIENT-side ({@link #findFirstLiveClientThread} — a thread whose
     * type {@code DebugTargetTypeUtil.isServer} positively marks as server-side
     * never counts; unknown/non-1C threads conservatively do).
     *
     * <p>Best-effort and fully guarded — returns {@code null} (never throws) when the
     * target manager is unavailable (headless), the API shape differs, or nothing
     * matches.
     *
     * @param projectName the launch's target project name (may be {@code null}/empty)
     * @param resolvedAppId the delegate-resolved application id (may be {@code null}/empty)
     * @return the matching live runtime-client debug target, or {@code null}
     */
    public static IDebugTarget findRuntimeClientDebugTarget(String projectName, String resolvedAppId)
    {
        if (projectName == null || projectName.isEmpty()
            || resolvedAppId == null || resolvedAppId.isEmpty())
        {
            return null;
        }
        for (ServerTarget st : listServerTargets())
        {
            IDebugTarget target = st.target;
            if (target == null || target.isTerminated())
            {
                continue;
            }
            Object application = reflectApplication(target);
            if (application == null)
            {
                continue;
            }
            String appId = reflectString(application, "getId"); //$NON-NLS-1$
            if (!resolvedAppId.equals(appId))
            {
                continue;
            }
            if (!projectName.equals(reflectApplicationProjectName(application)))
            {
                continue;
            }
            // A server/profiling target shares the client's app id+project — and a
            // DEBUG-mode standalone server even carries a LIVE thread (typed SERVER,
            // named «Сервер») — so neither the app id nor bare thread liveness makes
            // it the client's duplicate. Require a live CLIENT-typed thread: only a
            // session with one is a client worth short-circuiting/restarting.
            if (findFirstLiveClientThread(target) != null)
            {
                return target;
            }
        }
        return null;
    }

    /**
     * Resolves an {@code applicationId} to a debug-server target. Accepts, in
     * order of preference:
     * <ul>
     *   <li>the minted {@code ServerApplication.<app>} id (exact match),</li>
     *   <li>the bare application name (e.g. {@code MyConfiguration}),</li>
     *   <li>the debug server URL,</li>
     *   <li>the id of the owning Eclipse launch (real {@code ATTR_APPLICATION_ID},
     *       {@code attach:<name>} or {@code launch:<name>}).</li>
     * </ul>
     * When more than one server target matches loosely, a target that is actually
     * suspended wins over an idle one.
     *
     * @param applicationId the id to resolve (may be {@code null})
     * @return the matching {@link ServerTarget}, or {@code null} if none matches
     */
    public static ServerTarget resolve(String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty())
        {
            return null;
        }
        String bare = applicationId.startsWith(SERVER_APP_ID_PREFIX)
            ? applicationId.substring(SERVER_APP_ID_PREFIX.length())
            : applicationId;

        ServerTarget looseMatch = null;
        for (ServerTarget st : listServerTargets())
        {
            if (st.target == null || st.target.isTerminated())
            {
                continue;
            }
            boolean exact = applicationId.equals(st.applicationId);
            boolean byName = bare.equals(st.application);
            boolean byUrl = bare.equals(st.debugServerUrl);
            // Also match by the id of the Eclipse launch that owns this server
            // debuggee (real ATTR_APPLICATION_ID / attach:<name> / launch:<name>).
            boolean byLaunchId = matchesOwningLaunchId(st.target, applicationId);
            if (exact || byName || byUrl || byLaunchId)
            {
                if (isAnyThreadSuspended(st.target))
                {
                    return st; // prefer the suspended one
                }
                if (looseMatch == null)
                {
                    looseMatch = st;
                }
            }
        }
        return looseMatch;
    }

    /**
     * Returns the lone non-terminated server target if exactly one exists,
     * otherwise {@code null}. Lets the debug tools auto-resolve the single obvious
     * server debuggee the way they already auto-resolve a single Eclipse launch.
     *
     * @return the sole server target, or {@code null}
     */
    public static ServerTarget findLoneServerTarget()
    {
        ServerTarget only = null;
        for (ServerTarget st : listServerTargets())
        {
            if (st.target == null || st.target.isTerminated())
            {
                continue;
            }
            if (only != null)
            {
                return null; // more than one — ambiguous
            }
            only = st;
        }
        return only;
    }

    /**
     * Polls a server target for a suspended thread up to {@code timeoutMs},
     * returning the first suspended {@link IThread} found, or {@code null} on
     * timeout. Used by {@code wait_for_break}/{@code step} for server targets,
     * whose SUSPEND {@code DebugEvent}s do not reliably key into the launch-based
     * suspend registry (the 1C target's {@code getLaunch()} does not carry the
     * runtime-client {@code ATTR_APPLICATION_ID}). Polling the live model directly
     * is authoritative.
     *
     * @param target the server target viewed as an Eclipse debug target
     * @param timeoutMs maximum time to wait, in milliseconds
     * @param pollIntervalMs sleep between polls, in milliseconds
     * @return a suspended thread, or {@code null} on timeout
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public static IThread pollForSuspendedThread(IDebugTarget target, long timeoutMs, long pollIntervalMs)
        throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true)
        {
            IThread suspended = findSuspendedThread(target);
            if (suspended != null)
            {
                return suspended;
            }
            if (target == null || target.isTerminated())
            {
                return null;
            }
            if (System.currentTimeMillis() >= deadline)
            {
                return null;
            }
            Thread.sleep(Math.min(pollIntervalMs, Math.max(1, deadline - System.currentTimeMillis())));
        }
    }

    /**
     * Finds the first suspended {@link IThread} of a server target, or
     * {@code null} if none is currently suspended.
     *
     * @param target the server target viewed as an Eclipse debug target
     * @return a suspended thread, or {@code null}
     */
    public static IThread findSuspendedThread(IDebugTarget target)
    {
        if (target == null || target.isTerminated())
        {
            return null;
        }
        try
        {
            for (IThread thread : target.getThreads())
            {
                if (thread != null && thread.isSuspended() && !isStepping(thread))
                {
                    return thread;
                }
            }
        }
        catch (Exception ex)
        {
            // best-effort
        }
        return null;
    }

    /**
     * Finds the first non-terminated {@link IThread} of a target, or {@code null}
     * if it has none. Unlike {@link #findSuspendedThread}, this does NOT require the
     * thread to be suspended — it tests bare liveness only and does NOT look at the
     * 1C thread type. NOTE: bare liveness is NOT a client/server
     * discriminator — a standalone server launched in DEBUG mode carries a live
     * thread typed {@code SERVER} — so existing-CLIENT-session detection must use
     * {@link #findFirstLiveClientThread} instead. Best-effort and fully guarded —
     * never throws.
     *
     * @param target the target viewed as an Eclipse debug target
     * @return a non-terminated thread, or {@code null}
     */
    public static IThread findFirstLiveThread(IDebugTarget target)
    {
        if (target == null || target.isTerminated())
        {
            return null;
        }
        try
        {
            for (IThread thread : target.getThreads())
            {
                if (thread != null && !thread.isTerminated())
                {
                    return thread;
                }
            }
        }
        catch (Exception ex)
        {
            // best-effort
        }
        return null;
    }

    /**
     * Finds the first non-terminated {@link IThread} of a target that classifies as
     * CLIENT-side ({@link #isClientThread}), or {@code null} if the target has none.
     * This is THE existing-client-session discriminator: one EDT impl
     * class serves ALL 1C debug targets, so the target itself cannot be told apart —
     * the {@code IRuntimeDebugTargetThread.getType()} of its live threads is the only
     * kind signal. A debug-mode standalone server (ibsrv) target carries a LIVE
     * thread typed {@code SERVER} (presented as «Сервер»), which bare-liveness
     * checks mis-read as a client session; this finder skips such threads, so a
     * server session never short-circuits a client launch and is never terminated
     * by {@code restartIfRunning} / a fresh YAXUnit debug run. Best-effort and fully
     * guarded — never throws.
     *
     * @param target the target viewed as an Eclipse debug target
     * @return a non-terminated CLIENT-side thread, or {@code null}
     */
    public static IThread findFirstLiveClientThread(IDebugTarget target)
    {
        if (target == null || target.isTerminated())
        {
            return null;
        }
        try
        {
            for (IThread thread : target.getThreads())
            {
                if (thread != null && !thread.isTerminated() && isClientThread(thread))
                {
                    return thread;
                }
            }
        }
        catch (Exception ex)
        {
            // best-effort
        }
        return null;
    }

    /**
     * Classifies a debug thread as CLIENT-side or server-side using the canonical
     * 1C type classifier. A thread counts as a CLIENT thread UNLESS its
     * {@link IRuntimeDebugTargetThread#getType()} positively classifies as
     * server-side ({@link DebugTargetTypeUtil#isServer}: {@code SERVER},
     * {@code SERVER_EMULATION}, {@code MOBILE_SERVER}, {@code MOBILE_MANAGED_SERVER}).
     * Deliberately conservative: a non-1C thread, a {@code null}/UNKNOWN type, the
     * non-client-non-server types ({@code JOB}, web services, …) and any reflection/
     * model failure all count as CLIENT — behavior changes ONLY where the type
     * positively says server-side, so the pre-existing client detection cannot be
     * weakened by an unreadable type. Matching is on {@code getType()} exclusively,
     * NEVER on the thread name («Сервер» is just the localized presentation of the
     * type). Never throws.
     *
     * @param thread the thread to classify (may be {@code null})
     * @return {@code true} when the thread counts as CLIENT-side; {@code false} only
     *     for a positively server-typed 1C thread (or {@code null})
     */
    public static boolean isClientThread(IThread thread)
    {
        if (thread == null)
        {
            return false;
        }
        try
        {
            if (thread instanceof IRuntimeDebugTargetThread)
            {
                DebugTargetType type = ((IRuntimeDebugTargetThread)thread).getType();
                if (type != null && DebugTargetTypeUtil.isServer(type))
                {
                    return false;
                }
            }
        }
        catch (Throwable e)
        {
            // Best-effort: an unreadable type must never reclassify a thread as
            // server-side — fall through to the conservative CLIENT default.
        }
        return true;
    }

    /**
     * @param thread the thread to test
     * @return {@code true} if the thread is mid-step (so its {@code isSuspended()}
     *     would otherwise transiently report the pre-step stop). Threads that do
     *     not implement {@code IStep} are never stepping.
     */
    private static boolean isStepping(IThread thread)
    {
        if (thread instanceof org.eclipse.debug.core.model.IStep)
        {
            try
            {
                return ((org.eclipse.debug.core.model.IStep)thread).isStepping();
            }
            catch (Exception ex)
            {
                return false;
            }
        }
        return false;
    }

    /**
     * @param target the server target viewed as an Eclipse debug target
     * @return {@code true} if any of its threads reports suspended
     */
    public static boolean isAnyThreadSuspended(IDebugTarget target)
    {
        return findSuspendedThread(target) != null;
    }

    /**
     * Builds the diagnostic DTO for a server target used by {@code debug_status}.
     * Walks the target's threads (via the Eclipse {@link IThread} interface),
     * reporting the real suspend state, thread count, and the top suspended frame.
     *
     * @param st the server target
     * @return a JSON-friendly map describing the target
     */
    public static Map<String, Object> describe(ServerTarget st)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", st.applicationId); //$NON-NLS-1$
        m.put("targetClass", st.target.getClass().getSimpleName()); //$NON-NLS-1$
        if (st.debugServerUrl != null)
        {
            m.put("debugServerUrl", st.debugServerUrl); //$NON-NLS-1$
        }
        if (st.application != null)
        {
            m.put("application", st.application); //$NON-NLS-1$
        }

        int threadCount = 0;
        boolean anySuspended = false;
        String suspendedAt = null;
        String suspendedThreadName = null;
        try
        {
            for (IThread th : st.target.getThreads())
            {
                if (th == null)
                {
                    continue;
                }
                threadCount++;
                if (th.isSuspended())
                {
                    anySuspended = true;
                    if (suspendedAt == null)
                    {
                        suspendedThreadName = safe(th.getName());
                        IStackFrame top = th.getTopStackFrame();
                        if (top != null)
                        {
                            suspendedAt = safe(top.getName()) + " @ " + top.getLineNumber(); //$NON-NLS-1$
                        }
                    }
                }
            }
        }
        catch (Exception ex)
        {
            // best-effort; report what we have
        }
        m.put("threadCount", threadCount); //$NON-NLS-1$
        m.put("suspended", anySuspended); //$NON-NLS-1$
        if (suspendedAt != null)
        {
            m.put("suspendedAt", suspendedAt); //$NON-NLS-1$
        }
        if (suspendedThreadName != null)
        {
            m.put("suspendedThread", suspendedThreadName); //$NON-NLS-1$
        }
        return m;
    }

    /**
     * @param target the server target viewed as an Eclipse debug target
     * @param applicationId the id to compare against the owning launch's id
     * @return {@code true} if the Eclipse launch that owns {@code target} resolves to
     *     the same {@code applicationId} (real {@code ATTR_APPLICATION_ID},
     *     {@code attach:<name>} or {@code launch:<name>}). Best-effort; never throws.
     */
    private static boolean matchesOwningLaunchId(IDebugTarget target, String applicationId)
    {
        if (target == null || applicationId == null)
        {
            return false;
        }
        try
        {
            String launchId = LaunchConfigUtils.getApplicationIdFor(target.getLaunch());
            return applicationId.equals(launchId);
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    /** Reflectively invokes a no-arg getter and returns its {@code toString()}; null on any failure. */
    private static String reflectString(Object target, String getter)
    {
        try
        {
            Method m = target.getClass().getMethod(getter);
            m.setAccessible(true);
            Object v = m.invoke(target);
            return v != null ? v.toString() : null;
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    /** Reflectively resolves {@code getApplication()} (possibly an Optional) to its name. */
    private static String reflectApplicationName(Object target)
    {
        Object app = reflectApplication(target);
        if (app == null)
        {
            return null;
        }
        String name = reflectString(app, "getName"); //$NON-NLS-1$
        return name != null ? name : app.toString();
    }

    /**
     * Reflectively resolves the target's bound {@code IApplication}
     * ({@code getApplication()}, unwrapping an {@link java.util.Optional}); null on
     * any failure or when no application is bound.
     */
    private static Object reflectApplication(Object target)
    {
        try
        {
            Method m = target.getClass().getMethod("getApplication"); //$NON-NLS-1$
            m.setAccessible(true);
            Object app = m.invoke(target);
            if (app instanceof java.util.Optional<?>)
            {
                app = ((java.util.Optional<?>)app).orElse(null);
            }
            return app;
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    /**
     * Reflectively resolves an {@code IApplication}'s project name
     * ({@code getProject().getName()}); null on any failure. The
     * {@code com.e1c.g5.dt.applications.IApplication} interface is in a different
     * bundle, so this stays reflective like the rest of this support class.
     */
    private static String reflectApplicationProjectName(Object application)
    {
        try
        {
            Method m = application.getClass().getMethod("getProject"); //$NON-NLS-1$
            m.setAccessible(true);
            Object project = m.invoke(application);
            if (project == null)
            {
                return null;
            }
            return reflectString(project, "getName"); //$NON-NLS-1$
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    private static String safe(String s)
    {
        return s == null ? "" : s; //$NON-NLS-1$
    }
}

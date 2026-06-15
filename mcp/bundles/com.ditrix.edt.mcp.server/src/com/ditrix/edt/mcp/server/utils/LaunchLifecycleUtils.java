/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationEvent.ApplicationEventType;
import com.e1c.g5.dt.applications.IApplicationListener;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Shared helpers for the "prepare for a fresh launch" sequence used by tools
 * that spawn 1C clients via {@code workingCopy.launch()} (YAXUnit run/debug
 * tools, etc.).
 *
 * <p>The sequence is needed because EDT's launch delegate brings up its
 * interactive "Update database before launch?" dialog whenever the
 * configuration has unsynced changes, and that dialog blocks the MCP call
 * indefinitely — especially if a 1C client is already holding the infobase in
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

    /** Fallback label for a launch whose configuration name is unavailable. */
    private static final String UNKNOWN_LABEL = "<unknown>"; //$NON-NLS-1$

    // =========================================================================
    // Pre-launch preparation in-flight registry (Fix 2: 25 s budget + pending)
    // =========================================================================

    /**
     * Budget (ms) the tool thread waits for the background pre-launch preparation
     * job to finish before returning a {@code pending} response. 25 seconds is
     * generous enough to cover a typical short recompute without hitting the MCP
     * client's own call timeout.
     */
    public static final long PRELAUNCH_BUDGET_MS = 25_000L;

    /** How long (ms) a completed {@link PrepInFlight} entry stays in the map
     *  before being considered stale and discarded. */
    private static final long INFLIGHT_EXPIRY_MS = 10 * 60 * 1000L; // 10 min

    /**
     * Live state of a background pre-launch preparation job keyed by the same
     * {@code project\u0000applicationId} string as {@link #KEY_LOCKS}.
     *
     * <p>A tool thread that starts the prep job sets {@code startedAtMs} and adds
     * this entry to {@link #PREP_INFLIGHT}. The background job updates
     * {@link #phase} as it progresses and sets {@link #done} (or {@link #error})
     * when it finishes. A concurrent tool call picks up the existing entry and
     * waits on {@link #latch} instead of starting a second job.
     *
     * <p>Instances are published safely: the tool thread writes all fields before
     * putting the entry in the map; subsequent readers see a fully initialised
     * object. Volatile fields ({@code phase}, {@code done}, {@code error}) are
     * visible across threads without additional synchronisation.
     */
    public static final class PrepInFlight
    {
        /** Human-readable phase label set by the background job. */
        public volatile String phase = "recompute"; //$NON-NLS-1$
        /** Wall-clock time the job started (used to compute elapsed seconds). */
        public final long startedAtMs;
        /** Set to {@code true} by the background job when preparation completed. */
        public volatile boolean done = false;
        /** Non-null when preparation ended in a failure; the caller surfaces it. */
        public volatile String error = null;
        /** Latch the background job counts down when done (or on error). */
        public final CountDownLatch latch = new CountDownLatch(1);
        /**
         * Guards single-Job creation. Only the thread that wins
         * {@code started.compareAndSet(false, true)} constructs and schedules
         * the background Job; every other concurrent thread that obtained this
         * same entry via {@code computeIfAbsent} simply awaits {@link #latch}.
         *
         * <p>Public solely for access from the background Job lambda inside
         * {@code RunYaxunitTestsTool} — not part of the general API.
         */
        public final AtomicBoolean started = new AtomicBoolean(false);

        public PrepInFlight(long startedAtMs)
        {
            this.startedAtMs = startedAtMs;
        }

        /** Elapsed whole seconds since the job started. */
        public long elapsedSeconds()
        {
            return (System.currentTimeMillis() - startedAtMs) / 1000L;
        }

        /** {@code true} when the entry is older than {@link #INFLIGHT_EXPIRY_MS}. */
        public boolean isExpired()
        {
            return System.currentTimeMillis() - startedAtMs > INFLIGHT_EXPIRY_MS;
        }
    }

    /**
     * In-flight preparation map. Keyed by {@code project\u0000applicationId}
     * (the same string as {@link #KEY_LOCKS}).
     *
     * <p>Entries are created via {@link ConcurrentMap#computeIfAbsent}; the
     * thread that wins the {@link PrepInFlight#started} CAS schedules the
     * background Job while every other concurrent thread awaits
     * {@link PrepInFlight#latch} on the same entry. Entries for completed,
     * error'd, or timed-out preps expire after {@link #INFLIGHT_EXPIRY_MS} and
     * are replaced atomically on the next access for the same key.
     *
     * <p>Public solely for access from the background Job lambda inside
     * {@code RunYaxunitTestsTool} — not part of the general API.
     */
    public static final ConcurrentMap<String, PrepInFlight> PREP_INFLIGHT = new ConcurrentHashMap<>();

    /**
     * Returns (or creates) a preparation lock key identical in format to
     * {@link #lockFor(String, String)} but returned as a {@code String} so it can
     * be used both as a map key and as the argument to
     * {@code lockFor(projectName, applicationId)}.
     */
    public static String prepKeyFor(String projectName, String applicationId)
    {
        return (projectName != null ? projectName : "") //$NON-NLS-1$
            + '\u0000' + (applicationId != null ? applicationId : ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Production default for {@link #syncSettleWindowMs}: 5 seconds. */
    static final long DEFAULT_SYNC_SETTLE_WINDOW_MS = 5000L;

    /** Production default for {@link #syncApplyTimeoutMs}: 120 seconds. */
    static final long DEFAULT_SYNC_APPLY_TIMEOUT_MS = 120_000L;

    /** Production default for {@link #syncPollIntervalMs}: 500 ms. */
    static final long DEFAULT_SYNC_POLL_INTERVAL_MS = 500L;

    /**
     * Bounded window (ms) during which, right after a forced derived-data
     * recompute, we wait for EDT's {@link ApplicationEventType#UPDATE_STATE_CHANGED}
     * event even though {@link IApplicationManager#getUpdateState} currently reads
     * {@link ApplicationUpdateState#UPDATED}, so a lagging {@code …UPDATE_REQUIRED}
     * desync flag pushed by that event has a chance to surface before we decide
     * "no update needed" (the cached {@code getUpdateState} flag lags
     * the just-regenerated extension {@code .cfe}, so an entry short-circuit on a
     * stale {@code UPDATED} skips the update and the run executes a stale IB).
     *
     * <p>Mutable only so unit tests can shrink the timing windows for speed and
     * determinism (see {@link #setSyncTimingsForTest}); production code never
     * reassigns it.
     */
    static volatile long syncSettleWindowMs = DEFAULT_SYNC_SETTLE_WINDOW_MS;

    /**
     * Generous upper bound (ms) for blocking until the infobase has actually
     * applied the configuration/extension changes — i.e. an
     * {@link ApplicationEventType#UPDATE_STATE_CHANGED} event reports (or
     * {@link IApplicationManager#getUpdateState} confirms)
     * {@link ApplicationUpdateState#UPDATED} after we issue
     * {@link IApplicationManager#update}. {@code update(...)} may return before
     * the DB application completes (async / {@code BEING_UPDATED}), so awaiting
     * the event is the real gate that prevents starting a run against a
     * not-yet-applied IB.
     *
     * <p>Mutable only for tests (see {@link #setSyncTimingsForTest}).
     */
    static volatile long syncApplyTimeoutMs = DEFAULT_SYNC_APPLY_TIMEOUT_MS;

    /**
     * Re-check interval (ms) used as a safety net inside the event-driven await:
     * even while blocked on the listener latch, we wake every {@code syncPollIntervalMs}
     * to re-read {@link IApplicationManager#getUpdateState} once, in case an event
     * was missed (e.g. fired between the initial read and listener registration).
     * Half a second balances responsiveness against churn; the primary signal is
     * the {@link ApplicationEventType#UPDATE_STATE_CHANGED} event, not this poll.
     *
     * <p>Mutable only for tests (see {@link #setSyncTimingsForTest}).
     */
    static volatile long syncPollIntervalMs = DEFAULT_SYNC_POLL_INTERVAL_MS;

    /**
     * Test hook: overrides the infobase-sync timing windows so unit tests run
     * fast and deterministically. Not part of the public API.
     */
    static void setSyncTimingsForTest(long settleWindowMs, long applyTimeoutMs, long pollIntervalMs)
    {
        syncSettleWindowMs = settleWindowMs;
        syncApplyTimeoutMs = applyTimeoutMs;
        syncPollIntervalMs = pollIntervalMs;
    }

    /** Test hook: restores the production sync timing windows. */
    static void resetSyncTimingsForTest()
    {
        syncSettleWindowMs = DEFAULT_SYNC_SETTLE_WINDOW_MS;
        syncApplyTimeoutMs = DEFAULT_SYNC_APPLY_TIMEOUT_MS;
        syncPollIntervalMs = DEFAULT_SYNC_POLL_INTERVAL_MS;
    }

    /**
     * Classification of an {@link ApplicationUpdateState} from the perspective
     * of "is the infobase in sync with the (recomputed) project/extension
     * configuration?". This is the same notion that raises EDT's interactive
     * "Update database?" dialog. Kept as a small pure helper so the
     * synced / needs-update / in-progress decision is unit-testable without an
     * EDT runtime.
     */
    enum SyncCategory
    {
        /** Infobase matches the project: {@link ApplicationUpdateState#UPDATED}. */
        SYNCED,
        /**
         * Infobase is out of sync and a (full or incremental) update is
         * required: {@link ApplicationUpdateState#INCREMENTAL_UPDATE_REQUIRED}
         * or {@link ApplicationUpdateState#FULL_UPDATE_REQUIRED}.
         */
        NEEDS_UPDATE,
        /** An update is running right now: {@link ApplicationUpdateState#BEING_UPDATED}. */
        IN_PROGRESS,
        /**
         * State is {@link ApplicationUpdateState#UNKNOWN} (or {@code null}).
         * Treated conservatively as "not yet known to be synced" so the caller
         * keeps waiting / does not claim a stale-green success.
         */
        UNKNOWN
    }

    /**
     * Classifies an {@link ApplicationUpdateState} into a {@link SyncCategory}.
     * Pure and null-safe ({@code null} → {@link SyncCategory#UNKNOWN}).
     */
    static SyncCategory classify(ApplicationUpdateState state)
    {
        if (state == null)
        {
            return SyncCategory.UNKNOWN;
        }
        switch (state)
        {
        case UPDATED:
            return SyncCategory.SYNCED;
        case INCREMENTAL_UPDATE_REQUIRED:
        case FULL_UPDATE_REQUIRED:
            return SyncCategory.NEEDS_UPDATE;
        case BEING_UPDATED:
            return SyncCategory.IN_PROGRESS;
        case UNKNOWN:
        default:
            return SyncCategory.UNKNOWN;
        }
    }

    /** {@code true} iff {@code state} means the IB is in sync (no update needed). */
    static boolean isSynced(ApplicationUpdateState state)
    {
        return classify(state) == SyncCategory.SYNCED;
    }

    /** {@code true} iff {@code state} means the IB requires a (full/incremental) update. */
    static boolean needsUpdate(ApplicationUpdateState state)
    {
        return classify(state) == SyncCategory.NEEDS_UPDATE;
    }

    /** {@code true} iff an update is currently in progress for the IB. */
    static boolean isInProgress(ApplicationUpdateState state)
    {
        return classify(state) == SyncCategory.IN_PROGRESS;
    }

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
     * @return {@code true} when {@code launch} is currently registered as owned by
     *     an MCP tool (identity-equals lookup — see {@link #registerOwnedLaunch});
     *     {@code false} for {@code null}
     */
    static boolean isOwnedLaunch(ILaunch launch)
    {
        return launch != null && OWNED_LAUNCHES.contains(launch);
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
     * Waits for the incremental build and derived-data computations of the
     * launch's project <em>and every extension project that depends on it</em>
     * to settle before the pre-launch DB update runs.
     *
     * <p>This closes a stale-extension race: after a test module inside an
     * <strong>extension</strong> ({@code .cfe}) is edited, EDT schedules an
     * asynchronous incremental rebuild of that extension. The rebuild lives in a
     * <em>separate</em> {@link IProject} from the launch's configuration project,
     * so the application's update state can read "ready" while the extension is
     * still being rebuilt and exported. The pre-launch update then either no-ops
     * or pushes the stale {@code .cfe} into the infobase, so the first test run
     * executes the old extension and a freshly added test is silently missing —
     * only a second run (after the rebuild settled) picks it up.
     *
     * <p>Thin wrapper over {@link #recomputeAndSettle(Collection)} for the full
     * launch-project-plus-extensions scope, preserved so existing callers/tests
     * keep working. Prefer {@link #recomputeAndSettle(Collection)} +
     * {@link #resolveUpdateScope(IProject, String)} for a narrowable scope.
     *
     * @param launchProject the configuration project the launch targets
     */
    public static void waitForLaunchBuildSettled(IProject launchProject)
    {
        if (launchProject == null || !launchProject.exists() || !launchProject.isOpen())
        {
            return;
        }
        recomputeAndSettle(collectLaunchAndExtensionProjects(launchProject));
    }

    /**
     * Forces a derived-data recompute of the given projects and then waits for the
     * workspace build and per-project derived data to settle, so a freshly edited
     * <strong>extension</strong> ({@code .cfe}) is regenerated and exported to disk
     * before the pre-launch DB update reads the application's update state.
     *
     * <p>This is the FORCE lever the stale-extension fix needs. The previous pre-launch chain
     * only <em>waited</em> for derived data, but the wait returns immediately when
     * nothing is scheduled — so a stale extension {@code .cfe} was never
     * regenerated and {@code appManager.update(...)} simply consumed the existing
     * (stale) export artifact. {@link IDerivedDataManager#recomputeAll()} — EDT's
     * forced derived-data recompute — forces the extension's model, and thus its
     * {@code .cfe}, to be rebuilt before the update.
     *
     * <p>Sequence:
     * <ol>
     *   <li>schedule {@code recomputeAll()} for EVERY project first (so all rebuilds
     *       are queued before we start blocking on any of them);</li>
     *   <li>drain the workspace-wide build job families once via
     *       {@link BuildUtils#waitForBuildJobs};</li>
     *   <li>wait on each project's derived data via {@link BuildUtils#waitForDerivedData}.</li>
     * </ol>
     *
     * <p>Null-safe and defensive: a {@code null}/closed project, or unavailable EDT
     * services ({@code IDtProjectManager}, {@code IDerivedDataManagerProvider}, the
     * per-project {@code IDtProject} or {@code IDerivedDataManager}), make the
     * recompute a per-project no-op. Nothing here is allowed to throw into the
     * launch hot path — failures are logged and the loop continues.
     *
     * @param projects projects to force-recompute and settle (may be {@code null} or
     *            contain {@code null}/closed entries — all skipped)
     */
    public static void recomputeAndSettle(Collection<IProject> projects)
    {
        if (projects == null || projects.isEmpty())
        {
            return;
        }

        // Phase 1: schedule a forced recompute for every open project up front,
        // so all extension rebuilds are queued before we start blocking, via the
        // standard IDtProjectManager -> IDerivedDataManagerProvider ->
        // IDerivedDataManager.recomputeAll() chain.
        for (IProject project : projects)
        {
            if (project == null || !project.exists() || !project.isOpen())
            {
                continue;
            }
            forceRecompute(project);
        }

        // Phase 2: drain the workspace-wide build job families once. This is not
        // project-scoped, so it covers the extension's incremental rebuild
        // regardless of which project owns it.
        BuildUtils.waitForBuildJobs(new NullProgressMonitor());

        // Phase 3: wait on each project's derived data so the recomputed model
        // (and the regenerated .cfe) is fully exported before the DB update runs.
        for (IProject project : projects)
        {
            if (project == null || !project.exists() || !project.isOpen())
            {
                continue;
            }
            BuildUtils.waitForDerivedData(project);
        }
    }

    /**
     * Forces a derived-data recompute for a single open project via the standard
     * {@code IDtProjectManager -> IDerivedDataManagerProvider -> IDerivedDataManager}
     * chain. Any missing EDT service makes this a no-op for the project, and a
     * {@link RuntimeException} is logged and swallowed — a recompute failure must
     * never abort the launch hot path.
     *
     * @param project an open project (callers guard {@code null}/closed projects)
     */
    private static void forceRecompute(IProject project)
    {
        try
        {
            if (Activator.getDefault() == null)
            {
                return;
            }
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            if (dtProjectManager == null)
            {
                return;
            }
            IDtProject dtProject = dtProjectManager.getDtProject(project);
            if (dtProject == null)
            {
                return;
            }
            IDerivedDataManagerProvider ddProvider =
                Activator.getDefault().getDerivedDataManagerProvider();
            if (ddProvider == null)
            {
                return;
            }
            IDerivedDataManager ddManager = ddProvider.get(dtProject);
            if (ddManager == null)
            {
                return;
            }
            Activator.logInfo("Pre-launch: forcing derived-data recompute for project: " //$NON-NLS-1$
                + project.getName());
            ddManager.recomputeAll();
        }
        catch (RuntimeException e)
        {
            // A recompute failure must never abort the launch — log and move on.
            Activator.logError("Error forcing derived-data recompute for " //$NON-NLS-1$
                + project.getName(), e);
        }
    }

    /**
     * Selective variant of {@link #recomputeAndSettle}: consults
     * {@link PreLaunchChangeTracker} to partition {@code projects} into dirty and
     * clean subsets, then applies the expensive {@code recomputeAll()} only to
     * dirty projects while giving clean projects the cheap
     * {@link BuildUtils#waitForDerivedData} pass only.
     *
     * <p>This is the fix for the performance regression: on a large configuration,
     * unconditional {@code recomputeAll()} for every project on every
     * {@code run_yaxunit_tests} call costs 2–8 minutes. After a successful prepare,
     * projects are marked clean — no recompute until a file change is detected.
     * The stale-{@code .cfe} safety guarantee is preserved: a project is dirty on
     * the first call after plugin start, and again whenever the workspace listener
     * observes a non-derived file change in that project.
     *
     * <h3>Ordering-race fix</h3>
     * <p>The dirty snapshot ({@link PreLaunchChangeTracker#snapshotDirty}) is taken
     * BEFORE the recompute begins and returned to the caller. The caller passes it to
     * {@link PreLaunchChangeTracker#markPrepared(Collection, Map)} only on success
     * paths, so a file change that arrives DURING the recompute increments the
     * generation counter in {@link PreLaunchChangeTracker#DIRTY} to a value higher
     * than the snapshot; the subsequent conditional remove in {@code markPrepared}
     * fails and the project remains dirty for the next launch.
     *
     * <p>This method does NOT call {@code markPrepared} itself — that is the
     * caller's ({@link #prepareForFreshLaunch}) responsibility, and only on the
     * success returns.
     *
     * <p>Sequence:
     * <ol>
     *   <li>Take the dirty snapshot (generation-keyed) BEFORE scheduling any
     *       recompute.</li>
     *   <li>Partition scope: dirty (per {@link PreLaunchChangeTracker#isDirty}) vs.
     *       clean.</li>
     *   <li>Dirty projects: {@link #recomputeAndSettle(Collection)} — full
     *       forced recompute + workspace build drain + per-project derived-data
     *       wait.</li>
     *   <li>Clean projects: {@link BuildUtils#waitForDerivedData} only (returns
     *       immediately when nothing is pending — the pre-regression fast path).
     *       No {@code waitForBuildJobs} here: the workspace build was already
     *       drained by the dirty-project phase (or by EDT's own incremental builds
     *       if there were no dirty projects at all, in which case nothing pending
     *       means nothing to wait on).</li>
     * </ol>
     *
     * <p>The log lines produced by this method ("Pre-launch: N project(s) changed
     * since last prepared launch → forced recompute: [names]; M unchanged →
     * skipped" or "all N project(s) up-to-date — skipping recompute") are the
     * primary evidence that the optimisation is in effect.
     *
     * @param projects scope after {@link #resolveUpdateScope} has been applied
     *            (may be {@code null} or empty — returns empty snapshot)
     * @return the dirty snapshot taken before the recompute; pass it to
     *         {@link PreLaunchChangeTracker#markPrepared(Collection, Map)} on the
     *         success path (the caller, not this method, is responsible for that
     *         call so {@code markPrepared} is only invoked on genuine successes)
     */
    public static Map<String, Long> recomputeAndSettleIfDirty(Collection<IProject> projects)
    {
        // Take the snapshot BEFORE the recompute so any change arriving during
        // the recompute stores a HIGHER generation in DIRTY; markPrepared's
        // conditional remove will then leave that entry in place.
        Map<String, Long> snapshot = PreLaunchChangeTracker.snapshotDirty(projects);

        if (projects == null || projects.isEmpty())
        {
            return snapshot;
        }

        List<IProject> dirty = new ArrayList<>();
        List<IProject> clean = new ArrayList<>();
        for (IProject project : projects)
        {
            if (project == null || !project.exists() || !project.isOpen())
            {
                continue;
            }
            if (PreLaunchChangeTracker.isDirty(project))
            {
                dirty.add(project);
            }
            else
            {
                clean.add(project);
            }
        }

        if (!dirty.isEmpty())
        {
            String dirtyNames = dirty.stream().map(IProject::getName)
                .collect(Collectors.joining(", ")); //$NON-NLS-1$
            Activator.logInfo("Pre-launch: " + dirty.size() //$NON-NLS-1$
                + " project(s) changed since last prepared launch -> forced recompute: [" //$NON-NLS-1$
                + dirtyNames + "]; " + clean.size() + " unchanged -> skipped"); //$NON-NLS-1$ //$NON-NLS-2$
            // Full recompute+settle for the dirty subset.
            recomputeAndSettle(dirty);
        }
        else
        {
            Activator.logInfo("Pre-launch: all " + projects.size() //$NON-NLS-1$
                + " project(s) up-to-date — skipping recompute"); //$NON-NLS-1$
        }

        // Cheap derived-data drain for clean projects (no recomputeAll, returns
        // immediately when nothing pending — the pre-regression fast path).
        for (IProject project : clean)
        {
            BuildUtils.waitForDerivedData(project);
        }

        // NOTE: markPrepared is intentionally NOT called here. The caller
        // (prepareForFreshLaunch) calls PreLaunchChangeTracker.markPrepared(all, snapshot)
        // only on the success paths so that a failed or aborted prepare never
        // marks projects as clean.
        return snapshot;
    }

    /**
     * Resolves which projects the pre-launch recompute+update should cover, from
     * the optional {@code updateScope} tool parameter.
     * <ul>
     *   <li>{@code null} / empty / {@code "all"} (case-insensitive) → the full list
     *       from {@link #collectLaunchAndExtensionProjects(IProject)} (launch project
     *       first, then its dependent extensions);</li>
     *   <li>{@code "configuration"} (case-insensitive) → just {@code [launchProject]};</li>
     *   <li>{@code "extension:<Name>"} or a comma-separated list of such tokens →
     *       {@code launchProject} PLUS only the dependent extension projects whose
     *       {@link IExtensionProject#getProject()} name equals a requested
     *       {@code <Name>}. The launch (configuration) project is ALWAYS included,
     *       because an extension cannot reach the infobase without its parent
     *       configuration present.</li>
     * </ul>
     *
     * <p>This resolver itself stays lenient about unknown names (the configuration
     * is still returned) so it never empties the recompute scope; the HARD-ERROR
     * contract for typo'd extension names lives in {@link #validateUpdateScope},
     * which {@link #prepareForFreshLaunch} runs FIRST — a silently narrowed scope
     * would skip the recompute of the intended extension and produce the exact
     * stale-green run the {@code updateScope} parameter was built to prevent.
     *
     * <p>Null-safe: a {@code null} {@code launchProject} yields an empty list.
     * The launch (configuration) project is always first in the returned list.
     *
     * @param launchProject the configuration project the launch targets
     * @param updateScope the raw {@code updateScope} parameter value (may be {@code null})
     * @return ordered, de-duplicated project list (configuration first)
     */
    public static List<IProject> resolveUpdateScope(IProject launchProject, String updateScope)
    {
        if (launchProject == null)
        {
            return new ArrayList<>();
        }

        String scope = updateScope != null ? updateScope.trim() : ""; //$NON-NLS-1$
        if (scope.isEmpty() || "all".equalsIgnoreCase(scope)) //$NON-NLS-1$
        {
            return collectLaunchAndExtensionProjects(launchProject);
        }
        if ("configuration".equalsIgnoreCase(scope)) //$NON-NLS-1$
        {
            List<IProject> only = new ArrayList<>();
            only.add(launchProject);
            return only;
        }

        Set<String> requested = parseRequestedExtensionNames(scope);

        Set<IProject> projects = new LinkedHashSet<>();
        projects.add(launchProject);
        if (requested.isEmpty())
        {
            // Scope referenced no parseable extension name — degrade to just the
            // configuration (which is always rebuilt) rather than the full scope.
            // validateUpdateScope reports this as a hard error to callers that
            // go through prepareForFreshLaunch.
            return new ArrayList<>(projects);
        }
        for (IProject project : collectLaunchAndExtensionProjects(launchProject))
        {
            if (project == null || launchProject.equals(project))
            {
                continue;
            }
            if (requested.contains(project.getName()))
            {
                projects.add(project);
            }
        }
        return new ArrayList<>(projects);
    }

    /**
     * Parses the token list of an {@code updateScope} value (anything other than
     * {@code all}/{@code configuration}) into the set of requested extension
     * project names. Shared grammar for {@link #resolveUpdateScope} and
     * {@link #validateUpdateScope}: tokens are comma-separated; each is either
     * {@code extension:<Name>} (case-insensitive prefix) or a bare {@code <Name>}
     * (tolerated as the same intent). A token with an unrecognised
     * {@code <prefix>:} is kept WHOLE so the validator surfaces it as unknown
     * instead of a typo being silently dropped. Blank tokens and an
     * {@code extension:} with an empty name are skipped. Names are collected
     * case-sensitively (project names are case-sensitive on Linux).
     */
    static Set<String> parseRequestedExtensionNames(String scope)
    {
        Set<String> requested = new LinkedHashSet<>();
        if (scope == null)
        {
            return requested;
        }
        for (String token : scope.split(",")) //$NON-NLS-1$
        {
            String trimmed = token.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon >= 0)
            {
                String prefix = trimmed.substring(0, colon).trim();
                String name = trimmed.substring(colon + 1).trim();
                if ("extension".equalsIgnoreCase(prefix)) //$NON-NLS-1$
                {
                    if (!name.isEmpty())
                    {
                        requested.add(name);
                    }
                    continue;
                }
                // Unrecognised "<prefix>:" — keep the whole token so the
                // validator reports it instead of silently dropping a typo.
                requested.add(trimmed);
            }
            else
            {
                requested.add(trimmed);
            }
        }
        return requested;
    }

    /**
     * Validates an {@code updateScope} value against the launch project's actual
     * dependent extension projects. Unknown extension names are a HARD ERROR:
     * silently skipping a typo'd name would narrow the pre-launch recompute and
     * produce the exact stale-green run {@code updateScope} was built to prevent.
     *
     * @param launchProject the configuration project the launch targets
     * @param updateScope the raw {@code updateScope} parameter value (may be {@code null})
     * @return {@code null} when the scope is valid ({@code null}/empty/{@code all}/
     *         {@code configuration} are always valid); otherwise a human-readable
     *         error listing the requested-but-unknown names and the available
     *         extension project names
     */
    public static String validateUpdateScope(IProject launchProject, String updateScope)
    {
        String scope = updateScope != null ? updateScope.trim() : ""; //$NON-NLS-1$
        if (launchProject == null || scope.isEmpty()
            || "all".equalsIgnoreCase(scope) || "configuration".equalsIgnoreCase(scope)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return null;
        }
        Set<String> requested = parseRequestedExtensionNames(scope);
        if (requested.isEmpty())
        {
            return "updateScope '" + updateScope + "' contains no usable extension name. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Valid values: 'all', 'configuration', or 'extension:<ProjectName>' " //$NON-NLS-1$
                + "(comma-separate several)."; //$NON-NLS-1$
        }
        Set<String> available = new LinkedHashSet<>();
        for (IProject candidate : collectLaunchAndExtensionProjects(launchProject))
        {
            if (candidate != null && !launchProject.equals(candidate))
            {
                available.add(candidate.getName());
            }
        }
        List<String> unknown = new ArrayList<>();
        for (String name : requested)
        {
            if (!available.contains(name))
            {
                unknown.add(name);
            }
        }
        if (unknown.isEmpty())
        {
            return null;
        }
        return unknownExtensionNamesError(unknown, available);
    }

    /**
     * Builds the hard-error message for {@link #validateUpdateScope}: lists the
     * requested-but-unknown extension names AND the available extension project
     * names so the caller can fix the typo without another discovery round-trip.
     */
    static String unknownExtensionNamesError(Collection<String> unknownNames,
            Collection<String> availableNames)
    {
        return "updateScope requests unknown extension project name" //$NON-NLS-1$
            + (unknownNames.size() == 1 ? "" : "s") + ": " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + String.join(", ", unknownNames) //$NON-NLS-1$
            + ". Available extension projects for this configuration: " //$NON-NLS-1$
            + (availableNames.isEmpty() ? "<none>" : String.join(", ", availableNames)) //$NON-NLS-1$ //$NON-NLS-2$
            + ". Names are case-sensitive; fix the 'extension:<Name>' value, or use " //$NON-NLS-1$
            + "'all' / 'configuration'."; //$NON-NLS-1$
    }

    /**
     * Returns the launch's project followed by every open extension project whose
     * parent configuration project is the launch's project. Order is preserved
     * and duplicates removed; the launch project is always first.
     *
     * <p>Extensions are discovered via {@link IV8ProjectManager} —
     * {@link IExtensionProject#getParentProject()} links an extension back to the
     * configuration project it extends. When the project manager is unavailable
     * (headless tests, early startup) the list degrades gracefully to just the
     * launch project, so the build wait is still performed for it.
     */
    static List<IProject> collectLaunchAndExtensionProjects(IProject launchProject)
    {
        Set<IProject> projects = new LinkedHashSet<>();
        projects.add(launchProject);

        IV8ProjectManager projectManager = Activator.getDefault() != null
            ? Activator.getDefault().getV8ProjectManager() : null;
        if (projectManager == null)
        {
            return new ArrayList<>(projects);
        }
        try
        {
            Collection<IExtensionProject> extensions =
                projectManager.getProjects(IExtensionProject.class);
            if (extensions != null)
            {
                for (IExtensionProject extension : extensions)
                {
                    if (extension == null)
                    {
                        continue;
                    }
                    IProject parent = extension.getParentProject();
                    if (!launchProject.equals(parent))
                    {
                        continue;
                    }
                    IProject extProject = extension.getProject();
                    if (extProject != null && extProject.exists() && extProject.isOpen())
                    {
                        projects.add(extProject);
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            // Discovery is best-effort: a failure here must not abort the launch.
            // The workspace-wide build join already drained the extension build;
            // we just skip the per-extension derived-data wait.
            Activator.logError("Error collecting extension projects for " //$NON-NLS-1$
                + launchProject.getName(), e);
        }
        return new ArrayList<>(projects);
    }

    /**
     * Resolves the effective application id for a launch: returns {@code applicationId}
     * unchanged when it is set, otherwise falls back to the project's <b>default</b>
     * application id.
     *
     * <p>Needed because a runtime-client launch configuration can be created WITHOUT
     * an explicit application binding (its {@code applicationId} attribute is empty).
     * In that case {@code updateBeforeLaunch} has no target to update, the programmatic
     * pre-launch update fails "Application not found", and the EDT launch delegate then
     * pops its interactive "Update infobase before launch?" modal — which blocks the MCP
     * call. Falling back to the project default (the same application
     * {@code get_applications} reports as {@code defaultApplicationId}) lets the update
     * run headlessly so no dialog appears.
     *
     * @param project the resolved project (may be {@code null})
     * @param applicationId the id from the call / launch config (may be {@code null}/empty)
     * @param appManager the application manager (may be {@code null})
     * @return the given id when non-empty; else the project's default application id;
     *         else the original (possibly empty) value when no default exists
     */
    public static String resolveDefaultApplicationId(IProject project, String applicationId,
            IApplicationManager appManager)
    {
        if (applicationId != null && !applicationId.isEmpty())
        {
            return applicationId;
        }
        if (appManager == null || project == null)
        {
            return applicationId;
        }
        try
        {
            // getDefaultApplication may throw ApplicationException; degrade to the
            // original id (the caller then behaves exactly as before the fallback).
            return appManager.getDefaultApplication(project)
                .map(IApplication::getId)
                .orElse(applicationId);
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error resolving default application for project " //$NON-NLS-1$
                + project.getName(), e);
            return applicationId;
        }
    }

    /**
     * Brings the given application's database to {@link ApplicationUpdateState#UPDATED}
     * if needed, using the same programmatic path as {@code update_database}, and
     * <strong>blocks until the infobase has actually applied the change</strong>
     * (the {@code .cfe}/configuration is live in the DB) before returning success.
     *
     * <p>This is the hard stale-IB guarantee: never let a YAXUnit run start
     * while the IB is out of sync with the just-recomputed extension configuration.
     * The previous version trusted the very first {@code getUpdateState} read and
     * short-circuited on {@code UPDATED}; but that read is a <em>cached</em> EDT-side
     * flag that <em>lags</em> the regenerated {@code .cfe} (a manual 1C launch at that
     * instant shows the "update database?" dialog while the poll still reports
     * {@code UPDATED}). We now drive the wait off the same authoritative push-style
     * signal the "Applications" view's {@code ApplicationsDecorator} uses to flip its
     * out-of-sync "*" star: the {@link ApplicationEventType#UPDATE_STATE_CHANGED} event
     * delivered by {@link IApplicationManager}. Two phases:
     * <ol>
     *   <li><strong>Settle before deciding "no update needed".</strong> If the cheap
     *       entry read of {@code getUpdateState} is {@code UPDATED} (suspect of lag),
     *       {@linkplain #awaitUpdateState await} an {@code UPDATE_STATE_CHANGED} event
     *       carrying a {@code …UPDATE_REQUIRED} state for up to {@link #syncSettleWindowMs};
     *       if none arrives in that window the IB is treated as genuinely in sync.</li>
     *   <li><strong>Block until applied.</strong> After issuing
     *       {@link IApplicationManager#update}, {@linkplain #awaitUpdateState await} the
     *       {@code UPDATE_STATE_CHANGED→UPDATED} event (treating {@code BEING_UPDATED}
     *       as "keep waiting") for up to {@link #syncApplyTimeoutMs}. {@code update(...)}
     *       may return before the DB application completes, so the event — not its
     *       return value — is the real gate.</li>
     * </ol>
     *
     * <p>If sync is not observed within {@link #syncApplyTimeoutMs}, returns an
     * explicit, actionable error so the caller ABORTS the run rather than producing a
     * silent stale-green result.
     *
     * <p>Returns {@code Optional.empty()} on success (IB observed {@code UPDATED});
     * on failure, returns a populated error message. Null-safe (headless: a {@code null}
     * {@code appManager}/application is reported, never thrown into the launch path).
     *
     * <p>This 3-arg overload runs WITHOUT the Phase-A settle wait — it is the plain
     * "is the IB out of sync?" path used by {@code debug_launch} (and any caller that
     * did <em>not</em> just force a derived-data recompute). On a genuinely up-to-date
     * IB ({@code getUpdateState()==UPDATED}) it returns immediately, so a synced
     * {@code debug_launch} never pays the settle window. The settle-before-decide wait
     * (warranted only right after a forced recompute, where the cached {@code UPDATED}
     * flag can lag the just-regenerated {@code .cfe}) is opt-in via the 4-arg overload
     * with {@code settleAfterPossibleRecompute=true}.
     */
    public static Optional<String> updateApplicationIfNeeded(IProject project, String applicationId,
            IApplicationManager appManager)
    {
        return updateApplicationIfNeeded(project, applicationId, appManager, false);
    }

    /**
     * Same contract as {@link #updateApplicationIfNeeded(IProject, String, IApplicationManager)},
     * but with an explicit switch for the Phase-A settle-before-decide wait.
     *
     * <p>{@code settleAfterPossibleRecompute} controls the cost/safety trade-off of the
     * entry {@code UPDATED} reading:
     * <ul>
     *   <li>{@code true} — the caller has just run a forced derived-data recompute
     *       ({@code recomputeAndSettle}), so a cached {@code UPDATED} may LAG the
     *       freshly regenerated {@code .cfe}. On an entry {@code UPDATED}
     *       we therefore {@linkplain #awaitUpdateState await} a lagging
     *       {@code …UPDATE_REQUIRED} event for up to {@link #syncSettleWindowMs} before
     *       trusting "no update needed". This is the YAXUnit fresh-launch path.</li>
     *   <li>{@code false} — no recompute happened this call, so a cached {@code UPDATED}
     *       is authoritative: we return immediately (no settle window). This restores the
     *       fast path for a plain {@code debug_launch} against an already-synced IB
     *       (previously the async-launch benefit was undercut by an unconditional ~5s settle).</li>
     * </ul>
     *
     * <p>The {@code BEING_UPDATED} (in-progress) wait and the post-update
     * block-until-{@code UPDATED} gate are unaffected by this flag — both always run, so
     * the stale-IB refusal and the half-applied-IB guard stay intact regardless.
     *
     * @param settleAfterPossibleRecompute {@code true} to wait out a possibly-lagging
     *            {@code UPDATED} on entry (YAXUnit post-recompute path); {@code false} to
     *            trust an entry {@code UPDATED} and return immediately (plain debug_launch)
     */
    public static Optional<String> updateApplicationIfNeeded(IProject project, String applicationId,
            IApplicationManager appManager, boolean settleAfterPossibleRecompute)
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

            // Phase A — settle before deciding "no update needed". Cheap entry read
            // of getUpdateState; a stale UPDATED right after the recompute is suspect
            // because the cached flag lags the just-regenerated .cfe. Instead of
            // busy-polling that lagging cache, await EDT's UPDATE_STATE_CHANGED event
            // (the star signal): if a …UPDATE_REQUIRED event arrives within the settle
            // window we update; if none does, the IB is genuinely in sync.
            ApplicationUpdateState state = appManager.getUpdateState(application);

            if (isInProgress(state))
            {
                return awaitInProgressUpdate(appManager, application);
            }

            if (isSynced(state))
            {
                if (!settleAfterPossibleRecompute)
                {
                    // No recompute happened this call, so the cached UPDATED is
                    // authoritative — return immediately (no settle window). This is the
                    // plain debug_launch / update_database path: a synced IB must not pay
                    // the ~5s settle wait the YAXUnit recompute path needs.
                    return Optional.empty();
                }
                // Post-recompute and suspect of lag: wait briefly for a lagging
                // …UPDATE_REQUIRED event to arrive. If none does within the settle
                // window, treat as in sync.
                ApplicationUpdateState settled = awaitUpdateState(appManager, application,
                    s -> classify(s) == SyncCategory.NEEDS_UPDATE, syncSettleWindowMs);
                if (!needsUpdate(settled))
                {
                    // Confirmed in sync across the settle window — genuine no-op.
                    return Optional.empty();
                }
                Activator.logInfo("Pre-launch: UPDATE_STATE_CHANGED surfaced " + settled //$NON-NLS-1$
                    + " after an initial stale UPDATED reading — updating"); //$NON-NLS-1$
                state = settled;
            }

            // Phase B — IB needs an update (or state is UNKNOWN). Issue the update,
            // then await the UPDATE_STATE_CHANGED→UPDATED event before returning.
            return performUpdateAndAwaitApplied(appManager, application, applicationId, state);
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error during pre-launch DB update", e); //$NON-NLS-1$
            return Optional.of("Database update failed: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Handles the "another caller is already updating this IB" case: waits
     * (event-driven) until the in-progress update actually applies, then reports
     * success or the stale-infobase refusal. Launching while the update is mid-flight
     * would execute a half-updated IB.
     *
     * @param appManager the EDT application manager (event source)
     * @param application the application being updated by another caller
     * @return {@link Optional#empty()} once the IB reaches SYNCED, otherwise the
     *         stale-infobase error message
     */
    private static Optional<String> awaitInProgressUpdate(IApplicationManager appManager,
        IApplication application)
    {
        ApplicationUpdateState state = awaitUpdateState(appManager, application,
            s -> classify(s) == SyncCategory.SYNCED, syncApplyTimeoutMs);
        if (isSynced(state))
        {
            return Optional.empty();
        }
        return Optional.of(staleInfobaseError(state));
    }

    /**
     * Phase B of {@link #updateApplicationIfNeeded}: issues the incremental DB
     * update and gates on the {@code UPDATE_STATE_CHANGED→UPDATED} event before
     * returning, so a launch never runs against a not-yet-applied IB.
     *
     * <p>A terminal {@code …UPDATE_REQUIRED} return from {@code update()} fails fast
     * (no further transition can arrive without user action); a transitional
     * {@code BEING_UPDATED}/{@code UNKNOWN} is awaited up to {@link #syncApplyTimeoutMs}.
     * {@link ApplicationException} is propagated to the caller's existing handler.
     *
     * @param appManager the EDT application manager
     * @param application the application whose IB is updated
     * @param applicationId the application id (for log messages)
     * @param stateBefore the update state observed before issuing the update (logged)
     * @return {@link Optional#empty()} once the IB is UPDATED, otherwise an
     *         actionable out-of-sync error message
     * @throws ApplicationException if the update call itself fails
     */
    private static Optional<String> performUpdateAndAwaitApplied(IApplicationManager appManager,
        IApplication application, String applicationId, ApplicationUpdateState stateBefore)
        throws ApplicationException
    {
        ExecutionContext context = new ExecutionContext();
        Shell shell = grabActiveShell();
        if (shell != null)
        {
            context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
        }
        Activator.logInfo("Pre-launch DB update: application=" + applicationId //$NON-NLS-1$
            + ", stateBefore=" + stateBefore); //$NON-NLS-1$
        ApplicationUpdateState after = appManager.update(application,
            ApplicationUpdateType.INCREMENTAL, context, new NullProgressMonitor());
        Activator.logInfo("Pre-launch DB update returned: stateAfter=" + after //$NON-NLS-1$
            + " (now awaiting the UPDATE_STATE_CHANGED→UPDATED event)"); //$NON-NLS-1$

        // Post-condition gate: the auto-chain promises the IB is actually in
        // UPDATED state (the .cfe applied) before workingCopy.launch() runs.
        // appManager.update() may return UPDATED (done) or a transitional
        // BEING_UPDATED/UNKNOWN on the async path — those are awaited below.
        // A …UPDATE_REQUIRED return, however, is TERMINAL: the update itself
        // decided it cannot proceed without user action (e.g. an interactive
        // restructure), so no further UPDATE_STATE_CHANGED transition can ever
        // arrive. Awaiting SYNCED would stall the MCP call for the full apply
        // timeout and then fail with the very same out-of-sync error — fail
        // fast instead, restoring the old prompt-error behaviour.
        if (needsUpdate(after))
        {
            return Optional.of(terminalOutOfSyncError(after));
        }
        if (!isSynced(after))
        {
            after = awaitUpdateState(appManager, application,
                s -> classify(s) == SyncCategory.SYNCED, syncApplyTimeoutMs);
        }
        if (isSynced(after))
        {
            Activator.logInfo("Pre-launch DB update applied: IB is UPDATED for application=" //$NON-NLS-1$
                + applicationId);
            return Optional.empty();
        }
        return Optional.of(staleInfobaseError(after));
    }

    /**
     * Builds the explicit, actionable "infobase is still out of sync" message
     * returned when the IB does not reach {@link ApplicationUpdateState#UPDATED}
     * within {@link #syncApplyTimeoutMs}. The point is to ABORT the run rather
     * than execute it against a not-yet-applied IB (which would yield a stale
     * green result).
     */
    private static String staleInfobaseError(ApplicationUpdateState finalState)
    {
        return "Infobase is still out of sync (DB requires update; extension changes " //$NON-NLS-1$
            + "not yet applied) after " + (syncApplyTimeoutMs / 1000) //$NON-NLS-1$
            + "s (final update state: " + finalState + ") — results would be stale, " //$NON-NLS-1$ //$NON-NLS-2$
            + "so the run was refused. Retry the run; if it persists, call " //$NON-NLS-1$
            + "`update_database` (optionally with `fullUpdate=true`) and " //$NON-NLS-1$
            + "inspect the EDT problems view."; //$NON-NLS-1$
    }

    /**
     * Builds the "infobase is out of sync and the programmatic update cannot fix
     * it" message returned when {@link IApplicationManager#update} itself comes
     * back with a terminal {@code …UPDATE_REQUIRED} state (e.g. a restructure
     * that needs interactive confirmation). Unlike {@link #staleInfobaseError}
     * there is nothing to wait for — no further state transition can happen
     * without user action — so the caller is told immediately instead of after
     * the full apply timeout.
     */
    private static String terminalOutOfSyncError(ApplicationUpdateState finalState)
    {
        return "Infobase is still out of sync after the programmatic update " //$NON-NLS-1$
            + "(final update state: " + finalState + ") — the update requires user " //$NON-NLS-1$ //$NON-NLS-2$
            + "action (e.g. an interactive restructure), so the run was refused " //$NON-NLS-1$
            + "immediately rather than executed stale. Call `update_database` " //$NON-NLS-1$
            + "(optionally with `fullUpdate=true`) and " //$NON-NLS-1$
            + "inspect the EDT problems view, then retry."; //$NON-NLS-1$
    }

    /**
     * Waits (event-driven) until the given application's infobase is observed to be
     * {@link ApplicationUpdateState#UPDATED} (the DB has actually applied the
     * configuration/extension change), treating {@link ApplicationUpdateState#BEING_UPDATED}
     * as "keep waiting", up to {@link #syncApplyTimeoutMs}. Package-private on
     * purpose: nothing in production calls it today — the auto-chain
     * ({@link #updateApplicationIfNeeded}) applies the same
     * {@link #awaitUpdateState} gate inline — so this is kept only as the
     * canonical "block until the IB applied the update" wrapper (and its unit-test
     * seam), NOT as public API. Wakes on EDT's
     * {@link ApplicationEventType#UPDATE_STATE_CHANGED} event rather than polling
     * the lagging cached state.
     *
     * <p>Null-safe: a {@code null} {@code appManager}/{@code application} returns
     * {@link ApplicationUpdateState#UNKNOWN} without throwing.
     *
     * @return the last observed state; {@link ApplicationUpdateState#UPDATED} on
     *         success, otherwise the terminal/last state on timeout
     */
    static ApplicationUpdateState waitForInfobaseApplied(IApplicationManager appManager,
            IApplication application)
    {
        return awaitUpdateState(appManager, application,
            s -> classify(s) == SyncCategory.SYNCED, syncApplyTimeoutMs);
    }

    /**
     * Event-driven await for an application's {@link ApplicationUpdateState}.
     * Instead of repeatedly reading the <em>cached</em>
     * {@link IApplicationManager#getUpdateState} (which lags the freshly recomputed
     * {@code .cfe}), it registers an {@link IApplicationListener} and blocks on EDT's
     * push-style {@link ApplicationEventType#UPDATE_STATE_CHANGED} event — the very
     * signal the "Applications" view's {@code ApplicationsDecorator} uses to flip its
     * out-of-sync "*" star promptly.
     *
     * <p>Contract:
     * <ul>
     *   <li>BEFORE blocking, reads the current {@code getUpdateState} once (an event
     *       may already have fired) and returns immediately if {@code done} is
     *       already satisfied;</li>
     *   <li>otherwise blocks on a latch up to {@code timeoutMs}, re-evaluating
     *       {@code done} on every {@code UPDATE_STATE_CHANGED} signal for this
     *       application, and also waking every {@link #syncPollIntervalMs} to
     *       re-read {@code getUpdateState} as a safety net against a missed event;</li>
     *   <li>ALWAYS {@code removeAppllicationListener(...)} in a {@code finally};</li>
     *   <li>returns the last observed state once {@code done} holds, or the last
     *       observed state when {@code timeoutMs} elapses.</li>
     * </ul>
     *
     * <p>Null-safe: a {@code null} {@code appManager}/{@code application} (or a
     * {@code null} {@code done}) returns {@link ApplicationUpdateState#UNKNOWN}
     * without throwing. Note the EDT API method name carries a real upstream typo —
     * {@code addAppllicationListener}/{@code removeAppllicationListener} (double "l").
     *
     * @param appManager the EDT application manager (event source)
     * @param application the application whose update state is awaited
     * @param done predicate that, once satisfied by an observed state, ends the wait
     * @param timeoutMs maximum time (ms) to wait for {@code done} to be satisfied
     * @return the last observed {@link ApplicationUpdateState}
     */
    static ApplicationUpdateState awaitUpdateState(IApplicationManager appManager,
            IApplication application, Predicate<ApplicationUpdateState> done, long timeoutMs)
    {
        if (appManager == null || application == null || done == null)
        {
            return ApplicationUpdateState.UNKNOWN;
        }

        // Latest state pushed by an UPDATE_STATE_CHANGED event (or read directly).
        AtomicReference<ApplicationUpdateState> observed =
            new AtomicReference<>(ApplicationUpdateState.UNKNOWN);
        // Signals every time a relevant event arrives so the waiter re-evaluates.
        AtomicReference<CountDownLatch> signal = new AtomicReference<>(new CountDownLatch(1));

        IApplicationListener listener = event -> {
            if (event == null || event.getEventType() != ApplicationEventType.UPDATE_STATE_CHANGED
                || !isSameApplication(event.getApplication(), application))
            {
                return;
            }
            ApplicationUpdateState pushed = event.getUpdateState();
            if (pushed != null)
            {
                observed.set(pushed);
            }
            // Wake the waiter; it re-reads `observed` and re-evaluates `done`.
            signal.get().countDown();
        };

        appManager.addAppllicationListener(listener);
        try
        {
            // An event may already have fired before registration — read once now.
            ApplicationUpdateState current = readUpdateState(appManager, application);
            observed.set(current);
            if (done.test(current))
            {
                return current;
            }

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true)
            {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0)
                {
                    return observed.get();
                }
                CountDownLatch latch = signal.get();
                // Wake on the event OR every syncPollIntervalMs (safety net for a
                // missed event), whichever comes first.
                long waitMs = Math.min(remaining, Math.max(1L, syncPollIntervalMs));
                boolean signalled;
                try
                {
                    signalled = latch.await(waitMs, TimeUnit.MILLISECONDS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return observed.get();
                }
                if (signalled)
                {
                    // Reset the latch for the next event before re-evaluating, so an
                    // event arriving during evaluation is not lost.
                    signal.set(new CountDownLatch(1));
                    if (done.test(observed.get()))
                    {
                        return observed.get();
                    }
                }
                else
                {
                    // Timed wake — re-read the cached state as a fallback.
                    ApplicationUpdateState polled = readUpdateState(appManager, application);
                    observed.set(polled);
                    if (done.test(polled))
                    {
                        return polled;
                    }
                }
            }
        }
        finally
        {
            appManager.removeAppllicationListener(listener);
        }
    }

    /**
     * Value-based application match used to filter {@link IApplicationListener}
     * events. EDT may re-materialize {@link IApplication} instances for the same
     * application (implementations such as {@code InfobaseApplication} carry value
     * semantics with their own {@code equals}/{@code hashCode}), so a
     * reference-identity comparison would silently drop ALL push events for a
     * re-materialized instance and degrade the event-driven wait to polling the
     * lagging cached state — the exact lag this event-driven wait exists to avoid. Prefers
     * {@link IApplication#getId()} equality; falls back to {@code equals(...)}.
     * Null-safe and never throws into the listener.
     */
    static boolean isSameApplication(IApplication left, IApplication right)
    {
        if (left == right)
        {
            return true;
        }
        if (left == null || right == null)
        {
            return false;
        }
        try
        {
            String leftId = left.getId();
            String rightId = right.getId();
            if (leftId != null && rightId != null)
            {
                return leftId.equals(rightId);
            }
        }
        catch (RuntimeException e)
        {
            // Defensive: a flaky getId() implementation must not kill the event
            // listener — fall through to equals().
        }
        return left.equals(right);
    }

    /**
     * Reads {@link IApplicationManager#getUpdateState} defensively: any
     * {@link ApplicationException} is logged and mapped to
     * {@link ApplicationUpdateState#UNKNOWN} so the event-await never throws into
     * the launch hot path.
     */
    private static ApplicationUpdateState readUpdateState(IApplicationManager appManager,
            IApplication application)
    {
        try
        {
            ApplicationUpdateState state = appManager.getUpdateState(application);
            return state != null ? state : ApplicationUpdateState.UNKNOWN;
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error reading application update state", e); //$NON-NLS-1$
            return ApplicationUpdateState.UNKNOWN;
        }
    }

    /**
     * Auto-chain executed before {@code workingCopy.launch()} when the caller
     * wants to bypass EDT's interactive "Update database?" dialog:
     * <ol>
     *   <li>Find every live launch matching {@code project + applicationId};</li>
     *   <li>Politely terminate each and wait — aborts on timeout (the IB would
     *       still be locked, defeating the purpose);</li>
     *   <li>FORCE a derived-data recompute of the requested projects so a freshly
     *       edited extension {@code .cfe} is regenerated, then wait for it to settle;</li>
     *   <li>Run {@link #updateApplicationIfNeeded} to settle the IB in
     *       {@code UPDATED} state — the launch delegate then skips its dialog.
     *       EXCEPT for a STANDALONE-SERVER application
     *       ({@link DebugServerTargetSupport#isServerApplicationId}): updating one
     *       out-of-band starts the standalone server in RUN mode and holds a cached
     *       designer-agent connection that wedges the launch delegate's debug
     *       restart — for those the update is deferred to the
     *       delegate's coordinated path, whose dialog the YAXUnit tools'
     *       {@link LaunchUpdateDialogAutoConfirmer} arming auto-presses.</li>
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
     * @param updateScope             which projects to force-recompute+update before
     *            the launch (see {@link #resolveUpdateScope(IProject, String)}); pass
     *            {@code null} or {@code "all"} for the configuration plus its
     *            dependent extensions. Unknown extension names fail fast with
     *            {@code ok=false} BEFORE any live launch is terminated (see
     *            {@link #validateUpdateScope(IProject, String)})
     */
    public static PreLaunchResult prepareForFreshLaunch(ILaunchManager launchManager,
            IProject project, String applicationId, IApplicationManager appManager,
            int terminateTimeoutSeconds, String updateScope)
    {
        if (launchManager == null)
        {
            return new PreLaunchResult(false, 0, "Launch manager is not available"); //$NON-NLS-1$
        }
        if (project == null)
        {
            return new PreLaunchResult(false, 0, "Project is null"); //$NON-NLS-1$
        }

        // A typo'd or unknown extension name in updateScope is a HARD ERROR and
        // must fail BEFORE anything destructive happens (terminating live
        // launches, recompute, DB update): a silently narrowed scope would skip
        // the recompute of the intended extension and produce the exact
        // stale-green run updateScope was built to prevent.
        String scopeError = validateUpdateScope(project, updateScope);
        if (scopeError != null)
        {
            return new PreLaunchResult(false, 0, scopeError);
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
                    ? live.getLaunchConfiguration().getName() : UNKNOWN_LABEL;
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

            // Second pass: stale Attach launches on the same project. Attach
            // configs don't carry a real ATTR_APPLICATION_ID — getApplicationIdFor
            // synthesises "attach:<name>", which never equals a runtime client's
            // UUID, so the per-applicationId loop above never sweeps them. A
            // lingering Attach (e.g. left over from a previous debug session) can
            // mask which 1C client really holds the IB and clutter diagnostics,
            // so disconnect it before the new spawn. Killing an Attach launch is
            // just a debugger disconnect — the 1C server keeps running, no
            // unsaved state is at risk.
            for (ILaunch live : LaunchConfigUtils.getAllLiveLaunches(launchManager,
                project.getName()))
            {
                if (!LaunchConfigUtils.isAttachConfig(live.getLaunchConfiguration()))
                {
                    continue;
                }
                String name = live.getLaunchConfiguration() != null
                    ? live.getLaunchConfiguration().getName() : UNKNOWN_LABEL;
                // Defensive: today both YAXUnit tools register only runtime
                // launches, so an Attach in OWNED is purely hypothetical. If a
                // future tool starts registering Attach launches as owned, we
                // intentionally fast-fail here rather than skipping — at the
                // attach-pass level we don't know which IB the foreign attach
                // targets, and silent skip would risk a spawn that hangs on a
                // locked IB until the MCP timeout. The error message points the
                // caller at terminate_launch, which is the right escape hatch.
                if (OWNED_LAUNCHES.contains(live))
                {
                    return new PreLaunchResult(false, terminated,
                        "An Attach debug session for this project is owned by another " //$NON-NLS-1$
                            + "MCP tool (launch '" + name + "'). Wait for it to finish, " //$NON-NLS-1$ //$NON-NLS-2$
                            + "or call `terminate_launch` to disconnect it first."); //$NON-NLS-1$
                }
                boolean done = terminateAndWait(live, terminateTimeoutSeconds);
                if (!done)
                {
                    // An Attach disconnect that doesn't complete is unusual but
                    // not fatal to the test run: Attach launches don't hold the
                    // IB lock (the foreign 1C client does), so a stuck disconnect
                    // shouldn't block the new spawn. Log and continue.
                    Activator.logError("Could not disconnect stale Attach launch '" //$NON-NLS-1$
                        + name + "' within " + terminateTimeoutSeconds //$NON-NLS-1$
                        + "s, continuing with the auto-chain", null); //$NON-NLS-1$
                    continue;
                }
                // Surface attach disconnects in the log because the per-call
                // PreLaunchResult counter slips them into the same total as
                // runtime terminations — without this line, post-mortems can't
                // tell which launches the second pass swept.
                Activator.logInfo("Pre-launch auto-chain disconnected stale Attach launch '" //$NON-NLS-1$
                    + name + "' on project " + project.getName()); //$NON-NLS-1$
                terminated++;
            }

            // Selectively force a derived-data recompute of projects that have
            // had file changes since the last successful prepare (dirty projects).
            // Clean projects get only a cheap derived-data drain. Without the
            // forced recompute for dirty projects, an extension (.cfe) edited just
            // before the launch is never regenerated (the derived-data wait no-ops
            // when nothing is scheduled) and appManager.update() consumes the
            // stale export artifact — so the first test run executes the old
            // extension, missing freshly added tests. updateScope narrows the
            // project scope FIRST; the dirty filter is applied within that scope.
            //
            // The snapshot is taken INSIDE recomputeAndSettleIfDirty, BEFORE the
            // recompute begins. We receive it back so we can pass it to
            // markPrepared on the success paths — a change arriving DURING the
            // recompute bumps the generation counter; the conditional remove in
            // markPrepared then fails and the project stays dirty (stale-.cfe fix).
            List<IProject> scopeProjects = resolveUpdateScope(project, updateScope);
            Map<String, Long> dirtySnapshot = recomputeAndSettleIfDirty(scopeProjects);

            // A STANDALONE-SERVER application (literal
            // "ServerApplication." id prefix) must NOT be DB-updated out-of-band
            // here. IApplicationManager.update on a ServerApplication routes through
            // ServerApplicationBehaviourDelegate.update →
            // JobBasedServerModulePublisher.publish, which STARTS the standalone
            // server in RUN mode and caches a live designer-agent connection in the
            // global DesignerSessionPool; the launch delegate then needs the server
            // in DEBUG mode, and the restart's teardown of that cached connection
            // wedges the launch. Defer to the delegate's coordinated path instead —
            // EDT's native ApplicationUiSupport.ensureUpdated prepares the server
            // directly in the target mode FIRST and only then updates (no restart).
            // Both YAXUnit tools arm LaunchUpdateDialogAutoConfirmer around
            // workingCopy.launch, so the delegate's "Application update" dialog
            // (shown only when the IB is stale) is auto-pressed; when the IB is in
            // sync there is no dialog at all. The terminate-stale passes and the
            // recompute/settle above still ran — they do not open IB connections.
            if (DebugServerTargetSupport.isServerApplicationId(applicationId))
            {
                Activator.logInfo("Pre-launch auto-chain: server application: deferring DB update " //$NON-NLS-1$
                    + "to the launch delegate's coordinated path (auto-confirmed): applicationId=" //$NON-NLS-1$
                    + applicationId);
                // Success path: mark all scope projects as prepared with the
                // generation-keyed snapshot so the next call skips the recompute
                // when nothing changed (and keeps dirty flag on a change-during-recompute).
                PreLaunchChangeTracker.markPrepared(scopeProjects, dirtySnapshot);
                return new PreLaunchResult(true, terminated, null);
            }

            // settleAfterPossibleRecompute=true: we JUST forced a recompute, so a cached
            // UPDATED may lag the freshly regenerated .cfe — wait out the settle window
            // before trusting "no update needed". The settle is kept
            // UNCONDITIONALLY: the lagging UPDATE_STATE_CHANGED push this window
            // exists for arrives AFTER the recompute drain (it is emitted by the
            // applications-layer infobase-sync checker, which the drained build /
            // derived-data job families do NOT cover), so no during-drain probe can
            // prove it will not come. The plain debug_launch path passes false
            // (immediate return on UPDATED) to avoid that ~5s cost.
            Optional<String> updateErr = updateApplicationIfNeeded(project, applicationId,
                appManager, true);
            if (updateErr.isPresent())
            {
                // Error path: do NOT mark prepared — the next call must recompute.
                return new PreLaunchResult(false, terminated, updateErr.get());
            }
            // Success path: mark prepared with the generation-keyed snapshot.
            PreLaunchChangeTracker.markPrepared(scopeProjects, dirtySnapshot);
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

    /**
     * Returns the workbench {@link Display} WITHOUT creating one, or {@code null}
     * in a headless runtime (no workbench).
     *
     * <p>{@code Display.getDefault()} is the wrong probe for "is there a UI?":
     * per the SWT contract it CREATES a display owned by the calling thread when
     * none exists, so it never returns {@code null}. On a headless MCP worker
     * that stray display is never pumped — an {@code asyncExec} queued on it
     * silently never runs (see {@code DebugLaunchTool.performLaunch}),
     * and a later {@code syncExec} against it from a different thread blocks
     * forever (see {@link LaunchUpdateDialogAutoConfirmer}). The
     * workbench probe hands out only a display that a real UI thread is already
     * dispatching.
     *
     * <p>Shared by {@code DebugLaunchTool} and
     * {@link LaunchUpdateDialogAutoConfirmer} so the probe idiom lives in
     * exactly one place.
     */
    public static Display workbenchDisplayOrNull()
    {
        if (!PlatformUI.isWorkbenchRunning())
        {
            return null;
        }
        try
        {
            return PlatformUI.getWorkbench().getDisplay();
        }
        catch (IllegalStateException e)
        {
            // Workbench shut down between the check and the call (shutdown race).
            return null;
        }
    }

    /**
     * Returns the active SWT {@link Shell} (or the first available one) to seed
     * an {@link ExecutionContext} so EDT can parent its dialogs correctly.
     * Returns {@code null} in headless environments where no shell exists.
     *
     * <p>Probes via {@link #workbenchDisplayOrNull()} — NOT
     * {@code Display.getDefault()}, which would CREATE a display owned by the
     * calling thread in a headless runtime: the stray
     * display is never pumped, so the {@code syncExec} below would block forever
     * when issued from another thread. With the workbench probe, headless
     * callers simply get {@code null} (no dialogs can appear there anyway).
     *
     * <p>Shared by every tool that builds an {@code ExecutionContext} before
     * calling {@link IApplicationManager#update} (update_database, debug_launch,
     * the YAXUnit auto-chain) so the SWT-grab logic lives in exactly one place.
     */
    public static Shell grabActiveShell()
    {
        Display display = workbenchDisplayOrNull();
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

    // ==================================================================================
    // Existing-CLIENT-session layer. Moved here from
    // DebugLaunchTool so EVERY tool that spawns a 1C client (debug_launch, the YAXUnit
    // debug path) shares ONE detect+terminate policy: a session counts as an existing
    // CLIENT session only with ≥1 live CLIENT-typed thread (the type-aware
    // discriminator, DebugServerTargetSupport.findFirstLiveClientThread), so a
    // debug-mode standalone server — whose live thread is typed SERVER — never
    // short-circuits a client launch and is NEVER terminated.
    // ==================================================================================

    /** Max wait (ms) for a non-interactive terminate to take effect — mirrors the
     * delegate's {@code terminateOldDebugSessions} ~3s grace. */
    private static final long RESTART_TERMINATE_TIMEOUT_MS = 3000L;

    /**
     * The single existing-session decision: one
     * {@code (project, applicationId)} resolves to AT MOST one
     * {@link ExistingClientSession} via {@link #resolveExistingClientSession}, and
     * every call site funnels that result through one policy point (the
     * {@code restartIfRunning} handler in {@code DebugLaunchTool}, or
     * {@link #ensureNoExistingClientSession} for the always-fresh YAXUnit debug
     * path), so the decision is honored identically in EVERY path — by-name and
     * by-project+application, and BOTH the {@link DebugSessionRegistry}
     * ({@code ILaunchManager}) guards and the {@link DebugServerTargetSupport}
     * (target-manager) detect.
     *
     * <p>A session is a real CLIENT session worth short-circuiting/terminating only
     * when it is either:
     * <ul>
     *   <li>a DEBUG launch/target with ≥1 non-terminated CLIENT-typed thread — a
     *       thin-client debug session
     *       ({@link DebugServerTargetSupport#findFirstLiveClientThread} applies the
     *       canonical {@code DebugTargetTypeUtil} classifier to
     *       {@code IRuntimeDebugTargetThread.getType()}), or</li>
     *   <li>a RUN-mode launch — a genuine running 1C client that carries NO debug
     *       target at all (the already-running guard); the thread-type gate
     *       does not apply because there is no debug target to inspect.</li>
     * </ul>
     * A DEBUG launch/target whose live threads are all server-typed (a debug-mode
     * standalone server carries a live thread typed {@code SERVER}) — or that has no
     * live thread at all (profiling/idle server target) — is NEVER a client session:
     * it must not short-circuit the client (the client proceeds and attaches) and
     * must never be terminated.
     */
    public static final class ExistingClientSession
    {
        /**
         * The owning Eclipse launch. Non-{@code null} for the {@code ILaunchManager}
         * paths (it IS the terminate handle when {@link #liveTarget} is {@code null},
         * the RUN-mode case); may be {@code null} for the target-manager path, where
         * {@link #liveTarget} is always set and is the terminate handle instead.
         */
        public final ILaunch launch;
        /**
         * The matched live DEBUG target with ≥1 live CLIENT-typed thread, or
         * {@code null} when the session is a RUN-mode launch (no debug target).
         * Drives the terminate path: a target is terminated via the target; a
         * RUN-mode launch via the launch.
         */
        public final IDebugTarget liveTarget;
        /** The session's launch mode (e.g. {@code debug}, {@code run}). */
        public final String mode;

        public ExistingClientSession(ILaunch launch, IDebugTarget liveTarget, String mode)
        {
            this.launch = launch;
            this.liveTarget = liveTarget;
            this.mode = mode;
        }
    }

    /**
     * Resolves the ONE live CLIENT session the {@code ILaunchManager} knows for the
     * given applicationId, with the CLIENT-typed-thread discriminator applied so a
     * standalone-SERVER / profiling session never matches — including
     * a debug-mode standalone server whose live thread is typed {@code SERVER}.
     *
     * <p>Order, mirroring the legacy guards it unifies:
     * <ol>
     *   <li>{@link DebugSessionRegistry#findActiveTarget} — a non-terminated DEBUG
     *       target for this app id. It matches ONLY when that target also carries a
     *       live CLIENT-typed thread
     *       ({@link DebugServerTargetSupport#findFirstLiveClientThread}); a
     *       server/profiling target is rejected so the client proceeds.</li>
     *   <li>{@link DebugSessionRegistry#findActiveLaunch} — any non-terminated launch
     *       for this app id, catching a RUN-mode (or otherwise debug-target-less)
     *       client the target scan misses. A RUN-mode launch carries no debug target,
     *       so it is a genuine running client (returned as a session with a
     *       {@code null} target). A DEBUG launch is returned ONLY when one of its
     *       debug targets has a live CLIENT-typed thread — otherwise it is the same
     *       server session and is rejected.</li>
     * </ol>
     *
     * @param applicationId the application id (real or synthetic); {@code null}/empty
     *     never matches
     * @return the live client session, or {@code null} when none exists (so the
     *     caller proceeds to launch, including when only a server session shares
     *     this app id)
     */
    public static ExistingClientSession resolveExistingClientSession(String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty())
        {
            return null;
        }
        // The two ILaunchManager views the legacy guards used, now run through one
        // type-aware decision (decideExistingClientSession). The lookups are the only
        // workbench-bound part; the decision is pure and unit-tested.
        return decideExistingClientSession(
            DebugSessionRegistry.findActiveTarget(applicationId),
            DebugSessionRegistry.findActiveLaunch(applicationId));
    }

    /**
     * The PURE existing-client decision over the two {@code ILaunchManager} views
     * — split out from {@link #resolveExistingClientSession}
     * so the CLIENT-typed-thread discrimination is unit-testable without a live
     * workbench.
     *
     * <ol>
     *   <li>{@code activeTarget} (from {@link DebugSessionRegistry#findActiveTarget}) —
     *       a live DEBUG target. Matches a client ONLY when it carries a live
     *       CLIENT-typed thread
     *       ({@link DebugServerTargetSupport#findFirstLiveClientThread}); a target
     *       whose live threads are all server-typed (debug-mode standalone server) or
     *       absent (profiling/idle server) is rejected here.</li>
     *   <li>{@code activeLaunch} (from {@link DebugSessionRegistry#findActiveLaunch}) —
     *       any non-terminated launch the target scan missed. A live-CLIENT-thread
     *       debug target it owns ⇒ client; ZERO debug targets ⇒ genuine RUN-mode
     *       client (the already-running guard); debug target(s) but none with a live
     *       CLIENT-typed thread ⇒ server session ⇒ NOT a client (returns
     *       {@code null}, so the client proceeds and attaches).</li>
     * </ol>
     *
     * @param activeTarget the live DEBUG target for the app id, or {@code null}
     * @param activeLaunch a non-terminated launch for the app id, or {@code null}
     * @return the live client session, or {@code null} when none is a real client
     */
    public static ExistingClientSession decideExistingClientSession(IDebugTarget activeTarget,
        ILaunch activeLaunch)
    {
        // 1) A live DEBUG target with a live CLIENT-typed thread = a real client
        //    debug session. A live SERVER-typed thread (debug-mode standalone server)
        //    deliberately does NOT match.
        if (activeTarget != null
            && DebugServerTargetSupport.findFirstLiveClientThread(activeTarget) != null)
        {
            ILaunch launch = activeTarget.getLaunch();
            String mode = launch != null ? launch.getLaunchMode() : ILaunchManager.DEBUG_MODE;
            return new ExistingClientSession(launch, activeTarget, mode);
        }

        // 2) A non-terminated launch the target scan missed (e.g. RUN mode, no debug
        //    target). A RUN-mode launch is a genuine running client and short-circuits
        //    as before; a DEBUG launch with debug target(s) but no live CLIENT-typed
        //    thread is the same standalone-server session and must NOT short-circuit
        //    the client.
        if (activeLaunch == null)
        {
            return null;
        }
        IDebugTarget liveTarget = firstLiveClientThreadTarget(activeLaunch);
        if (liveTarget != null)
        {
            // A live CLIENT debug target this launch owns — a client debug session.
            return new ExistingClientSession(activeLaunch, liveTarget, activeLaunch.getLaunchMode());
        }
        if (activeLaunch.getDebugTargets().length == 0)
        {
            // No debug target at all (RUN mode, or a launch that never produced one):
            // a genuine running client — the already-running guard. There is
            // no server debug target to confuse it with.
            return new ExistingClientSession(activeLaunch, null, activeLaunch.getLaunchMode());
        }
        // The launch HAS debug target(s) but none carries a live CLIENT-typed thread —
        // a standalone-server / profiling session. Do NOT short-circuit; the client
        // proceeds and attaches.
        return null;
    }

    /**
     * @return the first debug target of {@code launch} that carries a non-terminated
     *     CLIENT-typed thread
     *     ({@link DebugServerTargetSupport#findFirstLiveClientThread}), or
     *     {@code null} when the launch has no such live-client target (e.g. its only
     *     target is a debug-mode standalone server, whose live thread is typed
     *     SERVER). Best-effort.
     */
    static IDebugTarget firstLiveClientThreadTarget(ILaunch launch)
    {
        if (launch == null)
        {
            return null;
        }
        for (IDebugTarget target : launch.getDebugTargets())
        {
            if (target != null && !target.isTerminated()
                && DebugServerTargetSupport.findFirstLiveClientThread(target) != null)
            {
                return target;
            }
        }
        return null;
    }

    /**
     * Terminates the given live CLIENT debug target non-interactively and waits up
     * to {@link #RESTART_TERMINATE_TIMEOUT_MS} for it to die, then clears the
     * registry for {@code appId} — mirroring the delegate's
     * {@code terminateOldDebugSessions} (terminate → short wait → proceed) and the
     * {@code terminate_launch} cleanup (forget the application so a half-dead client
     * is not raced). Best-effort: a termination failure is logged, not thrown — the
     * caller proceeds to launch regardless, and if a stale client lingers the launch
     * delegate's modal is the armed auto-confirmer's job. Callers must only ever pass
     * a target the type-aware discriminator matched as a CLIENT — never a server
     * target.
     *
     * @return {@code true} when the target was observed terminated within
     *     {@link #RESTART_TERMINATE_TIMEOUT_MS}; {@code false} on a {@code null} target or
     *     when the wait elapsed without confirming death (a warning is logged) — callers
     *     must NOT report "terminated" on a {@code false} return.
     */
    public static boolean terminateExistingSessionAndWait(IDebugTarget target, String appId)
    {
        return terminateExistingSessionAndWait(target, appId, RESTART_TERMINATE_TIMEOUT_MS);
    }

    /**
     * Timeout-parameterized seam of {@link #terminateExistingSessionAndWait(IDebugTarget, String)}
     * — package-private so the headless test can exercise the timeout-elapsed path without
     * waiting out the full production window. Production callers always go through the public
     * overload ({@link #RESTART_TERMINATE_TIMEOUT_MS}).
     */
    static boolean terminateExistingSessionAndWait(IDebugTarget target, String appId, long timeoutMs)
    {
        if (target == null)
        {
            if (appId != null && !appId.isEmpty())
            {
                DebugSessionRegistry.get().forgetApplication(appId);
            }
            return false;
        }
        try
        {
            if (target.canTerminate())
            {
                target.terminate();
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error terminating existing debug session before restart", e); //$NON-NLS-1$
        }
        boolean terminated = false;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                if (target.isTerminated())
                {
                    terminated = true;
                    break;
                }
            }
            catch (Exception e)
            {
                break;
            }
            try
            {
                Thread.sleep(LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!terminated)
        {
            Activator.logWarning("Existing client debug session did not confirm termination within " //$NON-NLS-1$
                + timeoutMs + "ms (applicationId=" + appId //$NON-NLS-1$
                + ") — the infobase may still be held"); //$NON-NLS-1$
        }
        if (appId != null && !appId.isEmpty())
        {
            DebugSessionRegistry.get().forgetApplication(appId);
        }
        return terminated;
    }

    /**
     * Terminates the given running launch non-interactively (the RUN-mode / no-debug-
     * target client case) and waits up to {@link #RESTART_TERMINATE_TIMEOUT_MS} for it
     * to die, then clears the registry for {@code appId} — the launch analogue of
     * {@link #terminateExistingSessionAndWait}. Best-effort: a failure is logged, not
     * thrown; the caller proceeds to launch regardless.
     *
     * @return {@code true} when the launch was observed terminated within
     *     {@link #RESTART_TERMINATE_TIMEOUT_MS}; {@code false} on a {@code null} launch or
     *     when the wait elapsed without confirming death (a warning is logged) — callers
     *     must NOT report "terminated" on a {@code false} return.
     */
    public static boolean terminateExistingLaunchAndWait(ILaunch launch, String appId)
    {
        if (launch == null)
        {
            if (appId != null && !appId.isEmpty())
            {
                DebugSessionRegistry.get().forgetApplication(appId);
            }
            return false;
        }
        try
        {
            if (launch.canTerminate())
            {
                launch.terminate();
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error terminating existing launch before restart", e); //$NON-NLS-1$
        }
        boolean terminated = false;
        long deadline = System.currentTimeMillis() + RESTART_TERMINATE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                if (launch.isTerminated())
                {
                    terminated = true;
                    break;
                }
            }
            catch (Exception e)
            {
                break;
            }
            try
            {
                Thread.sleep(LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!terminated)
        {
            Activator.logWarning("Existing client launch did not confirm termination within " //$NON-NLS-1$
                + RESTART_TERMINATE_TIMEOUT_MS + "ms (applicationId=" + appId //$NON-NLS-1$
                + ") — the infobase may still be held"); //$NON-NLS-1$
        }
        if (appId != null && !appId.isEmpty())
        {
            DebugSessionRegistry.get().forgetApplication(appId);
        }
        return terminated;
    }

    /**
     * Resolves the application id EXACTLY the way EDT's launch delegate's
     * {@code findConfiguredApplicationIdentifier} does: the config's persisted
     * {@code ATTR_APPLICATION_ID} when present, else the project's
     * {@code IApplicationManager.getDefaultApplication(project)} id. NOT the
     * synthetic {@code launch:<configName>} our {@code getApplicationIdFor} mints
     * when no real id is persisted — that synthetic id is what once made the duplicate
     * guard miss the delegate's session (and what let the YAXUnit
     * debug path's {@code prepareForFreshLaunch} sweep miss a UI-started session:
     * it never equals the delegate's real/default app id). Returns
     * {@code null} if the id cannot be resolved (no persisted id and no default
     * application).
     */
    public static String resolveDelegateApplicationId(ILaunchConfiguration config, String projectName)
    {
        String realId = LaunchConfigUtils.readAttribute(config,
            LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
        if (realId != null && !realId.isEmpty())
        {
            return realId;
        }
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.isOpen())
        {
            return null;
        }
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return null;
        }
        // resolveDefaultApplicationId returns the original (empty) value when there is
        // no default — normalize that to null so findRuntimeClientDebugTarget's
        // empty-id guard short-circuits instead of matching on "".
        String resolved = resolveDefaultApplicationId(ctx.project(), null, appManager);
        return resolved != null && !resolved.isEmpty() ? resolved : null;
    }

    /**
     * Detects and non-interactively terminates ANY existing live CLIENT session of
     * {@code (project, delegateAppId)} — the fresh-run guarantee of the YAXUnit
     * debug path, equivalent to {@code debug_launch}'s
     * {@code restartIfRunning=true} semantics. Detection runs over BOTH sources,
     * each with the type-aware CLIENT discriminator:
     * <ol>
     *   <li>the {@code ILaunchManager} views ({@link #resolveExistingClientSession} —
     *       the {@link DebugSessionRegistry} guards), and</li>
     *   <li>EDT's debug target manager
     *       ({@link DebugServerTargetSupport#findRuntimeClientDebugTarget}, keyed the
     *       launch delegate's way on {@code (project, delegateAppId)}) — the set the
     *       delegate's code-1003 "Debug session already exists" check scans, which
     *       includes UI-started "Debug As" sessions invisible to the
     *       {@code ILaunchManager} guards and to {@code prepareForFreshLaunch}'s
     *       {@code getApplicationIdFor}-keyed sweep.</li>
     * </ol>
     * Each found CLIENT session is terminated non-interactively
     * ({@link #terminateExistingSessionAndWait} /
     * {@link #terminateExistingLaunchAndWait}: terminate + {@code forgetApplication}
     * + ≤3s wait). A debug-mode standalone server session — live thread typed
     * {@code SERVER} — is NEVER matched and NEVER terminated.
     * A launch OWNED by another MCP tool ({@link #registerOwnedLaunch} — e.g. a
     * concurrent {@code run_yaxunit_tests} RUN launch of the same application) is
     * never terminated by the {@code ILaunchManager}-sourced sweep either: it is
     * skipped and logged, because it is managed by the tool that spawned it (see
     * {@link #sweepLaunchManagerSession}). Best-effort and fully guarded: never
     * throws; a {@code null} project / {@code null}-or-empty app id is a no-op.
     *
     * <p>{@code delegateAppId} MUST be the delegate-resolved application id
     * ({@code ATTR_APPLICATION_ID} else the project default — see
     * {@link #resolveDelegateApplicationId}); a synthetic {@code launch:<name>} id
     * never matches the delegate's session.
     *
     * @param project the launch's target project (may be {@code null} — skips the
     *     target-manager source)
     * @param delegateAppId the delegate-resolved application id (may be
     *     {@code null}/empty — no-op)
     * @return {@code true} when at least one existing client session was found and
     *     terminated
     */
    public static boolean ensureNoExistingClientSession(IProject project, String delegateAppId)
    {
        if (delegateAppId == null || delegateAppId.isEmpty())
        {
            return false;
        }
        // Source 1: the ILaunchManager views (registry guards), type-discriminated.
        // An MCP-OWNED launch (a concurrent run_yaxunit_tests / debug_yaxunit_tests
        // launch of the same application) is exempt — see sweepLaunchManagerSession.
        boolean terminatedAny =
            sweepLaunchManagerSession(resolveExistingClientSession(delegateAppId), delegateAppId);

        // Source 2: EDT's debug target manager — the delegate's own 1003 criterion
        // (catches UI-started "Debug As" sessions the ILaunchManager never sees).
        if (project != null)
        {
            terminatedAny |= sweepTargetManagerSession(
                DebugServerTargetSupport.findRuntimeClientDebugTarget(project.getName(), delegateAppId),
                delegateAppId);
        }
        return terminatedAny;
    }

    /**
     * Handles the {@code ILaunchManager}-sourced half of
     * {@link #ensureNoExistingClientSession}: terminates the resolved client session
     * non-interactively — EXCEPT a launch registered as MCP-owned
     * ({@link #registerOwnedLaunch}). The default {@code updateBeforeLaunch=true}
     * path never reaches an owned launch (it hard-fails on owned launches in
     * {@link #prepareForFreshLaunch} first), but with {@code updateBeforeLaunch=false}
     * this sweep is the only line of defence: without the exemption a YAXUnit debug
     * call would silently terminate a concurrent MCP-owned RUN test launch of the
     * same application (the RUN-mode branch below,
     * {@link #terminateExistingLaunchAndWait}). Owned launches are managed by the
     * tool that spawned them — skip and log instead. The target-manager source in
     * {@link #ensureNoExistingClientSession} is unaffected: the sessions it finds
     * have no owning {@link ILaunch} of ours.
     *
     * <p>Package-visible so the skip-vs-terminate decision is unit-testable without
     * a live {@code ILaunchManager}.
     *
     * @param session the session resolved by {@link #resolveExistingClientSession}
     *     (may be {@code null} — no-op)
     * @param delegateAppId the delegate-resolved application id (used for registry
     *     cleanup and logging)
     * @return {@code true} when the session was terminated; {@code false} when there
     *     was none or it was skipped as MCP-owned
     */
    static boolean sweepLaunchManagerSession(ExistingClientSession session, String delegateAppId)
    {
        if (session == null)
        {
            return false;
        }
        if (isOwnedLaunch(session.launch))
        {
            String name = session.launch.getLaunchConfiguration() != null
                ? session.launch.getLaunchConfiguration().getName() : UNKNOWN_LABEL;
            Activator.logInfo("Fresh-launch sweep: skipping MCP-owned launch '" + name //$NON-NLS-1$
                + "'; it is managed by its own tool: applicationId=" + delegateAppId); //$NON-NLS-1$
            return false;
        }
        if (session.liveTarget != null)
        {
            Activator.logInfo("Fresh-launch guard: terminating existing client debug target: " //$NON-NLS-1$
                + "applicationId=" + delegateAppId); //$NON-NLS-1$
            return terminateExistingSessionAndWait(session.liveTarget, delegateAppId);
        }
        Activator.logInfo("Fresh-launch guard: terminating existing client launch (mode=" //$NON-NLS-1$
            + session.mode + "): applicationId=" + delegateAppId); //$NON-NLS-1$ //$NON-NLS-2$
        return terminateExistingLaunchAndWait(session.launch, delegateAppId);
    }

    /**
     * Handles the debug-target-manager half of {@link #ensureNoExistingClientSession}: the
     * {@code IRuntimeDebugClientTargetManager} can surface a UI-started "Debug As" client the
     * {@link ILaunchManager} never sees. Mirrors {@link #sweepLaunchManagerSession} — a target
     * whose owning {@link ILaunch} is MCP-owned ({@link #registerOwnedLaunch}) is SKIPPED
     * (managed by its own tool), closing the gap where a concurrent MCP DEBUG session on the
     * same infobase was silently terminated; any other (UI-started / foreign) client is
     * terminated. Returns whether termination was CONFIRMED, not merely attempted.
     *
     * <p>Package-visible so the owned-vs-foreign decision is unit-testable without a live
     * target manager.
     *
     * @param existing the target resolved by {@code findRuntimeClientDebugTarget} (may be
     *     {@code null} — no-op)
     * @param delegateAppId the delegate-resolved application id (registry cleanup + logging)
     * @return {@code true} when a foreign client target was confirmed terminated; {@code false}
     *     when none, skipped as MCP-owned, or termination did not confirm
     */
    static boolean sweepTargetManagerSession(IDebugTarget existing, String delegateAppId)
    {
        if (existing == null)
        {
            return false;
        }
        if (isOwnedLaunch(existing.getLaunch()))
        {
            Activator.logInfo("Fresh-launch guard: skipping MCP-owned debug session from the " //$NON-NLS-1$
                + "debug target manager; it is managed by its own tool: applicationId=" //$NON-NLS-1$
                + delegateAppId);
            return false;
        }
        Activator.logInfo("Fresh-launch guard: terminating existing client session from " //$NON-NLS-1$
            + "the debug target manager (e.g. UI-started 'Debug As'): applicationId=" //$NON-NLS-1$
            + delegateAppId);
        return terminateExistingSessionAndWait(existing, delegateAppId);
    }
}

/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchListener;
import org.eclipse.debug.core.ILaunchManager;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.JUnitMarkdownFormatter;
import com.ditrix.edt.mcp.server.utils.JUnitTestResults;
import com.ditrix.edt.mcp.server.utils.JUnitXmlParser;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PrepInFlight;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PreLaunchResult;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Tool to run YAXUnit tests for a 1C:Enterprise project.
 *
 * Launches the application with the {@code RunUnitTests} startup parameter,
 * polls until the launch terminates or the polling window expires, then parses
 * the JUnit XML report and returns a Markdown summary. The full Markdown report
 * is also written to {@code report.md} next to {@code junit.xml} so the user
 * can read it directly from disk.
 */
public class RunYaxunitTestsTool implements IMcpTool
{
    public static final String NAME = "run_yaxunit_tests"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT = 60;
    private static final int POLL_INTERVAL_MS = 1000;

    /** Active launches keyed by stable run id (configName:filterHash). */
    private static final Map<String, ILaunch> ACTIVE_LAUNCHES = new ConcurrentHashMap<>();

    /**
     * Run keys for which a {@code Pending} was reported but whose result has NOT yet been delivered.
     * A re-call consumes the entry EXACTLY ONCE to fetch the completed report; any later call with
     * the same key then starts a fresh run. This is what lets a genuine re-run (e.g. after fixing the
     * code under test) always re-execute instead of returning a stale, time-cached report.
     *
     * <p>Identical arguments are inherently ambiguous (fetch-my-{@code Pending} vs. start-fresh): if a
     * caller receives {@code Pending} and never fetches, a later genuine re-run consumes the lingering
     * entry and delivers the prior report ONCE before the following call re-executes.
     */
    private static final Set<String> PENDING_FETCH = ConcurrentHashMap.newKeySet();

    /** Lazily registered listener that evicts terminated launches from {@link #ACTIVE_LAUNCHES}. */
    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean(false);

    /** Per-launch counter for the unique debug-mode report directory name. */
    private static final AtomicLong DEBUG_LAUNCH_COUNTER = new AtomicLong(0);

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Run YAXUnit tests for a 1C:Enterprise project and return a JUnit Markdown report. " //$NON-NLS-1$
               + "Polls for up to `timeout` seconds, then returns the report or **Pending** " //$NON-NLS-1$
               + "(call again with identical arguments to keep waiting; the launch is not terminated). " //$NON-NLS-1$
               + "Pass `debug=true` to instead launch in DEBUG mode (breakpoints fire) and return at once " //$NON-NLS-1$
               + "so you can call wait_for_break. " //$NON-NLS-1$
               + "The pre-launch auto-chain (updateBeforeLaunch=true, default) recomputes only projects " //$NON-NLS-1$
               + "that changed since the last prepared run; the first call after EDT starts always " //$NON-NLS-1$
               + "recomputes fully. If recompute+update exceeds 25s the tool returns **Pending** " //$NON-NLS-1$
               + "immediately — call again with the same arguments; preparation continues in the background. " //$NON-NLS-1$
               + "Requires an existing runtime-client launch configuration " //$NON-NLS-1$
               + "and the YAXUnit extension installed in the infobase. " //$NON-NLS-1$
               + "Full parameters and examples: call get_tool_guide('run_yaxunit_tests')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact runtime-client launch config name (preferred; from list_configurations).") //$NON-NLS-1$
            .stringProperty("projectName", "EDT project name (required if launchConfigurationName is omitted).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application ID from get_applications (required if launchConfigurationName is omitted).") //$NON-NLS-1$
            .stringArrayProperty("extensions", "Extension names to filter tests (array; a comma-separated string is also accepted).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("modules", "Module names to filter tests (array; a comma-separated string is also accepted).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("tests", "Test names in Module.Method format (array; a comma-separated string is also accepted).") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("timeout", "Polling window in seconds (default: 60); on expiry returns Pending.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Auto-chain (default: true): force-recompute the project + its extensions, terminate a " //$NON-NLS-1$
                    + "live client and run a silent DB update first, so a freshly edited extension runs " //$NON-NLS-1$
                    + "fresh (not stale). false: legacy delegate behaviour — no client sweep, no " //$NON-NLS-1$
                    + "auto-confirmed update dialog; platform dialogs may appear and block. Results are " //$NON-NLS-1$
                    + "never served from a cache — a completed run is re-executed on the next identical " //$NON-NLS-1$
                    + "call regardless of this flag.") //$NON-NLS-1$
            .stringProperty("updateScope", UPDATE_SCOPE_DESCRIPTION) //$NON-NLS-1$
            .booleanProperty("debug", //$NON-NLS-1$
                "Default false: poll and return the report. true: launch in DEBUG mode so breakpoints " //$NON-NLS-1$
                    + "fire, return immediately and call wait_for_break next (ignores timeout).") //$NON-NLS-1$
            .build();
    }

    /**
     * Shared schema doc for the {@code updateScope} parameter (also forwarded by
     * the {@code debug_yaxunit_tests} alias).
     */
    static final String UPDATE_SCOPE_DESCRIPTION =
        "Which projects to rebuild+update before the run: 'all' (configuration + dependent " //$NON-NLS-1$
            + "extensions, default), 'configuration', or 'extension:<ProjectName>' " //$NON-NLS-1$
            + "(comma-separate several). Forces a derived-data recompute so a freshly edited " //$NON-NLS-1$
            + "extension's .cfe is regenerated and loaded into the infobase before the run. " //$NON-NLS-1$
            + "Unknown extension names fail the call (the error lists the available names). " //$NON-NLS-1$
            + "Only applies when updateBeforeLaunch=true."; //$NON-NLS-1$

    /**
     * Pure gating decision (test seam) for the DEBUG path's fresh-run sweep: the
     * existing-client-session sweep
     * ({@code LaunchLifecycleUtils.ensureNoExistingClientSession}) runs ONLY as
     * part of the documented {@code updateBeforeLaunch=true} auto-chain (the
     * "fresh run" guarantee). {@code updateBeforeLaunch=false} keeps the legacy
     * delegate behaviour: NO sweep — an existing session is left alone and the
     * delegate's own code-1003 handling decides (the always-armed race-net
     * matcher presses the non-destructive keep-button if that modal appears).
     */
    static boolean shouldSweepExistingClientSession(boolean updateBeforeLaunch)
    {
        return updateBeforeLaunch;
    }

    /**
     * Arm flags for {@code LaunchUpdateDialogAutoConfirmer.arm} around the
     * RUN-mode spawn, as {@code [updateDialog, sessionDialog]} (test seam): the
     * "Application update" matcher follows {@code updateBeforeLaunch} —
     * auto-pressing that modal after the caller opted out of the DB update would
     * silently perform the very update they disabled (the same gating
     * {@code DebugLaunchTool.performLaunch} applies) — and the RUN path never
     * arms the code-1003 session matcher (that modal is raised only by the
     * debug-session check).
     */
    static boolean[] runPathArmFlags(boolean updateBeforeLaunch)
    {
        return new boolean[] {updateBeforeLaunch, false};
    }

    /**
     * Arm flags around the DEBUG-mode spawn, as
     * {@code [updateDialog, sessionDialog]} (test seam): the update matcher
     * follows {@code updateBeforeLaunch} (same opt-out contract as the RUN
     * path); the code-1003 session matcher is ALWAYS armed as the race net
     * behind the fresh-run sweep — its auto-press is the non-destructive
     * "Keep existing and start new", so it never undoes the opt-out.
     */
    static boolean[] debugPathArmFlags(boolean updateBeforeLaunch)
    {
        return new boolean[] {updateBeforeLaunch, true};
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        // extensions/modules/tests are declared as arrays but threaded internally as
        // comma-strings (run key, retry, buildParamsJson). extractArrayArgument accepts
        // BOTH a JSON array and a comma-separated string; re-join to the canonical comma
        // form so the downstream String plumbing is unchanged.
        String extensions = joinList(JsonUtils.extractArrayArgument(params, "extensions")); //$NON-NLS-1$
        String modules = joinList(JsonUtils.extractArrayArgument(params, "modules")); //$NON-NLS-1$
        String tests = joinList(JsonUtils.extractArrayArgument(params, "tests")); //$NON-NLS-1$
        int timeout = JsonUtils.extractIntArgument(params, "timeout", DEFAULT_TIMEOUT); //$NON-NLS-1$
        if (timeout < 1)
        {
            timeout = 1;
        }
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, //$NON-NLS-1$
            "updateBeforeLaunch", true); //$NON-NLS-1$
        String updateScope = JsonUtils.extractStringArgument(params, "updateScope"); //$NON-NLS-1$
        boolean debug = JsonUtils.extractBooleanArgument(params, "debug", false); //$NON-NLS-1$ //$NON-NLS-2$

        boolean hasName = configName != null && !configName.isEmpty();
        if (!hasName)
        {
            if (projectName == null || projectName.isEmpty())
            {
                return ToolResult.error("projectName is required (or pass launchConfigurationName)").toJson(); //$NON-NLS-1$
            }
            if (applicationId == null || applicationId.isEmpty())
            {
                return ToolResult.error("applicationId is required (or pass launchConfigurationName). " //$NON-NLS-1$
                    + "Use get_applications or list_configurations.").toJson(); //$NON-NLS-1$
            }
        }

        ensureLaunchListenerRegistered();
        purgeTerminatedLaunches();

        return runTests(configName, projectName, applicationId, extensions, modules, tests,
            timeout, updateBeforeLaunch, updateScope, debug);
    }

    /**
     * Main test execution flow.
     *
     * Non-blocking with state tracking. Behaviour:
     * <ol>
     *   <li>Compute stable runKey from the launch config name + filter.</li>
     *   <li>If a launch is already running for this key — poll up to {@code timeout}s, return result or "Pending".</li>
     *   <li>If no active launch but this key has an UNDELIVERED Pending result — deliver it ONCE, then
     *       forget the key so the next call re-runs.</li>
     *   <li>Otherwise — start a new launch, poll, return result or "Pending".</li>
     * </ol>
     *
     * There is deliberately NO time-based result cache: a re-run with identical arguments after a
     * completed run always re-executes the tests. A completed report is reused only to
     * satisfy a re-call fetching a previously reported {@code Pending} run, and only once.
     *
     * {@code debug=true} skips this polling lifecycle entirely and returns a launch handle at
     * once (see {@link #launchDebugMode}); {@code updateScope} narrows the pre-launch
     * auto-chain recompute+update (see {@link #UPDATE_SCOPE_DESCRIPTION}).
     *
     * The temp directory is NEVER deleted in finally — a Pending re-call can fetch the result. Old
     * runs are cleaned up automatically before starting a new launch.
     */
    private String runTests(String configName, String projectName, String applicationId,
            String extensions, String modules, String tests, int timeout, boolean updateBeforeLaunch,
            String updateScope, boolean debug)
    {
        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }

            // updateScope is ARGUMENT-validated as early as possible: when the caller
            // named the project directly, a typo'd extension name fails fast with the
            // available names BEFORE launch-config resolution — so the validation is
            // reachable (and e2e-testable) without a launch configuration or a live
            // infobase. The same validation inside prepareForFreshLaunch stays as the
            // backstop for the by-name call style, where the project is only known
            // after the config resolves. Gated on updateBeforeLaunch because
            // updateScope only applies to the auto-chain; gated on the project
            // existing so an unknown project keeps its established no-config sentinel.
            if (updateBeforeLaunch && projectName != null && !projectName.isEmpty())
            {
                ProjectContext scopeCtx = ProjectContext.of(projectName);
                if (scopeCtx.exists())
                {
                    String scopeError =
                        LaunchLifecycleUtils.validateUpdateScope(scopeCtx.project(), updateScope);
                    if (scopeError != null)
                    {
                        return ToolResult.error(scopeError).toJson();
                    }
                }
            }

            ILaunchConfiguration matchingConfig = LaunchConfigUtils.resolveLaunchConfig(
                    launchManager, configName, projectName, applicationId);
            if (matchingConfig == null)
            {
                boolean hasName = configName != null && !configName.isEmpty();
                return hasName
                    ? ToolResult.error("Launch configuration not found: '" + configName + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                        + "Use list_configurations to see what's available.").toJson() //$NON-NLS-1$
                    : buildNoConfigError(launchManager,
                        launchManager.getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID),
                        projectName, applicationId);
            }
            if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(matchingConfig)))
            {
                return ToolResult.error("Launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' is not a runtime-client config — YAXUnit tests require one.").toJson(); //$NON-NLS-1$
            }

            // Derive effective project/application from the resolved config.
            String effectiveProject = LaunchConfigUtils.readAttribute(matchingConfig,
                LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String effectiveAppId = LaunchConfigUtils.readAttribute(matchingConfig,
                LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
            if (projectName == null || projectName.isEmpty())
            {
                projectName = effectiveProject;
            }
            if (applicationId == null || applicationId.isEmpty())
            {
                applicationId = effectiveAppId;
            }
            if (projectName == null || projectName.isEmpty())
            {
                return ToolResult.error("Launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' has no project attribute set").toJson(); //$NON-NLS-1$
            }

            String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
            if (notReadyError != null)
            {
                return ToolResult.error(notReadyError).toJson();
            }

            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }

            if (!ctx.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }
            IProject project = ctx.project();

            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return ToolResult.error("IApplicationManager service is not available").toJson(); //$NON-NLS-1$
            }

            // A runtime-client launch config may carry no applicationId (it was not
            // bound to an application). Fall back to the project's default application
            // so updateBeforeLaunch has a target and the EDT launch delegate does not
            // pop its blocking "Update infobase before launch?" modal.
            applicationId = LaunchLifecycleUtils.resolveDefaultApplicationId(project, applicationId, appManager);

            if (applicationId != null && !applicationId.isEmpty())
            {
                try
                {
                    Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                    if (!appOpt.isPresent())
                    {
                        return ToolResult.error("Application not found: " + applicationId //$NON-NLS-1$
                                + ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
                    }
                }
                catch (ApplicationException e)
                {
                    Activator.logError("Error checking application", e); //$NON-NLS-1$
                    return ToolResult.error("Failed to validate application: " + applicationId //$NON-NLS-1$
                            + " (" + e.getMessage() + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            // DEBUG mode shares the whole setup above (resolve/validate/effective
            // project+app), then spawns a DEBUG launch and returns at once for
            // wait_for_break — no polling, no run-key reuse cache.
            if (debug)
            {
                return launchDebugMode(matchingConfig, project, projectName, applicationId,
                    appManager, launchManager, extensions, modules, tests, updateBeforeLaunch,
                    updateScope);
            }

            // Use the launch config name as the run-key root — stable across
            // (project, applicationId) vs. launchConfigurationName call styles.
            String runKey = matchingConfig.getName() + ":" //$NON-NLS-1$
                    + sha1(safe(extensions) + "|" + safe(modules) + "|" + safe(tests)); //$NON-NLS-1$ //$NON-NLS-2$
            Path reportDir = stableReportDir(runKey);

            // If a launch is already running for this key, just poll it.
            ILaunch existing = ACTIVE_LAUNCHES.get(runKey);
            if (existing != null)
            {
                if (existing.isTerminated())
                {
                    // Remove + read under the per-IB lock so remove-then-read is ATOMIC against a
                    // concurrent identical call that falls through to a fresh launch: that path holds
                    // the SAME lock for cleanupTempDir(reportDir) + spawn, so it cannot wipe reportDir
                    // between this thread's remove and read. With the remove OUTSIDE the lock, a racer
                    // could observe ACTIVE_LAUNCHES already empty, take the lock first, cleanupTempDir
                    // the fresh run's dir and delete junit.xml before this thread reads it — a spurious
                    // "no JUnit XML" error. pollLaunch's sibling read guards the same way (see there).
                    // remove(runKey, existing) is by identity — it never drops a newer launch a racing
                    // identical call may have put under the same runKey since the get() above. Worst
                    // case still degrades from a torn parse to a clean null; findJunitXml + readResults
                    // are fast (ms), so contention is negligible.
                    synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
                    {
                        ACTIVE_LAUNCHES.remove(runKey, existing);
                        PENDING_FETCH.remove(runKey);
                        File junitXml = findJunitXml(reportDir);
                        if (junitXml != null)
                        {
                            return readResults(junitXml);
                        }
                        return ToolResult.error("Previous launch finished but no JUnit XML found in " //$NON-NLS-1$
                                + reportDir + ". Make sure YAXUnit extension is installed.").toJson(); //$NON-NLS-1$
                    }
                }
                String pollResult = pollLaunch(existing, reportDir, timeout, runKey,
                        projectName, applicationId);
                if (pollResult != null)
                {
                    // Result delivered — forget any Pending bookkeeping so the next call re-runs.
                    PENDING_FETCH.remove(runKey);
                    return pollResult;
                }
                // Still running past the window — remember the key so a re-call can fetch the result.
                PENDING_FETCH.add(runKey);
                return buildPendingMessage(reportDir);
            }

            // No active launch. Deliver a previously reported Pending result EXACTLY ONCE: a re-call
            // fetching the result of a run that finished after a Pending response gets the report;
            // any later call with the same key falls through to a fresh run. There is NO time-based
            // cache, so a genuine re-run always re-executes the tests.
            // Consume + read under the per-IB lock so a concurrent identical call that falls through to
            // a fresh launch cannot cleanupTempDir(reportDir) mid-read — the fresh-run path holds the
            // SAME lock for cleanup+spawn. A racer blocked here finds PENDING_FETCH already drained and
            // proceeds to a fresh run; worst case degrades from a torn parse to a clean null.
            synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
            {
                if (PENDING_FETCH.remove(runKey))
                {
                    File pending = findJunitXml(reportDir);
                    if (pending != null)
                    {
                        Activator.logInfo("Delivering completed YAXUnit result for pending runKey=" + runKey); //$NON-NLS-1$
                        return readResults(pending);
                    }
                    // Pending was reported but no report materialised (the launch died without writing
                    // junit.xml) — fall through and start a fresh run.
                }
            }

            // Phase 1 (quick, JVM-wide): try to reuse an active launch for this runKey.
            ILaunch launch = null;
            synchronized (ACTIVE_LAUNCHES)
            {
                ILaunch concurrent = ACTIVE_LAUNCHES.get(runKey);
                if (concurrent != null && !concurrent.isTerminated())
                {
                    Activator.logInfo("Reusing active YAXUnit launch for runKey=" + runKey); //$NON-NLS-1$
                    launch = concurrent;
                }
                else if (concurrent != null)
                {
                    ACTIVE_LAUNCHES.remove(runKey);
                }
            }

            // Phase 2: pre-launch preparation (terminate stale launch + recompute
            // + DB update) runs in a background Job under a 25-second budget.
            // The tool thread waits on the job's latch; if the prep is not done
            // within the budget it returns a "Pending (preparation)" response and
            // the caller retries with the same arguments. The launch (Phase 3) is
            // NEVER run in the background — only the prep. A single in-flight
            // entry per (project, applicationId) prevents a second job from
            // starting while one is already running.
            //
            // Phase 3 (spawn) still runs under the per-key lock — this serialises
            // the spawn across both YAXUnit tools for the same IB and closes the
            // narrow window between workingCopy.launch() and registerOwnedLaunch
            // where a concurrent call could otherwise terminate this launch before
            // it's registered. Different (project, applicationId) pairs are unaffected.
            PreLaunchResult preLaunch = null;
            if (launch == null)
            {
                if (updateBeforeLaunch)
                {
                    String prepKey = LaunchLifecycleUtils.prepKeyFor(projectName, applicationId);
                    final ILaunchManager finalLaunchManager = launchManager;
                    final IProject finalProject = project;
                    final String finalApplicationId = applicationId;
                    final IApplicationManager finalAppManager = appManager;
                    final String finalUpdateScope = updateScope;
                    final PreLaunchResult[] resultHolder = new PreLaunchResult[1];

                    String pendingOrError = awaitPreparedOrPending(prepKey, projectName,
                        finalLaunchManager, finalProject, finalApplicationId, finalAppManager,
                        finalUpdateScope, "YAXUnit pre-launch preparation for " + projectName, //$NON-NLS-1$
                        resultHolder);
                    if (pendingOrError != null)
                    {
                        return pendingOrError;
                    }
                    preLaunch = resultHolder[0];
                }

                synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
                {
                    // Phase 3: re-check ACTIVE_LAUNCHES under JVM-wide sync (another
                    // thread may have spawned for the same runKey while we waited
                    // on the per-key lock), then either reuse or spawn.
                    synchronized (ACTIVE_LAUNCHES)
                    {
                        ILaunch racer = ACTIVE_LAUNCHES.get(runKey);
                        if (racer != null && !racer.isTerminated())
                        {
                            Activator.logInfo("Reusing YAXUnit launch spawned during auto-chain: runKey=" //$NON-NLS-1$
                                + runKey);
                            launch = racer;
                        }
                        else
                        {
                            cleanupTempDir(reportDir);
                            Files.createDirectories(reportDir);
                            Path paramsFile = reportDir.resolve("xUnitParams.json"); //$NON-NLS-1$
                            String paramsJson = buildParamsJson(reportDir.resolve("junit.xml").toString(), //$NON-NLS-1$
                                    extensions, modules, tests);
                            Files.write(paramsFile, paramsJson.getBytes(StandardCharsets.UTF_8));
                            Activator.logInfo("YAXUnit params written to: " + paramsFile); //$NON-NLS-1$

                            ILaunchConfigurationWorkingCopy workingCopy = matchingConfig.getWorkingCopy();
                            String startupOption = "RunUnitTests=" + paramsFile.toString(); //$NON-NLS-1$
                            workingCopy.setAttribute(LaunchConfigUtils.ATTR_STARTUP_OPTION, startupOption);
                            // Stamp the resolved applicationId onto the launch so the spawned
                            // client carries it (an app-less config would otherwise launch with
                            // an empty id), keeping it matchable by the terminate-before-launch
                            // sweep keyed on applicationId.
                            if (applicationId != null && !applicationId.isEmpty())
                            {
                                workingCopy.setAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, applicationId);
                            }

                            Activator.logInfo("Launching YAXUnit tests: config=" + matchingConfig.getName() //$NON-NLS-1$
                                    + ", startup=" + startupOption); //$NON-NLS-1$

                            // Auto-confirm EDT's blocking "Application update" modal
                            // for the duration of this launch only (the dependent
                            // test extension keeps the app in INCREMENTAL_UPDATE_REQUIRED,
                            // which no pre-update durably clears) — but ONLY when the
                            // caller did not opt out via updateBeforeLaunch=false:
                            // auto-pressing "Update then run" would silently perform
                            // the very DB update the caller disabled, so with the
                            // opt-out the platform's dialogs are left for a human.
                            // Manual EDT launches outside this window still prompt
                            // normally.
                            boolean[] armFlags = runPathArmFlags(updateBeforeLaunch);
                            LaunchUpdateDialogAutoConfirmer.arm(armFlags[0], armFlags[1]);
                            try
                            {
                                launch = workingCopy.launch(ILaunchManager.RUN_MODE,
                                    new NullProgressMonitor());
                            }
                            finally
                            {
                                LaunchUpdateDialogAutoConfirmer.disarm(armFlags[0], armFlags[1]);
                            }
                            // Register BEFORE leaving the per-key lock so a concurrent
                            // auto-chain on the same IB sees this launch as owned and
                            // refuses to terminate it.
                            LaunchLifecycleUtils.registerOwnedLaunch(launch);
                            ACTIVE_LAUNCHES.put(runKey, launch);
                        }
                    }
                }
            }

            String pollResult = pollLaunch(launch, reportDir, timeout, runKey,
                    projectName, applicationId);
            if (pollResult != null)
            {
                // Result delivered — forget any Pending bookkeeping so the next call re-runs.
                PENDING_FETCH.remove(runKey);
                return prependPreLaunchInfo(preLaunch, pollResult);
            }

            // Polling window expired — return Pending without terminating the launch. Remember the
            // key so a re-call can fetch the result once it completes.
            PENDING_FETCH.add(runKey);
            return prependPreLaunchInfo(preLaunch, buildPendingMessage(reportDir));
        }
        catch (CoreException e)
        {
            Activator.logError("Error running YAXUnit tests", e); //$NON-NLS-1$
            return ToolResult.error("Launch failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Test execution was interrupted").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error running YAXUnit tests", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /**
     * DEBUG-mode launch (shared by {@code debug=true} and the deprecated
     * {@code debug_yaxunit_tests} alias): spawns the test run in DEBUG mode so
     * breakpoints fire, then returns a Markdown launch handle immediately. Unlike
     * the polling path it does NOT wait for {@code junit.xml}; the caller is
     * expected to call {@code wait_for_break} next. The report is still written to
     * {@code reportDir} once the run finishes.
     */
    private String launchDebugMode(ILaunchConfiguration matchingConfig, IProject project,
            String projectName, String applicationId, IApplicationManager appManager,
            ILaunchManager launchManager, String extensions, String modules, String tests,
            boolean updateBeforeLaunch, String updateScope) throws IOException, CoreException
    {
        // Native path separators: YAXUnit builds file:// URIs and breaks on forward slashes on Windows.
        Path reportDir = Paths.get(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
            "edt-mcp-yaxunit-debug", projectName + "-" + System.currentTimeMillis() //$NON-NLS-1$ //$NON-NLS-2$
                + "-" + DEBUG_LAUNCH_COUNTER.getAndIncrement()); //$NON-NLS-1$
        Files.createDirectories(reportDir);
        Path paramsFile = reportDir.resolve("xUnitParams.json"); //$NON-NLS-1$
        Path junitFile = reportDir.resolve("junit.xml"); //$NON-NLS-1$
        Files.write(paramsFile,
            buildParamsJson(junitFile.toString(), extensions, modules, tests).getBytes(StandardCharsets.UTF_8));

        // Suspend listener must be live before the launch starts producing events.
        DebugSessionRegistry.get().ensureListenerRegistered();

        // Phase 2 (debug path): prep runs in a background Job under a 25-second
        // budget, same as the RUN path. The sweep + launch (Phase 3) runs
        // synchronously after prep completes, under the per-key lock.
        PreLaunchResult preLaunch = null;
        if (updateBeforeLaunch)
        {
            String prepKey = LaunchLifecycleUtils.prepKeyFor(projectName, applicationId);
            final PreLaunchResult[] resultHolder = new PreLaunchResult[1];

            String pendingOrError = awaitPreparedOrPending(prepKey, projectName,
                launchManager, project, applicationId, appManager,
                updateScope, "YAXUnit debug pre-launch preparation for " + projectName, //$NON-NLS-1$
                resultHolder);
            if (pendingOrError != null)
            {
                return pendingOrError;
            }
            preLaunch = resultHolder[0];
        }

        synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
        {
            // Fresh-run guarantee — PART OF THE updateBeforeLaunch AUTO-CHAIN: with
            // updateBeforeLaunch=true a YAXUnit debug run is ALWAYS a new session —
            // detect and non-interactively terminate any existing live CLIENT session
            // of this application BEFORE workingCopy.launch, so EDT's launch delegate
            // never raises its blocking code-1003 "Debug session already exists"
            // modal. This covers BOTH the ILaunchManager view and EDT's debug target
            // manager (a UI-started "Debug As" session lives ONLY there:
            // prepareForFreshLaunch's sweep keys on getApplicationIdFor and never
            // matches it). The detect is CLIENT-typed-thread-discriminated, so a
            // debug-mode standalone server session is never matched and never
            // terminated. A launch OWNED by another MCP tool (e.g. a concurrent
            // run_yaxunit_tests RUN launch of the same app) is exempt from the sweep —
            // it is managed by its own tool. With updateBeforeLaunch=false the sweep
            // is SKIPPED along with the rest of the auto-chain (the documented legacy
            // delegate behaviour): an existing session is left alone and the
            // delegate's own 1003 check decides — the always-armed race-net matcher
            // below presses the non-destructive keep-button if that modal appears.
            // applicationId here is already the delegate-resolved id
            // (ATTR_APPLICATION_ID else project default — see
            // resolveDefaultApplicationId above) and is stamped onto the working copy
            // below, so it is exactly the key the delegate's 1003 check uses.
            if (shouldSweepExistingClientSession(updateBeforeLaunch)
                && LaunchLifecycleUtils.ensureNoExistingClientSession(project, applicationId))
            {
                Activator.logInfo("YAXUnit debug: terminated an existing client session before " //$NON-NLS-1$
                    + "the fresh debug launch: applicationId=" + applicationId); //$NON-NLS-1$
            }

            ILaunchConfigurationWorkingCopy workingCopy = matchingConfig.getWorkingCopy();
            String startupOption = "RunUnitTests=" + paramsFile.toString(); //$NON-NLS-1$
            workingCopy.setAttribute(LaunchConfigUtils.ATTR_STARTUP_OPTION, startupOption);
            // Stamp the resolved applicationId so the spawned ILaunch carries it:
            // DebugSessionRegistry keys the suspend snapshot by this id and the
            // handle below hands the SAME id to wait_for_break.
            if (applicationId != null && !applicationId.isEmpty())
            {
                workingCopy.setAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, applicationId);
            }
            Activator.logInfo("Launching YAXUnit tests in DEBUG mode: config=" + matchingConfig.getName() //$NON-NLS-1$
                + ", startup=" + startupOption); //$NON-NLS-1$
            // Auto-confirm EDT's blocking launch modals for the launch window only:
            // the "Application update" matcher gated on updateBeforeLaunch (auto-
            // pressing it after the caller opted out of the DB update would silently
            // perform the very update they disabled — mirror DebugLaunchTool's
            // gating), PLUS the code-1003 "Debug session already exists" matcher as
            // the unconditional race net behind ensureNoExistingClientSession — if a
            // session slips in (or a terminate times out) between the sweep above and
            // the delegate's check, or the sweep was skipped via
            // updateBeforeLaunch=false, the armed confirmer presses the
            // non-destructive "Keep existing and start new" so an unattended call
            // never hangs on the modal.
            boolean[] armFlags = debugPathArmFlags(updateBeforeLaunch);
            LaunchUpdateDialogAutoConfirmer.arm(armFlags[0], armFlags[1]);
            try
            {
                ILaunch spawned = workingCopy.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor());
                LaunchLifecycleUtils.registerOwnedLaunch(spawned);
            }
            catch (CoreException ex)
            {
                Activator.logError("Failed to launch YAXUnit in debug mode", ex); //$NON-NLS-1$
                return ToolResult.error("Launch failed: " + ex.getMessage()).toJson(); //$NON-NLS-1$
            }
            finally
            {
                LaunchUpdateDialogAutoConfirmer.disarm(armFlags[0], armFlags[1]);
            }
        }
        return buildDebugLaunchMarkdown(matchingConfig.getName(), projectName, applicationId,
            reportDir, junitFile, preLaunch);
    }

    /**
     * Shared in-flight / budget / pending block for both the RUN and DEBUG paths.
     *
     * <p>Acquires (or creates) a {@link PrepInFlight} entry for {@code prepKey}
     * via {@link java.util.concurrent.ConcurrentMap#computeIfAbsent}, ensuring only ONE
     * background Job is ever scheduled for a given {@code (project, applicationId)} key
     * regardless of how many concurrent tool threads arrive: the thread that wins the
     * {@link PrepInFlight#started} CAS constructs and schedules the Job; every other
     * thread simply awaits {@link PrepInFlight#latch} on the same entry.
     *
     * <p>A stale (completed-with-error or expired) entry is replaced atomically via
     * {@link java.util.concurrent.ConcurrentMap#remove(Object, Object)} + retry before the
     * {@code computeIfAbsent}:
     * <ol>
     *   <li>If the existing entry is done-with-error, surface the error ONCE,
     *       remove the entry, and return the error string.</li>
     *   <li>If the existing entry is expired, remove it atomically so a fresh
     *       entry will be created.</li>
     *   <li>Use {@code computeIfAbsent} to get-or-create atomically.</li>
     *   <li>If this thread wins the {@code started} CAS, create and schedule the
     *       Job; otherwise just await the latch.</li>
     *   <li>If the budget expires before the Job completes, return the prep-pending
     *       message (caller returns Pending).</li>
     *   <li>On Job completion: remove the entry (if still the same), check for
     *       error; on success, store the {@link PreLaunchResult} in
     *       {@code resultHolder[0]} and return {@code null} so the caller proceeds.</li>
     * </ol>
     *
     * @param prepKey          the in-flight map key (project\u0000applicationId)
     * @param projectName      project name (for log messages)
     * @param launchManager    Eclipse launch manager passed through to
     *                         {@link LaunchLifecycleUtils#prepareForFreshLaunch}
     * @param project          project handle passed through to
     *                         {@link LaunchLifecycleUtils#prepareForFreshLaunch}
     * @param applicationId    application id passed through to
     *                         {@link LaunchLifecycleUtils#prepareForFreshLaunch}
     * @param appManager       application manager passed through to
     *                         {@link LaunchLifecycleUtils#prepareForFreshLaunch}
     * @param updateScope      updateScope value passed through to
     *                         {@link LaunchLifecycleUtils#prepareForFreshLaunch}
     * @param jobName          display name for the background Job
     * @param resultHolder     single-element array; on success the
     *                         {@link PreLaunchResult} is stored in {@code [0]}
     * @return a non-{@code null} string (a Pending or error message) when the
     *         caller must return immediately without proceeding to launch;
     *         {@code null} when preparation completed successfully and the caller
     *         may proceed
     */
    private static String awaitPreparedOrPending(String prepKey, String projectName,
            ILaunchManager launchManager, IProject project, String applicationId,
            IApplicationManager appManager, String updateScope, String jobName,
            PreLaunchResult[] resultHolder)
    {
        // Stale-entry eviction loop: if an expired or done-with-error entry is in
        // the map, remove it atomically so the computeIfAbsent below creates a fresh
        // one. At most two iterations: one to detect + remove, one to proceed.
        while (true)
        {
            PrepInFlight existing = LaunchLifecycleUtils.PREP_INFLIGHT.get(prepKey);
            if (existing == null)
            {
                break; // nothing stale — fall through to computeIfAbsent
            }
            if (existing.done && existing.error != null)
            {
                // Surface the error ONCE; clear the entry so the next call retries.
                if (LaunchLifecycleUtils.PREP_INFLIGHT.remove(prepKey, existing))
                {
                    return ToolResult.error("Pre-launch preparation failed: " + existing.error //$NON-NLS-1$
                        + "\n\nIf the previous launch is stuck, call `terminate_launch` " //$NON-NLS-1$
                        + "with `force=true` and retry. As a last resort, pass " //$NON-NLS-1$
                        + "`updateBeforeLaunch=false` — but the EDT launch delegate may " //$NON-NLS-1$
                        + "then pop a modal dialog that blocks the MCP call.").toJson(); //$NON-NLS-1$
                }
                continue; // another thread already replaced it — re-check
            }
            if (existing.isExpired())
            {
                // Atomically replace the expired entry; on failure another thread
                // already replaced it, so re-check.
                LaunchLifecycleUtils.PREP_INFLIGHT.remove(prepKey, existing);
                continue;
            }
            break; // active (not done, not expired) — fall through to computeIfAbsent
        }

        // Atomically get-or-create.  Only the thread that wins
        // entry.started.compareAndSet(false, true) schedules the Job.
        PrepInFlight entry = LaunchLifecycleUtils.PREP_INFLIGHT.computeIfAbsent(
            prepKey, k -> new PrepInFlight(System.currentTimeMillis()));

        if (entry.started.compareAndSet(false, true))
        {
            // This thread won: create and schedule the background Job.
            final PrepInFlight jobEntry = entry;
            Job prepJob = new Job(jobName)
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    try
                    {
                        jobEntry.phase = "recompute"; //$NON-NLS-1$
                        int terminateTimeout =
                            LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds();
                        PreLaunchResult result = LaunchLifecycleUtils.prepareForFreshLaunch(
                            launchManager, project, applicationId,
                            appManager, terminateTimeout, updateScope);
                        jobEntry.phase = "db-update"; //$NON-NLS-1$
                        resultHolder[0] = result;
                        if (!result.isOk())
                        {
                            jobEntry.error = result.getError();
                        }
                    }
                    catch (Exception e)
                    {
                        jobEntry.error = e.getMessage() != null ? e.getMessage()
                            : e.getClass().getSimpleName();
                        Activator.logError("Pre-launch preparation job failed: " + projectName, e); //$NON-NLS-1$
                    }
                    finally
                    {
                        jobEntry.done = true;
                        jobEntry.latch.countDown();
                    }
                    return Status.OK_STATUS;
                }
            };
            prepJob.setPriority(Job.INTERACTIVE);
            prepJob.schedule();
        }
        // else: another thread is already running the Job — just await the latch.

        boolean done;
        try
        {
            done = entry.latch.await(LaunchLifecycleUtils.PRELAUNCH_BUDGET_MS, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            done = entry.done;
        }
        if (!done)
        {
            // Budget expired — return Pending so the caller retries.
            return buildPrepPendingMessage(entry.elapsedSeconds(), entry.phase);
        }
        // Job completed within the budget.  Remove our entry (conditional so a
        // concurrent expired-entry replacement is not accidentally dropped).
        LaunchLifecycleUtils.PREP_INFLIGHT.remove(prepKey, entry);
        if (entry.error != null)
        {
            return ToolResult.error("Pre-launch preparation failed: " + entry.error //$NON-NLS-1$
                + "\n\nIf the previous launch is stuck, call `terminate_launch` " //$NON-NLS-1$
                + "with `force=true` and retry. As a last resort, pass " //$NON-NLS-1$
                + "`updateBeforeLaunch=false` — but the EDT launch delegate may " //$NON-NLS-1$
                + "then pop a modal dialog that blocks the MCP call.").toJson(); //$NON-NLS-1$
        }
        // resultHolder[0] already set by the Job; null for the concurrent-waiter path
        // (the original job-starter holds the result, but launch can proceed either way).
        return null; // success — caller may proceed to launch
    }

    /** Markdown launch handle returned by DEBUG mode — readable, with the wait_for_break next step. */
    private static String buildDebugLaunchMarkdown(String configName, String projectName,
            String applicationId, Path reportDir, Path junitFile, PreLaunchResult preLaunch)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# YAXUnit Debug Launch\n\n"); //$NON-NLS-1$
        sb.append("Debug launch **queued** for `").append(configName).append("`.\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("- **applicationId:** `").append(applicationId == null ? "" : applicationId).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("- **projectName:** `").append(projectName).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("- **reportDir:** `").append(reportDir).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("- **junitXml:** `").append(junitFile).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (preLaunch != null && preLaunch.getTerminatedCount() > 0)
        {
            sb.append("- **preLaunch:** ").append(preLaunch.summary()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n**Next step:** call `wait_for_break` (the applicationId is auto-resolved when this is " //$NON-NLS-1$
            + "the only active debug launch) to block until a breakpoint is hit, then `get_variables` / " //$NON-NLS-1$
            + "`evaluate_expression` / `step` / `resume`. Set breakpoints with `set_breakpoint` BEFORE the " //$NON-NLS-1$
            + "test reaches them. The `junit.xml` report is still written to `reportDir` after the run.\n"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Polls a launch for up to {@code timeoutSec} seconds. Returns the parsed Markdown report
     * if the launch finished, or {@code null} if still running (caller should return a Pending message).
     * <p>
     * The post-completion read ({@code ACTIVE_LAUNCHES.remove} + {@link #findJunitXml} +
     * {@link #readResults}) runs under the per-IB lock, for the SAME reason the existing-terminated
     * and pending-fetch read paths do: a concurrent identical call that falls through to a fresh
     * launch holds the SAME lock for {@link #cleanupTempDir}(reportDir) + spawn, so it cannot wipe
     * {@code reportDir} mid-read. The {@code remove} is INSIDE the lock together with the read so
     * remove-then-read is atomic against that cleanup — otherwise a racer could observe the launch
     * already gone, fall through to a fresh run, and {@code cleanupTempDir} the directory between
     * this thread's remove and read. The poll loop itself is deliberately OUTSIDE the lock: holding
     * it across the {@link Thread#sleep} window would serialise the whole IB for the poll duration.
     * Worst case still degrades from a torn parse to a clean null.
     */
    private String pollLaunch(ILaunch launch, Path reportDir, int timeoutSec, String runKey,
            String projectName, String applicationId)
            throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
        while (!launch.isTerminated())
        {
            if (System.currentTimeMillis() > deadline)
            {
                return null;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }

        synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
        {
            // Remove by identity. While this thread was blocked on the lock, a concurrent identical
            // call could have observed THIS launch already evicted (the termination listener) and
            // spawned a fresh one under the SAME runKey + cleanupTempDir(reportDir). An unconditional
            // remove(runKey) would then drop that newer launch's tracking, orphaning it.
            // remove(runKey, launch) deletes the entry only if it still maps to our own launch.
            ACTIVE_LAUNCHES.remove(runKey, launch);
            Activator.logInfo("YAXUnit tests completed for " + runKey); //$NON-NLS-1$

            File junitXml = findJunitXml(reportDir);
            if (junitXml == null)
            {
                return ToolResult.error("No JUnit XML report found in " + reportDir //$NON-NLS-1$
                        + ". Make sure YAXUnit extension is installed in the infobase " //$NON-NLS-1$
                        + "and test configuration is correct.").toJson(); //$NON-NLS-1$
            }

            return readResults(junitXml);
        }
    }

    /**
     * Parses the JUnit XML, formats it as Markdown and writes report.md next to junit.xml so
     * that the user can open the report manually from disk. Returns the Markdown content for
     * the MCP response, with an extra footer pointing at the on-disk file.
     */
    private String readResults(File junitXml)
    {
        try
        {
            JUnitTestResults results = JUnitXmlParser.parse(junitXml);
            String markdown = JUnitMarkdownFormatter.format(results);

            Path reportFile = junitXml.toPath().resolveSibling("report.md"); //$NON-NLS-1$
            boolean reportWritten = false;
            try
            {
                Files.write(reportFile, markdown.getBytes(StandardCharsets.UTF_8));
                reportWritten = Files.exists(reportFile);
            }
            catch (IOException io)
            {
                Activator.logError("Failed to write Markdown report to " + reportFile, io); //$NON-NLS-1$
            }

            if (reportWritten)
            {
                return markdown + "\n---\n*Full report saved to:* `" + reportFile + "`\n"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return markdown;
        }
        catch (Exception e)
        {
            Activator.logError("Error parsing JUnit XML: " + junitXml, e); //$NON-NLS-1$
            return ToolResult.error("Failed to parse test results: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Lazily registers a launch listener that evicts terminated launches from
     * {@link #ACTIVE_LAUNCHES}, preventing memory leaks for launches that the
     * tool never observes itself (for example because the caller never polls
     * again after a Pending response and the launch then crashes or finishes).
     */
    private static void ensureLaunchListenerRegistered()
    {
        if (LISTENER_REGISTERED.compareAndSet(false, true))
        {
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            if (debugPlugin == null)
            {
                LISTENER_REGISTERED.set(false);
                return;
            }
            ILaunchManager launchManager = debugPlugin.getLaunchManager();
            if (launchManager == null)
            {
                LISTENER_REGISTERED.set(false);
                return;
            }
            launchManager.addLaunchListener(new ILaunchListener()
            {
                @Override
                public void launchAdded(ILaunch launch)
                {
                    // ignored
                }

                @Override
                public void launchChanged(ILaunch launch)
                {
                    if (launch != null && launch.isTerminated())
                    {
                        evict(launch);
                    }
                }

                @Override
                public void launchRemoved(ILaunch launch)
                {
                    evict(launch);
                }
            });
            Activator.logInfo("YAXUnit launch listener registered"); //$NON-NLS-1$
        }
    }

    /** Removes the given launch from {@link #ACTIVE_LAUNCHES} regardless of which key it lives under. */
    private static void evict(ILaunch launch)
    {
        if (launch == null)
        {
            return;
        }
        ACTIVE_LAUNCHES.entrySet().removeIf(e -> e.getValue() == launch);
        // PENDING_FETCH is intentionally NOT cleared here: it is keyed by runKey (String) and there is
        // no reverse map from this ILaunch back to its key. A key left behind after an abandoned Pending
        // is bounded by the number of distinct (config, filter) combinations, and is consumed at most
        // once on the next identical call (the documented "ambiguous identical args" tradeoff of #136).
        LaunchLifecycleUtils.unregisterOwnedLaunch(launch);
    }

    /** Defensive sweep that drops any terminated launches still lingering in the map. */
    private static void purgeTerminatedLaunches()
    {
        ACTIVE_LAUNCHES.entrySet().removeIf(e -> {
            ILaunch l = e.getValue();
            return l == null || l.isTerminated();
        });
    }

    /**
     * Builds a Pending message that instructs the caller to invoke the tool again with
     * identical arguments to fetch the result.
     */
    private String buildPendingMessage(Path reportDir)
    {
        return "**Pending:** YAXUnit tests are still running.\n\n" //$NON-NLS-1$
                + "Report directory: `" + reportDir + "`\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "Call `run_yaxunit_tests` again with the same arguments to wait further " //$NON-NLS-1$
                + "and fetch the JUnit XML once the launch completes.\n"; //$NON-NLS-1$
    }

    /**
     * Builds a Pending response for the pre-launch preparation phase (background
     * recompute / DB update). The caller is instructed to retry with the SAME
     * arguments — the in-flight job continues server-side and a follow-up call
     * will either find the prep completed (and proceed to launch) or return
     * another pending response until the budget is met.
     *
     * @param elapsedSeconds elapsed time since the background job started
     * @param phase the current preparation phase label (e.g. {@code "recompute"} /
     *            {@code "db-update"})
     * @return a Markdown pending response matching the shape of
     *         {@link #buildPendingMessage(Path)}
     */
    private static String buildPrepPendingMessage(long elapsedSeconds, String phase)
    {
        int retryAfter = 5;
        return "**Pending:** Pre-launch preparation is still running " //$NON-NLS-1$
            + "(phase: `" + (phase != null ? phase : "recompute") + "`" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ", elapsed: " + elapsedSeconds + "s).\n\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "The server is rebuilding changed projects and updating the infobase in the " //$NON-NLS-1$
            + "background so the run starts against a fresh, up-to-date infobase. " //$NON-NLS-1$
            + "Call `run_yaxunit_tests` again with the **same arguments** in ~" //$NON-NLS-1$
            + retryAfter + "s to check for completion.\n"; //$NON-NLS-1$
    }

    /**
     * Prepends a one-line pre-launch summary to the given report, but only when
     * the auto-chain actually terminated a live launch — a no-op chain is silent
     * to avoid cluttering reports.
     */
    private static String prependPreLaunchInfo(PreLaunchResult preLaunch, String report)
    {
        if (preLaunch == null || preLaunch.getTerminatedCount() == 0)
        {
            return report;
        }
        return "> **Pre-launch:** " + preLaunch.summary() + "\n\n" + report; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Returns a stable directory under the system temp folder for the given run key.
     */
    private Path stableReportDir(String runKey)
    {
        String safeKey = runKey.replaceAll("[^a-zA-Z0-9_.-]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
        // Always preserve a unique hash suffix so different runs can never collide into the same dir.
        String uniqueSuffix = sha1Full(runKey);
        int maxSafeKeyLength = Math.max(0, 80 - uniqueSuffix.length() - 1);
        if (safeKey.length() > maxSafeKeyLength)
        {
            safeKey = safeKey.substring(0, maxSafeKeyLength);
        }
        String dirName = safeKey.isEmpty() ? uniqueSuffix : safeKey + "_" + uniqueSuffix; //$NON-NLS-1$
        return Paths.get(System.getProperty("java.io.tmpdir"), "edt-mcp-yaxunit", dirName); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Computes a full hex SHA-1 hash for values that must remain unique after truncation.
     */
    private String sha1Full(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-1"); //$NON-NLS-1$
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest)
            {
                hex.append(String.format("%02x", b)); //$NON-NLS-1$
            }
            return hex.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * Computes a short hex SHA-1 hash for filter parts so the runKey is bounded.
     */
    private String sha1(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-1"); //$NON-NLS-1$
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < digest.length; i++)
            {
                hex.append(String.format("%02x", digest[i])); //$NON-NLS-1$
            }
            return hex.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(input.hashCode());
        }
    }

    private String safe(String s)
    {
        return s == null ? "" : s; //$NON-NLS-1$
    }

    /**
     * Builds the xUnitParams.json content.
     */
    private String buildParamsJson(String reportPath, String extensions, String modules, String tests)
    {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reportPath", reportPath); //$NON-NLS-1$
        params.put("reportFormat", "jUnit"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("closeAfterTests", true); //$NON-NLS-1$

        Map<String, Object> filter = new LinkedHashMap<>();
        boolean hasFilter = false;

        if (extensions != null && !extensions.isEmpty())
        {
            filter.put("extensions", splitToList(extensions)); //$NON-NLS-1$
            hasFilter = true;
        }

        if (modules != null && !modules.isEmpty())
        {
            filter.put("modules", splitToList(modules)); //$NON-NLS-1$
            hasFilter = true;
        }

        if (tests != null && !tests.isEmpty())
        {
            filter.put("tests", splitToList(tests)); //$NON-NLS-1$
            hasFilter = true;
        }

        if (hasFilter)
        {
            params.put("filter", filter); //$NON-NLS-1$
        }

        return GsonProvider.toJson(params);
    }

    /**
     * Splits a comma-separated string into a list.
     */
    private List<String> splitToList(String value)
    {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) //$NON-NLS-1$
        {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
            {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Joins a list-valued argument back to the canonical comma-separated string used
     * internally (filter, run key, retry). Returns {@code null} when the list is
     * null/empty so the existing "no filter" branches keep working unchanged.
     */
    private static String joinList(List<String> values)
    {
        return (values == null || values.isEmpty()) ? null : String.join(",", values); //$NON-NLS-1$
    }

    /**
     * Builds an error message when no launch configuration is found.
     */
    private String buildNoConfigError(ILaunchManager launchManager,
            ILaunchConfigurationType configType, String projectName, String applicationId)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("No launch configuration found for project '"); //$NON-NLS-1$
        sb.append(projectName);
        sb.append("' and application '"); //$NON-NLS-1$
        sb.append(applicationId);
        sb.append("'.\n\n"); //$NON-NLS-1$
        sb.append("Create a launch configuration in EDT first (Run > Run Configurations > 1C:Enterprise Runtime Client).\n\n"); //$NON-NLS-1$

        ILaunchConfiguration[] allConfigs = LaunchConfigUtils.getAllRuntimeClientConfigs(launchManager, configType);
        if (allConfigs.length > 0)
        {
            sb.append("Available launch configurations:\n\n"); //$NON-NLS-1$
            sb.append("| Name | Project | Application ID |\n"); //$NON-NLS-1$
            sb.append("|------|---------|----------------|\n"); //$NON-NLS-1$
            for (ILaunchConfiguration config : allConfigs)
            {
                sb.append("| ").append(config.getName()); //$NON-NLS-1$
                sb.append(" | ").append(LaunchConfigUtils.readAttribute(config, LaunchConfigUtils.ATTR_PROJECT_NAME, "")); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append(" | ").append(LaunchConfigUtils.readAttribute(config, LaunchConfigUtils.ATTR_APPLICATION_ID, "")); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append(" |\n"); //$NON-NLS-1$
            }
        }

        return ToolResult.error(sb.toString()).toJson();
    }

    /**
     * Finds the JUnit XML report file in the temp directory.
     */
    private File findJunitXml(Path tempDir)
    {
        if (tempDir == null || !Files.exists(tempDir))
        {
            return null;
        }

        String[] candidates = {"junit.xml", "report.xml", "test-report.xml"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String name : candidates)
        {
            File f = tempDir.resolve(name).toFile();
            if (f.exists() && f.length() > 0)
            {
                return f;
            }
        }

        File[] xmlFiles = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".xml")); //$NON-NLS-1$
        if (xmlFiles != null && xmlFiles.length > 0)
        {
            return xmlFiles[0];
        }

        return null;
    }

    /**
     * Recursively deletes a temp directory if it exists. Silent if missing.
     */
    private void cleanupTempDir(Path tempDir)
    {
        if (tempDir == null || !Files.exists(tempDir))
        {
            return;
        }
        // try-with-resources releases the file-system handle held by Files.walk's stream;
        // on Windows, leaving it open can prevent subsequent deletions of the same path.
        try (java.util.stream.Stream<Path> stream = Files.walk(tempDir))
        {
            stream.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try
                    {
                        Files.delete(p);
                    }
                    catch (IOException ex)
                    {
                        Activator.logError("Failed to delete " + p, ex); //$NON-NLS-1$
                    }
                });
        }
        catch (IOException e)
        {
            Activator.logError("Failed to cleanup temp directory: " + tempDir, e); //$NON-NLS-1$
        }
    }
}

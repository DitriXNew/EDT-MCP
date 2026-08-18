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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.DebugException;
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
import com.ditrix.edt.mcp.server.utils.BackgroundJobPolling;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressReporter;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.ditrix.edt.mcp.server.utils.ExternalInfobaseChangesPolicy;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PrepInFlight;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PreLaunchResult;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.McpJobs;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery;
import com.ditrix.edt.mcp.server.utils.YaxunitJobCancellation;
import com.ditrix.edt.mcp.server.utils.YaxunitReportUtils;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Tool to run YAXUnit tests for a 1C:Enterprise project.
 *
 * Starts a named background job that launches the application with the
 * {@code RunUnitTests} startup parameter, waits for the launch to terminate, then parses the
 * JUnit XML report and retains its Markdown summary. The start call waits only for its clamped
 * transport-safe window; if the job is still running, it returns the job id for
 * {@code get_job_status}. The full Markdown report is also written to {@code report.md} next to
 * {@code junit.xml} so the user can read it directly from disk.
 */
public class RunYaxunitTestsTool implements IMcpTool
{
    public static final String NAME = "run_yaxunit_tests"; //$NON-NLS-1$

    /** Input/filter param: extension names to filter tests by extension. */
    private static final String KEY_EXTENSIONS = "extensions"; //$NON-NLS-1$

    /** Input/filter param: module names to filter tests. */
    private static final String KEY_MODULES = "modules"; //$NON-NLS-1$

    /** Input/filter param: test names in Module.Method format. */
    private static final String KEY_TESTS = "tests"; //$NON-NLS-1$

    /**
     * Input/filter param: YAXUnit tags to select tests by.
     *
     * <p>Lands in {@code filter.tags} of the generated {@code xUnitParams.json}; the framework
     * ({@code ЮТФильтрацияСлужебный.УстановитьКонтекст}) reads that key and applies the filter
     * itself, so nothing here interprets a tag. Verified against YAXUnit v25.12:
     * <ul>
     *   <li>values are OR-ed with one another and AND-ed with the other filter families;</li>
     *   <li>a test is selected when its MODULE, its SUITE, or the test itself carries a listed
     *       tag — tags are inherited downwards;</li>
     *   <li>matching is case-INSENSITIVE (the framework lowercases both the filter and the
     *       declared tag before comparing);</li>
     *   <li>an EMPTY list is not a filter at all — the framework treats it as "no tag filter"
     *       and runs everything, which is why the empty case is left out of the JSON entirely;</li>
     *   <li>there is NO negation/exclusion syntax — a leading '-' is matched literally.</li>
     * </ul>
     */
    private static final String KEY_TAGS = "tags"; //$NON-NLS-1$

    /** JUnit XML report file name written by the YAXUnit run. */
    private static final String VAL_JUNIT_XML = "junit.xml"; //$NON-NLS-1$

    /**
     * Hard ceiling (seconds) on how long ONE start call may hold the MCP transport open, and the
     * default start-call wait window.
     *
     * <p>An MCP client cuts a call at its own transport timeout — around 60 seconds for the
     * clients this tool is driven by — while the pre-launch preparation of a real
     * configuration runs for minutes. A polling window above that ceiling is therefore not a
     * longer wait, it is a wait the caller never sees the end of: the call dies on the wire
     * with a bare "operation timed out", carrying neither the phase nor the reason (#357).
     * The start call is bounded by this ceiling so the answer always arrives while someone is
     * still listening — {@code Pending} with the job id and phase when the work is not done,
     * which is strictly more information than a transport error. The named job is not bounded by
     * this value and continues collecting the report.
     *
     * <p>A caller may ask for LESS (a short probe), never for more: a larger {@code timeout}
     * is clamped, and the schema says so rather than advertising a window the transport
     * cannot deliver.
     */
    static final int MAX_TIMEOUT_SECONDS = 45;

    private static final int DEFAULT_TIMEOUT = MAX_TIMEOUT_SECONDS;
    private static final int POLL_INTERVAL_MS = 1000;

    /** Phase label while the launch configuration and its application are being resolved. */
    private static final String PHASE_RESOLVE = "resolve"; //$NON-NLS-1$

    /** Phase label while the test launch is being spawned. */
    private static final String PHASE_SPAWN = "spawn"; //$NON-NLS-1$

    /** Phase label while the spawned launch is running the tests. */
    private static final String PHASE_RUN = "run"; //$NON-NLS-1$

    /** Active launches keyed by stable run id (configName:filterHash). */
    private static final Map<String, ILaunch> ACTIVE_LAUNCHES = new ConcurrentHashMap<>();

    /**
     * Submission identities map to running registry jobs. This closes the short window before the
     * launch context has been resolved and the final {@link #buildRunKey} is available: an immediate
     * repeat with the same request attaches to the first job instead of starting another resolver.
     */
    private static final Map<String, String> SUBMISSION_JOBS = new ConcurrentHashMap<>();

    /**
     * Final run keys map to running registry jobs. Completed entries are never reused: their result
     * remains fetchable by job id in {@link BackgroundJobs}, while a new argument-based call starts a
     * fresh run. This keeps the run key in its real role as an in-flight duplicate guard.
     */
    private static final Map<String, String> RUN_JOBS = new ConcurrentHashMap<>();

    /** Serialises lookup + admission in both job indexes. */
    private static final Object RUN_JOBS_LOCK = new Object();

    /**
     * The public timeout is a per-call wait, not the lifetime of the YAXUnit run. Once EDT has
     * accepted preparation or launch, only that work can say when it is finished.
     */
    private static final long BACKGROUND_JOB_TIMEOUT_MS = Long.MAX_VALUE;

    /** Lazily registered listener that evicts terminated launches from {@link #ACTIVE_LAUNCHES}. */
    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean(false);

    /** Per-launch counter for the unique debug-mode report directory name. */
    private static final AtomicLong DEBUG_LAUNCH_COUNTER = new AtomicLong(0);

    private final BackgroundJobs jobs;

    public RunYaxunitTestsTool()
    {
        this(BackgroundJobs.shared());
    }

    RunYaxunitTestsTool(BackgroundJobs jobs)
    {
        this.jobs = jobs;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Run YAXUnit tests as a named background job and return a JUnit Markdown report. "  //$NON-NLS-1$
            + "The start call waits up to `timeout` (default and maximum " + MAX_TIMEOUT_SECONDS  //$NON-NLS-1$
            + "s): a short run returns the report in that call, otherwise Pending returns a jobId "  //$NON-NLS-1$
            + "to poll with get_job_status. Address a known run by jobId, NOT by repeating the "  //$NON-NLS-1$
            + "arguments - a repeated start attaches only while that job is still running, and "  //$NON-NLS-1$
            + "otherwise launches a second run. Parameters and examples: "  //$NON-NLS-1$
            + "get_tool_guide('run_yaxunit_tests')."; //$NON-NLS-1$
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
            .stringArrayProperty(KEY_EXTENSIONS, "Extension names to filter tests (array; a comma-separated string is also accepted).") //$NON-NLS-1$
            .stringArrayProperty(KEY_MODULES, "Module names to filter tests (array; a comma-separated string is also accepted).") //$NON-NLS-1$
            .stringArrayProperty(KEY_TESTS, "Test names in Module.Method format (array; a comma-separated string is also accepted).") //$NON-NLS-1$
            .stringArrayProperty(KEY_TAGS, //$NON-NLS-1$
                "YAXUnit tags to select tests by (array; a comma-separated string is also accepted). " //$NON-NLS-1$
                    + "A test is selected when its module, its suite, or the test itself carries one " //$NON-NLS-1$
                    + "of these tags; several tags are OR-ed, and the tag filter is AND-ed with " //$NON-NLS-1$
                    + "extensions/modules/tests. Matching is case-insensitive. Exclusion is NOT " //$NON-NLS-1$
                    + "supported by YAXUnit — a leading '-' is matched literally, not negated. A tag " //$NON-NLS-1$
                    + "no test carries is not an error, just an empty selection.") //$NON-NLS-1$
            .integerProperty("timeout", TIMEOUT_DESCRIPTION) //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Auto-chain (default: true): recompute only the projects whose sources changed " //$NON-NLS-1$
                    + "(the configuration is not exempt), terminate a live client and run a silent DB " //$NON-NLS-1$
                    + "update first, so a freshly edited extension runs " //$NON-NLS-1$
                    + "fresh (not stale), auto-answering the platform's update dialogs. This makes a " //$NON-NLS-1$
                    + "blocking dialog unlikely, NOT impossible: a dialog EDT raises outside the tool's " //$NON-NLS-1$
                    + "own windows still waits for a human, and the tool reports it as a Pending whose " //$NON-NLS-1$
                    + "phase stops changing. false: legacy delegate behaviour — no client sweep, no " //$NON-NLS-1$
                    + "auto-confirmed update dialog; platform dialogs may appear and block. Results are " //$NON-NLS-1$
                    + "retained by jobId until registry eviction, while a new start after completion " //$NON-NLS-1$
                    + "always executes a fresh run regardless of this flag.") //$NON-NLS-1$
            .stringProperty("updateScope", UPDATE_SCOPE_DESCRIPTION) //$NON-NLS-1$
            .stringProperty("externalInfobaseChanges", //$NON-NLS-1$
                EXTERNAL_INFOBASE_CHANGES_DESCRIPTION) //$NON-NLS-1$
            .stringProperty("standaloneServerPortConflict", //$NON-NLS-1$
                StandaloneServerPortConflictPolicy.PARAMETER_DESCRIPTION)
            .booleanProperty("debug", //$NON-NLS-1$
                "true launches in DEBUG mode so breakpoints fire: a short start returns the "  //$NON-NLS-1$
                    + "launch handle and you call wait_for_break next, while Pending returns a "  //$NON-NLS-1$
                    + "jobId for get_job_status. Default false polls and returns the report.")  //$NON-NLS-1$
            .build();
    }

    /**
     * Shared schema doc for the {@code timeout} parameter (also forwarded by the
     * {@code debug_yaxunit_tests} alias).
     *
     * <p>States the ceiling instead of hiding it. The parameter used to advertise an
     * unbounded window while the transport killed the call around 60 seconds, which made
     * every value above that actively misleading — the caller asked for a longer wait and
     * got LESS information, not more (#357).
     */
    static final String TIMEOUT_DESCRIPTION =
        "Maximum seconds the start call waits for its background job " //$NON-NLS-1$
            + "(default and maximum " + MAX_TIMEOUT_SECONDS + "; a larger value is clamped to it, " //$NON-NLS-1$ //$NON-NLS-2$
            + "because an MCP transport cuts the call at around 60s and a longer window would " //$NON-NLS-1$
            + "return a bare transport error instead of an answer). A job that finishes in this " //$NON-NLS-1$
            + "window returns the same report in this call. Otherwise the call returns Pending " //$NON-NLS-1$
            + "with jobId; poll get_job_status with that id. This value never limits the job's " //$NON-NLS-1$
            + "server-side lifetime."; //$NON-NLS-1$

    /**
     * Shared schema doc for the {@code externalInfobaseChanges} parameter (also forwarded by
     * the {@code debug_yaxunit_tests} alias and reused by {@code debug_launch} /
     * {@code update_database}).
     */
    static final String EXTERNAL_INFOBASE_CHANGES_DESCRIPTION =
        "How to answer EDT's blocking 'Infobase configuration changes' modal when the infobase was " //$NON-NLS-1$
            + "changed outside EDT (Designer, ibcmd, a CLI pipeline) since the last EDT interaction: " //$NON-NLS-1$
            + "'override' (default) keeps the project configuration and overwrites the infobase, " //$NON-NLS-1$
            + "'import' pulls the external changes into the PROJECT sources, 'cancel' aborts the update " //$NON-NLS-1$
            + "with an error. Omitted, the modal is still answered (with 'override'), so an " //$NON-NLS-1$
            + "unattended call never blocks on it."; //$NON-NLS-1$

    /**
     * Shared schema doc for the {@code updateScope} parameter (also forwarded by
     * the {@code debug_yaxunit_tests} alias).
     */
    static final String UPDATE_SCOPE_DESCRIPTION =
        "Which projects to rebuild+update before the run: 'all' (configuration + dependent " //$NON-NLS-1$
            + "extensions, default), 'configuration', or 'extension:<ProjectName>' " //$NON-NLS-1$
            + "(comma-separate several). Within that scope only the projects whose sources " //$NON-NLS-1$
            + "changed are recomputed, so a freshly edited extension's .cfe is regenerated and " //$NON-NLS-1$
            + "loaded into the infobase before the run. " //$NON-NLS-1$
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
     * The actionable message for a launch window in which this plugin auto-answered a blocking
     * modal that stopped the run — a standalone-server port conflict (the server never started) or
     * a cancelled external-changes dialog — or {@code null} when neither happened (or no window
     * was opened because the caller armed no policy).
     *
     * <p>This is the standalone-server case: {@code prepareForFreshLaunch} defers that
     * application's DB update to EDT's launch delegate, so the launch window is the only place the
     * conflict can appear - and without this the run would only fail later, generically.
     *
     * <p>Pure read: the window is CLOSED by the same {@code finally} that disarms the confirmer, so
     * a launch that throws cannot leave it registered.
     *
     * @param conflicts the window opened around the launch (may be {@code null})
     * @param policy the policy the call ran with (may be {@code null})
     * @return the message, or {@code null}
     */
    private static String declinedConflict(LaunchUpdateDialogAutoConfirmer.ConflictWatch conflicts,
        ExternalInfobaseChangesPolicy policy)
    {
        if (conflicts == null)
        {
            return null;
        }
        // The same launch window is where a standalone-server START fails on a busy port: that
        // modal is auto-cancelled (it would hang the run), and EDT then reports only a bare
        // cancellation. Checked first - it is the earlier cause, and nothing about the caller's
        // data was declined.
        if (conflicts.portConflicted())
        {
            return LaunchUpdateDialogAutoConfirmer.portConflictError(conflicts.portConflictDetail(),
                conflicts.portConflictReason());
        }
        // The external-changes branch is consulted ONLY when this call armed that matcher: the
        // window is now opened for the port matcher as well, and an unattributed cancel from a
        // concurrent operation must not be reported as this run's declined update.
        if (policy == null || !conflicts.cancelled())
        {
            return null;
        }
        return ExternalInfobaseChangesPolicy.declinedUpdateError(policy, conflicts.reason());
    }

    /** Closes a conflict window when one was opened; never throws. */
    private static void closeQuietly(LaunchUpdateDialogAutoConfirmer.ConflictWatch conflicts)
    {
        if (conflicts != null)
        {
            conflicts.close();
        }
    }

    /**
     * Terminates a launch this tool refuses to keep, best-effort: the caller is already reporting
     * the real failure, and a client left running against a not-updated infobase is worse than a
     * logged termination error.
     *
     * @param launch the launch to stop (may be {@code null})
     */
    private static void terminateQuietly(ILaunch launch)
    {
        if (launch == null)
        {
            return;
        }
        try
        {
            if (launch.canTerminate())
            {
                launch.terminate();
            }
        }
        catch (DebugException e)
        {
            Activator.logError("Failed to terminate a YAXUnit launch refused after a cancelled " //$NON-NLS-1$
                + "external-changes dialog", e); //$NON-NLS-1$
        }
    }

    /**
     * Reads the target project name straight off a launch configuration — the source that is
     * populated however the caller addressed the run (by name, or by project + application).
     *
     * @param config the launch configuration (may be {@code null})
     * @return the project name, or {@code null} when it cannot be read
     */
    private static String configProjectName(ILaunchConfiguration config)
    {
        if (config == null)
        {
            return null;
        }
        try
        {
            return config.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, (String)null);
        }
        catch (CoreException e) // NOSONAR a best-effort hint must never break the launch
        {
            return null;
        }
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
    public boolean connectsToInfobase()
    {
        // The pre-launch recompute + the launch itself connect to the infobase, both
        // possibly running in the background prep Job (issue #270).
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        return executeAs(params, NAME);
    }

    /** Shared implementation that preserves the actual surface which created the named job. */
    String executeAs(Map<String, String> params, String owningTool)
    {
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        // extensions/modules/tests/tags are declared as arrays but threaded internally as
        // comma-strings (run key, retry, buildParamsJson). extractArrayArgument accepts
        // BOTH a JSON array and a comma-separated string; re-join to the canonical comma
        // form so the downstream String plumbing is unchanged.
        String extensions = joinList(JsonUtils.extractArrayArgument(params, KEY_EXTENSIONS));
        String modules = joinList(JsonUtils.extractArrayArgument(params, KEY_MODULES));
        String tests = joinList(JsonUtils.extractArrayArgument(params, KEY_TESTS));
        String tags = joinList(JsonUtils.extractArrayArgument(params, KEY_TAGS));
        int timeout = clampTimeout(JsonUtils.extractIntArgument(params, "timeout", DEFAULT_TIMEOUT)); //$NON-NLS-1$
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, //$NON-NLS-1$
            "updateBeforeLaunch", true); //$NON-NLS-1$
        String updateScope = JsonUtils.extractStringArgument(params, "updateScope"); //$NON-NLS-1$
        String rawPolicy = JsonUtils.extractStringArgument(params, "externalInfobaseChanges"); //$NON-NLS-1$
        ExternalInfobaseChangesPolicy externalChanges = ExternalInfobaseChangesPolicy.parse(rawPolicy);
        if (externalChanges == null)
        {
            return ToolResult.error("Unknown externalInfobaseChanges value: '" + rawPolicy //$NON-NLS-1$
                + "'. Accepted values: " + ExternalInfobaseChangesPolicy.acceptedValues()).toJson(); //$NON-NLS-1$
        }
        String rawPortPolicy =
            JsonUtils.extractStringArgument(params, "standaloneServerPortConflict"); //$NON-NLS-1$
        StandaloneServerPortConflictPolicy portConflict =
            StandaloneServerPortConflictPolicy.parse(rawPortPolicy);
        if (portConflict == null)
        {
            return ToolResult.error("Unknown standaloneServerPortConflict value: '" + rawPortPolicy //$NON-NLS-1$
                + "'. Accepted values: " //$NON-NLS-1$
                + StandaloneServerPortConflictPolicy.acceptedValues()).toJson();
        }
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

        RunRequest request = new RunRequest(configName, projectName, applicationId, extensions,
            modules, tests, tags, timeout, updateBeforeLaunch, updateScope, externalChanges,
            portConflict, debug);
        return startOrAttach(request, owningTool);
    }

    /**
     * Starts one registry-owned run, or attaches this call to the job already executing the same
     * submission. The caller waits only for its clamped transport-safe window; the job continues
     * until it has collected the launch result.
     */
    private String startOrAttach(RunRequest request, String owningTool)
    {
        YaxunitJobCancellation cancellation =
            new YaxunitJobCancellation(RunYaxunitTestsTool::evict);
        return startOrAttachJob(owningTool, buildSubmissionKey(request), request.timeout,
            cancellation, (jobId, progress) ->
                runJobToCompletion(request, jobId, progress, cancellation));
    }

    /** Package-private seam over the production admission + synchronous-wait path. */
    String startOrAttachJob(String submissionKey, int waitSeconds, NamedJobWork work)
    {
        return startOrAttachJob(NAME, submissionKey, waitSeconds, null, work);
    }

    private String startOrAttachJob(String owningTool, String submissionKey, int waitSeconds,
        YaxunitJobCancellation cancellation, NamedJobWork work)
    {
        JobSnapshot started;
        try
        {
            synchronized (RUN_JOBS_LOCK)
            {
                purgeTerminalJobMappings();
                started = findRunningJob(SUBMISSION_JOBS.get(submissionKey));
                if (started == null)
                {
                    AtomicReference<String> jobId = new AtomicReference<>();
                    CountDownLatch jobIdReady = new CountDownLatch(1);
                    BackgroundJobs.CancellationCapability capability = cancellation != null
                        ? cancellation.capability() : null;
                    started = jobs.start(owningTool, BACKGROUND_JOB_TIMEOUT_MS,
                        "Accepted the YAXUnit request.", capability, progress -> { //$NON-NLS-1$
                            jobIdReady.await();
                            return work.run(jobId.get(), progress);
                        });
                    SUBMISSION_JOBS.put(submissionKey, started.getId());
                    jobId.set(started.getId());
                    jobIdReady.countDown();
                }
            }
        }
        catch (RejectedExecutionException e)
        {
            return ToolResult.error("Could not start " + owningTool //$NON-NLS-1$
                + " because the background-job " //$NON-NLS-1$
                + "registry is full or stopping: " + e.getMessage() + ". Poll existing jobs with " //$NON-NLS-1$ //$NON-NLS-2$
                + "get_job_status and retry, or restart EDT if the bundle is stopping.").toJson(); //$NON-NLS-1$
        }

        JobSnapshot latest = BackgroundJobPolling.await(jobs, started.getId(), waitSeconds);
        if (latest == null)
        {
            return ToolResult.error("The YAXUnit background job '" + started.getId() //$NON-NLS-1$
                + "' expired before this call could poll it. Start " + owningTool + " again to " //$NON-NLS-1$ //$NON-NLS-2$
                + "create a new job.").toJson(); //$NON-NLS-1$
        }
        return renderStartResult(latest);
    }

    @FunctionalInterface
    interface NamedJobWork
    {
        Object run(String jobId, ProgressReporter progress) throws Exception;
    }

    /** Runs the whole resolve -> prepare -> launch -> report pipeline inside one registry job. */
    private String runJobToCompletion(RunRequest request, String jobId,
        ProgressReporter progress, YaxunitJobCancellation cancellation)
    {
        CallState state = new CallState(progress);
        JobExecution execution = new JobExecution(jobId, progress, cancellation);
        InfobaseAuthDialogSuppressor.markActivityStart();
        try
        {
            while (true) // NOSONAR prep returns a bounded internal wait; the owning job keeps waiting
            {
                String result = runTests(request, state, Long.MAX_VALUE, execution);
                if (!isPendingResult(result))
                {
                    return result;
                }
                if (Thread.currentThread().isInterrupted())
                {
                    return ToolResult.error("The YAXUnit background job was interrupted while " //$NON-NLS-1$
                        + "waiting for pre-launch preparation. That preparation may already be " //$NON-NLS-1$
                        + "running separately in EDT; inspect EDT before starting " //$NON-NLS-1$
                        + "run_yaxunit_tests again.").toJson(); //$NON-NLS-1$
                }
                progress.add("Still working (phase: " + state.label() + ")."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        finally
        {
            InfobaseAuthDialogSuppressor.markActivityEnd();
        }
    }

    private String renderStartResult(JobSnapshot job)
    {
        if (job.getStatus() == BackgroundJobs.Status.DONE)
        {
            return renderStoredResult(job.getResult());
        }
        if (job.getStatus() == BackgroundJobs.Status.FAILED)
        {
            return ToolResult.error("YAXUnit background job '" + job.getId() + "' failed: " //$NON-NLS-1$ //$NON-NLS-2$
                + job.getErrorMessage() + ". Inspect its progress with get_job_status, fix the " //$NON-NLS-1$
                + "reported cause, and start " + job.getOwningTool() + " again.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (job.getStatus() == BackgroundJobs.Status.CANCELLED)
        {
            return BackgroundJobRenderer.render(job);
        }
        return "**Pending:** YAXUnit work continues in background job `" + job.getId() //$NON-NLS-1$
            + "`. Nothing was cancelled.\n\nPoll it with `get_job_status` using " //$NON-NLS-1$
            + "`jobId=\"" + job.getId() + "\"`; do not repeat the original arguments to " //$NON-NLS-1$ //$NON-NLS-2$
            + "address this run.\n\n" + BackgroundJobRenderer.render(job); //$NON-NLS-1$
    }

    /** Drops only unclaimed terminal/evicted mappings; retained results stay in the registry. */
    private void purgeTerminalJobMappings()
    {
        SUBMISSION_JOBS.entrySet().removeIf(entry -> findRunningJob(entry.getValue()) == null);
        RUN_JOBS.entrySet().removeIf(entry -> findRunningJob(entry.getValue()) == null);
    }

    JobSnapshot findRunningJob(String jobId)
    {
        if (jobId == null)
        {
            return null;
        }
        JobSnapshot snapshot = jobs.get(jobId);
        return snapshot != null && snapshot.isClaimed() ? snapshot : null;
    }

    /**
     * Claims the final run key for this job, or returns the running job that claimed it first.
     */
    private String claimRunKey(String runKey, String jobId)
    {
        synchronized (RUN_JOBS_LOCK)
        {
            purgeTerminalJobMappings();
            JobSnapshot existing = findRunningJob(RUN_JOBS.get(runKey));
            if (existing != null && !existing.getId().equals(jobId))
            {
                return existing.getId();
            }
            RUN_JOBS.put(runKey, jobId);
            return null;
        }
    }

    /** Mirrors an equivalent live job instead of launching the same run a second time. */
    String awaitExistingRun(String existingJobId, ProgressReporter progress)
    {
        progress.add("Attached to existing YAXUnit job " + existingJobId + "."); //$NON-NLS-1$ //$NON-NLS-2$
        while (true) // NOSONAR the referenced registry job supplies the terminal condition
        {
            JobSnapshot existing = jobs.await(existingJobId, 1_000L);
            if (Thread.currentThread().isInterrupted())
            {
                return ToolResult.error("This attachment was cancelled. The YAXUnit run it was " //$NON-NLS-1$
                    + "mirroring is a separate background job '" + existingJobId //$NON-NLS-1$
                    + "' that keeps running. Poll it with get_job_status using jobId '" //$NON-NLS-1$
                    + existingJobId + "'.").toJson(); //$NON-NLS-1$
            }
            if (existing == null)
            {
                return ToolResult.error("The equivalent YAXUnit job '" + existingJobId //$NON-NLS-1$
                    + "' expired while this job was attached. Start run_yaxunit_tests again.") //$NON-NLS-1$
                    .toJson();
            }
            if (existing.getStatus() == BackgroundJobs.Status.DONE)
            {
                return renderStoredResult(existing.getResult());
            }
            if (existing.getStatus() == BackgroundJobs.Status.FAILED)
            {
                return ToolResult.error("Equivalent YAXUnit job '" + existingJobId //$NON-NLS-1$
                    + "' failed: " + existing.getErrorMessage() //$NON-NLS-1$
                    + ". Inspect it with get_job_status and fix the reported cause before retrying.") //$NON-NLS-1$
                    .toJson();
            }
            if (existing.getStatus() == BackgroundJobs.Status.CANCELLED)
            {
                if (existing.getResult() != null)
                {
                    // A committed destructive stop stores the owner's honest partial outcome on
                    // the cancelled job. An attached caller must receive that same result instead
                    // of being told the launch never happened.
                    return renderStoredResult(existing.getResult());
                }
                return ToolResult.error("Equivalent YAXUnit job '" + existingJobId //$NON-NLS-1$
                    + "' was cancelled before launch. Start run_yaxunit_tests again if the tests " //$NON-NLS-1$
                    + "still need to run.").toJson(); //$NON-NLS-1$
            }
        }
    }

    private static String renderStoredResult(Object result)
    {
        return result instanceof String ? (String)result : GsonProvider.toJson(result);
    }

    private static boolean isPendingResult(String result)
    {
        return result != null && result.startsWith("**Pending:**"); //$NON-NLS-1$
    }

    /** Job-local access to the registry identity and commit handshake. */
    private final class JobExecution
    {
        final String jobId;
        final ProgressReporter progress;
        final YaxunitJobCancellation cancellation;

        JobExecution(String jobId, ProgressReporter progress,
            YaxunitJobCancellation cancellation)
        {
            this.jobId = jobId;
            this.progress = progress;
            this.cancellation = cancellation;
        }

        String claimOrExisting(String runKey)
        {
            return claimRunKey(runKey, jobId);
        }

        boolean tryCommit()
        {
            return progress.tryCommit();
        }

        void trackLaunch(ILaunch launch, Path reportDir)
        {
            if (cancellation != null)
            {
                cancellation.track(launch, reportDir);
            }
        }
    }

    /**
     * Clamps the caller's polling window into {@code [1, }{@link #MAX_TIMEOUT_SECONDS}{@code ]}.
     *
     * <p>Pure (test seam): the ceiling is the whole point of the parameter's contract, so it is
     * asserted directly rather than through a live launch.
     *
     * @param requested the raw {@code timeout} argument
     * @return the window this call will actually honour
     */
    static int clampTimeout(int requested)
    {
        if (requested < 1)
        {
            return 1;
        }
        return Math.min(requested, MAX_TIMEOUT_SECONDS);
    }

    /** Tracks the phase exposed through the owning background job's progress journal. */
    static final class CallState
    {
        private final AtomicReference<String> current = new AtomicReference<>(PHASE_RESOLVE);
        private final ProgressReporter progress;

        CallState()
        {
            this(null);
        }

        CallState(ProgressReporter progress)
        {
            this.progress = progress;
        }

        /** The stage the call has entered and not yet left. */
        void set(String phase)
        {
            String previous = current.getAndSet(phase);
            if (progress != null && phase != null && !phase.equals(previous))
            {
                progress.add("Phase: " + phase + "."); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        /** @return the current stage label, never {@code null} */
        String label()
        {
            String value = current.get();
            return value != null ? value : PHASE_RESOLVE;
        }
    }

    /**
     * Immutable carrier for the parsed {@code execute} arguments threaded through
     * {@link #runTests} and {@link #spawnOrReuseLaunch}. Pure value object — bundling
     * these keeps both methods below the 7-parameter limit without changing any value
     * (the resolved {@code projectName}/{@code applicationId} derived from the launch
     * config are kept as method locals in {@link #runTests}, never written back here).
     */
    static final class RunRequest
    {
        final String configName;
        final String projectName;
        final String applicationId;
        final String extensions;
        final String modules;
        final String tests;
        final String tags;
        final int timeout;
        final boolean updateBeforeLaunch;
        final String updateScope;
        final ExternalInfobaseChangesPolicy externalChanges;
        /** How EDT's standalone-server port-conflict modal is answered for this run. */
        final StandaloneServerPortConflictPolicy portConflict;
        final boolean debug;

        RunRequest(String configName, String projectName, String applicationId, String extensions, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                String modules, String tests, String tags, int timeout, boolean updateBeforeLaunch,
                String updateScope, ExternalInfobaseChangesPolicy externalChanges,
                StandaloneServerPortConflictPolicy portConflict, boolean debug)
        {
            this.configName = configName;
            this.projectName = projectName;
            this.applicationId = applicationId;
            this.extensions = extensions;
            this.modules = modules;
            this.tests = tests;
            this.tags = tags;
            this.timeout = timeout;
            this.updateBeforeLaunch = updateBeforeLaunch;
            this.updateScope = updateScope;
            this.externalChanges = externalChanges;
            this.portConflict = portConflict;
            this.debug = debug;
        }
    }

    /**
     * Main test execution flow.
     *
     * Registry-job execution with state tracking. Behaviour:
     * <ol>
     *   <li>Compute the stable runKey (see {@link #buildRunKey}) from everything that decides
     *       what the run executes.</li>
     *   <li>Claim that key for the owning background job, or mirror the live job that already
     *       claimed it.</li>
     *   <li>Reuse a tracked launch when present; otherwise prepare and start a new one.</li>
     *   <li>Poll until the launch terminates, parse the report, and retain it in the registry.</li>
     * </ol>
     *
     * A completed job is never selected by its run key: a new start after completion re-executes
     * the tests, while get_job_status can still fetch the completed result by job id.
     *
     * {@code debug=true} skips this polling lifecycle entirely and returns a launch handle at
     * once (see {@link #launchDebugMode}); {@code updateScope} narrows the pre-launch
     * auto-chain recompute+update (see {@link #UPDATE_SCOPE_DESCRIPTION}).
     *
     * The temp directory is never deleted in finally; the registry retains the parsed result and
     * a later fresh run cleans the stable directory before launching.
     */
    private String runTests(RunRequest req, CallState state, long deadlineMs, // NOSONAR reflective/form or transport god-method; further extraction deferred (reflective code)
        JobExecution execution)
    {
        // The owning registry job passes an effectively unbounded deadline and outlives the MCP
        // call. The parameter remains for the bounded preparation seam and defensive poll path;
        // only startOrAttach limits how long the transport waits.
        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }

            state.set(PHASE_RESOLVE);
            String earlyScopeError = validateUpdateScopeEarly(req.projectName, req.updateScope,
                req.updateBeforeLaunch);
            if (earlyScopeError != null)
            {
                return earlyScopeError;
            }

            LaunchContext context = resolveLaunchContext(launchManager, req.configName,
                req.projectName, req.applicationId);
            if (context.error != null)
            {
                return context.error;
            }
            ILaunchConfiguration matchingConfig = context.config;
            String projectName = context.projectName;
            String applicationId = context.applicationId;
            IProject project = context.project;
            IApplicationManager appManager = context.appManager;

            String runKey = buildRunKey(matchingConfig.getName(), projectName, applicationId, req);
            String jobRunKey = req.debug ? "debug:" + runKey : runKey; //$NON-NLS-1$
            String existingJobId = execution.claimOrExisting(jobRunKey);
            if (existingJobId != null)
            {
                return awaitExistingRun(existingJobId, execution.progress);
            }

            // DEBUG mode shares the whole setup above (resolve/validate/effective
            // project+app), including the live-job run-key guard, then spawns a DEBUG launch
            // and returns at once for wait_for_break — it does not poll for a test report.
            if (req.debug)
            {
                return launchDebugMode(matchingConfig, project, projectName, applicationId,
                    appManager, launchManager, req, deadlineMs, state, execution);
            }

            Path reportDir = stableReportDir(runKey);

            // If a launch is already running for this key, just poll it.
            ILaunch existing = ACTIVE_LAUNCHES.get(runKey);
            if (existing != null)
            {
                execution.trackLaunch(existing, reportDir);
                state.set(PHASE_RUN);
                return handleExistingLaunch(existing, reportDir, deadlineMs, runKey,
                        projectName, applicationId);
            }

            // Phase 1 (quick, JVM-wide): try to reuse an active launch for this runKey.
            ILaunch launch = reuseActiveLaunch(runKey);

            // Phase 2: pre-launch preparation (terminate stale launch + recompute
            // + DB update) runs in an Eclipse background Job under a 25-second wait slice.
            // The owning registry job waits on the latch repeatedly until preparation finishes;
            // only the original MCP call returns at its own timeout. One in-flight entry
            // per PREPARATION (see PrepRequest.prepKey: the project and the
            // application, plus the conflict policy and the rebuild scope)
            // prevents a second job for the same one from starting while it
            // is already running — and lets two calls that asked for DIFFERENT
            // preparations of the same infobase each get the one they asked for.
            //
            // Phase 3 (spawn) still runs under the per-key lock — this serialises
            // the spawn across both YAXUnit tools for the same IB and closes the
            // narrow window between workingCopy.launch() and registerOwnedLaunch
            // where a concurrent call could otherwise terminate this launch before
            // it's registered. Different (project, applicationId) pairs are unaffected.
            PreLaunchResult preLaunch = null;
            if (launch == null)
            {
                if (req.updateBeforeLaunch)
                {
                    // Preparation can terminate a client and update the infobase on a separate
                    // Eclipse Job. Once scheduled it cannot be recalled by interrupting this
                    // registry worker, so commit BEFORE that hand-off, not only before launch().
                    if (!execution.tryCommit())
                    {
                        return ToolResult.error("The YAXUnit job was cancelled before pre-launch " //$NON-NLS-1$
                            + "preparation started. Start run_yaxunit_tests again if the tests " //$NON-NLS-1$
                            + "still need to run.").toJson(); //$NON-NLS-1$
                    }
                    // What identifies this preparation is derived from the request that drives it
                    // (see PrepRequest.prepKey): a piggybacking call must never inherit a DIFFERENT
                    // caller's answer to the external-changes modal (one of the answers rewrites
                    // project sources) nor a DIFFERENT rebuild scope.
                    final PreLaunchResult[] resultHolder = new PreLaunchResult[1];
                    PrepRequest prepReq = new PrepRequest(projectName, launchManager, project,
                        applicationId, appManager, req.updateScope, req.externalChanges,
                        "YAXUnit pre-launch preparation for " + projectName); //$NON-NLS-1$

                    String pendingOrError = awaitPreparedOrPending(prepReq, resultHolder,
                        deadlineMs, state);
                    if (pendingOrError != null)
                    {
                        return pendingOrError;
                    }
                    preLaunch = resultHolder[0];
                }

                // Phase 3 (spawn-or-reuse) runs under the per-key lock — the spawn
                // body itself (re-check racer / cleanup+write-params+launch+register)
                // is extracted but stays INLINE under the SAME two locks here so the
                // lock scopes are byte-for-byte the inline behaviour.
                state.set(PHASE_SPAWN);
                synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
                {
                    synchronized (ACTIVE_LAUNCHES)
                    {
                        launch = spawnOrReuseLaunch(req, matchingConfig, applicationId,
                            runKey, reportDir, execution);
                    }
                }
            }

            execution.trackLaunch(launch, reportDir);
            state.set(PHASE_RUN);
            String pollResult = pollLaunch(launch, reportDir, deadlineMs, runKey,
                    projectName, applicationId);
            if (pollResult != null)
            {
                return prependPreLaunchInfo(preLaunch, pollResult);
            }

            // The registry job normally polls without a deadline. This remains a defensive
            // response for package-level seams and for a future bounded internal caller.
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
     * Milliseconds left before {@code deadlineMs}, never below zero.
     *
     * @param deadlineMs the absolute wall-clock deadline of the call
     * @return the milliseconds a further wait may use
     */
    static long remainingMillis(long deadlineMs)
    {
        return Math.max(0L, deadlineMs - System.currentTimeMillis());
    }

    /**
     * Phase 3 reuse-or-spawn body for the RUN path — extracted verbatim from the
     * inner {@code synchronized (ACTIVE_LAUNCHES)} block of {@link #runTests}. The
     * CALLER still holds BOTH locks ({@code lockFor(project, applicationId)} then
     * {@code ACTIVE_LAUNCHES}); this method runs entirely inside that scope, so the
     * mutating {@code workingCopy.launch} + {@code registerOwnedLaunch} +
     * {@code ACTIVE_LAUNCHES.put} sequence keeps the exact same serialisation it had
     * inline. Re-checks {@link #ACTIVE_LAUNCHES} for a launch a racing identical call
     * spawned during the auto-chain and reuses it; otherwise cleans the report dir,
     * writes the params file and spawns a fresh RUN-mode launch.
     *
     * @return the reused or freshly spawned launch (never {@code null})
     */
    private ILaunch spawnOrReuseLaunch(RunRequest req, ILaunchConfiguration matchingConfig,
            String applicationId, String runKey, Path reportDir, JobExecution execution)
        throws CoreException, IOException
    {
        ILaunch racer = ACTIVE_LAUNCHES.get(runKey);
        if (racer != null && !racer.isTerminated())
        {
            Activator.logInfo("Reusing YAXUnit launch spawned during auto-chain: runKey=" //$NON-NLS-1$
                + runKey);
            return racer;
        }

        cleanupTempDir(reportDir);
        Files.createDirectories(reportDir);
        Path paramsFile = reportDir.resolve("xUnitParams.json"); //$NON-NLS-1$
        String paramsJson = buildParamsJson(reportDir.resolve(VAL_JUNIT_XML).toString(), req);
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
        boolean[] armFlags = runPathArmFlags(req.updateBeforeLaunch);
        // The conflict matcher follows the same opt-out as the update matcher, and matters
        // most for a STANDALONE-SERVER application: there the pre-launch update is deferred
        // to EDT's launch delegate, so this window is the ONLY one covering that update.
        // Name the infobase so the conflict press stays ATTRIBUTABLE in this window: EDT states
        // it in the dialog message, and only a dialog naming an armed infobase may be answered
        // with a writing choice.
        // The project comes from the launch CONFIGURATION, not from req: a caller addressing the
        // run by launchConfigurationName leaves req.projectName null.
        ProjectContext launchCtx = ProjectContext.of(configProjectName(matchingConfig));
        String launchInfobase = LaunchLifecycleUtils.attributionInfobaseName(
            Activator.getDefault().getApplicationManager(),
            launchCtx.isOpen() ? launchCtx.project() : null, applicationId);
        // Armed even without a resolved name: the confirmer degrades such an arm to 'cancel', so
        // the modal is answered (no hang) but nothing is written on an unattributable dialog.
        ExternalInfobaseChangesPolicy launchPolicy = armFlags[0] ? req.externalChanges : null;
        // The port matcher is armed only for a STANDALONE-SERVER target: a file or client-server
        // application cannot raise that modal, and an arm held for the whole run would claim a
        // dialog belonging to a concurrent (or manual) server start.
        StandaloneServerPortConflictPolicy launchPortPolicy =
            DebugServerTargetSupport.isServerApplicationId(applicationId)
                ? req.portConflict : null;
        // For a STANDALONE-SERVER application this window is where the DB update actually
        // happens, so a conflict cancelled here must be reported with its cause - otherwise the run
        // just fails later with a generic "no junit.xml" and the caller never learns which knob
        // would have let it through.
        // Opened whenever EITHER matcher is armed. Gating it on launchPolicy alone lost the
        // port-conflict reason exactly when updateBeforeLaunch=false: the matcher is armed (it must
        // be, or the run hangs), the conflict is refused, and the run then failed with a generic
        // "no report" instead of the busy ports.
        LaunchUpdateDialogAutoConfirmer.ConflictWatch conflicts =
            launchPolicy == null && launchPortPolicy == null
                ? null
                : LaunchUpdateDialogAutoConfirmer.beginConflictWatch(launchInfobase);
        LaunchUpdateDialogAutoConfirmer.arm(armFlags[0], armFlags[1], armFlags[0], launchPolicy,
            launchInfobase, launchPortPolicy);
        ILaunch launch;
        try
        {
            // With no auto-chain this is the first irreversible hand-off. With the auto-chain
            // the job is already committed, and this idempotent check closes the launch race.
            if (!execution.tryCommit())
            {
                throw new CoreException(new Status(IStatus.CANCEL, Activator.PLUGIN_ID,
                    "The YAXUnit job was cancelled before the launch was handed to EDT.")); //$NON-NLS-1$
            }
            launch = StandaloneServerStateRecovery.launchWithRecovery(workingCopy,
                ILaunchManager.RUN_MODE, new NullProgressMonitor());
        }
        catch (CoreException ex)
        {
            // The cancel can also ABORT the launch instead of letting it return: the reason is
            // still in the window, and it explains the failure far better than the delegate's own
            // message does.
            String cancelled = declinedConflict(conflicts, launchPolicy);
            if (cancelled != null)
            {
                throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, cancelled, ex));
            }
            throw ex;
        }
        finally
        {
            LaunchUpdateDialogAutoConfirmer.disarm(armFlags[0], armFlags[1], armFlags[0], launchPolicy,
                launchInfobase, launchPortPolicy);
            // Closed HERE, not after the check below: a launch() that throws must not leave the
            // window registered in the confirmer for the rest of the session.
            closeQuietly(conflicts);
        }
        String declined = declinedConflict(conflicts, launchPolicy);
        if (declined != null)
        {
            // The client started, but the infobase it needs was never updated - it would run against
            // the old configuration. Stop it and report the cause instead of polling for a report
            // that cannot come. NOT registered as owned: that flag protects a launch from being
            // swept, which is the opposite of what this one needs.
            terminateQuietly(launch);
            throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, declined));
        }
        // Register BEFORE leaving the per-key lock so a concurrent
        // auto-chain on the same IB sees this launch as owned and
        // refuses to terminate it.
        LaunchLifecycleUtils.registerOwnedLaunch(launch);
        ACTIVE_LAUNCHES.put(runKey, launch);
        return launch;
    }

    /**
     * Resolved launch context produced by {@link #resolveLaunchContext}: either a
     * ready {@link ToolResult#error} JSON payload in {@link #error} (the caller
     * returns it verbatim) or the fully derived launch inputs (config, effective
     * project/application names, project handle and application manager).
     */
    private static final class LaunchContext
    {
        final String error;
        final ILaunchConfiguration config;
        final String projectName;
        final String applicationId;
        final IProject project;
        final IApplicationManager appManager;

        /** Failure result — only {@link #error} is meaningful. */
        static LaunchContext failure(String error)
        {
            return new LaunchContext(error, null, null, null, null, null);
        }

        /** Success result — {@link #error} is {@code null}. */
        static LaunchContext success(ILaunchConfiguration config, String projectName,
                String applicationId, IProject project, IApplicationManager appManager)
        {
            return new LaunchContext(null, config, projectName, applicationId, project, appManager);
        }

        private LaunchContext(String error, ILaunchConfiguration config, String projectName,
                String applicationId, IProject project, IApplicationManager appManager)
        {
            this.error = error;
            this.config = config;
            this.projectName = projectName;
            this.applicationId = applicationId;
            this.project = project;
            this.appManager = appManager;
        }
    }

    /**
     * Argument-validates {@code updateScope} as early as possible: when the caller
     * named the project directly a typo'd extension name fails fast with the
     * available names BEFORE launch-config resolution, so the validation is
     * reachable (and e2e-testable) without a launch configuration or a live
     * infobase. The same validation inside {@code prepareForFreshLaunch} stays as
     * the backstop for the by-name call style, where the project is only known
     * after the config resolves. Gated on {@code updateBeforeLaunch} because
     * {@code updateScope} only applies to the auto-chain; gated on the project
     * existing so an unknown project keeps its established no-config sentinel.
     *
     * @return a ready {@link ToolResult#error} JSON payload to return verbatim, or
     *         {@code null} when the scope is valid (or the guard does not apply)
     */
    private static String validateUpdateScopeEarly(String projectName, String updateScope,
            boolean updateBeforeLaunch)
    {
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
        return null;
    }

    /**
     * Resolves and validates the runtime-client launch configuration and derives
     * the effective project/application from it (read-only — no launch is spawned).
     * Mirrors the exact early-return errors the inline flow produced; on success the
     * returned {@link LaunchContext} carries the resolved config, the possibly
     * config-derived project/application names, the project handle and the
     * application manager (with the project's default application substituted for a
     * missing applicationId).
     *
     * @return a {@link LaunchContext} whose {@link LaunchContext#error} is non-{@code null}
     *         when the caller must return that JSON payload, otherwise a populated success
     */
    private LaunchContext resolveLaunchContext(ILaunchManager launchManager, String configName,
            String projectName, String applicationId)
    {
        ILaunchConfiguration matchingConfig = LaunchConfigUtils.resolveLaunchConfig(
                launchManager, configName, projectName, applicationId);
        if (matchingConfig == null)
        {
            boolean hasName = configName != null && !configName.isEmpty();
            return LaunchContext.failure(hasName
                ? ToolResult.error("Launch configuration not found: '" + configName + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Use list_configurations to see what's available.").toJson() //$NON-NLS-1$
                : buildNoConfigError(launchManager,
                    launchManager.getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID),
                    projectName, applicationId));
        }
        if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(matchingConfig)))
        {
            return LaunchContext.failure(ToolResult.error("Launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                + "' is not a runtime-client config — YAXUnit tests require one.").toJson()); //$NON-NLS-1$
        }

        return deriveLaunchContext(matchingConfig, projectName, applicationId);
    }

    /**
     * Second half of {@link #resolveLaunchContext} (extracted, behaviour-identical):
     * derives the effective project/application from the already-validated runtime-client
     * config, then runs the project-state / existence / open / application-manager /
     * application-exists gates in the SAME order, returning the first failure or a
     * populated success. Pure (read-only) — no launch is spawned.
     *
     * @return a {@link LaunchContext} whose {@link LaunchContext#error} is non-{@code null}
     *         when the caller must return that JSON payload, otherwise a populated success
     */
    private LaunchContext deriveLaunchContext(ILaunchConfiguration matchingConfig,
            String projectName, String applicationId)
    {
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
            return LaunchContext.failure(ToolResult.error("Launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                + "' has no project attribute set").toJson()); //$NON-NLS-1$
        }

        LaunchContext projectError = checkProjectGate(projectName);
        if (projectError != null)
        {
            return projectError;
        }
        IProject project = ProjectContext.of(projectName).project();

        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return LaunchContext.failure(
                ToolResult.error("IApplicationManager service is not available").toJson()); //$NON-NLS-1$
        }

        // A runtime-client launch config may carry no applicationId (it was not
        // bound to an application). Fall back to the project's default application
        // so updateBeforeLaunch has a target and the EDT launch delegate does not
        // pop its blocking "Update infobase before launch?" modal.
        applicationId = LaunchLifecycleUtils.resolveDefaultApplicationId(project, applicationId, appManager);

        if (applicationId != null && !applicationId.isEmpty())
        {
            String appError = validateApplicationExists(appManager, project, applicationId);
            if (appError != null)
            {
                return LaunchContext.failure(appError);
            }
        }

        return LaunchContext.success(matchingConfig, projectName, applicationId, project, appManager);
    }

    /**
     * Runs the project-readiness / existence / open gates for {@code projectName}
     * in the exact order {@link #deriveLaunchContext} previously ran them inline.
     *
     * @return a failure {@link LaunchContext} for the first gate that fails, or
     *         {@code null} when the project is ready, present and open
     */
    private static LaunchContext checkProjectGate(String projectName)
    {
        String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return LaunchContext.failure(ToolResult.error(notReadyError).toJson());
        }

        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return LaunchContext.failure(ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson());
        }

        if (!ctx.isOpen())
        {
            return LaunchContext.failure(ToolResult.error("Project is closed: " + projectName).toJson()); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Handles a run-key that already has a launch tracked in {@link #ACTIVE_LAUNCHES}:
     * if it terminated, evicts it and reads the report (or reports a missing one);
     * otherwise polls until {@code deadlineMs} and returns the parsed report or a
     * Pending message. Does NOT spawn a launch — it only reads results and updates
     * the {@link #ACTIVE_LAUNCHES} tracking map, exactly as the inline branch did.
     * <p>
     * The terminated remove + read runs under the per-IB lock so remove-then-read is
     * ATOMIC against a concurrent identical call that falls through to a fresh launch:
     * that path holds the SAME lock for cleanupTempDir(reportDir) + spawn, so it cannot
     * wipe reportDir between this thread's remove and read. With the remove OUTSIDE the
     * lock, a racer could observe ACTIVE_LAUNCHES already empty, take the lock first,
     * cleanupTempDir the fresh run's dir and delete junit.xml before this thread reads it
     * — a spurious "no JUnit XML" error. pollLaunch's sibling read guards the same way
     * (see there). remove(runKey, existing) is by identity — it never drops a newer launch
     * a racing identical call may have put under the same runKey since the get() above.
     * Worst case still degrades from a torn parse to a clean null; findJunitXml + readResults
     * are fast (ms), so contention is negligible.
     *
     * @return the Markdown report, a structured error, or a Pending message — always non-{@code null}
     */
    private String handleExistingLaunch(ILaunch existing, Path reportDir, long deadlineMs, String runKey, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            String projectName, String applicationId) throws InterruptedException
    {
        if (existing.isTerminated())
        {
            synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
            {
                ACTIVE_LAUNCHES.remove(runKey, existing);
                File junitXml = YaxunitReportUtils.findJunitXml(reportDir);
                if (junitXml != null)
                {
                    return YaxunitReportUtils.renderAndSave(junitXml);
                }
                return ToolResult.error("Previous launch finished but no JUnit XML found in " //$NON-NLS-1$
                        + reportDir + ". Make sure YAXUnit extension is installed.").toJson(); //$NON-NLS-1$
            }
        }
        String pollResult = pollLaunch(existing, reportDir, deadlineMs, runKey,
                projectName, applicationId);
        return pollResult != null ? pollResult : buildPendingMessage(reportDir);
    }

    /**
     * Phase 1 reuse check (read-only — no launch is spawned): under JVM-wide sync,
     * returns the active launch tracked for {@code runKey} when it is still running,
     * or {@code null} when there is none. A tracked-but-terminated entry is evicted
     * from {@link #ACTIVE_LAUNCHES} so the caller proceeds to a fresh launch.
     *
     * @return the reusable running launch, or {@code null} when none can be reused
     */
    private static ILaunch reuseActiveLaunch(String runKey)
    {
        synchronized (ACTIVE_LAUNCHES)
        {
            ILaunch concurrent = ACTIVE_LAUNCHES.get(runKey);
            if (concurrent != null && !concurrent.isTerminated())
            {
                Activator.logInfo("Reusing active YAXUnit launch for runKey=" + runKey); //$NON-NLS-1$
                return concurrent;
            }
            if (concurrent != null)
            {
                ACTIVE_LAUNCHES.remove(runKey);
            }
        }
        return null;
    }

    /**
     * DEBUG-mode launch (shared by {@code debug=true} and the deprecated
     * {@code debug_yaxunit_tests} alias): spawns the test run in DEBUG mode so
     * breakpoints fire, then returns a Markdown launch handle immediately. Unlike
     * the polling path it does NOT wait for {@code junit.xml}; the caller is
     * expected to call {@code wait_for_break} next. The report is still written to
     * {@code reportDir} once the run finishes.
     *
     * <p>The debug path ignores {@code timeout} for POLLING (there is nothing to poll — it
     * hands back a launch handle), but it still shares the call's deadline: the pre-launch
     * preparation it waits on is the same one, and a wait that outlives the transport is no
     * more useful here than on the polling path.
     */
    private String launchDebugMode(ILaunchConfiguration matchingConfig, IProject project, // NOSONAR the request IS the parameter object; the rest are the RESOLVED context, which the request deliberately does not carry
            String projectName, String applicationId, IApplicationManager appManager,
            ILaunchManager launchManager, RunRequest req, long deadlineMs, CallState state,
            JobExecution execution)
        throws IOException, CoreException
    {
        // Native path separators: YAXUnit builds file:// URIs and breaks on forward slashes on Windows.
        Path reportDir = Paths.get(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
            "edt-mcp-yaxunit-debug", projectName + "-" + System.currentTimeMillis() //$NON-NLS-1$ //$NON-NLS-2$
                + "-" + DEBUG_LAUNCH_COUNTER.getAndIncrement()); //$NON-NLS-1$
        Files.createDirectories(reportDir);
        Path paramsFile = reportDir.resolve("xUnitParams.json"); //$NON-NLS-1$
        Path junitFile = reportDir.resolve(VAL_JUNIT_XML);
        Files.write(paramsFile,
            buildParamsJson(junitFile.toString(), req).getBytes(StandardCharsets.UTF_8));

        // Suspend listener must be live before the launch starts producing events.
        DebugSessionRegistry.get().ensureListenerRegistered();

        // Phase 2 (debug path): prep runs in a background Job under a 25-second
        // budget, same as the RUN path. The sweep + launch (Phase 3) runs
        // synchronously after prep completes, under the per-key lock.
        PreLaunchResult preLaunch = null;
        if (req.updateBeforeLaunch)
        {
            // The shared preparation job can terminate a client and update the infobase after
            // this worker stops waiting, so cancellation must lose before it is scheduled.
            if (!execution.tryCommit())
            {
                return ToolResult.error("The YAXUnit debug job was cancelled before pre-launch " //$NON-NLS-1$
                    + "preparation started. Start it again if the debug launch is still needed.") //$NON-NLS-1$
                    .toJson();
            }
            final PreLaunchResult[] resultHolder = new PreLaunchResult[1];
            PrepRequest prepReq = new PrepRequest(projectName, launchManager, project,
                applicationId, appManager, req.updateScope, req.externalChanges,
                "YAXUnit debug pre-launch preparation for " + projectName); //$NON-NLS-1$

            String pendingOrError = awaitPreparedOrPending(prepReq, resultHolder,
                deadlineMs, state);
            if (pendingOrError != null)
            {
                return pendingOrError;
            }
            preLaunch = resultHolder[0];
        }

        state.set(PHASE_SPAWN);
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
            if (shouldSweepExistingClientSession(req.updateBeforeLaunch)
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
            boolean[] armFlags = debugPathArmFlags(req.updateBeforeLaunch);
            // Same as the RUN path: gated on the update opt-out, and the only armed window
            // around a standalone-server application's delegate-performed update.
            String launchInfobase = LaunchLifecycleUtils.attributionInfobaseName(appManager, project,
                applicationId);
            ExternalInfobaseChangesPolicy launchPolicy = armFlags[0] ? req.externalChanges : null;
            // The port matcher is armed only for a STANDALONE-SERVER target: a file or client-server
            // application cannot raise that modal, and an arm held for the whole run would claim a
            // dialog belonging to a concurrent (or manual) server start.
            StandaloneServerPortConflictPolicy launchPortPolicy =
                DebugServerTargetSupport.isServerApplicationId(applicationId)
                    ? req.portConflict : null;
            // Same as the RUN path: this is the only armed window around a standalone-server
            // application's delegate-performed update, so a cancel here is reported with its cause.
            // Same as the RUN path: the window covers the port matcher too, which is armed even
            // when this launch performs no DB update.
            LaunchUpdateDialogAutoConfirmer.ConflictWatch conflicts =
                launchPolicy == null && launchPortPolicy == null
                    ? null
                    : LaunchUpdateDialogAutoConfirmer.beginConflictWatch(launchInfobase);
            LaunchUpdateDialogAutoConfirmer.arm(armFlags[0], armFlags[1], armFlags[0], launchPolicy,
                launchInfobase, launchPortPolicy);
            ILaunch[] spawned = new ILaunch[1];
            try
            {
                // DEBUG launch attaches the debugger and starts code in the client. Interrupting
                // the registry worker cannot recall that hand-off, so commit immediately before
                // launch(). The owner-declared consent capability may later terminate the client,
                // but it cannot roll back infobase changes already made by the tests.
                if (!execution.tryCommit())
                {
                    return ToolResult.error("The YAXUnit debug job was cancelled before the " //$NON-NLS-1$
                        + "launch was handed to EDT. Start it again if it is still needed.") //$NON-NLS-1$
                        .toJson();
                }
                spawned[0] = StandaloneServerStateRecovery.launchWithRecovery(workingCopy,
                    ILaunchManager.DEBUG_MODE, new NullProgressMonitor());
            }
            catch (CoreException ex)
            {
                Activator.logError("Failed to launch YAXUnit in debug mode", ex); //$NON-NLS-1$
                // Same as the RUN path: a cancel that aborted the launch is reported with its own
                // cause, not with the delegate's generic message.
                String cancelled = declinedConflict(conflicts, launchPolicy);
                return ToolResult.error(cancelled != null ? cancelled
                    : "Launch failed: " + ex.getMessage()).toJson(); //$NON-NLS-1$
            }
            finally
            {
                LaunchUpdateDialogAutoConfirmer.disarm(armFlags[0], armFlags[1], armFlags[0],
                    launchPolicy, launchInfobase, launchPortPolicy);
                closeQuietly(conflicts);
            }
            String declined = declinedConflict(conflicts, launchPolicy);
            if (declined != null)
            {
                // Registered only AFTER this check: the owned flag protects a launch from being
                // swept, so marking one we are about to refuse would leave it live and protected
                // if the termination below cannot go through.
                terminateQuietly(spawned[0]);
                return ToolResult.error(declined).toJson();
            }
            LaunchLifecycleUtils.registerOwnedLaunch(spawned[0]);
            execution.trackLaunch(spawned[0], reportDir);
        }
        return buildDebugLaunchMarkdown(matchingConfig.getName(), projectName, applicationId,
            reportDir, junitFile, preLaunch);
    }

    /**
     * Shared in-flight / budget / pending block for both the RUN and DEBUG paths.
     *
     * <p>Acquires (or creates) a {@link PrepInFlight} entry for {@link PrepRequest#prepKey()}
     * via {@link java.util.concurrent.ConcurrentMap#computeIfAbsent}, ensuring only ONE
     * background Job is ever scheduled for a given preparation key
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
     * <p>The in-flight key is NOT a parameter: it is derived from the request itself via
     * {@link PrepRequest#prepKey()}, so no caller can guard a preparation with a key that
     * describes a different one. It used to be spelled out at each of the two call sites, which
     * is why the missing {@code updateScope} of #411 had to be found and fixed in BOTH.
     *
     * @param req              the pre-launch preparation pass-throughs (project name,
     *                         launch manager, project, application id, application
     *                         manager and updateScope forwarded to
     *                         {@link LaunchLifecycleUtils#prepareForFreshLaunch}, plus
     *                         the background Job display name)
     * @param resultHolder     single-element array; on success the
     *                         {@link PreLaunchResult} is stored in {@code [0]}
     * @param deadlineMs       the call's absolute deadline; the wait takes the SMALLER of the
     *                         preparation budget and what is left of it, so the preparation
     *                         budget can never push the call past the transport limit
     * @param state            receives the preparation's live phase label, so a
     *                         {@code Pending} produced anywhere after this point names what
     *                         the server is actually doing
     * @return a non-{@code null} string (a Pending or error message) when the
     *         caller must return immediately without proceeding to launch;
     *         {@code null} when preparation completed successfully and the caller
     *         may proceed
     */
    static String awaitPreparedOrPending(PrepRequest req, // NOSONAR package-private for the bounded-wait ratchet, which must drive this wait directly
            PreLaunchResult[] resultHolder, long deadlineMs, CallState state)
    {
        String prepKey = req.prepKey();
        // Stale-entry eviction loop: if an expired or done-with-error entry is in
        // the map, remove it atomically so the computeIfAbsent below creates a fresh
        // one. At most two iterations: one to detect + remove, one to proceed.
        String staleError = evictStalePrepEntry(prepKey);
        if (staleError != null)
        {
            return staleError;
        }

        // Atomically get-or-create.  Only the thread that wins
        // entry.started.compareAndSet(false, true) schedules the Job.
        PrepInFlight entry = LaunchLifecycleUtils.PREP_INFLIGHT.computeIfAbsent(
            prepKey, k -> new PrepInFlight(System.currentTimeMillis()));

        if (entry.started.compareAndSet(false, true))
        {
            // This thread won: create and schedule the background Job.
            schedulePrepJob(entry, req, resultHolder);
        }
        // else: another thread is already running the Job — just await the latch.
        // The live phase of THAT job is what any later Pending must report: this call may be a
        // repeat that joined a preparation started minutes ago, and naming the phase it was in
        // when this call arrived would be fiction.
        state.set(prepPhaseLabel(entry));

        boolean done;
        // The SMALLER of the preparation budget and what the call has left. The budget alone
        // is not a bound on the call: it is spent AFTER resolution, so honouring it in full
        // is what pushed a repeat call past the transport limit (#357).
        long waitMs = Math.min(LaunchLifecycleUtils.PRELAUNCH_BUDGET_MS, remainingMillis(deadlineMs));
        try
        {
            done = entry.latch.await(waitMs, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            done = entry.done;
        }
        if (!done)
        {
            // Budget expired — return Pending so the caller retries. The label is the
            // NAMESPACED one, the same string the description and guide enumerate: a caller
            // matching on `prep:recompute` must not have to know that some Pendings drop the
            // prefix and others keep it.
            String label = prepPhaseLabel(entry);
            state.set(label);
            return buildPrepPendingMessage(entry.elapsedSeconds(), label);
        }
        // Job completed within the budget.  Remove our entry (conditional so a
        // concurrent expired-entry replacement is not accidentally dropped).
        LaunchLifecycleUtils.PREP_INFLIGHT.remove(prepKey, entry);
        if (entry.error != null)
        {
            return prepFailedError(entry.error);
        }
        // resultHolder[0] already set by the Job; null for the concurrent-waiter path
        // (the original job-starter holds the result, but launch can proceed either way).
        return null; // success — caller may proceed to launch
    }

    /**
     * Immutable carrier for the pre-launch preparation pass-throughs (everything
     * the background {@link #schedulePrepJob} hands to
     * {@link LaunchLifecycleUtils#prepareForFreshLaunch}, plus the project name for
     * logging and the Job display name). Bundling these keeps
     * {@link #awaitPreparedOrPending} and {@link #schedulePrepJob} below the
     * 7-parameter limit without changing any value or order.
     *
     * <p>Package-private (not {@code private}) so the same-package
     * {@code runPrepJobBody} ratchet can construct a request, exactly as
     * {@code DebugLaunchTool.runLaunchJobBody} is a package-private seam.
     */
    static final class PrepRequest
    {
        final String projectName;
        final ILaunchManager launchManager;
        final IProject project;
        final String applicationId;
        final IApplicationManager appManager;
        final String updateScope;
        final ExternalInfobaseChangesPolicy externalChanges;
        final String jobName;

        PrepRequest(String projectName, ILaunchManager launchManager, IProject project, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                String applicationId, IApplicationManager appManager, String updateScope,
                ExternalInfobaseChangesPolicy externalChanges, String jobName)
        {
            this.projectName = projectName;
            this.launchManager = launchManager;
            this.project = project;
            this.applicationId = applicationId;
            this.appManager = appManager;
            this.updateScope = updateScope;
            this.externalChanges = externalChanges;
            this.jobName = jobName;
        }

        /**
         * The {@link LaunchLifecycleUtils#PREP_INFLIGHT} key for THIS preparation.
         *
         * <p>Derived from the request instead of chosen by the caller. That is the whole point:
         * the RUN path and the DEBUG path each spelled this string out themselves, and
         * {@code updateScope} was handed to the preparation without being part of the identity
         * of that preparation — in both copies — so two concurrent calls with different rebuild
         * scopes shared one job and the first to start won. Deriving the key here, from the
         * object the preparation consumes, means a call site can no longer state a DIFFERENT
         * project, application, policy or scope than the one it is about to prepare with. It is
         * not a proof that the whole request is captured: the fields below are excluded
         * deliberately, and their reasons are what carries that part. In particular {@code project}
         * is excluded because BOTH call sites derive it from {@code projectName} — the type does
         * not enforce that, so a future call site that passed an unrelated project would key it
         * under the wrong name.
         *
         * <p>Keyed: {@code projectName} + {@code applicationId} (via
         * {@link LaunchLifecycleUtils#prepKeyFor}, the same string as the per-infobase lock), the
         * external-changes policy — one of its answers rewrites project sources, so a piggybacking
         * call must never inherit a different caller's answer — and the
         * {@linkplain LaunchLifecycleUtils#canonicalUpdateScope canonical} update scope, which
         * decides which projects are rebuilt.
         *
         * <p>NOT keyed, deliberately:
         * <ul>
         *   <li>{@code jobName} — the ONLY field that differs between the RUN and the DEBUG call
         *       site. Keying it would give the two tools separate entries and run the preparation
         *       twice for one infobase, losing the single-in-flight guarantee this map exists
         *       for;</li>
         *   <li>{@code project} — it IS {@code ProjectContext.of(projectName).project()}, so
         *       {@code projectName} already keys it;</li>
         *   <li>{@code launchManager} / {@code appManager} — platform service handles, not inputs.
         *       {@code appManager} in particular is tracked through an OSGi {@code ServiceTracker}
         *       and may legitimately be a different object between two calls; keying it would
         *       start a duplicate preparation on a service rebind instead of joining the running
         *       one.</li>
         * </ul>
         *
         * <p>The test filter is not keyed either — it does not affect preparation at all.
         *
         * @return the in-flight preparation key; never {@code null}
         */
        String prepKey()
        {
            // NUL-joined exactly like prepKeyFor's own separator: neither a project nor an
            // application name can contain it, so the readable prefix can never be confused
            // with the framed suffix.
            return LaunchLifecycleUtils.prepKeyFor(projectName, applicationId)
                + '\u0000'
                + framed(externalChanges == null ? null : externalChanges.wireValue(),
                    LaunchLifecycleUtils.canonicalUpdateScope(updateScope));
        }
    }

    /**
     * Stale-entry eviction loop for {@link #awaitPreparedOrPending}: if an expired
     * or done-with-error {@link PrepInFlight} entry is in {@link LaunchLifecycleUtils#PREP_INFLIGHT},
     * removes it atomically so the caller's {@code computeIfAbsent} creates a fresh
     * one. At most two iterations: one to detect + remove, one to proceed.
     *
     * @return a ready {@link ToolResult#error} JSON payload when a done-with-error
     *         entry was surfaced (caller returns it verbatim), otherwise {@code null}
     *         once no stale entry blocks the path
     */
    private static String evictStalePrepEntry(String prepKey)
    {
        while (true) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            PrepInFlight existing = LaunchLifecycleUtils.PREP_INFLIGHT.get(prepKey);
            if (existing == null)
            {
                return null; // nothing stale — caller falls through to computeIfAbsent
            }
            if (existing.done && existing.error != null)
            {
                // Surface the error ONCE; clear the entry so the next call retries.
                if (LaunchLifecycleUtils.PREP_INFLIGHT.remove(prepKey, existing))
                {
                    return prepFailedError(existing.error);
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
            return null; // active (not done, not expired) — caller falls through
        }
    }

    /**
     * Creates and schedules the single background preparation Job for the entry the
     * calling thread won (the {@link PrepInFlight#started} CAS). The Job runs
     * {@link LaunchLifecycleUtils#prepareForFreshLaunch}, stores the
     * {@link PreLaunchResult} in {@code resultHolder[0]} and always counts down the
     * entry's latch — identical to the inline body it replaces.
     */
    static void schedulePrepJob(PrepInFlight entry, PrepRequest req, // NOSONAR package-private so the hand-over ratchet can drive the real scheduling site
            PreLaunchResult[] resultHolder)
    {
        final PrepInFlight jobEntry = entry;
        Job prepJob = new Job(req.jobName)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                return runPrepJobBody(jobEntry, req, resultHolder);
            }
        };
        prepJob.setPriority(Job.INTERACTIVE);
        try
        {
            McpJobs.schedule(prepJob);
        }
        finally
        {
            // In a finally, and never skipped: the entry needs the job to tell "still queued"
            // from "gone without running". A schedule() that threw produces exactly the second
            // case, and an entry that cannot report it is one nothing will ever replace.
            entry.trackScheduledJob(prepJob);
        }
    }

    /**
     * Body of the background pre-launch preparation {@link Job} that
     * {@link #schedulePrepJob} schedules — extracted as a package-private static seam so
     * the headless ratchet can exercise it directly (scheduling a real Job needs a live
     * workbench). Runs {@link LaunchLifecycleUtils#prepareForFreshLaunch}, stores the
     * {@link PreLaunchResult} in {@code resultHolder[0]} and always completes the entry
     * ({@code error}/{@code done}/latch) — identical to the inline body it replaces.
     *
     * <p><b>#230:</b> brackets the whole prep with the {@link InfobaseAuthDialogSuppressor}
     * in-flight counter. This Job is fire-and-forget: {@code execute()} only blocks on it
     * for {@code PRELAUNCH_BUDGET_MS} before returning a "pending" response, so
     * {@code tool.execute()} has already returned and stamped {@code lastActivityEndMillis}.
     * {@code prepareForFreshLaunch}'s db-update phase does the infobase-connecting
     * {@code appManager.update} — the SAME connect that raises the blocking "Configure
     * Infobase access Settings" auth dialog — and the recompute phase before it can
     * legitimately run for minutes on a real config, far past the trailing grace window.
     * The in-flight counter — not the short grace window — must therefore cover the whole
     * recompute+db-update, so a dialog raised by this connect (missing/wrong stored creds)
     * is still auto-cancelled instead of hanging the unattended call (mirrors
     * {@code DebugLaunchTool.runLaunchJobBody}). The counter is ALWAYS released in
     * {@code finally}, so it never leaks even on an {@link Error} escaping the prep.
     *
     * @param jobEntry the in-flight entry to complete (phase / error / done / latch)
     * @param req the immutable prep pass-throughs
     * @param resultHolder receives the {@link PreLaunchResult} in slot {@code [0]}
     * @return {@link Status#OK_STATUS} (the Job outcome is carried on {@code jobEntry},
     *         not on the returned status)
     */
    static IStatus runPrepJobBody(PrepInFlight jobEntry, PrepRequest req,
            PreLaunchResult[] resultHolder)
    {
        InfobaseAuthDialogSuppressor.markActivityStart();
        try
        {
            int terminateTimeout =
                LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds();
            // The phase is published BY the preparation as it enters each stage. It used to be
            // stamped here instead — "recompute" before the whole chain and "db-update" after it
            // had already finished — so every Pending said "recompute" no matter what the server
            // was doing, and "db-update" was only ever visible once there was nothing left to
            // wait for (#357).
            PreLaunchResult result = LaunchLifecycleUtils.prepareForFreshLaunch(
                req.launchManager, req.project, req.applicationId,
                req.appManager, terminateTimeout, req.updateScope, req.externalChanges,
                stage -> jobEntry.phase = stage);
            resultHolder[0] = result;
            if (!result.isOk())
            {
                jobEntry.error = result.getError();
            }
        }
        catch (Throwable e) // NOSONAR deliberate catch-all at a reflective/best-effort boundary
        {
            // Throwable, not Exception: an Error escaping the prep must still
            // surface as a prep failure — otherwise the retry call would see
            // done-without-error and proceed as if preparation succeeded.
            jobEntry.error = e.getMessage() != null ? e.getMessage()
                : e.getClass().getSimpleName();
            Activator.logError("Pre-launch preparation job failed: " + req.projectName, e); //$NON-NLS-1$
        }
        finally
        {
            InfobaseAuthDialogSuppressor.markActivityEnd();
            jobEntry.done = true;
            jobEntry.latch.countDown();
        }
        return Status.OK_STATUS;
    }

    /**
     * The call-level phase label for a preparation that is still running.
     *
     * <p>Pure (test seam). Namespaced with a {@code prep:} prefix so a reader can tell the
     * background preparation's own stage apart from the stages this call runs itself
     * ({@link #PHASE_RESOLVE} / {@link #PHASE_SPAWN} / {@link #PHASE_RUN}) — they overlap in
     * name ("recompute" happens inside the preparation, never in the call) and confusing the
     * two would point a waiting caller at the wrong thing.
     *
     * <p>With no entry to read, the fallback names the FIRST stage a preparation enters, for the
     * same reason {@code PrepInFlight.phase} is initialised to it: a label we cannot observe must
     * degrade to the stage every preparation begins with, never to one it may never reach. The
     * fallback used to be "recompute" — a stage the change gate skips entirely on an
     * unchanged scope (#310).
     *
     * @param entry the in-flight preparation (may be {@code null})
     * @return the namespaced label
     */
    static String prepPhaseLabel(PrepInFlight entry)
    {
        String inner = entry != null ? entry.phase : null;
        return "prep:" + (inner != null ? inner : LaunchLifecycleUtils.PHASE_TERMINATE); //$NON-NLS-1$
    }

    /** Shared "Pre-launch preparation failed" error payload (identical wording in both surfacing sites). */
    private static String prepFailedError(String error)
    {
        return ToolResult.error("Pre-launch preparation failed: " + error //$NON-NLS-1$
            + "\n\nIf the previous launch is stuck, call `terminate_launch` " //$NON-NLS-1$
            + "with `force=true` and retry. As a last resort, pass " //$NON-NLS-1$
            + "`updateBeforeLaunch=false` — but the EDT launch delegate may " //$NON-NLS-1$
            + "then pop a modal dialog that blocks the MCP call.").toJson(); //$NON-NLS-1$
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
     * Polls a launch until the absolute {@code deadline}. Returns the parsed Markdown report
     * if the launch finished, or {@code null} if still running (caller should return a Pending message).
     * <p>
     * The post-completion read ({@code ACTIVE_LAUNCHES.remove} +
     * {@link YaxunitReportUtils#findJunitXml(Path)} + report rendering) runs under the per-IB
     * lock, for the SAME reason the existing-terminated
     * and pending-fetch read paths do: a concurrent identical call that falls through to a fresh
     * launch holds the SAME lock for {@link #cleanupTempDir}(reportDir) + spawn, so it cannot wipe
     * {@code reportDir} mid-read. The {@code remove} is INSIDE the lock together with the read so
     * remove-then-read is atomic against that cleanup — otherwise a racer could observe the launch
     * already gone, fall through to a fresh run, and {@code cleanupTempDir} the directory between
     * this thread's remove and read. The poll loop itself is deliberately OUTSIDE the lock: holding
     * it across the {@link Thread#sleep} window would serialise the whole IB for the poll duration.
     * Worst case still degrades from a torn parse to a clean null.
     */
    private String pollLaunch(ILaunch launch, Path reportDir, long deadline, String runKey,
            String projectName, String applicationId)
            throws InterruptedException
    {
        // An ABSOLUTE deadline, not a second count: rounding the remainder down to whole seconds
        // threw away everything below a second, so a short window polled for exactly zero time
        // and answered Pending without ever having waited.
        while (!launch.isTerminated())
        {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
            {
                return null;
            }
            // Capped to the remainder: a full-interval sleep taken just before the deadline
            // overshoots it by up to a second on every call, which the whole-call bound cannot
            // absorb on a short window.
            Thread.sleep(Math.min(POLL_INTERVAL_MS, remaining));
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

            File junitXml = YaxunitReportUtils.findJunitXml(reportDir);
            if (junitXml == null)
            {
                return ToolResult.error("No JUnit XML report found in " + reportDir //$NON-NLS-1$
                        + ". Make sure YAXUnit extension is installed in the infobase " //$NON-NLS-1$
                        + "and test configuration is correct.").toJson(); //$NON-NLS-1$
            }

            return YaxunitReportUtils.renderAndSave(junitXml);
        }
    }

    /**
     * Validates that the given application exists for the project. Returns {@code null} when the
     * application resolves, or a JSON error string (identical to the previous inline handling) when
     * the application is not found or the lookup throws.
     */
    private String validateApplicationExists(IApplicationManager appManager, IProject project,
            String applicationId)
    {
        try
        {
            Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
            if (!appOpt.isPresent())
            {
                return ToolResult.error("Application not found: " + applicationId //$NON-NLS-1$
                        + ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
            }
            return null;
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error checking application", e); //$NON-NLS-1$
            return ToolResult.error("Failed to validate application: " + applicationId //$NON-NLS-1$
                    + " (" + e.getMessage() + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
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

    /** Defensive internal marker; the owning registry job normally polls to completion. */
    private String buildPendingMessage(Path reportDir)
    {
        return "**Pending:** YAXUnit tests are still running (phase: `" + PHASE_RUN + "`).\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "Report directory: `" + reportDir + "`\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "The owning background job continues waiting for the report.\n"; //$NON-NLS-1$
    }

    /**
     * Builds the bounded internal wait marker for pre-launch preparation. The registry job
     * consumes this marker and keeps waiting; it is not the public addressing contract.
     *
     * <p>The sentence under the phase says what the preparation is FOR, not what it is doing:
     * only the phase names that, and it names it per stage. It used to claim the server was
     * "rebuilding changed projects" in every Pending, which on an unchanged scope described work
     * the change gate had just decided to skip (#310).
     *
     * @param elapsedSeconds elapsed time since the background job started
     * @param phase the current preparation phase label (e.g. {@code "check-changes"} /
     *            {@code "recompute"} / {@code "db-update"})
     * @return a Markdown pending response matching the shape of
     *         {@link #buildPendingMessage(Path)}
     */
    static String buildPrepPendingMessage(long elapsedSeconds, String phase)
    {
        return "**Pending:** Pre-launch preparation is still running " //$NON-NLS-1$
            + "(phase: `" + (phase != null ? phase : prepPhaseLabel(null)) + "`" //$NON-NLS-1$ //$NON-NLS-2$
            + ", elapsed: " + elapsedSeconds + "s).\n\n" //$NON-NLS-1$ //$NON-NLS-2$
            + "The server is preparing the infobase in the background so the run starts " //$NON-NLS-1$
            + "against a fresh, up-to-date one; the phase above names the stage it had " //$NON-NLS-1$
            + "reached when this reply was built.\n"; //$NON-NLS-1$
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
     * Builds the stable run key that identifies one (launch target + filter + freshness
     * guarantee) combination.
     *
     * <p>The launch config name is the key root — stable across the
     * {@code (project, applicationId)} and {@code launchConfigurationName} call styles. Everything
     * that changes WHICH tests run, WHERE they run, or WHAT code they run against is folded into
     * the hash, because the key governs live-job reuse ({@link #RUN_JOBS}), active-launch reuse
     * ({@link #ACTIVE_LAUNCHES}) and the report directory ({@link #stableReportDir}), and those
     * reuse checks happen BEFORE preparation. A term
     * that is NOT in the key lets a run started under one request be polled by — and have its
     * report delivered to — a call that asked for a different one, and the answer is
     * indistinguishable from an honest fresh run.
     *
     * <p>Every term, and why it changes the outcome:
     * <ul>
     *   <li>the RESOLVED {@code applicationId} — the config name does NOT pin it: a named launch
     *       configuration is returned BY NAME, and a caller-supplied {@code applicationId} then
     *       overrides the config's own binding (see {@code deriveLaunchContext}) and is stamped
     *       onto the launch working copy. Two calls naming one config and two applications run
     *       against two infobases;</li>
     *   <li>the RESOLVED {@code projectName} — the project the pre-launch chain recompiles and
     *       locks on. (It is NOT the project the client launches with: that one comes from the
     *       launch configuration itself.) Keying the RESOLVED values rather than the request's is
     *       also what keeps the two call styles that reach one target sharing a run;</li>
     *   <li>{@code extensions} / {@code modules} / {@code tests} / {@code tags} — WHICH tests run;
     *       normalised through {@link #filterKeyPart} so two requests that generate a
     *       byte-identical {@code xUnitParams.json} filter are one run;</li>
     *   <li>the auto-chain — {@code updateBeforeLaunch} and {@code updateScope} as a single
     *       {@linkplain #preLaunchKeyPart term}: whether the extension is recomputed and the
     *       infobase updated before the run, and which projects that covers. A call asking for
     *       a refresh must never be answered by a run started without one: that report came
     *       from a possibly STALE {@code .cfe} and reads exactly like an honest one;</li>
     *   <li>{@code externalChanges} — how EDT's conflict modal is answered; one of the answers
     *       rewrites project sources. Kept UNCONDITIONAL, unlike the scope: its contract states
     *       no applicability condition, so there is nothing declared to lean on. Today the code
     *       happens to make it inert with the chain off (no preparation runs, and both arm paths
     *       null the policy), but that is an implementation detail no test or contract holds in
     *       place; narrowing the key on it would turn a future change into a silently wrong
     *       report, while keeping it costs at most one extra run.</li>
     * </ul>
     *
     * <p>Deliberately NOT keyed: {@code timeout} (the caller's waiting window — keying it would
     * drop a Pending report the moment a caller retried with a longer one) and {@code debug} (the
     * DEBUG path returns before any run key exists). The request's own
     * {@code configName}/{@code projectName}/{@code applicationId} are not keyed either; their
     * RESOLVED counterparts are, above.
     *
     * <p>Terms are {@linkplain #framed length-framed} rather than joined with a separator. Most
     * of them are caller-controlled strings, and a separator join is forgeable across any two
     * adjacent parts whenever one ENDS with the separator and the next BEGINS with it: under
     * the previous literal {@code "|"} joiner, {@code extensions="a|", modules="b"} and
     * {@code extensions="a", modules="|b"} were the same key. A collision here is a false HIT —
     * the quietest failure this method has, since it is served as a successful report. With a
     * length there is nothing to impersonate. (The digest is then truncated to 48 bits, so the
     * key is not injective in the cryptographic sense; the framing removes what a caller can hit
     * by accident, which is the threat model — a caller can always ask for another run directly,
     * so there is no boundary to attack.)
     *
     * <p>Package-private and static so a test can pin the PRODUCTION formula: this is the exact
     * method the run path calls, not a reconstruction of it. It takes the whole {@link RunRequest}
     * rather than its individual fields on purpose — a call site listing them could silently omit
     * one, and an omitted term fails as a SHARED run identity. The resolved trio is passed
     * separately because the request deliberately does not carry the resolved values.
     *
     * @param configName resolved launch configuration name
     * @param projectName the RESOLVED project name the run targets
     * @param applicationId the RESOLVED application id the run targets (may be empty)
     * @param req the request whose filter, freshness guarantee and conflict policy identify the run
     * @return the run key
     */
    static String buildRunKey(String configName, String projectName, String applicationId,
            RunRequest req)
    {
        return configName + ":" //$NON-NLS-1$
            + sha1(framed(projectName, applicationId, filterKeyPart(req.extensions),
                filterKeyPart(req.modules), filterKeyPart(req.tests), filterKeyPart(req.tags),
                req.externalChanges.wireValue(), portConflictKeyPart(req),
                preLaunchKeyPart(req)));
    }

    /**
     * Pre-resolution identity used only to close the admission window before a final run key can
     * be derived. It is never exposed to callers and never addresses a completed run. Timeout is
     * deliberately excluded because it controls only how long this call waits for the same job.
     */
    static String buildSubmissionKey(RunRequest req)
    {
        return (req.debug ? "debug:" : "run:") //$NON-NLS-1$ //$NON-NLS-2$
            + sha1(framed(req.configName, req.projectName, req.applicationId,
                filterKeyPart(req.extensions), filterKeyPart(req.modules),
                filterKeyPart(req.tests), filterKeyPart(req.tags),
                req.externalChanges.wireValue(), portConflictKeyPart(req),
                preLaunchKeyPart(req)));
    }

    /**
     * The port-conflict term of both keys: how this request answers EDT's standalone-server
     * port-conflict modal.
     *
     * <p>Keyed for the same reason {@code externalChanges} is: it is a decision the CALLER made
     * about what may happen to their stand, and the two answers are not interchangeable — one
     * refuses, the other lets EDT rewrite the server configuration. Attaching a caller who asked
     * for "reassign" to a run started under "cancel" would silently drop their choice and hand
     * back a report produced under a decision they did not make.
     *
     * <p>Null-tolerant: a request built without a policy reads as the default.
     */
    private static String portConflictKeyPart(RunRequest req)
    {
        return (req.portConflict == null
            ? StandaloneServerPortConflictPolicy.DEFAULT : req.portConflict).wireValue();
    }

    /**
     * The pre-launch auto-chain term of the run key: WHETHER the run refreshes what it executes,
     * and HOW MUCH of it.
     *
     * <p>{@code updateBeforeLaunch} and {@code updateScope} are ONE decision, so they are one
     * term. Writing them as two — a boolean plus a scope that empties itself when the boolean is
     * false — makes each of them redundant with the other: dropping either one leaves the key
     * still telling the two cases apart, so neither can be shown to be load-bearing and a
     * regression in either is invisible to a test. (Measured, not assumed: with both terms
     * present, deleting the boolean from the formula broke nothing.)
     *
     * <p>The scope is folded in only when the chain is on, because the parameter's own contract
     * says so — "Only applies when updateBeforeLaunch=true", see
     * {@link #UPDATE_SCOPE_DESCRIPTION} — and the implementation agrees: with the chain off no
     * preparation is scheduled at all, on either the RUN or the DEBUG path. Keying a scope that
     * applies to nothing would split requests the tool itself declares identical, and every such
     * call would re-run the whole suite instead of joining the run already in flight.
     */
    private static String preLaunchKeyPart(RunRequest req)
    {
        return req.updateBeforeLaunch
            ? "chain:" + LaunchLifecycleUtils.canonicalUpdateScope(req.updateScope) //$NON-NLS-1$
            : "no-chain"; //$NON-NLS-1$
    }

    /**
     * The key term for one filter family: empty when the family is absent, otherwise {@code "+"}
     * plus the family exactly as {@link #buildParamsJson} would write it.
     *
     * <p>Two requests get the same term precisely when the generated {@code xUnitParams.json}
     * carries the same filter for that family — no more and no less. That needs both halves:
     * {@link #splitToList} (trim, drop empty tokens) because {@code " smoke "} and {@code "smoke"}
     * generate the same array and must be one run, and the {@code "+"} presence marker because
     * {@code null} (family omitted) and {@code ","} (family written as an empty array) generate
     * DIFFERENT files.
     *
     * <p>The framework very likely treats those two files alike ({@link #buildParamsJson} explains
     * why an empty list is not a filter), so the marker probably costs one re-run for a caller who
     * passes a filter of nothing but separators. The identity deliberately stops at what this file
     * can prove — the bytes it writes — rather than at an assumption about the framework: being
     * wrong that way costs a re-run, being wrong the other way serves the wrong report. The
     * previous formula kept these two apart as well, so nothing is lost either.
     */
    private static String filterKeyPart(String value)
    {
        if (value == null || value.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        return "+" + String.join(",", splitToList(value)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Length-frames the parts of a key so the concatenation is injective for arbitrary content.
     *
     * <p>Each part is written as {@code <length>:<value>} (netstring framing), so no value can
     * impersonate a separator: with a plain joiner, a part ENDING in that joiner and the next
     * part BEGINNING with it produce one key — and a key collision here is served as a
     * successful, wrong report. The framing is injective over the values it frames; {@code null}
     * is normalised to the empty string FIRST, deliberately, because every caller of this method
     * treats an absent value and an empty one as the same thing.
     */
    private static String framed(String... parts)
    {
        StringBuilder sb = new StringBuilder();
        for (String part : parts)
        {
            String value = safe(part);
            sb.append(value.length()).append(':').append(value);
        }
        return sb.toString();
    }

    /**
     * Computes a short hex SHA-1 hash of the framed run-key identity (target, filter,
     * conflict policy and pre-launch chain) so the runKey stays bounded in length.
     *
     * <p>Six digest bytes: the key is therefore not injective, and {@link #buildRunKey}
     * explains why that is accepted.
     */
    private static String sha1(String input)
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

    private static String safe(String s)
    {
        return s == null ? "" : s; //$NON-NLS-1$
    }

    /**
     * Builds the xUnitParams.json content.
     *
     * <p>Each filter family is written only when it is non-empty. That is not a micro-optimization:
     * YAXUnit decides whether a family filters at all by whether its list is filled
     * ({@code ЗначениеЗаполнено}), so an empty list and an absent key mean the same thing to the
     * framework — "do not filter on this" — and writing an empty array would be a promise the
     * framework does not keep.
     *
     * <p>"Non-empty" is decided on the raw comma-string, not on the parsed list, so one input
     * does still write {@code []}: a value made only of separators ({@code ","}). Left as is —
     * the file it produces is what {@link #filterKeyPart} keys on, and the two must agree.
     *
     * <p>Package-private and static so the generated filter can be asserted directly; this is the
     * method both the RUN and the DEBUG path call, and it reads the filter families off the request
     * so neither path can pass a subset of them.
     *
     * @param reportPath absolute path of the JUnit XML the run must write
     * @param req the request carrying the filter families
     * @return the serialized parameters file content
     */
    static String buildParamsJson(String reportPath, RunRequest req)
    {
        String extensions = req.extensions;
        String modules = req.modules;
        String tests = req.tests;
        String tags = req.tags;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("reportPath", reportPath); //$NON-NLS-1$
        params.put("reportFormat", "jUnit"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("closeAfterTests", true); //$NON-NLS-1$

        Map<String, Object> filter = new LinkedHashMap<>();
        boolean hasFilter = false;

        if (extensions != null && !extensions.isEmpty())
        {
            filter.put(KEY_EXTENSIONS, splitToList(extensions));
            hasFilter = true;
        }

        if (modules != null && !modules.isEmpty())
        {
            filter.put(KEY_MODULES, splitToList(modules));
            hasFilter = true;
        }

        if (tests != null && !tests.isEmpty())
        {
            filter.put(KEY_TESTS, splitToList(tests));
            hasFilter = true;
        }

        if (tags != null && !tags.isEmpty())
        {
            filter.put(KEY_TAGS, splitToList(tags));
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
    private static List<String> splitToList(String value)
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
     * Recursively deletes a temp directory if it exists. Silent if missing.
     */
    private void cleanupTempDir(Path tempDir)
    {
        if (tempDir == null || !Files.exists(tempDir))
        {
            return;
        }
        // try-with-resources releases the file-system handle held by Files.walk's stream; // NOSONAR explanatory comment, not commented-out code
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

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
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.ExternalInfobaseChangesPolicy;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
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

    /** Input/filter param: extension names to filter tests by extension. */
    private static final String KEY_EXTENSIONS = "extensions"; //$NON-NLS-1$

    /** Input/filter param: module names to filter tests. */
    private static final String KEY_MODULES = "modules"; //$NON-NLS-1$

    /** Input/filter param: test names in Module.Method format. */
    private static final String KEY_TESTS = "tests"; //$NON-NLS-1$

    /** JUnit XML report file name written by the YAXUnit run. */
    private static final String VAL_JUNIT_XML = "junit.xml"; //$NON-NLS-1$

    /**
     * Hard ceiling (seconds) on how long ONE call may hold the MCP transport open, and the
     * default polling window.
     *
     * <p>An MCP client cuts a call at its own transport timeout — around 60 seconds for the
     * clients this tool is driven by — while the pre-launch preparation of a real
     * configuration runs for minutes. A polling window above that ceiling is therefore not a
     * longer wait, it is a wait the caller never sees the end of: the call dies on the wire
     * with a bare "operation timed out", carrying neither the phase nor the reason (#357).
     * Every wait in this tool is bounded by this ceiling so the answer always arrives while
     * someone is still listening — {@code Pending} with the phase when the work is not done,
     * which is strictly more information than a transport error.
     *
     * <p>A caller may ask for LESS (a short probe), never for more: a larger {@code timeout}
     * is clamped, and the schema says so rather than advertising a window the transport
     * cannot deliver.
     */
    static final int MAX_TIMEOUT_SECONDS = 45;

    private static final int DEFAULT_TIMEOUT = MAX_TIMEOUT_SECONDS;
    private static final int POLL_INTERVAL_MS = 1000;

    /**
     * Time (ms) held back from the INNER deadline so the backstop cannot steal the answer.
     *
     * <p>The deadline threaded through the call is what normally answers — it returns a
     * {@code Pending} naming the phase. The backstop exists for the case that cannot: a
     * platform call that blocks on the tool thread and never returns to look at any deadline
     * (the pre-launch reads and the spawn both reach EDT services that a running recompute or
     * an unanswered modal can hold for minutes). The two must not race, or a merely-slow run
     * would get the generic "did not answer" message instead of its real phase.
     *
     * <p>The reserve therefore sits INSIDE the caller's window, not on top of it. Adding it to
     * the backstop instead made the public parameter stop bounding the call: {@code timeout: 1}
     * held the request for about six seconds, and a client whose transport is shorter than ours
     * still saw the bare transport error this whole change exists to remove. A parameter named
     * as the bound of the call has to be the bound of the call.
     */
    private static final long CALL_BACKSTOP_RESERVE_MS = 5_000L;

    /**
     * The smallest fraction of the caller's window that is left to the work itself, as a
     * divisor: the reserve never takes more than {@code 1/RESERVE_MAX_SHARE} of it.
     *
     * <p>Without this a short window would go negative ({@code timeout: 1} minus five seconds),
     * and a healthy quick run would answer "did not finish" the moment it started — a false
     * "still working" on a call that was doing fine, which is worse than waiting a second longer.
     */
    private static final long RESERVE_MAX_SHARE = 5L;

    /** Phase label while the launch configuration and its application are being resolved. */
    private static final String PHASE_RESOLVE = "resolve"; //$NON-NLS-1$

    /** Phase label while the test launch is being spawned. */
    private static final String PHASE_SPAWN = "spawn"; //$NON-NLS-1$

    /** Phase label while the spawned launch is running the tests. */
    private static final String PHASE_RUN = "run"; //$NON-NLS-1$

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
               + "The whole call is bounded by `timeout` (default and maximum " + MAX_TIMEOUT_SECONDS //$NON-NLS-1$
               + "s, larger values are clamped) so it always answers before an MCP transport " //$NON-NLS-1$
               + "cuts it: it returns the report, an error, or **Pending** naming the current " //$NON-NLS-1$
               + "phase (resolve / prep:terminate / prep:recompute / prep:db-update / spawn / run) — " //$NON-NLS-1$
               + "call again with identical arguments to keep waiting; nothing is terminated. " //$NON-NLS-1$
               + "A phase that stops changing is the server's only signal — it means either a " //$NON-NLS-1$
               + "legitimately long stage or one blocked on a modal dialog in EDT, and the tool " //$NON-NLS-1$
               + "cannot tell them apart: look at EDT before waiting indefinitely. " //$NON-NLS-1$
               + "Pass `debug=true` to instead launch in DEBUG mode (breakpoints fire) and return at once " //$NON-NLS-1$
               + "so you can call wait_for_break. " //$NON-NLS-1$
               + "The pre-launch auto-chain (updateBeforeLaunch=true, default) recomputes only projects " //$NON-NLS-1$
               + "whose sources changed since their last prepared run; that mark survives an EDT " //$NON-NLS-1$
               + "restart, so an unchanged project is not recomputed at all. " //$NON-NLS-1$
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
            .stringArrayProperty(KEY_EXTENSIONS, "Extension names to filter tests (array; a comma-separated string is also accepted).") //$NON-NLS-1$
            .stringArrayProperty(KEY_MODULES, "Module names to filter tests (array; a comma-separated string is also accepted).") //$NON-NLS-1$
            .stringArrayProperty(KEY_TESTS, "Test names in Module.Method format (array; a comma-separated string is also accepted).") //$NON-NLS-1$
            .integerProperty("timeout", TIMEOUT_DESCRIPTION) //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Auto-chain (default: true): force-recompute the project + its extensions, terminate a " //$NON-NLS-1$
                    + "live client and run a silent DB update first, so a freshly edited extension runs " //$NON-NLS-1$
                    + "fresh (not stale), auto-answering the platform's update dialogs. This makes a " //$NON-NLS-1$
                    + "blocking dialog unlikely, NOT impossible: a dialog EDT raises outside the tool's " //$NON-NLS-1$
                    + "own windows still waits for a human, and the tool reports it as a Pending whose " //$NON-NLS-1$
                    + "phase stops changing. false: legacy delegate behaviour — no client sweep, no " //$NON-NLS-1$
                    + "auto-confirmed update dialog; platform dialogs may appear and block. Results are " //$NON-NLS-1$
                    + "never served from a cache — a completed run is re-executed on the next identical " //$NON-NLS-1$
                    + "call regardless of this flag.") //$NON-NLS-1$
            .stringProperty("updateScope", UPDATE_SCOPE_DESCRIPTION) //$NON-NLS-1$
            .stringProperty("externalInfobaseChanges", //$NON-NLS-1$
                EXTERNAL_INFOBASE_CHANGES_DESCRIPTION) //$NON-NLS-1$
            .booleanProperty("debug", //$NON-NLS-1$
                "Default false: poll and return the report. true: launch in DEBUG mode so breakpoints " //$NON-NLS-1$
                    + "fire, return a launch handle as soon as it is spawned and call wait_for_break " //$NON-NLS-1$
                    + "next. It does not POLL, so `timeout` is not a waiting window here — but the " //$NON-NLS-1$
                    + "call is still bounded by it: a pre-launch preparation longer than the window " //$NON-NLS-1$
                    + "returns Pending instead of the handle, and the next identical call picks up " //$NON-NLS-1$
                    + "where it left off.") //$NON-NLS-1$
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
        "Wall-clock window in seconds for the WHOLE call, not just the polling step " //$NON-NLS-1$
            + "(default and maximum " + MAX_TIMEOUT_SECONDS + "; a larger value is clamped to it, " //$NON-NLS-1$ //$NON-NLS-2$
            + "because an MCP transport cuts the call at around 60s and a longer window would " //$NON-NLS-1$
            + "return a bare transport error instead of an answer). The call returns WITHIN this " //$NON-NLS-1$
            + "window: at least 80% of it is available to the work, the remainder is reserved so " //$NON-NLS-1$
            + "the answer can be assembled rather than cut off. On expiry the tool returns " //$NON-NLS-1$
            + "Pending with the current phase — or, if the work never started at all, an explicit " //$NON-NLS-1$
            + "error saying so (that one case can take up to half a second longer, while the " //$NON-NLS-1$
            + "server establishes the work never began); call again with the same arguments to " //$NON-NLS-1$
            + "keep waiting."; //$NON-NLS-1$

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
     * The actionable message for a launch window whose external-changes dialog was cancelled, or
     * {@code null} when nothing was cancelled (or no window was opened because the caller armed no
     * policy).
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
        if (conflicts == null || !conflicts.cancelled())
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
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        // extensions/modules/tests are declared as arrays but threaded internally as
        // comma-strings (run key, retry, buildParamsJson). extractArrayArgument accepts
        // BOTH a JSON array and a comma-separated string; re-join to the canonical comma
        // form so the downstream String plumbing is unchanged.
        String extensions = joinList(JsonUtils.extractArrayArgument(params, KEY_EXTENSIONS));
        String modules = joinList(JsonUtils.extractArrayArgument(params, KEY_MODULES));
        String tests = joinList(JsonUtils.extractArrayArgument(params, KEY_TESTS));
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
            modules, tests, timeout, updateBeforeLaunch, updateScope, externalChanges, debug);
        return runBounded(request);
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

    /**
     * The wall clock the backstop is given — EXACTLY the caller's (clamped) window.
     *
     * <p>Pure (test seam). This is the number that makes {@code timeout} mean what its name
     * says, so it is asserted directly rather than inferred from a live run.
     *
     * <p>One documented exception, from {@link BoundedJob}: a job the deadline catches while it
     * is still QUEUED costs up to a further half second while the scheduler establishes that it
     * never started. That path returns the "did not start" error, not a {@code Pending}.
     *
     * @param timeoutSeconds the clamped window
     * @return the backstop's budget in milliseconds
     */
    static long backstopBudgetMs(int timeoutSeconds)
    {
        return timeoutSeconds * 1000L;
    }

    /**
     * The window the call's own deadline races against: the caller's window minus the reserve.
     *
     * <p>Pure (test seam). Strictly smaller than {@link #backstopBudgetMs(int)} for every
     * accepted {@code timeout}, so the inner flow always gets to answer first with its real
     * phase, and always positive, so a short window still buys real working time.
     *
     * @param timeoutSeconds the clamped window
     * @return the inner deadline's budget in milliseconds
     */
    static long innerWindowMs(int timeoutSeconds)
    {
        long requestedMs = backstopBudgetMs(timeoutSeconds);
        // Proportional on short windows: a flat five seconds would swallow them whole.
        long reserveMs = Math.min(CALL_BACKSTOP_RESERVE_MS, requestedMs / RESERVE_MAX_SHARE);
        return requestedMs - reserveMs;
    }

    /**
     * Runs {@link #runTests} under a hard wall-clock bound, so the call answers while the MCP
     * transport is still listening no matter what the platform does.
     *
     * <p>{@link #runTests} already carries the caller's deadline and returns a {@code Pending}
     * of its own accord; this is the layer beneath that, for the failure mode a deadline cannot
     * cover — work that blocks on the tool thread and never reaches the next deadline check.
     * That is not hypothetical here: the pre-launch resolution and the spawn both call into EDT
     * services (the application manager, the per-infobase launch monitor) that a running
     * recompute or an unanswered modal holds for minutes, which is exactly how identical repeat
     * calls came back as a bare transport timeout with no phase and no reason (#357).
     *
     * <p>A timed-out job keeps running — nothing here cancels the preparation or the launch, and
     * the returned {@code Pending} says so. The next identical call re-attaches to the same
     * in-flight work.
     *
     * @param request the parsed call arguments
     * @return the report, a structured error, or a {@code Pending} naming the phase
     */
    private String runBounded(RunRequest request)
    {
        CallState state = new CallState();
        String[] resultHolder = new String[1];
        // Anchored to the CALL, not to the job body: the job can sit in EDT's scheduler for a
        // while before it runs, and a deadline started at that point would land AFTER the
        // backstop's — handing the backstop a race it is supposed to lose.
        long innerDeadlineMs = System.currentTimeMillis() + innerWindowMs(request.timeout);
        BoundedJob.Result bounded = BoundedJob.run("run_yaxunit_tests: " //$NON-NLS-1$
            + (request.configName != null ? request.configName : String.valueOf(request.projectName)),
            backstopBudgetMs(request.timeout), monitor -> {
                // The suppressor's in-flight window is scoped to execute(); this job can outlive
                // it, and it reaches the same infobase-connecting calls, so it marks its own.
                InfobaseAuthDialogSuppressor.markActivityStart();
                try
                {
                    // Write the result BEFORE claiming it: the claim's compare-and-set is the
                    // happens-before edge that publishes this write to whoever reads the holder.
                    resultHolder[0] = runTests(request, state, innerDeadlineMs);
                    state.publishResult();
                }
                finally
                {
                    InfobaseAuthDialogSuppressor.markActivityEnd();
                }
            });
        if (bounded.getOutcome() == BoundedJob.Outcome.COMPLETED && bounded.getFailure() == null)
        {
            return resultHolder[0];
        }
        // EVERY remaining outcome means this thread, not the worker, is about to answer — so the
        // claim is settled ONCE, here, before any of them. Claiming inside the individual
        // branches let the timed-out/interrupted paths return while the worker still believed
        // someone was listening, and a worker that believes that keeps the result it consumed.
        if (!state.claimAnswer())
        {
            // The work finished in the race window between the deadline elapsing and this line.
            // The real answer exists and was published to the holder — returning a Pending (or an
            // error) here would throw away a completed report and send the caller round again.
            return resultHolder[0];
        }
        if (bounded.getFailure() != null)
        {
            Activator.logError("Unexpected error running YAXUnit tests", bounded.getFailure()); //$NON-NLS-1$
            return ToolResult.error(bounded.getFailure().getMessage() != null
                ? bounded.getFailure().getMessage()
                : bounded.getFailure().getClass().getSimpleName()).toJson();
        }
        if (bounded.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START
            || bounded.getOutcome() == BoundedJob.Outcome.NOT_RUN)
        {
            // The body NEVER ran: no preparation was started, no launch was spawned, nothing is
            // pending. Calling that a Pending would be a lie in the one sentence the caller uses
            // to decide whether to wait — there is nothing to wait FOR.
            return ToolResult.error("run_yaxunit_tests did not start: the job carrying it never " //$NON-NLS-1$
                + "left the scheduler (outcome " + bounded.getOutcome() + " after " //$NON-NLS-1$ //$NON-NLS-2$
                + bounded.getElapsedMs() + "ms). Nothing was launched and nothing is running. " //$NON-NLS-1$
                + "Retry; if it repeats, EDT's job scheduler is blocked or suspended — check the " //$NON-NLS-1$
                + "EDT progress view.").toJson(); //$NON-NLS-1$
        }
        return buildStalledPendingMessage(state.label(), bounded.getElapsedMs() / 1000L);
    }

    /**
     * State shared between the call's worker and the backstop that may end the call before the
     * worker does: the stage the call is in, and which of the two owns the answer.
     *
     * <p>A {@code Pending} whose phase is guessed is worse than none: the caller uses it to
     * decide whether waiting is even the right thing to do, so every label here is written on
     * ENTRY to the stage it names and the pre-launch label is read LIVE from the background
     * preparation rather than remembered from when the wait began.
     *
     * <p>The ownership half exists because the backstop does not stop the worker — it stops
     * WAITING for it. A worker that finishes afterwards would otherwise complete the normal
     * success path, consume the pending-fetch marker, and hand its report to a holder nobody
     * reads: the finished report becomes unreachable and the next identical call re-runs the
     * tests from scratch, which is the opposite of the "call again to pick up where you left
     * off" this tool promises.
     */
    static final class CallState
    {
        private final AtomicReference<String> current = new AtomicReference<>(PHASE_RESOLVE);

        /**
         * The run key whose undelivered-result marker THIS call actually took off the board, or
         * {@code null} when it took none.
         *
         * <p>Set only where a {@code PENDING_FETCH.remove} genuinely removed something. Two
         * distinctions ride on that:
         * <ul>
         *   <li>a call that never reached a run must not re-arm a key, or the next call would
         *       serve a report left over from an EARLIER run as if it were this one's — a false
         *       success, worse than the lost report this mechanism exists to prevent;</li>
         *   <li>a call whose remove was a no-op because ANOTHER caller had already taken and
         *       delivered that result must not put the marker back either, or the report would
         *       be delivered twice and a genuine re-run suppressed.</li>
         * </ul>
         */
        private volatile String consumedKey;

        /** Whoever wins this owns the answer: the worker (it returns it) or the backstop. */
        private final AtomicBoolean answered = new AtomicBoolean(false);

        /** The stage the call has entered and not yet left. */
        void set(String phase)
        {
            current.set(phase);
        }

        /** @return the current stage label, never {@code null} */
        String label()
        {
            String value = current.get();
            return value != null ? value : PHASE_RESOLVE;
        }

        /**
         * Takes the undelivered-result marker for {@code runKey} off the board on this call's
         * behalf, recording it so the marker can be restored if this call turns out to have no
         * one listening.
         *
         * <p>Deliberately a single place: consuming the marker and being able to give it back are
         * the same decision, and splitting them is how the first version of this lost results.
         *
         * @param runKey the run key whose result this call is about to deliver
         */
        void consumeResultFor(String runKey)
        {
            if (PENDING_FETCH.remove(runKey))
            {
                consumedKey = runKey;
            }
        }

        /**
         * Whether THIS call is the one that took {@code runKey}'s marker off the board.
         *
         * @param runKey the run key to test
         * @return {@code true} when this call owns that undelivered result
         */
        boolean consumed(String runKey)
        {
            return runKey != null && runKey.equals(consumedKey);
        }

        /**
         * Gives up ownership of a consumed marker, for the case where it turned out to refer to
         * no result at all.
         *
         * <p>Ownership is what licenses {@link #publishResult()} to put the marker back, so it
         * must not outlive the result it stands for: a call that consumed a marker, found no
         * report and went on to start a FRESH run would otherwise still be entitled to re-arm the
         * key later — by which time the key can belong to that fresh run and its result may
         * already have been delivered by somebody else.
         */
        void releaseConsumed()
        {
            consumedKey = null;
        }

        /**
         * The worker announcing it has a result.
         *
         * <p>When the caller already gave up, a marker THIS call consumed is put BACK, because
         * the report on disk is now the only copy of a run that really finished. A spurious
         * marker is cheap and self-clearing — the next call consumes it, finds no report and
         * falls through to a fresh run — whereas a missing one silently discards completed work.
         *
         * @return {@code true} when the caller is still listening and will read the result
         */
        boolean publishResult()
        {
            if (answered.compareAndSet(false, true))
            {
                return true;
            }
            String key = consumedKey;
            if (key != null)
            {
                PENDING_FETCH.add(key);
                Activator.logInfo("YAXUnit run finished after its call had already returned " //$NON-NLS-1$
                    + "Pending; keeping the result fetchable for runKey=" + key); //$NON-NLS-1$
            }
            return false;
        }

        /**
         * The backstop announcing it is about to answer for the call.
         *
         * @return {@code true} when it owns the answer; {@code false} when the worker beat it to
         *         it, in which case the worker's result is published and must be returned instead
         */
        boolean claimAnswer()
        {
            return answered.compareAndSet(false, true);
        }
    }

    /**
     * Whether {@code runKey} currently has an undelivered result waiting to be fetched.
     *
     * <p>Package-private read-only probe over {@link #PENDING_FETCH}, which is otherwise private
     * process-wide state: the guarantee that a late worker keeps its result reachable is only
     * observable through this set.
     *
     * @param runKey the run key to test
     * @return {@code true} when the next identical call would fetch a result for it
     */
    static boolean hasUndeliveredResult(String runKey)
    {
        return PENDING_FETCH.contains(runKey);
    }

    /** Drops a pending-fetch marker; for tests that must not leak process-wide state. */
    static void forgetUndeliveredResult(String runKey)
    {
        PENDING_FETCH.remove(runKey);
    }

    /** Arms a pending-fetch marker, as the polling paths do before they start waiting. */
    static void armUndeliveredResult(String runKey)
    {
        PENDING_FETCH.add(runKey);
    }

    /**
     * Immutable carrier for the parsed {@code execute} arguments threaded through
     * {@link #runTests} and {@link #spawnOrReuseLaunch}. Pure value object — bundling
     * these keeps both methods below the 7-parameter limit without changing any value
     * (the resolved {@code projectName}/{@code applicationId} derived from the launch
     * config are kept as method locals in {@link #runTests}, never written back here).
     */
    private static final class RunRequest
    {
        final String configName;
        final String projectName;
        final String applicationId;
        final String extensions;
        final String modules;
        final String tests;
        final int timeout;
        final boolean updateBeforeLaunch;
        final String updateScope;
        final ExternalInfobaseChangesPolicy externalChanges;
        final boolean debug;

        RunRequest(String configName, String projectName, String applicationId, String extensions, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
                String modules, String tests, int timeout, boolean updateBeforeLaunch,
                String updateScope, ExternalInfobaseChangesPolicy externalChanges, boolean debug)
        {
            this.configName = configName;
            this.projectName = projectName;
            this.applicationId = applicationId;
            this.extensions = extensions;
            this.modules = modules;
            this.tests = tests;
            this.timeout = timeout;
            this.updateBeforeLaunch = updateBeforeLaunch;
            this.updateScope = updateScope;
            this.externalChanges = externalChanges;
            this.debug = debug;
        }
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
    private String runTests(RunRequest req, CallState state, long deadlineMs) // NOSONAR reflective/form or transport god-method; further extraction deferred (reflective code)
    {
        // ONE deadline for the whole call, not one per step: the steps run in sequence, so a
        // per-step budget adds up (resolve + 25s preparation + spawn + the polling window) to
        // far more than the transport allows, which is how a call that honoured every
        // individual limit still died on the wire (#357).
        //
        // The deadline is the caller's window MINUS the backstop reserve, measured from when the
        // CALL began (see runBounded), so this flow reaches its own deadline first and answers
        // with the real phase. The reserve comes out of the window, never on top of it.
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

            // DEBUG mode shares the whole setup above (resolve/validate/effective
            // project+app), then spawns a DEBUG launch and returns at once for
            // wait_for_break — no polling, no run-key reuse cache.
            if (req.debug)
            {
                return launchDebugMode(matchingConfig, project, projectName, applicationId,
                    appManager, launchManager, req.extensions, req.modules, req.tests,
                    req.updateBeforeLaunch, req.updateScope, req.externalChanges, deadlineMs, state);
            }

            // Use the launch config name as the run-key root — stable across
            // (project, applicationId) vs. launchConfigurationName call styles.
            // The conflict policy is part of the key: a run started under one
            // externalInfobaseChanges answer must never be reused (or its report delivered) for a
            // later call that asked for a different one.
            String runKey = matchingConfig.getName() + ":" //$NON-NLS-1$
                    + sha1(safe(req.extensions) + "|" + safe(req.modules) + "|" + safe(req.tests) //$NON-NLS-1$ //$NON-NLS-2$
                        + "|" + req.externalChanges.wireValue()); //$NON-NLS-1$
            Path reportDir = stableReportDir(runKey);

            // If a launch is already running for this key, just poll it.
            ILaunch existing = ACTIVE_LAUNCHES.get(runKey);
            if (existing != null)
            {
                state.set(PHASE_RUN);
                return handleExistingLaunch(existing, reportDir, deadlineMs, runKey,
                        projectName, applicationId, state);
            }

            // No active launch. Deliver a previously reported Pending result EXACTLY ONCE: a re-call
            // fetching the result of a run that finished after a Pending response gets the report; // NOSONAR explanatory comment, not commented-out code
            // any later call with the same key falls through to a fresh run. There is NO time-based
            // cache, so a genuine re-run always re-executes the tests.
            String pendingResult = tryDeliverPendingResult(runKey, reportDir, projectName,
                applicationId, state);
            if (pendingResult != null)
            {
                return pendingResult;
            }

            // Phase 1 (quick, JVM-wide): try to reuse an active launch for this runKey.
            ILaunch launch = reuseActiveLaunch(runKey);

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
                if (req.updateBeforeLaunch)
                {
                    // The policy is part of the key: a piggybacking call must never inherit a
                    // DIFFERENT caller's answer to the external-changes modal (one of the answers
                    // rewrites project sources). Same project+application+policy still share one prep.
                    String prepKey = LaunchLifecycleUtils.prepKeyFor(projectName, applicationId)
                        + "|" + req.externalChanges.wireValue(); //$NON-NLS-1$
                    final PreLaunchResult[] resultHolder = new PreLaunchResult[1];
                    PrepRequest prepReq = new PrepRequest(projectName, launchManager, project,
                        applicationId, appManager, req.updateScope, req.externalChanges,
                        "YAXUnit pre-launch preparation for " + projectName); //$NON-NLS-1$

                    String pendingOrError = awaitPreparedOrPending(prepKey, prepReq, resultHolder,
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
                            runKey, reportDir);
                    }
                }
            }

            state.set(PHASE_RUN);
            // Marked BEFORE the poll, not only when the window expires: this call can also end
            // WITHOUT reaching either branch — the backstop can answer while the poll is inside
            // the platform — and a Pending that left no marker sends the retry into a fresh run
            // that wipes the very report the abandoned poll was about to read. Cleared again the
            // moment a result is actually delivered — and put back by publishResult when that
            // delivery turns out to have no one listening.
            PENDING_FETCH.add(runKey);
            String pollResult = pollLaunch(launch, reportDir, deadlineMs, runKey,
                    projectName, applicationId);
            if (pollResult != null)
            {
                // Result delivered — forget any Pending bookkeeping so the next call re-runs.
                state.consumeResultFor(runKey);
                return prependPreLaunchInfo(preLaunch, pollResult);
            }

            // Polling window expired — return Pending without terminating the launch; the marker
            // set above lets a re-call fetch the result once it completes.
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
            String applicationId, String runKey, Path reportDir) throws CoreException, IOException
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
        String paramsJson = buildParamsJson(reportDir.resolve(VAL_JUNIT_XML).toString(),
                req.extensions, req.modules, req.tests);
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
        // For a STANDALONE-SERVER application this window is where the DB update actually
        // happens, so a conflict cancelled here must be reported with its cause - otherwise the run
        // just fails later with a generic "no junit.xml" and the caller never learns which knob
        // would have let it through.
        LaunchUpdateDialogAutoConfirmer.ConflictWatch conflicts = launchPolicy == null
            ? null
            : LaunchUpdateDialogAutoConfirmer.beginConflictWatch(launchInfobase);
        LaunchUpdateDialogAutoConfirmer.arm(armFlags[0], armFlags[1], armFlags[0], launchPolicy,
            launchInfobase);
        ILaunch launch;
        try
        {
            launch = workingCopy.launch(ILaunchManager.RUN_MODE,
                new NullProgressMonitor());
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
                launchInfobase);
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
     * the {@link #ACTIVE_LAUNCHES}/{@link #PENDING_FETCH} tracking maps, exactly as
     * the inline branch did.
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
            String projectName, String applicationId, CallState state) throws InterruptedException
    {
        if (existing.isTerminated())
        {
            synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
            {
                ACTIVE_LAUNCHES.remove(runKey, existing);
                state.consumeResultFor(runKey);
                File junitXml = findJunitXml(reportDir);
                if (junitXml != null)
                {
                    return readResults(junitXml);
                }
                return ToolResult.error("Previous launch finished but no JUnit XML found in " //$NON-NLS-1$
                        + reportDir + ". Make sure YAXUnit extension is installed.").toJson(); //$NON-NLS-1$
            }
        }
        // Marked BEFORE the poll for the same reason as the fresh-run path: this call can end
        // without reaching either branch (the backstop answering while the poll is inside the
        // platform), and an unmarked Pending sends the retry into a fresh run that wipes the
        // report.
        PENDING_FETCH.add(runKey);
        String pollResult = pollLaunch(existing, reportDir, deadlineMs, runKey,
                projectName, applicationId);
        if (pollResult != null)
        {
            // Result delivered — forget any Pending bookkeeping so the next call re-runs.
            state.consumeResultFor(runKey);
            return pollResult;
        }
        // Still running past the window — the marker set above lets a re-call fetch the result.
        return buildPendingMessage(reportDir);
    }

    /**
     * Delivers a previously reported Pending result EXACTLY ONCE: a re-call fetching
     * the result of a run that finished after a Pending response gets the report;
     * any later call with the same key falls through to a fresh run. Reads the report
     * only (no launch is spawned) and consumes the {@link #PENDING_FETCH} entry.
     * <p>
     * Consume + read run under the per-IB lock so a concurrent identical call that falls
     * through to a fresh launch cannot cleanupTempDir(reportDir) mid-read — the fresh-run
     * path holds the SAME lock for cleanup+spawn. A racer blocked here finds PENDING_FETCH
     * already drained and proceeds to a fresh run; worst case degrades from a torn parse to
     * a clean null.
     *
     * @return the parsed report when a pending result was delivered, or {@code null}
     *         when the caller should fall through and start a fresh run (no pending
     *         entry, or the launch died without writing junit.xml)
     */
    String tryDeliverPendingResult(String runKey, Path reportDir, String projectName, // NOSONAR package-private so the released-ownership ratchet can drive the fall-through headlessly
            String applicationId, CallState state)
    {
        synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
        {
            state.consumeResultFor(runKey);
            if (state.consumed(runKey))
            {
                File pending = findJunitXml(reportDir);
                if (pending != null)
                {
                    Activator.logInfo("Delivering completed YAXUnit result for pending runKey=" + runKey); //$NON-NLS-1$
                    return readResults(pending);
                }
                // Pending was reported but no report materialised (the launch died without writing
                // junit.xml) — fall through and start a fresh run.
                //
                // Ownership is RELEASED here: the marker this call took off the board referred to
                // nothing, so the call is not holding an undelivered result any more. Keeping it
                // would make a later publishResult re-arm the key on the strength of a report that
                // never existed — and by then the key can belong to a different run whose result
                // somebody else has already delivered, which would serve it twice and suppress a
                // genuine re-run.
                state.releaseConsumed();
            }
        }
        return null;
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
    private String launchDebugMode(ILaunchConfiguration matchingConfig, IProject project, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
            String projectName, String applicationId, IApplicationManager appManager,
            ILaunchManager launchManager, String extensions, String modules, String tests,
            boolean updateBeforeLaunch, String updateScope, ExternalInfobaseChangesPolicy externalChanges,
            long deadlineMs, CallState state)
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
            buildParamsJson(junitFile.toString(), extensions, modules, tests).getBytes(StandardCharsets.UTF_8));

        // Suspend listener must be live before the launch starts producing events.
        DebugSessionRegistry.get().ensureListenerRegistered();

        // Phase 2 (debug path): prep runs in a background Job under a 25-second
        // budget, same as the RUN path. The sweep + launch (Phase 3) runs
        // synchronously after prep completes, under the per-key lock.
        PreLaunchResult preLaunch = null;
        if (updateBeforeLaunch)
        {
            String prepKey = LaunchLifecycleUtils.prepKeyFor(projectName, applicationId)
                + "|" + externalChanges.wireValue(); //$NON-NLS-1$
            final PreLaunchResult[] resultHolder = new PreLaunchResult[1];
            PrepRequest prepReq = new PrepRequest(projectName, launchManager, project,
                applicationId, appManager, updateScope, externalChanges,
                "YAXUnit debug pre-launch preparation for " + projectName); //$NON-NLS-1$

            String pendingOrError = awaitPreparedOrPending(prepKey, prepReq, resultHolder,
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
            // Same as the RUN path: gated on the update opt-out, and the only armed window
            // around a standalone-server application's delegate-performed update.
            String launchInfobase = LaunchLifecycleUtils.attributionInfobaseName(appManager, project,
                applicationId);
            ExternalInfobaseChangesPolicy launchPolicy = armFlags[0] ? externalChanges : null;
            // Same as the RUN path: this is the only armed window around a standalone-server
            // application's delegate-performed update, so a cancel here is reported with its cause.
            LaunchUpdateDialogAutoConfirmer.ConflictWatch conflicts = launchPolicy == null
                ? null
                : LaunchUpdateDialogAutoConfirmer.beginConflictWatch(launchInfobase);
            LaunchUpdateDialogAutoConfirmer.arm(armFlags[0], armFlags[1], armFlags[0], launchPolicy,
                launchInfobase);
            ILaunch[] spawned = new ILaunch[1];
            try
            {
                spawned[0] = workingCopy.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor());
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
                    launchPolicy, launchInfobase);
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
    static String awaitPreparedOrPending(String prepKey, PrepRequest req, // NOSONAR package-private for the bounded-wait ratchet, which must drive this wait directly
            PreLaunchResult[] resultHolder, long deadlineMs, CallState state)
    {
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
            prepJob.schedule();
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
     * @param entry the in-flight preparation (may be {@code null})
     * @return the namespaced label
     */
    static String prepPhaseLabel(PrepInFlight entry)
    {
        String inner = entry != null ? entry.phase : null;
        return "prep:" + (inner != null ? inner : LaunchLifecycleUtils.PHASE_RECOMPUTE); //$NON-NLS-1$
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
            boolean reportWritten = writeReportFile(reportFile, markdown);

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
     * Writes the Markdown report to {@code reportFile}. Returns {@code true} if the file
     * was written and exists afterwards; a failed write is logged and returns {@code false}
     * (the report content is still returned to the caller without the on-disk footer).
     */
    private boolean writeReportFile(Path reportFile, String markdown)
    {
        try
        {
            Files.write(reportFile, markdown.getBytes(StandardCharsets.UTF_8));
            return Files.exists(reportFile);
        }
        catch (IOException io)
        {
            Activator.logError("Failed to write Markdown report to " + reportFile, io); //$NON-NLS-1$
            return false;
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
        return "**Pending:** YAXUnit tests are still running (phase: `" + PHASE_RUN + "`).\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "Report directory: `" + reportDir + "`\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "Call `run_yaxunit_tests` again with the same arguments to wait further " //$NON-NLS-1$
                + "and fetch the JUnit XML once the launch completes.\n"; //$NON-NLS-1$
    }

    /**
     * The {@code Pending} returned when the call hit its wall-clock bound while a step was
     * still inside the platform — the case the per-step deadlines cannot answer because the
     * step never came back to look at one.
     *
     * <p>This is the message that replaces the bare transport timeout of #357, so it must
     * carry the two things that error carried neither of: WHICH stage the call was in, and
     * whether waiting is the right response. Both are stated plainly, and the work is
     * explicitly described as still running — nothing here cancels it.
     *
     * @param phase the stage the call was in when the bound elapsed
     * @param elapsedSeconds how long the call waited
     * @return a Markdown pending response
     */
    static String buildStalledPendingMessage(String phase, long elapsedSeconds)
    {
        return "**Pending:** the launch pipeline did not answer within this call's window " //$NON-NLS-1$
            + "(phase: `" + phase + "`, waited: " + elapsedSeconds + "s).\n\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "The work is still running on the server — nothing was cancelled. This call " //$NON-NLS-1$
            + "returns early ON PURPOSE: an MCP transport cuts a call at around 60 seconds, " //$NON-NLS-1$
            + "so waiting longer would replace this message with a bare transport error.\n\n" //$NON-NLS-1$
            + "Call `run_yaxunit_tests` again with the **same arguments** to keep waiting. If the " //$NON-NLS-1$
            + "phase stops changing across several calls, this tool cannot tell a legitimately " //$NON-NLS-1$
            + "long stage from one blocked on a modal dialog — check EDT for a dialog waiting " //$NON-NLS-1$
            + "for a click, and see get_tool_guide('run_yaxunit_tests') for the pre-flight order " //$NON-NLS-1$
            + "that keeps the infobase update out of the launch.\n"; //$NON-NLS-1$
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
    static String buildPrepPendingMessage(long elapsedSeconds, String phase)
    {
        int retryAfter = 5;
        return "**Pending:** Pre-launch preparation is still running " //$NON-NLS-1$
            + "(phase: `" + (phase != null ? phase : prepPhaseLabel(null)) + "`" //$NON-NLS-1$ //$NON-NLS-2$
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

        String[] candidates = {VAL_JUNIT_XML, "report.xml", "test-report.xml"}; //$NON-NLS-1$ //$NON-NLS-2$
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

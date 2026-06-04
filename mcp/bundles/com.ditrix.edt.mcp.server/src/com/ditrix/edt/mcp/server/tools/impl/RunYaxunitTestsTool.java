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
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
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
import com.ditrix.edt.mcp.server.utils.JUnitMarkdownFormatter;
import com.ditrix.edt.mcp.server.utils.JUnitTestResults;
import com.ditrix.edt.mcp.server.utils.JUnitXmlParser;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
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
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /** Active launches keyed by stable run id (projectName:applicationId:filterHash). */
    private static final Map<String, ILaunch> ACTIVE_LAUNCHES = new ConcurrentHashMap<>();

    /** Lazily registered listener that evicts terminated launches from {@link #ACTIVE_LAUNCHES}. */
    private static final AtomicBoolean LISTENER_REGISTERED = new AtomicBoolean(false);

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
               + "Requires an existing runtime-client launch configuration and the YAXUnit extension " //$NON-NLS-1$
               + "installed in the infobase. " //$NON-NLS-1$
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
            .stringProperty("extensions", "Comma-separated extension names to filter tests.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modules", "Comma-separated module names to filter tests.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("tests", "Comma-separated test names in Module.Method format.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("timeout", "Polling window in seconds (default: 60); on expiry returns Pending.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Auto-chain (default: true): terminate a live client and run a silent DB update first.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# run_yaxunit_tests\n\n" //$NON-NLS-1$
            + "Launches the 1C:Enterprise application with the `RunUnitTests` startup parameter, " //$NON-NLS-1$
            + "polls until the launch terminates or the polling window expires, then parses the " //$NON-NLS-1$
            + "JUnit XML report and returns a Markdown summary. The full Markdown report is also " //$NON-NLS-1$
            + "written to `report.md` next to `junit.xml` so you can read it directly from disk.\n\n" //$NON-NLS-1$

            + "## When to use\n\n" //$NON-NLS-1$
            + "Use after writing or changing test code to verify it. " //$NON-NLS-1$
            + "Prerequisites: an existing runtime-client launch configuration for the project/application, " //$NON-NLS-1$
            + "and the YAXUnit extension installed in the target infobase. " //$NON-NLS-1$
            + "Without YAXUnit no JUnit XML is produced and the tool returns an error.\n\n" //$NON-NLS-1$

            + "## Parameters\n\n" //$NON-NLS-1$
            + "Two ways to identify the launch:\n\n" //$NON-NLS-1$
            + "- `launchConfigurationName` (preferred) — the exact runtime-client config name from " //$NON-NLS-1$
            + "`list_configurations`. When set, `projectName` and `applicationId` are derived from it.\n" //$NON-NLS-1$
            + "- `projectName` + `applicationId` — required together when `launchConfigurationName` is " //$NON-NLS-1$
            + "omitted. Get the application id from `get_applications`.\n\n" //$NON-NLS-1$
            + "Optional test filters (comma-separated, AND-combined):\n\n" //$NON-NLS-1$
            + "- `extensions` — restrict to tests in these extensions.\n" //$NON-NLS-1$
            + "- `modules` — restrict to these test modules.\n" //$NON-NLS-1$
            + "- `tests` — individual tests in `Module.Method` format.\n\n" //$NON-NLS-1$
            + "Control:\n\n" //$NON-NLS-1$
            + "- `timeout` — polling window in seconds (default 60). See ## Polling and Pending.\n" //$NON-NLS-1$
            + "- `updateBeforeLaunch` — auto-chain, default `true`. See ## Auto-chain.\n\n" //$NON-NLS-1$

            + "## Polling and Pending\n\n" //$NON-NLS-1$
            + "The tool polls for up to `timeout` seconds. If the launch finishes in that window it " //$NON-NLS-1$
            + "returns the parsed JUnit report. If the window expires while the launch is still running " //$NON-NLS-1$
            + "it returns **Pending** and does NOT terminate the launch. Call the tool again with the " //$NON-NLS-1$
            + "SAME arguments to keep waiting and fetch the result once the launch completes. " //$NON-NLS-1$
            + "A run key is derived from the config name plus the filter, so identical arguments reattach " //$NON-NLS-1$
            + "to the in-flight launch instead of starting a new one. A fresh report (under a few minutes " //$NON-NLS-1$
            + "old) is served from cache.\n\n" //$NON-NLS-1$

            + "## Auto-chain (updateBeforeLaunch)\n\n" //$NON-NLS-1$
            + "Default `true`: before spawning a new test launch, the tool politely terminates any live " //$NON-NLS-1$
            + "1C client running this configuration and runs a silent database update — so EDT's launch " //$NON-NLS-1$
            + "delegate does not pop its modal 'Update database?' dialog that would otherwise block the " //$NON-NLS-1$
            + "MCP call. Set `false` to keep legacy behaviour (the delegate decides; the dialog may " //$NON-NLS-1$
            + "appear and block). If pre-launch preparation fails because a previous launch is stuck, " //$NON-NLS-1$
            + "call `terminate_launch` with `force=true` and retry.\n\n" //$NON-NLS-1$

            + "## Examples\n\n" //$NON-NLS-1$
            + "Run all tests via a named config:\n\n" //$NON-NLS-1$
            + "```json\n" //$NON-NLS-1$
            + "{ \"launchConfigurationName\": \"TestClient\" }\n" //$NON-NLS-1$
            + "```\n\n" //$NON-NLS-1$
            + "Run by project + application, filtered to two modules:\n\n" //$NON-NLS-1$
            + "```json\n" //$NON-NLS-1$
            + "{ \"projectName\": \"MyProject\", \"applicationId\": \"<id-from-get_applications>\", " //$NON-NLS-1$
            + "\"modules\": \"Tests_Catalog, Tests_Document\" }\n" //$NON-NLS-1$
            + "```\n\n" //$NON-NLS-1$
            + "Run a single test method with a longer window:\n\n" //$NON-NLS-1$
            + "```json\n" //$NON-NLS-1$
            + "{ \"launchConfigurationName\": \"TestClient\", \"tests\": \"Tests_Catalog.CreateAndPost\", " //$NON-NLS-1$
            + "\"timeout\": 180 }\n" //$NON-NLS-1$
            + "```\n\n" //$NON-NLS-1$

            + "## Notes\n\n" //$NON-NLS-1$
            + "- Response type is Markdown; the report is also saved to `report.md` next to `junit.xml`.\n" //$NON-NLS-1$
            + "- The temp/report directory is not deleted on completion so a later call can re-fetch it.\n" //$NON-NLS-1$
            + "- Module and test names are 1C identifiers (programmatic `Name`), not synonyms.\n\n" //$NON-NLS-1$

            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- A timeout returns **Pending**, not a failure — do not retry with different arguments; " //$NON-NLS-1$
            + "reuse the same ones so the run key matches.\n" //$NON-NLS-1$
            + "- If no JUnit XML appears after the launch finishes, the YAXUnit extension is likely not " //$NON-NLS-1$
            + "installed in the infobase, or the filter matched no tests.\n" //$NON-NLS-1$
            + "- The config must be a runtime-client launch configuration; other types are rejected.\n"; //$NON-NLS-1$
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
        String extensions = JsonUtils.extractStringArgument(params, "extensions"); //$NON-NLS-1$
        String modules = JsonUtils.extractStringArgument(params, "modules"); //$NON-NLS-1$
        String tests = JsonUtils.extractStringArgument(params, "tests"); //$NON-NLS-1$
        int timeout = JsonUtils.extractIntArgument(params, "timeout", DEFAULT_TIMEOUT); //$NON-NLS-1$
        if (timeout < 1)
        {
            timeout = 1;
        }
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, //$NON-NLS-1$
            "updateBeforeLaunch", true); //$NON-NLS-1$

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
            timeout, updateBeforeLaunch);
    }

    /**
     * Main test execution flow.
     *
     * Non-blocking with state tracking. Behaviour:
     * <ol>
     *   <li>Compute stable runKey from projectName + applicationId + filter.</li>
     *   <li>If a launch is already running for this key — poll up to {@code timeout}s, return result or "Pending".</li>
     *   <li>If no active launch but a fresh junit.xml exists — return cached result.</li>
     *   <li>Otherwise — start a new launch, poll, return result or "Pending".</li>
     * </ol>
     *
     * The temp directory is NEVER deleted in finally — the caller can invoke the tool again to fetch
     * the result. Old runs are cleaned up automatically before starting a new launch.
     */
    private String runTests(String configName, String projectName, String applicationId,
            String extensions, String modules, String tests, int timeout, boolean updateBeforeLaunch)
    {
        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
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
                    ACTIVE_LAUNCHES.remove(runKey);
                    File junitXml = findJunitXml(reportDir);
                    if (junitXml != null)
                    {
                        return readResults(junitXml);
                    }
                    return ToolResult.error("Previous launch finished but no JUnit XML found in " //$NON-NLS-1$
                            + reportDir + ". Make sure YAXUnit extension is installed.").toJson(); //$NON-NLS-1$
                }
                String pollResult = pollLaunch(existing, reportDir, timeout, runKey);
                if (pollResult != null)
                {
                    return pollResult;
                }
                return buildPendingMessage(reportDir);
            }

            // No active launch — return fresh cached result if available.
            File cached = findJunitXml(reportDir);
            if (cached != null && (System.currentTimeMillis() - cached.lastModified()) < CACHE_TTL_MS)
            {
                Activator.logInfo("Returning cached YAXUnit results from " + cached); //$NON-NLS-1$
                return readResults(cached);
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

            // Phases 2 + 3 under the per-key lock — this serialises auto-chain
            // and spawn across both YAXUnit tools for the same IB, and closes
            // the narrow window between workingCopy.launch() and
            // registerOwnedLaunch where a concurrent call could otherwise
            // terminate this fresh launch before it's registered. Different
            // (project, applicationId) pairs are unaffected.
            PreLaunchResult preLaunch = null;
            if (launch == null)
            {
                synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
                {
                    // Phase 2: auto-chain (LaunchLifecycleUtils.prepareForFreshLaunch
                    // re-acquires the same monitor — Java synchronized is reentrant).
                    if (updateBeforeLaunch)
                    {
                        int terminateTimeout = LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds();
                        preLaunch = LaunchLifecycleUtils.prepareForFreshLaunch(launchManager,
                            project, applicationId, appManager, terminateTimeout);
                        if (!preLaunch.isOk())
                        {
                            return ToolResult.error("Pre-launch preparation failed: " //$NON-NLS-1$
                                + preLaunch.getError()
                                + "\n\nIf the previous launch is stuck, call `terminate_launch` " //$NON-NLS-1$
                                + "with `force=true` and retry. As a last resort, pass " //$NON-NLS-1$
                                + "`updateBeforeLaunch=false` — but the EDT launch delegate may " //$NON-NLS-1$
                                + "then pop a modal dialog that blocks the MCP call.").toJson(); //$NON-NLS-1$
                        }
                    }

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

                            Activator.logInfo("Launching YAXUnit tests: config=" + matchingConfig.getName() //$NON-NLS-1$
                                    + ", startup=" + startupOption); //$NON-NLS-1$

                            launch = workingCopy.launch(ILaunchManager.RUN_MODE,
                                new NullProgressMonitor());
                            // Register BEFORE leaving the per-key lock so a concurrent
                            // auto-chain on the same IB sees this launch as owned and
                            // refuses to terminate it.
                            LaunchLifecycleUtils.registerOwnedLaunch(launch);
                            ACTIVE_LAUNCHES.put(runKey, launch);
                        }
                    }
                }
            }

            String pollResult = pollLaunch(launch, reportDir, timeout, runKey);
            if (pollResult != null)
            {
                return prependPreLaunchInfo(preLaunch, pollResult);
            }

            // Polling window expired — return Pending without terminating the launch.
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
     * Polls a launch for up to {@code timeoutSec} seconds. Returns the parsed Markdown report
     * if the launch finished, or {@code null} if still running (caller should return a Pending message).
     */
    private String pollLaunch(ILaunch launch, Path reportDir, int timeoutSec, String runKey)
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

        ACTIVE_LAUNCHES.remove(runKey);
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

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
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
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
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.JUnitMarkdownFormatter;
import com.ditrix.edt.mcp.server.utils.JUnitTestResults;
import com.ditrix.edt.mcp.server.utils.JUnitXmlParser;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
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
        return "Run YAXUnit tests for a 1C:Enterprise project. " + //$NON-NLS-1$
               "Launches the application with RunUnitTests parameter, polls for completion " + //$NON-NLS-1$
               "for up to `timeout` seconds (default 60), then returns the JUnit Markdown report. " + //$NON-NLS-1$
               "If the launch is still running when the polling window expires, returns " + //$NON-NLS-1$
               "**Pending** — call this tool again with the same arguments to keep waiting and " + //$NON-NLS-1$
               "fetch the result once the launch finishes. The launch is NOT terminated on timeout. " + //$NON-NLS-1$
               "A full Markdown report is also written to report.md next to junit.xml. " + //$NON-NLS-1$
               "Requires an existing launch configuration and YAXUnit extension installed in the infobase."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact EDT runtime-client launch configuration name (preferred; from list_configurations)") //$NON-NLS-1$
            .stringProperty("projectName", "EDT project name (required if launchConfigurationName is omitted)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application ID from get_applications (required if launchConfigurationName is omitted)") //$NON-NLS-1$
            .stringProperty("extensions", "Comma-separated extension names to filter tests by extension") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modules", "Comma-separated module names to filter tests") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("tests", "Comma-separated test names in Module.Method format") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("timeout", "Polling window in seconds (default: 60). On expiry returns Pending; call again to keep waiting.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
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

        boolean hasName = configName != null && !configName.isEmpty();
        if (!hasName)
        {
            if (projectName == null || projectName.isEmpty())
            {
                return "**Error:** projectName is required (or pass launchConfigurationName)"; //$NON-NLS-1$
            }
            if (applicationId == null || applicationId.isEmpty())
            {
                return "**Error:** applicationId is required (or pass launchConfigurationName). " //$NON-NLS-1$
                    + "Use get_applications or list_configurations."; //$NON-NLS-1$
            }
        }

        ensureLaunchListenerRegistered();
        purgeTerminatedLaunches();

        return runTests(configName, projectName, applicationId, extensions, modules, tests, timeout);
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
            String extensions, String modules, String tests, int timeout)
    {
        try
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return "**Error:** Launch manager is not available"; //$NON-NLS-1$
            }

            ILaunchConfiguration matchingConfig = LaunchConfigUtils.resolveLaunchConfig(
                    launchManager, configName, projectName, applicationId);
            if (matchingConfig == null)
            {
                boolean hasName = configName != null && !configName.isEmpty();
                return hasName
                    ? "**Error:** Launch configuration not found: '" + configName + "'. " //$NON-NLS-1$ //$NON-NLS-2$
                        + "Use list_configurations to see what's available." //$NON-NLS-1$
                    : buildNoConfigError(launchManager,
                        launchManager.getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID),
                        projectName, applicationId);
            }
            if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(matchingConfig)))
            {
                return "**Error:** Launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' is not a runtime-client config — YAXUnit tests require one."; //$NON-NLS-1$
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
                return "**Error:** Launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' has no project attribute set"; //$NON-NLS-1$
            }

            String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
            if (notReadyError != null)
            {
                return "**Error:** " + notReadyError; //$NON-NLS-1$
            }

            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject project = workspace.getRoot().getProject(projectName);

            if (project == null || !project.exists())
            {
                return "**Error:** Project not found: " + projectName; //$NON-NLS-1$
            }

            if (!project.isOpen())
            {
                return "**Error:** Project is closed: " + projectName; //$NON-NLS-1$
            }

            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return "**Error:** IApplicationManager service is not available"; //$NON-NLS-1$
            }

            if (applicationId != null && !applicationId.isEmpty())
            {
                try
                {
                    Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                    if (!appOpt.isPresent())
                    {
                        return "**Error:** Application not found: " + applicationId //$NON-NLS-1$
                                + ". Use get_applications to get valid application IDs."; //$NON-NLS-1$
                    }
                }
                catch (ApplicationException e)
                {
                    Activator.logError("Error checking application", e); //$NON-NLS-1$
                    return "**Error:** Failed to validate application: " + applicationId //$NON-NLS-1$
                            + " (" + e.getMessage() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
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
                    return "**Error:** Previous launch finished but no JUnit XML found in " //$NON-NLS-1$
                            + reportDir + ". Make sure YAXUnit extension is installed."; //$NON-NLS-1$
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

            // Atomically reuse an existing active launch for the same runKey, or create exactly
            // one new launch. Cleanup + params write is inside the critical section so a concurrent
            // call cannot overwrite the params while the first is starting.
            ILaunch launch;
            synchronized (ACTIVE_LAUNCHES)
            {
                ILaunch concurrent = ACTIVE_LAUNCHES.get(runKey);
                if (concurrent != null && !concurrent.isTerminated())
                {
                    Activator.logInfo("Reusing active YAXUnit launch for runKey=" + runKey); //$NON-NLS-1$
                    launch = concurrent;
                }
                else
                {
                    if (concurrent != null)
                    {
                        ACTIVE_LAUNCHES.remove(runKey);
                    }

                    // Prepare a clean report dir and write the params file inside the lock.
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

                    launch = workingCopy.launch(ILaunchManager.RUN_MODE, new NullProgressMonitor());
                    ACTIVE_LAUNCHES.put(runKey, launch);
                }
            }

            String pollResult = pollLaunch(launch, reportDir, timeout, runKey);
            if (pollResult != null)
            {
                return pollResult;
            }

            // Polling window expired — return Pending without terminating the launch.
            return buildPendingMessage(reportDir);
        }
        catch (CoreException e)
        {
            Activator.logError("Error running YAXUnit tests", e); //$NON-NLS-1$
            return "**Error:** Launch failed: " + e.getMessage(); //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return "**Error:** Test execution was interrupted"; //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error running YAXUnit tests", e); //$NON-NLS-1$
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
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
            return "**Error:** No JUnit XML report found in " + reportDir //$NON-NLS-1$
                    + ". Make sure YAXUnit extension is installed in the infobase " //$NON-NLS-1$
                    + "and test configuration is correct."; //$NON-NLS-1$
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
            return "**Error:** Failed to parse test results: " + e.getMessage(); //$NON-NLS-1$
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
        sb.append("**Error:** No launch configuration found for project '"); //$NON-NLS-1$
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

        return sb.toString();
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

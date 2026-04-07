/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.io.IOException;
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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

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
import org.eclipse.debug.core.ILaunchManager;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to run YAXUnit tests for a 1C:Enterprise project.
 * Launches the application with RunUnitTests startup parameter,
 * waits for completion, parses JUnit XML results and returns
 * a formatted Markdown report.
 */
public class RunYaxunitTestsTool implements IMcpTool
{
    public static final String NAME = "run_yaxunit_tests"; //$NON-NLS-1$

    private static final String LAUNCH_CONFIG_TYPE_ID = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$
    private static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$
    private static final String ATTR_APPLICATION_ID = "com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID"; //$NON-NLS-1$
    private static final String STARTUP_OPTION_ATTR = "com._1c.g5.v8.dt.launching.core.ATTR_STARTUP_OPTION"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT = 60;
    private static final int POLL_INTERVAL_MS = 1000;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /** Active launches keyed by stable run id (projectName:applicationId:filterHash). */
    private static final Map<String, ILaunch> ACTIVE_LAUNCHES = new ConcurrentHashMap<>();

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
               "Requires an existing launch configuration and YAXUnit extension installed in the infobase."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application ID from get_applications (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String extensions = JsonUtils.extractStringArgument(params, "extensions"); //$NON-NLS-1$
        String modules = JsonUtils.extractStringArgument(params, "modules"); //$NON-NLS-1$
        String tests = JsonUtils.extractStringArgument(params, "tests"); //$NON-NLS-1$
        int timeout = JsonUtils.extractIntArgument(params, "timeout", DEFAULT_TIMEOUT); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return "**Error:** projectName is required"; //$NON-NLS-1$
        }

        if (applicationId == null || applicationId.isEmpty())
        {
            return "**Error:** applicationId is required. Use get_applications to get application list."; //$NON-NLS-1$
        }

        // Check if project is ready
        String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return "**Error:** " + notReadyError; //$NON-NLS-1$
        }

        return runTests(projectName, applicationId, extensions, modules, tests, timeout);
    }

    /**
     * Main test execution flow.
     *
     * Non-blocking with state tracking. Behavior:
     * 1. Compute stable runKey from projectName + applicationId + filter
     * 2. If launch already running for this key → poll up to {@code timeout}s, return result or "Pending"
     * 3. If no active launch but fresh junit.xml exists in stable dir → return cached result
     * 4. Otherwise → start new launch, poll up to {@code timeout}s, return result or "Pending"
     *
     * The temp directory is NEVER deleted in finally — Claude can call the tool again to fetch
     * the result. Old runs are cleaned up automatically before starting a new launch.
     */
    private String runTests(String projectName, String applicationId,
            String extensions, String modules, String tests, int timeout)
    {
        try
        {
            // 1. Validate project and application
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

            try
            {
                Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                if (!appOpt.isPresent())
                {
                    return "**Error:** Application not found: " + applicationId + //$NON-NLS-1$
                            ". Use get_applications to get valid application IDs."; //$NON-NLS-1$
                }
            }
            catch (ApplicationException e)
            {
                Activator.logError("Error checking application", e); //$NON-NLS-1$
            }

            // 2. Compute stable run key and report dir
            String runKey = projectName + ":" + applicationId + ":" //$NON-NLS-1$ //$NON-NLS-2$
                    + sha1(safe(extensions) + "|" + safe(modules) + "|" + safe(tests)); //$NON-NLS-1$ //$NON-NLS-2$
            Path reportDir = stableReportDir(runKey);

            // 3. If a launch is already running for this key, just poll it
            ILaunch existing = ACTIVE_LAUNCHES.get(runKey);
            if (existing != null)
            {
                if (existing.isTerminated())
                {
                    ACTIVE_LAUNCHES.remove(runKey);
                    File junitXml = findJunitXml(reportDir);
                    if (junitXml != null)
                    {
                        return parseAndFormatResults(junitXml);
                    }
                    return "**Error:** Previous launch finished but no JUnit XML found in " //$NON-NLS-1$
                            + reportDir + ". Make sure YAXUnit extension is installed."; //$NON-NLS-1$
                }
                // Still running — poll for up to `timeout` seconds, then return Pending
                String pollResult = pollLaunch(existing, reportDir, timeout, runKey);
                if (pollResult != null)
                {
                    return pollResult;
                }
                return buildPendingMessage(reportDir, runKey);
            }

            // 4. No active launch for this key — check fresh cached result
            File cached = findJunitXml(reportDir);
            if (cached != null && (System.currentTimeMillis() - cached.lastModified()) < CACHE_TTL_MS)
            {
                Activator.logInfo("Returning cached YAXUnit results from " + cached); //$NON-NLS-1$
                return parseAndFormatResults(cached);
            }

            // 5. Prepare clean report dir and params file
            cleanupTempDir(reportDir);
            Files.createDirectories(reportDir);
            Path paramsFile = reportDir.resolve("xUnitParams.json"); //$NON-NLS-1$
            String paramsJson = buildParamsJson(reportDir.resolve("junit.xml").toString(), extensions, modules, tests);
            Files.write(paramsFile, paramsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Activator.logInfo("YAXUnit params written to: " + paramsFile); //$NON-NLS-1$

            // 6. Find launch configuration
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return "**Error:** Launch manager is not available"; //$NON-NLS-1$
            }

            ILaunchConfigurationType configType = launchManager.getLaunchConfigurationType(LAUNCH_CONFIG_TYPE_ID);
            if (configType == null)
            {
                return "**Error:** Launch configuration type not found: " + LAUNCH_CONFIG_TYPE_ID; //$NON-NLS-1$
            }

            ILaunchConfiguration matchingConfig = findLaunchConfig(
                    launchManager, configType, projectName, applicationId);

            if (matchingConfig == null)
            {
                return buildNoConfigError(launchManager, configType, projectName, applicationId);
            }

            // 7. Create working copy with RunUnitTests startup parameter
            ILaunchConfigurationWorkingCopy workingCopy = matchingConfig.getWorkingCopy();
            String startupOption = "RunUnitTests=" + paramsFile.toString().replace('\\', '/'); //$NON-NLS-1$
            workingCopy.setAttribute(STARTUP_OPTION_ATTR, startupOption);

            Activator.logInfo("Launching YAXUnit tests: config=" + matchingConfig.getName() + //$NON-NLS-1$
                    ", startup=" + startupOption); //$NON-NLS-1$

            // 8. Launch in RUN mode and register
            ILaunch launch = workingCopy.launch(ILaunchManager.RUN_MODE, new NullProgressMonitor());
            ACTIVE_LAUNCHES.put(runKey, launch);

            // 9. Poll for completion up to `timeout` seconds
            String pollResult = pollLaunch(launch, reportDir, timeout, runKey);
            if (pollResult != null)
            {
                return pollResult;
            }

            // 10. Not finished within polling window — return Pending. DO NOT terminate the launch.
            return buildPendingMessage(reportDir, runKey);
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
            return "**Error:** No JUnit XML report found in " + reportDir + //$NON-NLS-1$
                    ". Make sure YAXUnit extension is installed in the infobase " + //$NON-NLS-1$
                    "and test configuration is correct."; //$NON-NLS-1$
        }

        return parseAndFormatResults(junitXml);
    }

    /**
     * Builds a Pending message that instructs the caller to invoke the tool again with
     * identical arguments to fetch the result.
     */
    private String buildPendingMessage(Path reportDir, String runKey)
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
        if (safeKey.length() > 80)
        {
            safeKey = safeKey.substring(0, 80);
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "edt-mcp-yaxunit", safeKey); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Computes a short hex SHA-1 hash for filter parts so the runKey is bounded.
     */
    private String sha1(String input)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-1"); //$NON-NLS-1$
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

        // Build filter
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
     * Finds a matching launch configuration for the project/application.
     */
    private ILaunchConfiguration findLaunchConfig(ILaunchManager launchManager,
            ILaunchConfigurationType configType, String projectName, String applicationId)
    {
        try
        {
            ILaunchConfiguration[] allConfigs = launchManager.getLaunchConfigurations(configType);

            // First try exact match (project + application)
            for (ILaunchConfiguration config : allConfigs)
            {
                try
                {
                    String configProject = config.getAttribute(ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                    String configAppId = config.getAttribute(ATTR_APPLICATION_ID, ""); //$NON-NLS-1$

                    if (projectName.equals(configProject) && applicationId.equals(configAppId))
                    {
                        return config;
                    }
                }
                catch (CoreException e)
                {
                    Activator.logError("Error reading launch configuration: " + config.getName(), e); //$NON-NLS-1$
                }
            }

            // Fallback: match by project only
            for (ILaunchConfiguration config : allConfigs)
            {
                try
                {
                    String configProject = config.getAttribute(ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                    if (projectName.equals(configProject))
                    {
                        return config;
                    }
                }
                catch (CoreException e)
                {
                    // Skip
                }
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Error searching launch configurations", e); //$NON-NLS-1$
        }

        return null;
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

        // List available configs
        try
        {
            ILaunchConfiguration[] allConfigs = launchManager.getLaunchConfigurations(configType);
            if (allConfigs.length > 0)
            {
                sb.append("Available launch configurations:\n\n"); //$NON-NLS-1$
                sb.append("| Name | Project | Application ID |\n"); //$NON-NLS-1$
                sb.append("|------|---------|----------------|\n"); //$NON-NLS-1$
                for (ILaunchConfiguration config : allConfigs)
                {
                    try
                    {
                        sb.append("| ").append(config.getName()); //$NON-NLS-1$
                        sb.append(" | ").append(config.getAttribute(ATTR_PROJECT_NAME, "")); //$NON-NLS-1$ //$NON-NLS-2$
                        sb.append(" | ").append(config.getAttribute(ATTR_APPLICATION_ID, "")); //$NON-NLS-1$ //$NON-NLS-2$
                        sb.append(" |\n"); //$NON-NLS-1$
                    }
                    catch (CoreException e)
                    {
                        // Skip
                    }
                }
            }
        }
        catch (CoreException e)
        {
            // Ignore
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

        // Try common names
        String[] candidates = {"junit.xml", "report.xml", "test-report.xml"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String name : candidates)
        {
            File f = tempDir.resolve(name).toFile();
            if (f.exists() && f.length() > 0)
            {
                return f;
            }
        }

        // Search for any XML file in the directory
        File[] xmlFiles = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".xml")); //$NON-NLS-1$
        if (xmlFiles != null && xmlFiles.length > 0)
        {
            return xmlFiles[0];
        }

        return null;
    }

    /**
     * Parses JUnit XML and formats results as Markdown.
     */
    private String parseAndFormatResults(File junitXml)
    {
        try
        {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Disable external entities for security
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(junitXml);
            doc.getDocumentElement().normalize();

            int totalTests = 0;
            int totalFailures = 0;
            int totalErrors = 0;
            int totalSkipped = 0;

            List<TestCaseResult> failures = new ArrayList<>();
            List<TestCaseResult> errors = new ArrayList<>();
            List<TestCaseResult> skipped = new ArrayList<>();

            // Parse testsuites or testsuite root
            NodeList suiteNodes = doc.getElementsByTagName("testsuite"); //$NON-NLS-1$
            for (int i = 0; i < suiteNodes.getLength(); i++)
            {
                Element suite = (Element) suiteNodes.item(i);

                totalTests += getIntAttr(suite, "tests", 0); //$NON-NLS-1$
                totalFailures += getIntAttr(suite, "failures", 0); //$NON-NLS-1$
                totalErrors += getIntAttr(suite, "errors", 0); //$NON-NLS-1$
                totalSkipped += getIntAttr(suite, "skipped", 0); //$NON-NLS-1$

                // Parse individual test cases
                NodeList caseNodes = suite.getElementsByTagName("testcase"); //$NON-NLS-1$
                for (int j = 0; j < caseNodes.getLength(); j++)
                {
                    Element testCase = (Element) caseNodes.item(j);
                    String className = testCase.getAttribute("classname"); //$NON-NLS-1$
                    String testName = testCase.getAttribute("name"); //$NON-NLS-1$
                    String fullName = (className != null && !className.isEmpty())
                            ? className + "." + testName //$NON-NLS-1$
                            : testName;

                    // Check for failure
                    NodeList failureNodes = testCase.getElementsByTagName("failure"); //$NON-NLS-1$
                    if (failureNodes.getLength() > 0)
                    {
                        Element failure = (Element) failureNodes.item(0);
                        failures.add(new TestCaseResult(fullName,
                                failure.getAttribute("message"), //$NON-NLS-1$
                                failure.getTextContent()));
                    }

                    // Check for error
                    NodeList errorNodes = testCase.getElementsByTagName("error"); //$NON-NLS-1$
                    if (errorNodes.getLength() > 0)
                    {
                        Element error = (Element) errorNodes.item(0);
                        errors.add(new TestCaseResult(fullName,
                                error.getAttribute("message"), //$NON-NLS-1$
                                error.getTextContent()));
                    }

                    // Check for skipped
                    NodeList skippedNodes = testCase.getElementsByTagName("skipped"); //$NON-NLS-1$
                    if (skippedNodes.getLength() > 0)
                    {
                        Element skip = (Element) skippedNodes.item(0);
                        skipped.add(new TestCaseResult(fullName,
                                skip.getAttribute("message"), //$NON-NLS-1$
                                null));
                    }
                }
            }

            // If no testsuite elements found, try parsing standalone testcases
            if (suiteNodes.getLength() == 0)
            {
                NodeList caseNodes = doc.getElementsByTagName("testcase"); //$NON-NLS-1$
                totalTests = caseNodes.getLength();
            }

            int totalPassed = totalTests - totalFailures - totalErrors - totalSkipped;
            if (totalPassed < 0)
            {
                totalPassed = 0;
            }

            // Format Markdown
            return formatMarkdown(totalTests, totalPassed, totalFailures, totalErrors,
                    totalSkipped, failures, errors, skipped);
        }
        catch (Exception e)
        {
            Activator.logError("Error parsing JUnit XML: " + junitXml, e); //$NON-NLS-1$
            return "**Error:** Failed to parse test results: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    /**
     * Formats test results as Markdown.
     */
    private String formatMarkdown(int total, int passed, int failures, int errors,
            int skipped, List<TestCaseResult> failureDetails,
            List<TestCaseResult> errorDetails, List<TestCaseResult> skippedDetails)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("# YAXUnit Test Results\n\n"); //$NON-NLS-1$

        // Summary table
        sb.append("## Summary\n\n"); //$NON-NLS-1$
        sb.append("| Metric | Count |\n"); //$NON-NLS-1$
        sb.append("|--------|-------|\n"); //$NON-NLS-1$
        sb.append("| Total  | ").append(total).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| Passed | ").append(passed).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| Failed | ").append(failures).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| Errors | ").append(errors).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| Skipped| ").append(skipped).append(" |\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Overall result
        if (failures == 0 && errors == 0)
        {
            sb.append("**Result: PASSED**\n"); //$NON-NLS-1$
        }
        else
        {
            sb.append("**Result: FAILED**\n"); //$NON-NLS-1$
        }

        // Failures
        if (!failureDetails.isEmpty())
        {
            sb.append("\n## Failures\n"); //$NON-NLS-1$
            for (TestCaseResult tc : failureDetails)
            {
                sb.append("\n### ").append(tc.name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                if (tc.message != null && !tc.message.isEmpty())
                {
                    sb.append("**Message:** ").append(tc.message).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (tc.trace != null && !tc.trace.trim().isEmpty())
                {
                    sb.append("```\n").append(tc.trace.trim()).append("\n```\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }

        // Errors
        if (!errorDetails.isEmpty())
        {
            sb.append("\n## Errors\n"); //$NON-NLS-1$
            for (TestCaseResult tc : errorDetails)
            {
                sb.append("\n### ").append(tc.name).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                if (tc.message != null && !tc.message.isEmpty())
                {
                    sb.append("**Message:** ").append(tc.message).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (tc.trace != null && !tc.trace.trim().isEmpty())
                {
                    sb.append("```\n").append(tc.trace.trim()).append("\n```\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }

        // Skipped
        if (!skippedDetails.isEmpty())
        {
            sb.append("\n## Skipped\n\n"); //$NON-NLS-1$
            for (TestCaseResult tc : skippedDetails)
            {
                sb.append("- **").append(tc.name).append("**"); //$NON-NLS-1$ //$NON-NLS-2$
                if (tc.message != null && !tc.message.isEmpty())
                {
                    sb.append(" — ").append(tc.message); //$NON-NLS-1$
                }
                sb.append("\n"); //$NON-NLS-1$
            }
        }

        return sb.toString();
    }

    /**
     * Gets an integer attribute from an XML element.
     */
    private int getIntAttr(Element element, String attr, int defaultValue)
    {
        String value = element.getAttribute(attr);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
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
        try
        {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
        catch (IOException e)
        {
            Activator.logError("Failed to cleanup temp directory: " + tempDir, e); //$NON-NLS-1$
        }
    }

    /**
     * Simple holder for test case results.
     */
    private static class TestCaseResult
    {
        final String name;
        final String message;
        final String trace;

        TestCaseResult(String name, String message, String trace)
        {
            this.name = name;
            this.message = message;
            this.trace = trace;
        }
    }
}

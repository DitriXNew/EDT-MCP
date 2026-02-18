/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonObject;

/**
 * Tool to run Vanessa Automation BDD tests for an EDT project.
 * Launches 1C:Enterprise in enterprise mode with Vanessa Automation processing,
 * executes specified feature files, and returns structured test results.
 */
public class RunVanessaTestsTool implements IMcpTool
{
    public static final String NAME = "run_vanessa_tests"; //$NON-NLS-1$

    /** 1C:EDT launch configuration type ID */
    private static final String LAUNCH_CONFIG_TYPE_ID = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$

    /** Launch configuration attributes */
    private static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$
    private static final String ATTR_APPLICATION_ID = "com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID"; //$NON-NLS-1$

    /** Default timeout in seconds */
    private static final int DEFAULT_TIMEOUT = 600;

    /** Maximum timeout in seconds (30 minutes) */
    private static final int MAX_TIMEOUT = 1800;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Run Vanessa Automation BDD tests for an EDT project. " + //$NON-NLS-1$
               "Launches 1C:Enterprise with Vanessa Automation processing, " + //$NON-NLS-1$
               "executes feature files and returns test results. " + //$NON-NLS-1$
               "Requires vanessa-automation.epf path to be configured in Preferences -> MCP Server."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application ID from get_applications. If not specified, uses default application.") //$NON-NLS-1$
            .stringProperty("featurePath", //$NON-NLS-1$
                "Path to feature file or directory. If not specified, runs all tests.") //$NON-NLS-1$
            .stringProperty("filterTags", //$NON-NLS-1$
                "Comma-separated tags to include, e.g. '@smoke,@critical'") //$NON-NLS-1$
            .stringProperty("ignoreTags", //$NON-NLS-1$
                "Comma-separated tags to exclude") //$NON-NLS-1$
            .integerProperty("timeout", //$NON-NLS-1$
                "Timeout in seconds (default: 600, max: 1800)") //$NON-NLS-1$
            .stringProperty("username", //$NON-NLS-1$
                "1C user name for authentication") //$NON-NLS-1$
            .stringProperty("password", //$NON-NLS-1$
                "1C user password for authentication (warning: visible in process arguments)") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String featurePath = JsonUtils.extractStringArgument(params, "featurePath"); //$NON-NLS-1$
        String filterTags = JsonUtils.extractStringArgument(params, "filterTags"); //$NON-NLS-1$
        String ignoreTags = JsonUtils.extractStringArgument(params, "ignoreTags"); //$NON-NLS-1$
        int timeout = JsonUtils.extractIntArgument(params, "timeout", DEFAULT_TIMEOUT); //$NON-NLS-1$
        String username = JsonUtils.extractStringArgument(params, "username"); //$NON-NLS-1$
        String password = JsonUtils.extractStringArgument(params, "password"); //$NON-NLS-1$

        // Validate projectName
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }

        // Validate Vanessa Automation path from preferences
        String vanessaEpfPath = Activator.getDefault().getPreferenceStore()
            .getString(PreferenceConstants.PREF_VANESSA_EPF_PATH);
        if (vanessaEpfPath == null || vanessaEpfPath.isEmpty())
        {
            return ToolResult.error("Не указан путь к обработке Vanessa Automation. " + //$NON-NLS-1$
                "Откройте Preferences \u2192 MCP Server и укажите путь к файлу " + //$NON-NLS-1$
                "vanessa-automation.epf в секции 'Vanessa Automation'.").toJson(); //$NON-NLS-1$
        }

        File vanessaEpfFile = new File(vanessaEpfPath);
        if (!vanessaEpfFile.exists())
        {
            return ToolResult.error("Файл обработки Vanessa Automation не найден: " + vanessaEpfPath + //$NON-NLS-1$
                ". Проверьте путь в Preferences \u2192 MCP Server.").toJson(); //$NON-NLS-1$
        }

        // Clamp timeout
        if (timeout <= 0)
        {
            timeout = DEFAULT_TIMEOUT;
        }
        if (timeout > MAX_TIMEOUT)
        {
            timeout = MAX_TIMEOUT;
        }

        // Check project readiness
        String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }

        return runTests(projectName, applicationId, vanessaEpfPath, featurePath,
            filterTags, ignoreTags, timeout, username, password);
    }

    private String runTests(String projectName, String applicationId, String vanessaEpfPath,
        String featurePath, String filterTags, String ignoreTags, int timeout,
        String username, String password)
    {
        Path tempDir = null;
        try
        {
            // Find project
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject project = workspace.getRoot().getProject(projectName);
            if (project == null || !project.exists() || !project.isOpen())
            {
                return ToolResult.error("Project not found or closed: " + projectName).toJson(); //$NON-NLS-1$
            }

            // Resolve application
            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return ToolResult.error("Application manager is not available").toJson(); //$NON-NLS-1$
            }

            IApplication application = null;
            try
            {
                if (applicationId != null && !applicationId.isEmpty())
                {
                    Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                    if (!appOpt.isPresent())
                    {
                        return ToolResult.error("Application not found: " + applicationId + //$NON-NLS-1$
                            ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
                    }
                    application = appOpt.get();
                }
                else
                {
                    Optional<IApplication> defaultApp = appManager.getDefaultApplication(project);
                    if (!defaultApp.isPresent())
                    {
                        return ToolResult.error("No default application found for project: " + projectName + //$NON-NLS-1$
                            ". Specify applicationId parameter or create an application in EDT.").toJson(); //$NON-NLS-1$
                    }
                    application = defaultApp.get();
                    applicationId = application.getId();
                }
            }
            catch (ApplicationException e)
            {
                Activator.logError("Error getting application", e); //$NON-NLS-1$
                return ToolResult.error("Error getting application: " + e.getMessage()).toJson(); //$NON-NLS-1$
            }

            // Create temp directory for VAParams and results
            tempDir = Files.createTempDirectory("vanessa-results"); //$NON-NLS-1$
            File junitReportFile = tempDir.resolve("junit-report.xml").toFile(); //$NON-NLS-1$
            File vaParamsFile = tempDir.resolve("VAParams.json").toFile(); //$NON-NLS-1$

            // Generate VAParams.json
            generateVAParams(vaParamsFile, junitReportFile.getAbsolutePath(),
                featurePath, filterTags, ignoreTags);

            // Find launch configuration
            ILaunchConfiguration launchConfig = findLaunchConfiguration(projectName, applicationId);
            if (launchConfig == null)
            {
                return ToolResult.error("No launch configuration found for project '" + projectName + //$NON-NLS-1$
                    "' and application '" + applicationId + "'. " + //$NON-NLS-1$ //$NON-NLS-2$
                    "Create a launch configuration in EDT first (Run/Debug configuration).").toJson(); //$NON-NLS-1$
            }

            // Build Vanessa Automation /C parameter
            StringBuilder cParam = new StringBuilder();
            cParam.append("StartFeaturePlayer"); //$NON-NLS-1$
            cParam.append(";QuietInstallVanessaExt"); //$NON-NLS-1$
            cParam.append(";VAParams=").append(vaParamsFile.getAbsolutePath()); //$NON-NLS-1$

            // Create working copy with additional arguments
            ILaunchConfigurationWorkingCopy workingCopy = launchConfig.getWorkingCopy();

            // Add Vanessa Automation arguments
            // NOTE: Using standard Eclipse program arguments attribute.
            // If EDT launch delegate does not honor this attribute, the correct
            // 1C-specific attribute name must be determined at runtime
            // (e.g. from com._1c.g5.v8.dt.launching.core constants).
            String existingArgs = workingCopy.getAttribute(
                "org.eclipse.debug.core.ATTR_PROGRAM_ARGUMENTS", ""); //$NON-NLS-1$ //$NON-NLS-2$
            StringBuilder additionalArgs = new StringBuilder();
            additionalArgs.append("/Execute \"").append(vanessaEpfPath).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
            additionalArgs.append(" /C\"").append(cParam).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
            additionalArgs.append(" /DisableStartupDialogs"); //$NON-NLS-1$
            additionalArgs.append(" /DisableStartupMessages"); //$NON-NLS-1$
            if (username != null && !username.isEmpty())
            {
                additionalArgs.append(" /N\"").append(username).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (password != null && !password.isEmpty())
            {
                additionalArgs.append(" /P\"").append(password).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
            }

            String fullArgs = existingArgs.isEmpty()
                ? additionalArgs.toString()
                : existingArgs + " " + additionalArgs.toString(); //$NON-NLS-1$
            workingCopy.setAttribute("org.eclipse.debug.core.ATTR_PROGRAM_ARGUMENTS", fullArgs); //$NON-NLS-1$

            Activator.logInfo("Launching Vanessa Automation tests: project=" + projectName + //$NON-NLS-1$
                ", application=" + applicationId + //$NON-NLS-1$
                ", featurePath=" + (featurePath != null ? featurePath : "all")); //$NON-NLS-1$ //$NON-NLS-2$

            // Launch in RUN mode (not DEBUG)
            ILaunch launch = workingCopy.launch(ILaunchManager.RUN_MODE, null);

            // Wait for process to terminate with timeout
            boolean terminated = waitForTermination(launch, timeout);

            if (!terminated)
            {
                // Timeout - force terminate
                terminateLaunch(launch);
                return ToolResult.error("Test execution timed out after " + timeout + //$NON-NLS-1$
                    " seconds. The 1C process was terminated.") //$NON-NLS-1$
                    .put("timedOut", true) //$NON-NLS-1$
                    .toJson();
            }

            // Collect process output
            String processOutput = collectProcessOutput(launch);

            // Check if JUnit report was generated
            if (!junitReportFile.exists())
            {
                ToolResult errorResult = ToolResult.error(
                    "JUnit report was not generated. Tests may have failed to start."); //$NON-NLS-1$
                if (processOutput != null && !processOutput.isEmpty())
                {
                    errorResult.put("outputLog", processOutput); //$NON-NLS-1$
                }
                return errorResult.toJson();
            }

            // Parse JUnit XML results
            JsonObject testResults = JUnitXmlParser.parse(junitReportFile);

            // Build response
            JsonObject summary = testResults.has("summary") //$NON-NLS-1$
                ? testResults.getAsJsonObject("summary") : null; //$NON-NLS-1$
            boolean allPassed = summary != null
                && summary.has("allPassed") //$NON-NLS-1$
                && summary.get("allPassed").getAsBoolean(); //$NON-NLS-1$

            ToolResult result = allPassed ? ToolResult.success() : ToolResult.error("Some tests failed"); //$NON-NLS-1$
            result.put("project", projectName); //$NON-NLS-1$
            result.put("applicationId", applicationId); //$NON-NLS-1$
            if (summary != null)
            {
                result.put("summary", summary); //$NON-NLS-1$
            }
            if (testResults.has("failures")) //$NON-NLS-1$
            {
                result.put("failures", testResults.getAsJsonArray("failures")); //$NON-NLS-1$
            }
            if (testResults.has("tests")) //$NON-NLS-1$
            {
                result.put("tests", testResults.getAsJsonArray("tests")); //$NON-NLS-1$
            }
            if (processOutput != null && !processOutput.isEmpty())
            {
                // Limit output to last 50 lines
                String[] lines = processOutput.split("\n"); //$NON-NLS-1$
                if (lines.length > 50)
                {
                    StringBuilder lastLines = new StringBuilder();
                    for (int i = lines.length - 50; i < lines.length; i++)
                    {
                        lastLines.append(lines[i]).append("\n"); //$NON-NLS-1$
                    }
                    result.put("outputLog", lastLines.toString()); //$NON-NLS-1$
                }
                else
                {
                    result.put("outputLog", processOutput); //$NON-NLS-1$
                }
            }

            return result.toJson();
        }
        catch (CoreException e)
        {
            Activator.logError("Error launching Vanessa Automation tests", e); //$NON-NLS-1$
            return ToolResult.error("Launch failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error running Vanessa tests", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        finally
        {
            // Cleanup temp directory
            if (tempDir != null)
            {
                cleanupTempDir(tempDir);
            }
        }
    }

    /**
     * Generates VAParams.json configuration file for Vanessa Automation.
     */
    private void generateVAParams(File vaParamsFile, String junitReportPath,
        String featurePath, String filterTags, String ignoreTags) throws IOException
    {
        JsonObject vaParams = new JsonObject();

        if (featurePath != null && !featurePath.isEmpty())
        {
            vaParams.addProperty("featurepath", featurePath); //$NON-NLS-1$
        }
        if (filterTags != null && !filterTags.isEmpty())
        {
            vaParams.addProperty("filtertags", filterTags); //$NON-NLS-1$
        }
        if (ignoreTags != null && !ignoreTags.isEmpty())
        {
            vaParams.addProperty("ignoretags", ignoreTags); //$NON-NLS-1$
        }

        vaParams.addProperty("junitcreatereport", true); //$NON-NLS-1$
        vaParams.addProperty("junitpath", junitReportPath); //$NON-NLS-1$

        // Close test client and 1C after tests complete
        // ЗакрытьTestClientПослеЗапускаСценариев
        vaParams.addProperty("ЗакрытьTestClientПослеЗапускаСценариев", true); //$NON-NLS-1$
        // ЗавершитьРаботуСистемыПослеТестирования
        vaParams.addProperty("ЗавершитьРаботуСистемыПослеТестирования", true); //$NON-NLS-1$

        try (FileWriter writer = new FileWriter(vaParamsFile, java.nio.charset.StandardCharsets.UTF_8))
        {
            writer.write(com.ditrix.edt.mcp.server.protocol.GsonProvider.toJson(vaParams));
        }
    }

    /**
     * Finds a launch configuration matching the project and application.
     */
    private ILaunchConfiguration findLaunchConfiguration(String projectName, String applicationId)
        throws CoreException
    {
        ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
        if (launchManager == null)
        {
            return null;
        }

        ILaunchConfigurationType configType = launchManager.getLaunchConfigurationType(LAUNCH_CONFIG_TYPE_ID);
        if (configType == null)
        {
            return null;
        }

        ILaunchConfiguration[] allConfigs = launchManager.getLaunchConfigurations(configType);

        // Try exact match first
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
                // Skip this config
            }
        }

        // Fallback - any config for this project
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

        return null;
    }

    /**
     * Waits for all processes in the launch to terminate.
     *
     * @return true if terminated, false if timed out
     */
    private boolean waitForTermination(ILaunch launch, int timeoutSeconds)
    {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline)
        {
            if (launch.isTerminated())
            {
                return true;
            }
            try
            {
                Thread.sleep(1000);
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
     * Force terminates all processes in the launch.
     */
    private void terminateLaunch(ILaunch launch)
    {
        try
        {
            IProcess[] processes = launch.getProcesses();
            for (IProcess process : processes)
            {
                if (process.canTerminate() && !process.isTerminated())
                {
                    process.terminate();
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error terminating launch processes", e); //$NON-NLS-1$
        }
    }

    /**
     * Collects stdout/stderr output from all processes in the launch.
     */
    private String collectProcessOutput(ILaunch launch)
    {
        StringBuilder output = new StringBuilder();
        IProcess[] processes = launch.getProcesses();
        for (IProcess process : processes)
        {
            try
            {
                IStreamsProxy streamsProxy = process.getStreamsProxy();
                if (streamsProxy != null)
                {
                    IStreamMonitor outMonitor = streamsProxy.getOutputStreamMonitor();
                    IStreamMonitor errMonitor = streamsProxy.getErrorStreamMonitor();
                    String stdout = outMonitor != null ? outMonitor.getContents() : null;
                    String stderr = errMonitor != null ? errMonitor.getContents() : null;
                    if (stdout != null && !stdout.isEmpty())
                    {
                        output.append(stdout);
                    }
                    if (stderr != null && !stderr.isEmpty())
                    {
                        if (output.length() > 0)
                        {
                            output.append("\n"); //$NON-NLS-1$
                        }
                        output.append("[STDERR]\n").append(stderr); //$NON-NLS-1$
                    }
                }
            }
            catch (Exception e)
            {
                // Ignore errors collecting output
            }
        }
        return output.toString();
    }

    /**
     * Cleans up the temporary directory and its contents.
     */
    private void cleanupTempDir(Path tempDir)
    {
        try
        {
            File[] files = tempDir.toFile().listFiles();
            if (files != null)
            {
                for (File file : files)
                {
                    file.delete();
                }
            }
            tempDir.toFile().delete();
        }
        catch (Exception e)
        {
            Activator.logError("Error cleaning up temp directory: " + tempDir, e); //$NON-NLS-1$
        }
    }
}

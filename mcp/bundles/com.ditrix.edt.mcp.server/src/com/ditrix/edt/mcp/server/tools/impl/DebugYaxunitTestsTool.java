/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Launches YAXUnit tests in <strong>DEBUG mode</strong> so that breakpoints
 * set via {@code set_breakpoint} actually trip when the test runs the code
 * under inspection.
 *
 * <p>Unlike {@code run_yaxunit_tests}, this tool does NOT poll for {@code junit.xml}.
 * After the launch is queued, control returns to the caller immediately and the
 * LLM is expected to call {@code wait_for_break} next. The full debug cycle is:
 *
 * <pre>
 *   set_breakpoint → debug_yaxunit_tests → wait_for_break
 *   → get_variables / evaluate_expression / step → resume
 * </pre>
 *
 * <p>The junit.xml report still gets written to the same {@code reportDir} the
 * tool returns, so a follow-up call to {@code run_yaxunit_tests} (or any file
 * read) can pick it up after the test finishes.
 */
public class DebugYaxunitTestsTool implements IMcpTool
{
    public static final String NAME = "debug_yaxunit_tests"; //$NON-NLS-1$
    private static final AtomicLong LAUNCH_COUNTER = new AtomicLong(0);

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Launch YAXUnit tests in DEBUG mode so breakpoints fire. " //$NON-NLS-1$
            + "Returns immediately after the launch is queued — call wait_for_break next " //$NON-NLS-1$
            + "to block until a breakpoint is hit, then inspect with get_variables / " //$NON-NLS-1$
            + "evaluate_expression / step / resume. " //$NON-NLS-1$
            + "Use a tight tests filter (single test method) to make the cycle predictable. " //$NON-NLS-1$
            + "Requires an existing 1C launch configuration and YAXUnit installed in the infobase."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application id from get_applications (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("extensions", "Comma-separated extension names to filter tests by extension") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modules", "Comma-separated module names to filter tests") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("tests", "Comma-separated test names in Module.Method format (recommended: pin to one test)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String extensions = JsonUtils.extractStringArgument(params, "extensions"); //$NON-NLS-1$
        String modules = JsonUtils.extractStringArgument(params, "modules"); //$NON-NLS-1$
        String tests = JsonUtils.extractStringArgument(params, "tests"); //$NON-NLS-1$

        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult.error("applicationId is required").toJson(); //$NON-NLS-1$
        }

        String notReady = ProjectStateChecker.checkReadyOrError(projectName);
        if (notReady != null)
        {
            return ToolResult.error(notReady).toJson();
        }

        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project == null || !project.exists())
            {
                return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
            }
            if (!project.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }

            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return ToolResult.error("IApplicationManager service not available").toJson(); //$NON-NLS-1$
            }
            try
            {
                Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                if (!appOpt.isPresent())
                {
                    return ToolResult.error("Application not found: " + applicationId).toJson(); //$NON-NLS-1$
                }
            }
            catch (ApplicationException e)
            {
                return ToolResult.error("Failed to validate application: " + e.getMessage()).toJson(); //$NON-NLS-1$
            }

            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunchConfigurationType configType =
                launchManager.getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID);
            if (configType == null)
            {
                return ToolResult.error("Launch configuration type not found").toJson(); //$NON-NLS-1$
            }
            ILaunchConfiguration matchingConfig = LaunchConfigUtils.findLaunchConfig(
                launchManager, configType, projectName, applicationId);
            if (matchingConfig == null)
            {
                return ToolResult.error("No launch configuration for project '" + projectName //$NON-NLS-1$
                        + "' and application '" + applicationId + "'. Create one in EDT first.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }

            // Prepare a unique report dir + xUnitParams.json (uses native path separators
            // because YAXUnit constructs file:// URIs and breaks on forward slashes on Windows).
            Path reportDir = Paths.get(System.getProperty("java.io.tmpdir"), //$NON-NLS-1$
                "edt-mcp-yaxunit-debug", projectName + "-" + System.currentTimeMillis() //$NON-NLS-1$ //$NON-NLS-2$
                    + "-" + LAUNCH_COUNTER.getAndIncrement()); //$NON-NLS-1$
            Files.createDirectories(reportDir);
            Path paramsFile = reportDir.resolve("xUnitParams.json"); //$NON-NLS-1$
            Path junitFile = reportDir.resolve("junit.xml"); //$NON-NLS-1$
            String paramsJson = buildParamsJson(junitFile.toString(), extensions, modules, tests);
            Files.write(paramsFile, paramsJson.getBytes(StandardCharsets.UTF_8));

            // Make sure suspend listener is in place before the launch starts producing events.
            DebugSessionRegistry.get().ensureListenerRegistered();

            ILaunchConfigurationWorkingCopy workingCopy = matchingConfig.getWorkingCopy();
            String startupOption = "RunUnitTests=" + paramsFile.toString(); //$NON-NLS-1$
            workingCopy.setAttribute(LaunchConfigUtils.ATTR_STARTUP_OPTION, startupOption);

            Activator.logInfo("Launching YAXUnit tests in DEBUG mode: config=" + matchingConfig.getName() //$NON-NLS-1$
                + ", startup=" + startupOption); //$NON-NLS-1$

            // Launch the working copy directly so our ATTR_STARTUP_OPTION mutation
            // actually takes effect (DebugUITools.launch on a working copy can
            // re-resolve to the saved config and silently drop our changes).
            try
            {
                workingCopy.launch(ILaunchManager.DEBUG_MODE, new org.eclipse.core.runtime.NullProgressMonitor());
            }
            catch (Exception ex)
            {
                Activator.logError("Failed to launch YAXUnit in debug mode", ex); //$NON-NLS-1$
                return ToolResult.error("Launch failed: " + ex.getMessage()).toJson(); //$NON-NLS-1$
            }

            return ToolResult.success()
                .put("launched", true) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("reportDir", reportDir.toString()) //$NON-NLS-1$
                .put("junitXml", junitFile.toString()) //$NON-NLS-1$
                .put("nextStep", "call wait_for_break with the same applicationId") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error in debug_yaxunit_tests", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private static String buildParamsJson(String reportPath, String extensions, String modules, String tests)
    {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("reportPath", reportPath); //$NON-NLS-1$
        p.put("reportFormat", "jUnit"); //$NON-NLS-1$ //$NON-NLS-2$
        p.put("closeAfterTests", true); //$NON-NLS-1$
        Map<String, Object> filter = new LinkedHashMap<>();
        boolean hasFilter = false;
        if (extensions != null && !extensions.isEmpty())
        {
            filter.put("extensions", split(extensions)); //$NON-NLS-1$
            hasFilter = true;
        }
        if (modules != null && !modules.isEmpty())
        {
            filter.put("modules", split(modules)); //$NON-NLS-1$
            hasFilter = true;
        }
        if (tests != null && !tests.isEmpty())
        {
            filter.put("tests", split(tests)); //$NON-NLS-1$
            hasFilter = true;
        }
        if (hasFilter)
        {
            p.put("filter", filter); //$NON-NLS-1$
        }
        return GsonProvider.toJson(p);
    }

    private static List<String> split(String csv)
    {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) //$NON-NLS-1$
        {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}

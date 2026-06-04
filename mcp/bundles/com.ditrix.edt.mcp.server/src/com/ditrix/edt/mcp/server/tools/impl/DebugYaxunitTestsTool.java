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
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
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
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.PreLaunchResult;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
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
        return "Launch YAXUnit tests in DEBUG mode so breakpoints fire, then call wait_for_break " //$NON-NLS-1$
            + "to inspect; use when you need to step through test-driven code, not just get a pass/fail " //$NON-NLS-1$
            + "report (use run_yaxunit_tests for that). Requires an existing 1C launch configuration and " //$NON-NLS-1$
            + "YAXUnit installed in the infobase. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('debug_yaxunit_tests')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact runtime-client launch config name (preferred; from list_configurations).") //$NON-NLS-1$
            .stringProperty("projectName", "EDT project name (required if launchConfigurationName is omitted).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application id from get_applications (required if launchConfigurationName is omitted).") //$NON-NLS-1$
            .stringProperty("extensions", "Comma-separated extension names to filter tests by extension.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("modules", "Comma-separated module names to filter tests.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("tests", //$NON-NLS-1$
                "Comma-separated test names as Module.Method (recommended: pin to one test for a predictable cycle).") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Default true: terminate any live client and run a silent DB update first so no modal " //$NON-NLS-1$
                    + "'Update database?' dialog blocks the call; false keeps legacy delegate behaviour.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("launched", "Whether the debug launch was queued") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "Effective EDT project name of the launched config") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Effective application id of the launched config") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("reportDir", "Directory where junit.xml will be written") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("junitXml", "Full path to the junit.xml report file") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("nextStep", "Hint for the next tool call in the debug cycle") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("preLaunch", "Summary of pre-launch terminations and DB update") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# debug_yaxunit_tests\n\n" //$NON-NLS-1$
            + "Launches YAXUnit tests in **DEBUG mode** so that breakpoints set via `set_breakpoint` " //$NON-NLS-1$
            + "actually trip when the test executes the code under inspection. Unlike " //$NON-NLS-1$
            + "`run_yaxunit_tests`, this tool does NOT poll for `junit.xml`: after the launch is queued " //$NON-NLS-1$
            + "control returns immediately and you are expected to call `wait_for_break` next.\n\n" //$NON-NLS-1$
            + "## When to use\n" //$NON-NLS-1$
            + "- You need to step through code that a YAXUnit test drives and inspect variables/expressions.\n" //$NON-NLS-1$
            + "- You do NOT just want a pass/fail report — for that use `run_yaxunit_tests` (it polls the report).\n\n" //$NON-NLS-1$
            + "## Preconditions\n" //$NON-NLS-1$
            + "- An existing 1C runtime-client launch configuration (see `list_configurations`).\n" //$NON-NLS-1$
            + "- YAXUnit installed in the target infobase.\n" //$NON-NLS-1$
            + "- Set your breakpoints with `set_breakpoint` BEFORE calling this tool.\n\n" //$NON-NLS-1$
            + "## The full debug cycle\n" //$NON-NLS-1$
            + "```\n" //$NON-NLS-1$
            + "set_breakpoint -> debug_yaxunit_tests -> wait_for_break\n" //$NON-NLS-1$
            + "  -> get_variables / evaluate_expression / step -> resume\n" //$NON-NLS-1$
            + "```\n" //$NON-NLS-1$
            + "Use a tight `tests` filter (a single Module.Method) so exactly one breakpoint trips and the " //$NON-NLS-1$
            + "cycle stays predictable.\n\n" //$NON-NLS-1$
            + "## Parameters\n" //$NON-NLS-1$
            + "Identify the launch one of two ways:\n" //$NON-NLS-1$
            + "- `launchConfigurationName` (preferred): the exact runtime-client config name from " //$NON-NLS-1$
            + "`list_configurations`. When given, `projectName`/`applicationId` are derived from it.\n" //$NON-NLS-1$
            + "- OR both `projectName` (EDT project) AND `applicationId` (from `get_applications`) when " //$NON-NLS-1$
            + "`launchConfigurationName` is omitted. Either one alone is an error.\n\n" //$NON-NLS-1$
            + "Test filters (all optional, comma-separated, AND-combined):\n" //$NON-NLS-1$
            + "- `extensions` — extension names, e.g. `MyExtension`.\n" //$NON-NLS-1$
            + "- `modules` — module names, e.g. `OrderTests,InvoiceTests`.\n" //$NON-NLS-1$
            + "- `tests` — `Module.Method` names, e.g. `OrderTests.ShouldComputeTotal`. Recommended: pin to ONE.\n\n" //$NON-NLS-1$
            + "Auto-chain switch:\n" //$NON-NLS-1$
            + "- `updateBeforeLaunch` (default `true`) — before spawning the debug launch, politely terminate " //$NON-NLS-1$
            + "any live 1C client running this configuration and run a silent DB update, so EDT's launch " //$NON-NLS-1$
            + "delegate does not pop its modal 'Update database?' dialog that would otherwise block the MCP " //$NON-NLS-1$
            + "call. Set `false` to keep legacy behaviour (the delegate decides; the dialog may appear).\n\n" //$NON-NLS-1$
            + "## Examples\n" //$NON-NLS-1$
            + "Debug a single test by config name:\n" //$NON-NLS-1$
            + "```json\n" //$NON-NLS-1$
            + "{ \"launchConfigurationName\": \"MyApp - client\", \"tests\": \"OrderTests.ShouldComputeTotal\" }\n" //$NON-NLS-1$
            + "```\n" //$NON-NLS-1$
            + "Debug by project + application:\n" //$NON-NLS-1$
            + "```json\n" //$NON-NLS-1$
            + "{ \"projectName\": \"MyApp\", \"applicationId\": \"app-123\", \"modules\": \"OrderTests\" }\n" //$NON-NLS-1$
            + "```\n\n" //$NON-NLS-1$
            + "## Result\n" //$NON-NLS-1$
            + "JSON with `launched`, the effective `projectName`/`applicationId`, the `reportDir`, the " //$NON-NLS-1$
            + "`junitXml` path, and `nextStep`. The `junit.xml` report is still written to `reportDir` after " //$NON-NLS-1$
            + "the test finishes, so a later `run_yaxunit_tests` call (or any file read) can pick it up.\n\n" //$NON-NLS-1$
            + "## Gotchas\n" //$NON-NLS-1$
            + "- After this returns you MUST call `wait_for_break` with the SAME `applicationId` to block until " //$NON-NLS-1$
            + "a breakpoint is hit; this tool does not wait.\n" //$NON-NLS-1$
            + "- Without a `tests` filter, every matching test runs and any of their breakpoints may trip first.\n" //$NON-NLS-1$
            + "- If a previous launch is stuck, the pre-launch step fails: call `terminate_launch` with " //$NON-NLS-1$
            + "`force=true` and retry, or as a last resort pass `updateBeforeLaunch=false` (the modal dialog " //$NON-NLS-1$
            + "may then block the call).\n" //$NON-NLS-1$
            + "- The config must be a runtime-client launch config; other launch types are rejected.\n"; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
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
                return ToolResult.error("applicationId is required (or pass launchConfigurationName)").toJson(); //$NON-NLS-1$
            }
        }

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
                return ToolResult.error(hasName
                    ? "Launch configuration not found: '" + configName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                    : "No runtime-client launch configuration for project '" + projectName //$NON-NLS-1$
                        + "' and application '" + applicationId //$NON-NLS-1$
                        + "'. Use list_configurations to see what's available.").toJson(); //$NON-NLS-1$
            }

            if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(matchingConfig)))
            {
                return ToolResult.error("Launch configuration '" + matchingConfig.getName() //$NON-NLS-1$
                    + "' is not a runtime-client config — YAXUnit tests require one.").toJson(); //$NON-NLS-1$
            }

            // Derive effective project/application from the resolved config so
            // subsequent validation and the success response match reality.
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

            // Refuse only the transient BUILDING state; a missing/closed project
            // falls through to the value-naming "Project not found" below.
            String building = ProjectStateChecker.buildingErrorOrNull(projectName);
            if (building != null)
            {
                return ToolResult.error(building).toJson();
            }

            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.exists())
            {
                return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
            }
            if (!ctx.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }
            IProject project = ctx.project();

            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return ToolResult.error("IApplicationManager service not available").toJson(); //$NON-NLS-1$
            }
            if (applicationId != null && !applicationId.isEmpty())
            {
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

            // Auto-chain + spawn under the per-key lock — closes the window
            // between workingCopy.launch() and registerOwnedLaunch in which a
            // concurrent call against the same IB could otherwise terminate
            // this fresh debug launch. Different (project, applicationId) pairs
            // run in parallel.
            PreLaunchResult preLaunch = null;
            synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
            {
                if (updateBeforeLaunch)
                {
                    int terminateTimeout = LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds();
                    preLaunch = LaunchLifecycleUtils.prepareForFreshLaunch(launchManager, project,
                        applicationId, appManager, terminateTimeout);
                    if (!preLaunch.isOk())
                    {
                        return ToolResult.error("Pre-launch preparation failed: " //$NON-NLS-1$
                            + preLaunch.getError()
                            + ". If the previous launch is stuck, call terminate_launch " //$NON-NLS-1$
                            + "with force=true and retry. As a last resort, pass " //$NON-NLS-1$
                            + "updateBeforeLaunch=false — but the EDT launch delegate may " //$NON-NLS-1$
                            + "then pop a modal dialog that blocks the MCP call.").toJson(); //$NON-NLS-1$
                    }
                }

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
                    ILaunch spawned = workingCopy.launch(ILaunchManager.DEBUG_MODE,
                        new org.eclipse.core.runtime.NullProgressMonitor());
                    // Register inside the per-key lock so a concurrent auto-chain
                    // for the same IB sees this launch as owned and refuses to
                    // terminate it.
                    LaunchLifecycleUtils.registerOwnedLaunch(spawned);
                }
                catch (Exception ex)
                {
                    Activator.logError("Failed to launch YAXUnit in debug mode", ex); //$NON-NLS-1$
                    return ToolResult.error("Launch failed: " + ex.getMessage()).toJson(); //$NON-NLS-1$
                }
            }

            ToolResult result = ToolResult.success()
                .put("launched", true) //$NON-NLS-1$
                .put("projectName", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("reportDir", reportDir.toString()) //$NON-NLS-1$
                .put("junitXml", junitFile.toString()) //$NON-NLS-1$
                .put("nextStep", "call wait_for_break with the same applicationId"); //$NON-NLS-1$ //$NON-NLS-2$
            if (preLaunch != null && preLaunch.getTerminatedCount() > 0)
            {
                result.put("preLaunch", preLaunch.summary()); //$NON-NLS-1$
            }
            return result.toJson();
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

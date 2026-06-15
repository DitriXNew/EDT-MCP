/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.HashMap;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Deprecated alias for {@code run_yaxunit_tests} with {@code debug=true}.
 *
 * <p>The two tools were near-twins (identical launch selector + filter
 * parameters; the only difference was the launch mode), so they were merged
 * behind a {@code debug} flag on {@code run_yaxunit_tests}. This tool is kept as
 * a thin backward-compatible alias: it forwards its arguments to
 * {@code run_yaxunit_tests} with {@code debug=true} and returns the same
 * Markdown launch handle (which points at {@code wait_for_break}).
 *
 * @deprecated prefer {@code run_yaxunit_tests} with {@code debug=true}.
 */
@Deprecated
public class DebugYaxunitTestsTool implements IMcpTool
{
    public static final String NAME = "debug_yaxunit_tests"; //$NON-NLS-1$

    /** Input param: comma-separated update scope for the pre-launch auto-chain. */
    private static final String KEY_UPDATE_SCOPE = "updateScope"; //$NON-NLS-1$

    /** Input param: exact runtime-client launch configuration name. */
    private static final String KEY_LAUNCH_CONFIGURATION_NAME = "launchConfigurationName"; //$NON-NLS-1$

    /** Input param: comma-separated extension names to filter tests by extension. */
    private static final String KEY_EXTENSIONS = "extensions"; //$NON-NLS-1$

    /** Input param: comma-separated module names to filter tests. */
    private static final String KEY_MODULES = "modules"; //$NON-NLS-1$

    /** Input param: comma-separated test names as Module.Method. */
    private static final String KEY_TESTS = "tests"; //$NON-NLS-1$

    /** Input param: whether to run a silent DB update before launch. */
    private static final String KEY_UPDATE_BEFORE_LAUNCH = "updateBeforeLaunch"; //$NON-NLS-1$

    /**
     * The merged implementation. A fresh instance is fine — all of
     * {@code RunYaxunitTestsTool}'s launch state is static, so this shares the
     * same active-launch registry as the registered {@code run_yaxunit_tests}.
     */
    private static final RunYaxunitTestsTool DELEGATE = new RunYaxunitTestsTool();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Deprecated alias for run_yaxunit_tests with debug=true. Launches YAXUnit tests in DEBUG " //$NON-NLS-1$
            + "mode so breakpoints fire, then call wait_for_break to inspect. Prefer " //$NON-NLS-1$
            + "run_yaxunit_tests(debug=true) — identical behaviour. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('debug_yaxunit_tests')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_LAUNCH_CONFIGURATION_NAME,
                "Exact runtime-client launch config name (preferred; from list_configurations).") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name (required if launchConfigurationName is omitted).") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application id from get_applications (required if launchConfigurationName is omitted).") //$NON-NLS-1$
            .stringProperty(KEY_EXTENSIONS, "Comma-separated extension names to filter tests by extension.") //$NON-NLS-1$
            .stringProperty(KEY_MODULES, "Comma-separated module names to filter tests.") //$NON-NLS-1$
            .stringProperty(KEY_TESTS,
                "Comma-separated test names as Module.Method (recommended: pin to one test for a predictable cycle).") //$NON-NLS-1$
            .booleanProperty(KEY_UPDATE_BEFORE_LAUNCH,
                "Default true: terminate any live client and run a silent DB update first so no modal " //$NON-NLS-1$
                    + "'Update database?' dialog blocks the call; false keeps legacy delegate behaviour — " //$NON-NLS-1$
                    + "no client sweep, no auto-confirmed update dialog; platform dialogs may appear.") //$NON-NLS-1$
            .stringProperty(KEY_UPDATE_SCOPE, RunYaxunitTestsTool.UPDATE_SCOPE_DESCRIPTION)
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Deprecated alias: forward the accepted arguments to the merged tool in
        // DEBUG mode. Each key is copied by its literal name so this forwarding
        // shim still satisfies schema/execute parity (rule #6), and the explicit
        // list documents exactly what the alias accepts.
        Map<String, String> forwarded = new HashMap<>();
        putIfPresent(forwarded, KEY_LAUNCH_CONFIGURATION_NAME, params.get(KEY_LAUNCH_CONFIGURATION_NAME));
        putIfPresent(forwarded, McpKeys.PROJECT_NAME, params.get(McpKeys.PROJECT_NAME));
        putIfPresent(forwarded, McpKeys.APPLICATION_ID, params.get(McpKeys.APPLICATION_ID));
        putIfPresent(forwarded, KEY_EXTENSIONS, params.get(KEY_EXTENSIONS));
        putIfPresent(forwarded, KEY_MODULES, params.get(KEY_MODULES));
        putIfPresent(forwarded, KEY_TESTS, params.get(KEY_TESTS));
        putIfPresent(forwarded, KEY_UPDATE_BEFORE_LAUNCH, params.get(KEY_UPDATE_BEFORE_LAUNCH));
        putIfPresent(forwarded, KEY_UPDATE_SCOPE, params.get(KEY_UPDATE_SCOPE));
        forwarded.put("debug", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        return DELEGATE.execute(forwarded);
    }

    private static void putIfPresent(Map<String, String> target, String key, String value)
    {
        if (value != null)
        {
            target.put(key, value);
        }
    }
}

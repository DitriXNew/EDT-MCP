/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.HashMap;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
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
    public String getGuide()
    {
        return "# debug_yaxunit_tests (deprecated)\n\n" //$NON-NLS-1$
            + "**Deprecated alias.** Use `run_yaxunit_tests` with `debug=true` — identical behaviour. " //$NON-NLS-1$
            + "This tool simply forwards its arguments to `run_yaxunit_tests(debug=true)`.\n\n" //$NON-NLS-1$
            + "Launches YAXUnit tests in **DEBUG mode** so that breakpoints set via `set_breakpoint` trip " //$NON-NLS-1$
            + "when the test executes the code under inspection. It does NOT poll for `junit.xml`: after " //$NON-NLS-1$
            + "the launch is queued it returns a Markdown launch handle and you call `wait_for_break` next.\n\n" //$NON-NLS-1$
            + "## The full debug cycle\n" //$NON-NLS-1$
            + "```\n" //$NON-NLS-1$
            + "set_breakpoint -> run_yaxunit_tests(debug=true) -> wait_for_break\n" //$NON-NLS-1$
            + "  -> get_variables / evaluate_expression / step -> resume\n" //$NON-NLS-1$
            + "```\n\n" //$NON-NLS-1$
            + "## Parameters\n" //$NON-NLS-1$
            + "Same as `run_yaxunit_tests` (minus `timeout`, which DEBUG mode ignores): identify the launch " //$NON-NLS-1$
            + "by `launchConfigurationName`, or by `projectName` + `applicationId`; filter with " //$NON-NLS-1$
            + "`extensions` / `modules` / `tests` (pin to ONE test for a predictable cycle); " //$NON-NLS-1$
            + "`updateBeforeLaunch` (default true) silences the modal update dialog. See " //$NON-NLS-1$
            + "`get_tool_guide('run_yaxunit_tests')` for the full reference.\n"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Deprecated alias: forward the accepted arguments to the merged tool in
        // DEBUG mode. Each key is copied by its literal name so this forwarding
        // shim still satisfies schema/execute parity (rule #6), and the explicit
        // list documents exactly what the alias accepts.
        Map<String, String> forwarded = new HashMap<>();
        putIfPresent(forwarded, "launchConfigurationName", params.get("launchConfigurationName")); //$NON-NLS-1$ //$NON-NLS-2$
        putIfPresent(forwarded, "projectName", params.get("projectName")); //$NON-NLS-1$ //$NON-NLS-2$
        putIfPresent(forwarded, "applicationId", params.get("applicationId")); //$NON-NLS-1$ //$NON-NLS-2$
        putIfPresent(forwarded, "extensions", params.get("extensions")); //$NON-NLS-1$ //$NON-NLS-2$
        putIfPresent(forwarded, "modules", params.get("modules")); //$NON-NLS-1$ //$NON-NLS-2$
        putIfPresent(forwarded, "tests", params.get("tests")); //$NON-NLS-1$ //$NON-NLS-2$
        putIfPresent(forwarded, "updateBeforeLaunch", params.get("updateBeforeLaunch")); //$NON-NLS-1$ //$NON-NLS-2$
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

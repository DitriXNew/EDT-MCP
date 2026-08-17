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
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;

/**
 * Deprecated alias for {@code run_yaxunit_tests} with {@code debug=true}.
 *
 * <p>The two tools were near-twins (identical launch selector + filter
 * parameters; the only difference was the launch mode), so they were merged
 * behind a {@code debug} flag on {@code run_yaxunit_tests}. This tool is kept as
 * a thin backward-compatible alias: it forwards its arguments to
 * {@code run_yaxunit_tests} with {@code debug=true}. Both surfaces use the same named-job
 * implementation: a short start returns the Markdown launch handle synchronously, while a longer
 * resolve/preparation returns a job id for {@code get_job_status}.
 *
 * @deprecated prefer {@code run_yaxunit_tests} with {@code debug=true}.
 */
@Deprecated
public class DebugYaxunitTestsTool implements IMcpTool // NOSONAR intentional retained backward-compatible deprecated alias
{
    public static final String NAME = "debug_yaxunit_tests"; //$NON-NLS-1$

    /** Input param: comma-separated update scope for the pre-launch auto-chain. */
    private static final String KEY_UPDATE_SCOPE = "updateScope"; //$NON-NLS-1$

    private static final String KEY_EXTERNAL_INFOBASE_CHANGES = "externalInfobaseChanges"; //$NON-NLS-1$

    /**
     * Input param: how EDT's standalone-server port-conflict modal is answered.
     *
     * <p>Carried by the alias for the same reason as the filters: an argument this shim does not
     * list is dropped silently, and dropping THIS one makes the refusal's own advice impossible to
     * follow — the error tells the caller to re-call with {@code reassign}, and the alias would
     * throw that value away again.
     */
    private static final String KEY_PORT_CONFLICT = "standaloneServerPortConflict"; //$NON-NLS-1$

    /** Input param: exact runtime-client launch configuration name. */
    private static final String KEY_LAUNCH_CONFIGURATION_NAME = "launchConfigurationName"; //$NON-NLS-1$

    /** Input param: comma-separated extension names to filter tests by extension. */
    private static final String KEY_EXTENSIONS = "extensions"; //$NON-NLS-1$

    /** Input param: comma-separated module names to filter tests. */
    private static final String KEY_MODULES = "modules"; //$NON-NLS-1$

    /** Input param: comma-separated test names as Module.Method. */
    private static final String KEY_TESTS = "tests"; //$NON-NLS-1$

    /**
     * Input param: comma-separated YAXUnit tags to select tests by.
     *
     * <p>Carried by the alias rather than left to the delegate: an argument this shim does not
     * list is dropped silently, and a dropped TAG filter is the one failure mode that looks like
     * success — the run would start unfiltered and execute every test instead of the requested
     * slice. The alias promises "identical behaviour", so the filter families have to stay in step.
     */
    private static final String KEY_TAGS = "tags"; //$NON-NLS-1$

    /** Input param: whether to run a silent DB update before launch. */
    private static final String KEY_UPDATE_BEFORE_LAUNCH = "updateBeforeLaunch"; //$NON-NLS-1$

    /** Input param: bounded wait for this start call's named background job. */
    private static final String KEY_TIMEOUT = "timeout"; //$NON-NLS-1$

    /**
     * The merged implementation. A fresh instance is fine — all of
     * {@code RunYaxunitTestsTool}'s launch state is static, so this shares the
     * same active-launch registry as the registered {@code run_yaxunit_tests}.
     */
    private final RunYaxunitTestsTool delegate;

    public DebugYaxunitTestsTool()
    {
        this(new RunYaxunitTestsTool());
    }

    DebugYaxunitTestsTool(RunYaxunitTestsTool delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "DEPRECATED alias of run_yaxunit_tests(debug=true) - prefer that instead; the "  //$NON-NLS-1$
            + "implementation is shared. DEBUG mode, so breakpoints fire: a short start returns "  //$NON-NLS-1$
            + "the launch handle and you call wait_for_break next, while Pending returns a jobId "  //$NON-NLS-1$
            + "to poll with get_job_status. Parameters and examples: "  //$NON-NLS-1$
            + "get_tool_guide('debug_yaxunit_tests')."; //$NON-NLS-1$
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
            .stringArrayProperty(KEY_EXTENSIONS,
                "Extension names to filter tests (array; a comma-separated string is also accepted).") //$NON-NLS-1$
            .stringArrayProperty(KEY_MODULES,
                "Module names to filter tests (array; a comma-separated string is also accepted).") //$NON-NLS-1$
            .stringArrayProperty(KEY_TESTS,
                "Test names in Module.Method format (array; a comma-separated string is also accepted; pin to one test for a predictable debug cycle).") //$NON-NLS-1$
            .stringArrayProperty(KEY_TAGS,
                "YAXUnit tags to select tests by (array; a comma-separated string is also accepted). " //$NON-NLS-1$
                    + "A test is selected when its module, " //$NON-NLS-1$
                    + "its suite, or the test itself carries one of these tags; matching is " //$NON-NLS-1$
                    + "case-insensitive and exclusion is not supported by YAXUnit.") //$NON-NLS-1$
            .integerProperty(KEY_TIMEOUT, RunYaxunitTestsTool.TIMEOUT_DESCRIPTION)
            .booleanProperty(KEY_UPDATE_BEFORE_LAUNCH,
                "Default true: terminate any live client and run a silent DB update first so no modal " //$NON-NLS-1$
                    + "'Update database?' dialog blocks the call; false keeps legacy delegate behaviour — " //$NON-NLS-1$
                    + "no client sweep, no auto-confirmed update dialog; platform dialogs may appear.") //$NON-NLS-1$
            .stringProperty(KEY_UPDATE_SCOPE, RunYaxunitTestsTool.UPDATE_SCOPE_DESCRIPTION)
            .stringProperty(KEY_EXTERNAL_INFOBASE_CHANGES,
                RunYaxunitTestsTool.EXTERNAL_INFOBASE_CHANGES_DESCRIPTION)
            .stringProperty(KEY_PORT_CONFLICT,
                StandaloneServerPortConflictPolicy.PARAMETER_DESCRIPTION)
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
        putIfPresent(forwarded, KEY_TAGS, params.get(KEY_TAGS));
        putIfPresent(forwarded, KEY_TIMEOUT, params.get(KEY_TIMEOUT));
        putIfPresent(forwarded, KEY_UPDATE_BEFORE_LAUNCH, params.get(KEY_UPDATE_BEFORE_LAUNCH));
        putIfPresent(forwarded, KEY_UPDATE_SCOPE, params.get(KEY_UPDATE_SCOPE));
        putIfPresent(forwarded, KEY_EXTERNAL_INFOBASE_CHANGES,
            params.get(KEY_EXTERNAL_INFOBASE_CHANGES));
        putIfPresent(forwarded, KEY_PORT_CONFLICT, params.get(KEY_PORT_CONFLICT));
        forwarded.put("debug", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        return delegate.executeAs(forwarded, NAME);
    }

    private static void putIfPresent(Map<String, String> target, String key, String value)
    {
        if (value != null)
        {
            target.put(key, value);
        }
    }
}

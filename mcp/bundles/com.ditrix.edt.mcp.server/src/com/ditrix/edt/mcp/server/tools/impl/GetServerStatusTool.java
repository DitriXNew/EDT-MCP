/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import com.ditrix.edt.mcp.server.utils.EdtVersion;
import com.ditrix.edt.mcp.server.HostKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.InFlightToolCalls;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.transport.HttpTransport;
import com.ditrix.edt.mcp.server.transport.StdioPluginSupport;
import com.ditrix.edt.mcp.server.transport.TransportStatus;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.QueryValidationJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.CheckDescriptionLoader;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe;
import com.ditrix.edt.mcp.server.utils.NativeRenderModeProbe.NativeRenderMode;

/**
 * Self-diagnosis tool: returns the running MCP server's introspection snapshot
 * so a client can answer "why is the screenshot blank / why is JSON plain /
 * which tools are exposed" without guessing.
 * <p>
 * Reports: listening port, MCP protocol version, plugin and EDT version, the
 * enabled/total tool counts, the {@code plainTextMode} and {@code checksFolder}
 * preference flags, the startup modes and requested properties of the two form-render modes
 * ({@code -DnativeFormBufferedLayoutRender} / {@code -DnativeFormLayoutRender}),
 * whether authentication is enabled, and what the server is busy with: the tool calls executing
 * now ({@link InFlightToolCalls}, this call itself left out) and the running background jobs.
 * <p>
 * SECURITY: the auth token value is never emitted — only the {@code authEnabled}
 * boolean derived from whether {@link PreferenceConstants#PREF_AUTH_TOKEN} is
 * non-empty. The {@code checksFolder} path is likewise reduced to a boolean
 * ({@code checksFolderConfigured}), never the path itself.
 * <p>
 * Read-only: the {@code get_} name prefix lets the central
 * {@code ToolAnnotationClassifier} mark this tool read-only and idempotent.
 * {@code execute()} is null-safe for a headless context (a missing
 * {@link Activator} or {@link McpServer} degrades to {@code unknown}/{@code false}
 * rather than throwing).
 */
public class GetServerStatusTool implements IMcpTool
{
    private final Supplier<McpServer> httpServer;
    private final BooleanSupplier ipcRunning;
    private final Supplier<InFlightToolCalls.Report> activeCalls;
    private final Supplier<List<JobSnapshot>> runningJobs;

    public GetServerStatusTool()
    {
        this(() -> Activator.getDefault() == null ? null : Activator.getDefault().getMcpServer(), StdioPluginSupport::isIpcRunning);
    }

    GetServerStatusTool(Supplier<McpServer> httpServer, BooleanSupplier ipcRunning)
    {
        // The caller's own call is left out: it is running by definition, and listing it would
        // make every answer report one call more than the server was busy with.
        this(httpServer, ipcRunning, () -> InFlightToolCalls.report(InFlightToolCalls.current()),
            () -> java.util.stream.Stream.concat(BackgroundJobs.shared().running().stream(),
                QueryValidationJobs.shared().running().stream())
                .sorted(java.util.Comparator.comparingLong(JobSnapshot::getStartedAtMs)).toList());
    }

    GetServerStatusTool(Supplier<McpServer> httpServer, BooleanSupplier ipcRunning,
        Supplier<InFlightToolCalls.Report> activeCalls, Supplier<List<JobSnapshot>> runningJobs)
    {
        this.httpServer = httpServer;
        this.ipcRunning = ipcRunning;
        this.activeCalls = activeCalls;
        this.runningJobs = runningJobs;
    }
    public static final String NAME = "get_server_status"; //$NON-NLS-1$

    /** Output key: the tool calls executing now, oldest first. */
    static final String KEY_ACTIVE_CALLS = "activeCalls"; //$NON-NLS-1$

    /** Output key: calls running but not listed because the in-flight registry was full. */
    static final String KEY_ACTIVE_CALLS_UNTRACKED = "activeCallsUntracked"; //$NON-NLS-1$

    /** Output key: the background jobs whose status is running, oldest first. */
    static final String KEY_RUNNING_JOBS = "runningJobs"; //$NON-NLS-1$

    /** Form-render JVM flag: enables the offscreen buffered layout render. */
    private static final String FLAG_BUFFERED_LAYOUT_RENDER = "nativeFormBufferedLayoutRender"; //$NON-NLS-1$

    /** Form-render JVM flag: selects the native (C++) layout render path. */
    private static final String FLAG_NATIVE_LAYOUT_RENDER = "nativeFormLayoutRender"; //$NON-NLS-1$

    /** Output key: whether the MCP server is currently running. */
    private static final String KEY_RUNNING = "running"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Diagnose the EDT MCP server: its configuration and the tool calls and background jobs " //$NON-NLS-1$
            + "running right now. Parameters and examples: get_tool_guide('get_server_status')."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("port", "HTTP configured or last bound TCP port; active only when transports.http is true") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty(KEY_RUNNING, "Whether at least one MCP transport is listening") //$NON-NLS-1$
            .objectProperty("transports", "Active MCP listeners; IPC exposes no secret or socket address",
                JsonSchemaBuilder.object()
                    .booleanProperty("http", "Whether the HTTP listener is running", true)
                    .booleanProperty("ipc", "Whether the local IPC listener is running", true), false)
            .stringProperty("hostKind", "EDT host kind: WINDOWED or HEADLESS")
            .stringProperty("protocolVersion", "MCP protocol version implemented by the server") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("pluginVersion", "Version of the EDT-MCP plugin") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("edtVersion", "Detected 1C:EDT version") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("enabledTools", "Number of tools currently enabled") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("totalTools", "Total number of registered tools") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("plainTextMode", "Whether JSON responses are forced to plain text") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("checksFolderConfigured", "Whether a checks folder path is configured") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("authEnabled", "Whether bearer-token authentication is enabled") //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("formRenderFlags", //$NON-NLS-1$
                "atStartup is the mode when this plugin activated; requested is the current " //$NON-NLS-1$
                    + "system property. EDT binds these modes at class initialization; changing them " //$NON-NLS-1$
                    + "requires an EDT restart") //$NON-NLS-1$
            .objectArrayProperty(KEY_ACTIVE_CALLS, "Tool calls whose EDT work is running now, oldest first, " //$NON-NLS-1$
                + "without this call: tool, projectName (when the call passed one), startedAtMs, elapsedMs, " //$NON-NLS-1$
                + "responseSent (only when true: the client already has an answer and the work goes on)") //$NON-NLS-1$
            .integerProperty(KEY_ACTIVE_CALLS_UNTRACKED, "Present only when positive: calls running but not " //$NON-NLS-1$
                + "listed in activeCalls because the in-flight registry was full when they began") //$NON-NLS-1$
            .objectArrayProperty(KEY_RUNNING_JOBS, "Background jobs whose status is running, oldest first: " //$NON-NLS-1$
                + "jobId, tool, startedAtMs, elapsedMs; poll one with get_job_status") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        try
        {
            Activator activator = Activator.getDefault();

            // Tool counts come from the singleton registry; getEnabledTools()
            // applies the per-tool enablement preference, getToolCount() is the total.
            McpToolRegistry registry = McpToolRegistry.getInstance();
            int totalTools = registry.getToolCount();
            int enabledTools = registry.getEnabledTools().size();

            ToolResult result = ToolResult.success();

            // Port: read from the live server when available; null-safe for headless.
            McpServer server = httpServer.get();
            TransportStatus transports = TransportStatus.read(server, ipcRunning);
            boolean http = transports.http();
            boolean ipc = transports.ipc();
            result.put("port", server == null ? PreferenceConstants.DEFAULT_PORT : server.getPort()); //$NON-NLS-1$
            result.put(KEY_RUNNING, transports.running());
            result.put("transports", Map.of("http", http, "ipc", ipc));

            result.put("hostKind", HostKind.current().name());
            result.put("protocolVersion", McpConstants.PROTOCOL_VERSION); //$NON-NLS-1$
            result.put("pluginVersion", McpConstants.PLUGIN_VERSION); //$NON-NLS-1$
            result.put("edtVersion", EdtVersion.getEdtVersion()); //$NON-NLS-1$

            result.put("enabledTools", enabledTools); //$NON-NLS-1$
            result.put("totalTools", totalTools); //$NON-NLS-1$

            // Preference-backed flags. Degrade to defaults/false when the
            // preference store is unavailable (headless / no Activator).
            boolean plainTextMode = PreferenceConstants.DEFAULT_PLAIN_TEXT_MODE;
            boolean checksFolderConfigured = false;
            boolean authEnabled = false;
            if (activator != null)
            {
                IPreferenceStore store = activator.getPreferenceStore();
                if (store != null)
                {
                    plainTextMode = store.getBoolean(PreferenceConstants.PREF_PLAIN_TEXT_MODE);

                    // Only whether a checks folder is configured, never the path. Since #31 the
                    // descriptions SHIP with the plugin, so this reports an OVERRIDE being in
                    // play - not whether get_check_description works. Read through the loader so
                    // "configured" means here exactly what it means where it is acted on.
                    checksFolderConfigured = CheckDescriptionLoader.hasOverrideFolder();

                    // Only whether auth is on, never the token value - and "on" means what the
                    // authorizer means by it, so a preference of blanks reports auth OFF here
                    // instead of promising a check that HttpTransport does not perform.
                    String authToken = store.getString(PreferenceConstants.PREF_AUTH_TOKEN);
                    authEnabled = !HttpTransport.normalizeToken(authToken).isEmpty();
                }
            }
            result.put("plainTextMode", plainTextMode); //$NON-NLS-1$
            result.put("checksFolderConfigured", checksFolderConfigured); //$NON-NLS-1$
            result.put("authEnabled", authEnabled); //$NON-NLS-1$

            // EDT-startup render modes and raw requested System properties:
            // the diagnostic for a blank get_form_screenshot / get_form_layout_snapshot.
            Map<String, Object> formRenderFlags = new LinkedHashMap<>();
            formRenderFlags.put(FLAG_NATIVE_LAYOUT_RENDER,
                createRenderFlagState(NativeRenderModeProbe.getStartupNativeRenderMode(),
                    System.getProperty(FLAG_NATIVE_LAYOUT_RENDER)));
            formRenderFlags.put(FLAG_BUFFERED_LAYOUT_RENDER,
                createRenderFlagState(NativeRenderModeProbe.getStartupBufferedRenderMode(),
                    System.getProperty(FLAG_BUFFERED_LAYOUT_RENDER)));
            result.put("formRenderFlags", formRenderFlags); //$NON-NLS-1$

            putRunningWork(result);

            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in get_server_status", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /**
     * Adds what the server is busy with (#993). Both lists are always present: an empty array is
     * the statement "nothing is running", which an absent key could not make. Only the tool name and
     * the {@code projectName} argument of a call are reported - never its other arguments.
     */
    private void putRunningWork(ToolResult result)
    {
        InFlightToolCalls.Report report = activeCalls.get();
        List<Map<String, Object>> calls = new ArrayList<>();
        for (InFlightToolCalls.Snapshot call : report.calls())
        {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tool", call.tool()); //$NON-NLS-1$
            if (call.projectName() != null)
            {
                entry.put(McpKeys.PROJECT_NAME, call.projectName());
            }
            entry.put("startedAtMs", call.startedAtMs()); //$NON-NLS-1$
            entry.put("elapsedMs", call.elapsedMs()); //$NON-NLS-1$
            if (call.responseSent())
            {
                entry.put("responseSent", true); //$NON-NLS-1$
            }
            calls.add(entry);
        }
        result.put(KEY_ACTIVE_CALLS, calls);
        if (report.untracked() > 0)
        {
            result.put(KEY_ACTIVE_CALLS_UNTRACKED, report.untracked());
        }

        List<Map<String, Object>> jobs = new ArrayList<>();
        for (JobSnapshot job : runningJobs.get())
        {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("jobId", job.getId()); //$NON-NLS-1$
            entry.put("tool", job.getOwningTool()); //$NON-NLS-1$
            entry.put("startedAtMs", job.getStartedAtMs()); //$NON-NLS-1$
            entry.put("elapsedMs", job.getElapsedMs()); //$NON-NLS-1$
            jobs.add(entry);
        }
        result.put(KEY_RUNNING_JOBS, jobs);
    }

    /**
     * Builds one render-flag state from the mode at MCP activation and the raw requested
     * system property. EDT reads the flags into static finals at class initialization and
     * binds its offscreen handler once in {@code HippoLayoutService.INSTANCE}; changing
     * a property later cannot reconfigure that handler. An EDT restart is required.
     */
    private static Map<String, Object> createRenderFlagState(NativeRenderMode startupMode,
        String requested)
    {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("atStartup", startupMode.name().toLowerCase(Locale.ROOT)); //$NON-NLS-1$
        if (requested != null)
        {
            state.put("requested", requested); //$NON-NLS-1$
        }
        return state;
    }
}

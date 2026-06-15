/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;

/**
 * Self-diagnosis tool: returns the running MCP server's introspection snapshot
 * so a client can answer "why is the screenshot blank / why is JSON plain /
 * which tools are exposed" without guessing.
 * <p>
 * Reports: listening port, MCP protocol version, plugin and EDT version, the
 * enabled/total tool counts, the {@code plainTextMode} and {@code checksFolder}
 * preference flags, the two form-render JVM flags
 * ({@code -DnativeFormBufferedLayoutRender} / {@code -DnativeFormLayoutRender}),
 * and whether authentication is enabled.
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
    public static final String NAME = "get_server_status"; //$NON-NLS-1$

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
        return "Self-diagnosis snapshot of the running MCP server: listening port, MCP protocol version, " //$NON-NLS-1$
            + "plugin version, EDT version, enabled/total tool counts, the plainTextMode and " //$NON-NLS-1$
            + "checksFolderConfigured preference flags, the two form-render JVM flags " //$NON-NLS-1$
            + "(nativeFormBufferedLayoutRender / nativeFormLayoutRender), and whether authentication is " //$NON-NLS-1$
            + "enabled. Use it to explain a blank form screenshot or a plain-text JSON response. " //$NON-NLS-1$
            + "Never returns the auth token value or the checks folder path, only booleans."; //$NON-NLS-1$
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
            .integerProperty("port", "TCP port the MCP server listens on") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty(KEY_RUNNING, "Whether the MCP server is currently running") //$NON-NLS-1$
            .stringProperty("protocolVersion", "MCP protocol version implemented by the server") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("pluginVersion", "Version of the EDT-MCP plugin") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("edtVersion", "Detected 1C:EDT version") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("enabledTools", "Number of tools currently enabled") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("totalTools", "Total number of registered tools") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("plainTextMode", "Whether JSON responses are forced to plain text") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("checksFolderConfigured", "Whether a checks folder path is configured") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("authEnabled", "Whether bearer-token authentication is enabled") //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("formRenderFlags", "Form-render JVM flag states keyed by flag name") //$NON-NLS-1$ //$NON-NLS-2$
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
            McpServer server = activator != null ? activator.getMcpServer() : null;
            if (server != null)
            {
                result.put("port", server.getPort()); //$NON-NLS-1$
                result.put(KEY_RUNNING, server.isRunning());
            }
            else
            {
                result.put("port", PreferenceConstants.DEFAULT_PORT); //$NON-NLS-1$
                result.put(KEY_RUNNING, false);
            }

            result.put("protocolVersion", McpConstants.PROTOCOL_VERSION); //$NON-NLS-1$
            result.put("pluginVersion", McpConstants.PLUGIN_VERSION); //$NON-NLS-1$
            result.put("edtVersion", GetEdtVersionTool.getEdtVersion()); //$NON-NLS-1$

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

                    // Only whether a checks folder is configured, never the path.
                    String checksFolder = store.getString(PreferenceConstants.PREF_CHECKS_FOLDER);
                    checksFolderConfigured = checksFolder != null && !checksFolder.trim().isEmpty();

                    // Only whether auth is on, never the token value.
                    String authToken = store.getString(PreferenceConstants.PREF_AUTH_TOKEN);
                    authEnabled = authToken != null && !authToken.isEmpty();
                }
            }
            result.put("plainTextMode", plainTextMode); //$NON-NLS-1$
            result.put("checksFolderConfigured", checksFolderConfigured); //$NON-NLS-1$
            result.put("authEnabled", authEnabled); //$NON-NLS-1$

            // Form-render JVM flags (System properties), the diagnostic for a
            // blank get_form_screenshot / get_form_layout_snapshot.
            Map<String, Object> formRenderFlags = new LinkedHashMap<>();
            formRenderFlags.put(FLAG_BUFFERED_LAYOUT_RENDER,
                Boolean.parseBoolean(System.getProperty(FLAG_BUFFERED_LAYOUT_RENDER)));
            formRenderFlags.put(FLAG_NATIVE_LAYOUT_RENDER,
                Boolean.parseBoolean(System.getProperty(FLAG_NATIVE_LAYOUT_RENDER)));
            result.put("formRenderFlags", formRenderFlags); //$NON-NLS-1$

            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in get_server_status", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
}

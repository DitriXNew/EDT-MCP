/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/**
 * Deletes a 1C:EDT launch configuration (runtime client or Attach) by name.
 *
 * <p>Guarded by a confirm-preview (mirrors {@link DeleteProjectTool}): a bare call
 * PREVIEWS what would be removed without changing anything; only {@code confirm=true}
 * performs the deletion.
 *
 * <p>Refuses to delete a configuration that is currently running — terminate the launch
 * first with {@code terminate_launch}.
 *
 * <p>This is the cleanup counterpart of {@link CreateLaunchConfigTool} and closes the
 * workspace-.metadata residue gap: launch configs are stored in workspace metadata, not
 * in the git fixture, so only this tool can clean them up via MCP.
 */
public class DeleteLaunchConfigTool implements IMcpTool
{
    public static final String NAME = "delete_launch_config"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Delete a 1C:EDT launch configuration by name (runtime client or Attach). " //$NON-NLS-1$
            + "Destructive: guarded by a confirm-preview - call without confirm to preview " //$NON-NLS-1$
            + "(no change), then confirm=true to delete. Refuses to delete a running config " //$NON-NLS-1$
            + "(terminate_launch first). The inverse of create_launch_config. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('delete_launch_config')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("name", //$NON-NLS-1$
                "Exact launch configuration name (required). " //$NON-NLS-1$
                + "Use list_configurations to find available names.", //$NON-NLS-1$
                true)
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = perform the deletion; default false = preview only (what would be removed, " //$NON-NLS-1$
                + "no change).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "Either 'preview' (nothing changed) or 'deleted' (removed).") //$NON-NLS-1$
            .booleanProperty("confirmationRequired", //$NON-NLS-1$
                "true on a preview (no change made); absent/false once deleted.") //$NON-NLS-1$
            .stringProperty("name", "Name of the targeted launch configuration.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.PROJECT, "Project the configuration targets (if known).") //$NON-NLS-1$
            .stringProperty("type", "Launch configuration type id (if known).") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status message.") //$NON-NLS-1$
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
        // ── 1. Required argument ───────────────────────────────────────────────
        String err = JsonUtils.requireArgument(params, "name", //$NON-NLS-1$
            ". Use list_configurations to find available configuration names."); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        String configName = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$

        // ── 2. Acquire launch manager ──────────────────────────────────────────
        ILaunchManager lm = LaunchConfigUtils.getLaunchManager();
        if (lm == null)
        {
            return ToolResult.error("Eclipse launch manager is not available. " //$NON-NLS-1$
                + "The debug plugin may not have started yet — retry in a moment.").toJson(); //$NON-NLS-1$
        }

        // ── 3. Find the configuration ──────────────────────────────────────────
        ILaunchConfiguration config = LaunchConfigUtils.findLaunchConfigByName(lm, configName);
        if (config == null)
        {
            return ToolResult.error("Launch configuration not found: '" + configName //$NON-NLS-1$
                + "'. Use list_configurations to see available configurations.").toJson(); //$NON-NLS-1$
        }

        // ── 4. Read metadata for the response ─────────────────────────────────
        String project = LaunchConfigUtils.readAttribute(config,
            LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
        String typeId = LaunchConfigUtils.getConfigTypeId(config);

        // ── 5. Refuse to delete a currently-running config ────────────────────
        // check whether there is a live (non-terminated) launch for this config.
        if (isRunning(lm, configName))
        {
            return ToolResult.error("Cannot delete launch configuration '" + configName //$NON-NLS-1$
                + "': it is currently running. Use terminate_launch to stop it first, " //$NON-NLS-1$
                + "then retry delete_launch_config.").toJson(); //$NON-NLS-1$
        }

        // ── 6. Preview / confirm gate ──────────────────────────────────────────
        if (!confirm)
        {
            ToolResult result = ToolResult.success()
                .put(McpKeys.ACTION, "preview") //$NON-NLS-1$
                .put("confirmationRequired", true) //$NON-NLS-1$
                .put("name", configName); //$NON-NLS-1$
            if (!project.isEmpty())
            {
                result.put(McpKeys.PROJECT, project);
            }
            if (!typeId.isEmpty())
            {
                result.put("type", typeId); //$NON-NLS-1$
            }
            result.put(McpKeys.MESSAGE, "PREVIEW: this would delete launch configuration '" //$NON-NLS-1$
                + configName + "'" //$NON-NLS-1$
                + (project.isEmpty() ? "" : " (project: " + project + ")") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ". Re-call with confirm=true to apply it."); //$NON-NLS-1$
            return result.toJson();
        }

        // ── 7. Delete ──────────────────────────────────────────────────────────
        try
        {
            Activator.logInfo("delete_launch_config: name=" + configName //$NON-NLS-1$
                + ", project=" + project); //$NON-NLS-1$
            config.delete();

            ToolResult result = ToolResult.success()
                .put(McpKeys.ACTION, "deleted") //$NON-NLS-1$
                .put("name", configName); //$NON-NLS-1$
            if (!project.isEmpty())
            {
                result.put(McpKeys.PROJECT, project);
            }
            if (!typeId.isEmpty())
            {
                result.put("type", typeId); //$NON-NLS-1$
            }
            result.put(McpKeys.MESSAGE, "Launch configuration '" + configName + "' deleted."); //$NON-NLS-1$ //$NON-NLS-2$
            return result.toJson();
        }
        catch (CoreException e)
        {
            Activator.logError("Error deleting launch config: " + configName, e); //$NON-NLS-1$
            return ToolResult.error("Failed to delete launch configuration '" + configName //$NON-NLS-1$
                + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if there is a live (non-terminated) launch whose config
     * carries the given name.
     */
    private static boolean isRunning(ILaunchManager lm, String configName)
    {
        return LaunchConfigUtils.findLiveLaunchByName(lm, configName) != null;
    }
}

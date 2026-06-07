/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.SseStreamRegistry;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.ToolsetState;
import com.ditrix.edt.mcp.server.tools.Toolsets;
import com.ditrix.edt.mcp.server.tools.Toolsets.Toolset;

/**
 * Reveals (or hides) tool groups for progressive tool disclosure. When the visible
 * set changes the server pushes {@code notifications/tools/list_changed} to any open
 * SSE stream (capability {@code tools.listChanged}); a client without an open stream
 * re-requests {@code tools/list} to see the newly revealed tools.
 * <p>
 * Pass {@code toolsets} (one or more toolset ids from {@code list_toolsets}); set
 * {@code disable=true} to hide them instead. The {@code core} toolset is always
 * visible and cannot be toggled. When progressive disclosure is off the full tool
 * list is already exposed, so the change is recorded but has no effect until the
 * preference is turned on.
 */
public class EnableToolsetTool implements IMcpTool
{
    public static final String NAME = "enable_toolset"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Reveal (or hide) tool groups for progressive disclosure. Pass toolsets=[ids] from " //$NON-NLS-1$
            + "list_toolsets to reveal them, then RE-REQUEST tools/list to see the newly revealed tools. " //$NON-NLS-1$
            + "Set disable=true to hide. The 'core' toolset is always visible and cannot be toggled. " //$NON-NLS-1$
            + "When progressive disclosure is off, all tools are already listed and this has no effect " //$NON-NLS-1$
            + "until you enable it in EDT Preferences → MCP Server."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringArrayProperty("toolsets", //$NON-NLS-1$
                "Toolset ids to reveal (or hide with disable=true), e.g. [\"code\",\"debug\"]. " //$NON-NLS-1$
                    + "Call list_toolsets for the valid ids.", true) //$NON-NLS-1$
            .booleanProperty("disable", //$NON-NLS-1$
                "Hide the listed toolsets instead of revealing them (default false).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "'enabled' or 'disabled'") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("applied", "Toolset ids the change was applied to") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("invalid", "Requested ids that are not valid toolsets") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("ignored", "Requested ids that cannot be toggled (core)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("visibleToolsets", "Toolset ids visible in tools/list after the change") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("progressiveDisclosure", "Whether progressive disclosure is currently on") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("note", "Human-readable next step") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        try
        {
            List<String> requested = JsonUtils.extractArrayArgument(params, "toolsets"); //$NON-NLS-1$
            if (requested == null || requested.isEmpty())
            {
                return ToolResult.error(
                    "toolsets is required: one or more toolset ids to reveal, e.g. [\"code\",\"debug\"]. " //$NON-NLS-1$
                        + "Call list_toolsets to see the valid ids.").toJson(); //$NON-NLS-1$
            }

            boolean disable = JsonUtils.extractBooleanArgument(params, "disable", false); //$NON-NLS-1$
            ToolsetState state = ToolsetState.getInstance();

            List<String> applied = new ArrayList<>();
            List<String> invalid = new ArrayList<>();
            List<String> ignored = new ArrayList<>();
            for (String raw : requested)
            {
                String id = raw == null ? null : raw.trim();
                if (id == null || id.isEmpty())
                {
                    continue;
                }
                if (!Toolsets.exists(id))
                {
                    invalid.add(id);
                    continue;
                }
                if (Toolsets.CORE.equals(id))
                {
                    // Core is always visible; toggling it is meaningless, not an error.
                    ignored.add(id);
                    continue;
                }
                if (disable)
                {
                    state.disable(id);
                }
                else
                {
                    state.enable(id);
                }
                applied.add(id);
            }

            // All requested ids were invalid (none applied, none ignored-as-core) -> a clear error
            // that names the bad values and points at the discovery tool.
            if (applied.isEmpty() && ignored.isEmpty())
            {
                return ToolResult.error(
                    "No valid toolsets in " + invalid //$NON-NLS-1$
                        + ". Call list_toolsets to see the valid ids.").toJson(); //$NON-NLS-1$
            }

            boolean pd = Toolsets.isProgressiveDisclosureEnabled();
            ToolResult result = ToolResult.success();
            result.put("action", disable ? "disabled" : "enabled"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            result.put("applied", applied); //$NON-NLS-1$
            if (!invalid.isEmpty())
            {
                result.put("invalid", invalid); //$NON-NLS-1$
            }
            if (!ignored.isEmpty())
            {
                result.put("ignored", ignored); //$NON-NLS-1$
            }
            result.put("progressiveDisclosure", pd); //$NON-NLS-1$

            List<String> visible = new ArrayList<>();
            for (Toolset ts : Toolsets.all())
            {
                if (!pd || state.isVisible(ts.getId()))
                {
                    visible.add(ts.getId());
                }
            }
            result.put("visibleToolsets", visible); //$NON-NLS-1$

            result.put("note", pd //$NON-NLS-1$
                ? "Re-request tools/list to see the updated tool set." //$NON-NLS-1$
                : "Progressive disclosure is OFF, so tools/list already exposes every tool; this change " //$NON-NLS-1$
                    + "takes effect only once you enable it in EDT Preferences → MCP Server."); //$NON-NLS-1$

            // The visible tool set changed under progressive disclosure -> push
            // notifications/tools/list_changed to any open SSE stream. Clients without
            // an open stream rely on the pull path (the note tells them to re-list).
            if (pd && !applied.isEmpty())
            {
                SseStreamRegistry.getInstance().notifyToolsListChanged();
            }

            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in enable_toolset", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
}

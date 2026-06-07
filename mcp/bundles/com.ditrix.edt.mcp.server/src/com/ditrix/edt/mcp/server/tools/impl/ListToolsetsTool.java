/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.ToolsetState;
import com.ditrix.edt.mcp.server.tools.Toolsets;
import com.ditrix.edt.mcp.server.tools.Toolsets.Toolset;

/**
 * Lists the tool groups (toolsets) that drive progressive tool disclosure: their
 * id, title, description, member tools, and whether each is currently visible in
 * {@code tools/list}. Read-only; the discovery counterpart of {@code enable_toolset}.
 * <p>
 * When progressive disclosure is on, only the {@code core} toolset plus toolsets
 * revealed via {@code enable_toolset} are visible; when off (the default) every
 * toolset is reported visible because the full tool list is exposed.
 */
public class ListToolsetsTool implements IMcpTool
{
    public static final String NAME = "list_toolsets"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List the tool groups (toolsets) used by progressive tool disclosure: each toolset's id, " //$NON-NLS-1$
            + "title, description, member tools, and whether it is currently visible in tools/list. " //$NON-NLS-1$
            + "When progressive disclosure is on, only the 'core' toolset plus toolsets you reveal with " //$NON-NLS-1$
            + "enable_toolset appear in tools/list; when off (default) all tools are already listed. " //$NON-NLS-1$
            + "Read-only. Use it to discover which toolset id to pass to enable_toolset."; //$NON-NLS-1$
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
            .booleanProperty("progressiveDisclosure", "Whether progressive disclosure is currently on") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("count", "Number of toolsets") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("toolsets", "The toolsets (id, title, description, tools, toolCount, visible, core)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("note", "Human-readable guidance for the current disclosure mode") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        try
        {
            boolean pd = Toolsets.isProgressiveDisclosureEnabled();
            ToolsetState state = ToolsetState.getInstance();

            ToolResult result = ToolResult.success();
            result.put("progressiveDisclosure", pd); //$NON-NLS-1$

            List<Map<String, Object>> toolsets = new ArrayList<>();
            for (Toolset ts : Toolsets.all())
            {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", ts.getId()); //$NON-NLS-1$
                entry.put("title", ts.getTitle()); //$NON-NLS-1$
                entry.put("description", ts.getDescription()); //$NON-NLS-1$
                List<String> names = Toolsets.toolNamesOf(ts.getId());
                entry.put("toolCount", names.size()); //$NON-NLS-1$
                entry.put("tools", names); //$NON-NLS-1$
                // When disclosure is off, the full list is exposed, so every toolset is visible.
                entry.put("visible", !pd || state.isVisible(ts.getId())); //$NON-NLS-1$
                entry.put("core", Toolsets.CORE.equals(ts.getId())); //$NON-NLS-1$
                toolsets.add(entry);
            }
            result.put("toolsets", toolsets); //$NON-NLS-1$
            result.put("count", toolsets.size()); //$NON-NLS-1$

            result.put("note", pd //$NON-NLS-1$
                ? "Progressive disclosure is ON: only the core toolset and toolsets you reveal appear in " //$NON-NLS-1$
                    + "tools/list. Call enable_toolset(toolsets=[...]) with the ids below, then re-request tools/list." //$NON-NLS-1$
                : "Progressive disclosure is OFF: every tool is already listed. Turn it on in EDT Preferences " //$NON-NLS-1$
                    + "→ MCP Server to shrink tools/list and reveal toolsets on demand with enable_toolset."); //$NON-NLS-1$

            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in list_toolsets", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
}

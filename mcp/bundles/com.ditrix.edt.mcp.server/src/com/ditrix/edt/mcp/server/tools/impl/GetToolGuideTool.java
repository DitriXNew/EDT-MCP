/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.GuideRenderer;

/**
 * Serves the on-demand, full how-to guide for any registered tool: its
 * description, the complete parameter table (type, required, allowed values), and
 * the extended {@link IMcpTool#getGuide() guide} text kept OUT of the
 * always-loaded {@code tools/list} to save client context.
 * <p>
 * The guide is rendered by {@link GuideRenderer} from the target tool's own
 * {@code getDescription()} / {@code getInputSchema()} / {@code getGuide()}, so it
 * stays in sync with the tool automatically. The same content is also reachable as
 * the resource {@code guide://<toolName>}.
 * <p>
 * Read-only: the {@code get_} name prefix lets the central
 * {@code ToolAnnotationClassifier} mark this tool read-only and idempotent.
 */
public class GetToolGuideTool implements IMcpTool
{
    public static final String NAME = McpConstants.TOOL_GET_TOOL_GUIDE;

    /** Input param: exact name of the tool to document, as it appears in tools/list. */
    private static final String KEY_TOOL_NAME = "toolName"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Get the full on-demand how-to for a tool: its description, every parameter " //$NON-NLS-1$
            + "(type, required, allowed values) and extended examples/preconditions kept OUT of " //$NON-NLS-1$
            + "the always-loaded tool list to save context. Pass the exact tool name from " //$NON-NLS-1$
            + "tools/list. Also available as the resource guide://<toolName>."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_TOOL_NAME,
                "The exact name of the tool to document, as it appears in tools/list (e.g. 'read_module_source').", //$NON-NLS-1$
                true)
            .build();
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String toolName = params != null ? params.get(KEY_TOOL_NAME) : null;
        return "guide-" + (toolName != null ? toolName : "tool") + ".md"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String err = JsonUtils.requireArgument(params, KEY_TOOL_NAME);
        if (err != null)
        {
            return err;
        }

        String toolName = params.get(KEY_TOOL_NAME);
        IMcpTool tool = McpToolRegistry.getInstance().getTool(toolName);
        if (tool == null)
        {
            return ToolResult.error("Unknown tool: " + toolName //$NON-NLS-1$
                + ". Call tools/list to see available tool names.").toJson(); //$NON-NLS-1$
        }

        return GuideRenderer.render(tool);
    }
}

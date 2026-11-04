/*
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.EdtDialogs;

/** Lists EDT modal dialogs and explicitly presses an allowlisted dialog's button. */
public class EdtDialogTool implements IMcpTool
{
    public static final String NAME = "edt_dialog";
    private final EdtDialogs dialogs;

    public EdtDialogTool()
    {
        this(EdtDialogs.getInstance());
    }

    EdtDialogTool(EdtDialogs dialogs)
    {
        this.dialogs = dialogs;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Inspect open EDT modal dialogs or press an allowlisted dialog's button. "
            + "List first; press only an exact returned button on an answerable dialog. "
            + "Full parameters and examples: call get_tool_guide('edt_dialog').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty("action", "list or press", true, "list", "press")
            .stringProperty("dialogId", "Required for press; use the id from list.")
            .stringProperty("button", "Required for press; exact button label from list.")
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return "{\"type\":\"object\",\"required\":[\"success\"],\"properties\":{"
            + "\"success\":{\"type\":\"boolean\"},\"dialogs\":{\"type\":\"array\",\"items\":"
            + "{\"type\":\"object\",\"required\":[\"dialogId\",\"title\",\"message\",\"buttons\",\"answerable\"],"
            + "\"properties\":{\"dialogId\":{\"type\":\"string\"},\"title\":{\"type\":\"string\"},"
            + "\"message\":{\"type\":\"string\"},\"buttons\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},"
            + "\"answerable\":{\"type\":\"boolean\"},\"reason\":{\"type\":\"string\"}}}},"
            + "\"dialogId\":{\"type\":\"string\"},\"button\":{\"type\":\"string\"},\"message\":{\"type\":\"string\"}}}";
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        return new ToolAnnotations(null, false, true, false, false);
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String action = JsonUtils.extractStringArgument(params, "action");
        String dialogId = JsonUtils.extractStringArgument(params, "dialogId");
        String button = JsonUtils.extractStringArgument(params, "button");
        if ("list".equals(action))
        {
            if (dialogId != null || button != null)
            {
                return ToolResult.error("action=list accepts no dialogId or button; use action=press to answer.").toJson();
            }
            return dialogs.list();
        }
        if (!"press".equals(action))
        {
            return ToolResult.error("Unknown action '" + action + "'. Use list or press.").toJson();
        }
        if (dialogId == null || dialogId.isBlank() || button == null || button.isBlank())
        {
            return ToolResult.error("dialogId and button are required for press. Call edt_dialog with action=list first.")
                .toJson();
        }
        return dialogs.press(dialogId, button);
    }
}

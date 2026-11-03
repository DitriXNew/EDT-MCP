/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.function.BooleanSupplier;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.EditorNavigation;
import com.ditrix.edt.mcp.server.utils.EditorNavigation.Request;
import com.ditrix.edt.mcp.server.utils.FrontMatter;

/** Explicit, opt-in navigation in the existing EDT workbench. */
public class OpenEditorTool implements IMcpTool
{
    public static final String NAME = "open_editor";
    private static final String FQN = "fqn";
    private static final String LINE = "line";
    private static final String METHOD_NAME = "methodName";
    private final BooleanSupplier enabled;
    private final Navigator navigator;

    @FunctionalInterface
    interface Navigator
    {
        void open(Request request) throws Exception;
    }

    public OpenEditorTool()
    {
        this(EditorNavigation::isEnabled, EditorNavigation::open);
    }

    OpenEditorTool(BooleanSupplier enabled, Navigator navigator)
    {
        this.enabled = enabled;
        this.navigator = navigator;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Open an EDT editor for metadata, a managed form, or BSL source. "
            + "Requires Allow UI navigation in MCP Server preferences (off by default). "
            + "Parameters and examples: get_tool_guide('open_editor').";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name", true)
            .stringProperty(FQN, "Metadata FQN, e.g. Catalog.Products.Attribute.Price; "
                + "only TYPE/KIND tokens may be English or Russian, Names are identifiers, not synonyms. "
                + "Pass exactly one of fqn or modulePath.")
            .stringProperty(McpKeys.MODULE_PATH, "Path from src/, e.g. CommonModules/MyModule/Module.bsl; "
                + "pass exactly one of modulePath or fqn.")
            .integerProperty(LINE, "1-based BSL line; requires modulePath; mutually exclusive with methodName.")
            .stringProperty(METHOD_NAME, "BSL procedure/function name; requires modulePath; "
                + "mutually exclusive with line.")
            .build();
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        return new ToolAnnotations(null, true, false, true, false);
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String error = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME);
        if (error != null)
        {
            return error;
        }
        try
        {
            String project = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
            String fqn = JsonUtils.extractStringArgument(params, FQN);
            String module = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
            String method = JsonUtils.extractStringArgument(params, METHOD_NAME);
            String lineValue = JsonUtils.extractStringArgument(params, LINE);
            Integer line = lineValue == null ? null : JsonUtils.extractIntArgument(params, LINE, 0);
            if (line != null && line < 1)
            {
                return ToolResult.error("Invalid line '" + lineValue
                    + "'. Pass a positive 1-based integer with modulePath.").toJson();
            }
            Request request = new Request(project, fqn, module, line, method);
            if (!enabled.getAsBoolean())
            {
                return ToolResult.error("UI navigation is disabled. Enable 'Allow UI navigation' "
                    + "in Window > Preferences > MCP Server > General to use open_editor.").toJson();
            }
            navigator.open(request);
            return FrontMatter.create().put("status", "opened").put(McpKeys.PROJECT_NAME, project)
                .put("target", fqn == null ? module : fqn).build()
                + "\nOpened the requested editor in EDT.\n";
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("open_editor was interrupted. Check EDT before retrying.").toJson();
        }
        catch (Exception e)
        {
            return ToolResult.error("Could not open editor: " + e.getMessage()
                + " Use get_tool_guide('open_editor') to check the target and prerequisites.").toJson();
        }
    }
}

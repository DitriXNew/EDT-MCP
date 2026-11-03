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
import com.ditrix.edt.mcp.server.utils.GuideSections;
import com.ditrix.edt.mcp.server.utils.GuideSections.Section;

/**
 * Serves the on-demand how-to guide for any registered tool: its
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
    private static final String KEY_MODE = "mode"; //$NON-NLS-1$
    private static final String KEY_SECTION = "section"; //$NON-NLS-1$
    private static final String KEY_QUERY = "query"; //$NON-NLS-1$
    private static final String MODE_FULL = "full"; //$NON-NLS-1$
    private static final String MODE_SUMMARY = "summary"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Retrieve detailed instructions for an MCP tool. " //$NON-NLS-1$
            + "mode=summary lists sections; section=<heading|#anchor>; query=<text>."; //$NON-NLS-1$
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
            .enumProperty(KEY_MODE,
                "summary: parameters and ## headings; full: default. Use only one of mode, section, query.", //$NON-NLS-1$
                MODE_FULL, MODE_SUMMARY)
            .stringProperty(KEY_SECTION, "Heading or #anchor; ignores case, backticks and heading markers.") //$NON-NLS-1$
            .stringProperty(KEY_QUERY, "Literal search, ignoring case and Russian yo/ye; 2+ characters, up to 20 lines of 200 characters.") //$NON-NLS-1$
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

        String toolName = JsonUtils.extractStringArgument(params, KEY_TOOL_NAME);
        IMcpTool tool = McpToolRegistry.getInstance().getTool(toolName);
        if (tool == null)
        {
            return ToolResult.error("Unknown tool: " + toolName //$NON-NLS-1$
                + ". Call tools/list to see available tool names.").toJson(); //$NON-NLS-1$
        }

        String mode = JsonUtils.extractStringArgument(params, KEY_MODE);
        String section = JsonUtils.extractStringArgument(params, KEY_SECTION);
        String query = JsonUtils.extractStringArgument(params, KEY_QUERY);
        int selectors = (mode != null ? 1 : 0) + (section != null ? 1 : 0) + (query != null ? 1 : 0);
        if (selectors > 1)
        {
            return ToolResult.error("mode, section and query are mutually exclusive. " //$NON-NLS-1$
                + "Pass only one, or omit all three for the full guide.").toJson(); //$NON-NLS-1$
        }
        if (mode != null && !MODE_FULL.equals(mode) && !MODE_SUMMARY.equals(mode))
        {
            return ToolResult.error("Unknown mode: " + mode //$NON-NLS-1$
                + ". Use 'full' or 'summary', or omit mode for the full guide.").toJson(); //$NON-NLS-1$
        }
        if (section != null && section.isBlank())
        {
            return ToolResult.error("section must not be blank. " //$NON-NLS-1$
                + "Pass a heading or #anchor from mode=summary, or omit section for the full guide.").toJson(); //$NON-NLS-1$
        }
        if (query != null && query.isBlank())
        {
            return ToolResult.error("query must not be blank. Pass a literal search term " //$NON-NLS-1$
                + "or omit query for the full guide.").toJson(); //$NON-NLS-1$
        }

        if (query != null && query.strip().codePointCount(0, query.strip().length()) < 2)
        {
            return ToolResult.error("query must contain at least 2 characters. " //$NON-NLS-1$
                + "Pass a longer literal search term or omit query for the full guide.").toJson(); //$NON-NLS-1$
        }

        String markdown = GuideRenderer.render(tool);
        if (section == null && query == null && !MODE_SUMMARY.equals(mode))
        {
            // Preserve the full renderer output verbatim, including its line endings.
            return markdown;
        }
        GuideSections sections = GuideSections.parse(markdown);
        if (section != null)
        {
            Section selected = sections.find(section);
            return selected != null ? selected.text()
                : ToolResult.error("Unknown section: " + section //$NON-NLS-1$
                    + ". Pass a heading or anchor from the available sections:\n\n" //$NON-NLS-1$
                    + sections.tableOfContents()).toJson();
        }
        if (query != null)
        {
            return sections.query(query);
        }
        return sections.summary()
            + "\nFetch a section with section=<heading|#anchor>; full guide: guide://" + toolName + ".\n"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}

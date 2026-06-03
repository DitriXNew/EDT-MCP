/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Map;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Renders a self-contained Markdown how-to guide for a single {@link IMcpTool}.
 * <p>
 * The guide is the on-demand companion to the lean {@code tools/list} surface: it
 * stitches together the tool's {@link IMcpTool#getName() name}, its
 * {@link IMcpTool#getDescription() description}, a parsed parameter table derived
 * from {@link IMcpTool#getInputSchema() the input schema}, and, when present, the
 * extended {@link IMcpTool#getGuide() guide} text. This keeps the always-loaded
 * tool list small while still letting a client pull the full depth for one tool.
 * <p>
 * The input schema is parsed defensively: any malformed JSON is swallowed and the
 * parameter table is simply skipped, so a renderer call never throws on a bad
 * schema. Every table cell is escaped through {@link MarkdownUtils#escapeForTable}
 * so a {@code |} or newline in a description can never break the table layout.
 */
public final class GuideRenderer
{
    private GuideRenderer()
    {
        // Utility class - no instantiation
    }

    /**
     * Renders the full Markdown guide for the given tool.
     *
     * @param tool the tool to document (must not be {@code null})
     * @return the rendered Markdown guide
     */
    public static String render(IMcpTool tool)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("# ").append(tool.getName()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        String description = tool.getDescription();
        sb.append(description != null ? description : "").append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("## Parameters\n"); //$NON-NLS-1$
        appendParameters(sb, tool.getInputSchema());

        String guide = tool.getGuide();
        if (guide != null && !guide.isEmpty())
        {
            // The renderer already emitted the tool name as the document H1, so a
            // guide that opens with its own "# <name>" would duplicate it. Strip that
            // one redundant leading heading; guides that open with prose or a
            // different heading are unaffected.
            sb.append("\n## Guide\n").append(stripRedundantH1(guide, tool.getName())); //$NON-NLS-1$
        }

        return sb.toString();
    }

    /**
     * Removes a redundant leading {@code # <toolName>} H1 from a guide body. The
     * renderer emits the tool name as the document H1 in {@link #render}, so a guide
     * whose first line is exactly {@code # <name>} would render a duplicated
     * top-level heading. Only that exact first-line heading (with any following blank
     * lines) is dropped; a guide opening with prose, a {@code ##} subheading, or a
     * different {@code #} title is returned unchanged.
     *
     * @param guide the non-empty guide text
     * @param name the tool name
     * @return the guide with a redundant leading H1 removed, otherwise unchanged
     */
    static String stripRedundantH1(String guide, String name)
    {
        String h1 = "# " + name; //$NON-NLS-1$
        int newline = guide.indexOf('\n');
        String firstLine = newline >= 0 ? guide.substring(0, newline) : guide;
        if (!firstLine.trim().equals(h1))
        {
            return guide;
        }
        String rest = newline >= 0 ? guide.substring(newline + 1) : ""; //$NON-NLS-1$
        // Drop blank lines left between the removed H1 and the first real content.
        while (rest.startsWith("\n")) //$NON-NLS-1$
        {
            rest = rest.substring(1);
        }
        return rest;
    }

    /**
     * Appends the parameter table parsed from the JSON {@code inputSchema}. When
     * the schema has no properties, emits "No parameters." instead of an empty
     * table. Any malformed schema is swallowed and the table is skipped (the
     * method never throws).
     *
     * @param sb the buffer to append to
     * @param inputSchema the tool's input schema as a JSON string
     */
    private static void appendParameters(StringBuilder sb, String inputSchema)
    {
        JsonObject properties = null;
        JsonArray required = null;
        try
        {
            if (inputSchema != null && !inputSchema.isEmpty())
            {
                JsonElement parsed = JsonParser.parseString(inputSchema);
                if (parsed != null && parsed.isJsonObject())
                {
                    JsonObject root = parsed.getAsJsonObject();
                    if (root.has("properties") && root.get("properties").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        properties = root.getAsJsonObject("properties"); //$NON-NLS-1$
                    }
                    if (root.has("required") && root.get("required").isJsonArray()) //$NON-NLS-1$ //$NON-NLS-2$
                    {
                        required = root.getAsJsonArray("required"); //$NON-NLS-1$
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            // Malformed schema: skip the table gracefully, never throw.
            properties = null;
            required = null;
        }

        if (properties == null || properties.size() == 0)
        {
            sb.append("No parameters.\n"); //$NON-NLS-1$
            return;
        }

        sb.append(MarkdownUtils.tableHeader("Parameter", "Required", "Type", "Description")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (Map.Entry<String, JsonElement> entry : properties.entrySet())
        {
            String name = entry.getKey();
            JsonObject prop = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : new JsonObject();

            String requiredCell = isRequired(required, name) ? "yes" : "—"; //$NON-NLS-1$ //$NON-NLS-2$
            String type = prop.has("type") && prop.get("type").isJsonPrimitive() //$NON-NLS-1$ //$NON-NLS-2$
                ? prop.get("type").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$
            String typeCell = appendEnum(type, prop);
            String descriptionCell = prop.has("description") && prop.get("description").isJsonPrimitive() //$NON-NLS-1$ //$NON-NLS-2$
                ? prop.get("description").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$

            sb.append(MarkdownUtils.tableRow(name, requiredCell, typeCell, descriptionCell));
        }
    }

    /**
     * Returns the type cell, appending " (one of: a, b, c)" when the property
     * declares a non-empty {@code enum} array.
     *
     * @param type the JSON type string
     * @param prop the property object
     * @return the type cell text
     */
    private static String appendEnum(String type, JsonObject prop)
    {
        if (!prop.has("enum") || !prop.get("enum").isJsonArray()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return type;
        }
        JsonArray values = prop.getAsJsonArray("enum"); //$NON-NLS-1$
        if (values.size() == 0)
        {
            return type;
        }
        StringBuilder enumText = new StringBuilder(type).append(" (one of: "); //$NON-NLS-1$
        for (int i = 0; i < values.size(); i++)
        {
            if (i > 0)
            {
                enumText.append(", "); //$NON-NLS-1$
            }
            JsonElement value = values.get(i);
            enumText.append(value.isJsonPrimitive() ? value.getAsString() : value.toString());
        }
        enumText.append(")"); //$NON-NLS-1$
        return enumText.toString();
    }

    /**
     * Tests whether {@code name} appears in the schema's {@code required} array.
     *
     * @param required the required array (may be {@code null})
     * @param name the property name
     * @return {@code true} when the name is listed as required
     */
    private static boolean isRequired(JsonArray required, String name)
    {
        if (required == null)
        {
            return false;
        }
        for (JsonElement element : required)
        {
            if (element.isJsonPrimitive() && name.equals(element.getAsString()))
            {
                return true;
            }
        }
        return false;
    }
}

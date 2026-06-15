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
    private static final String PROPERTIES = "properties"; //$NON-NLS-1$
    private static final String REQUIRED = "required"; //$NON-NLS-1$
    private static final String DESCRIPTION = "description"; //$NON-NLS-1$

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
        ParsedSchema schema = parseSchema(inputSchema);
        JsonObject properties = schema.properties;

        if (properties == null || properties.size() == 0)
        {
            sb.append("No parameters.\n"); //$NON-NLS-1$
            return;
        }

        sb.append(MarkdownUtils.tableHeader("Parameter", "Required", "Type", "Description")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (Map.Entry<String, JsonElement> entry : properties.entrySet())
        {
            sb.append(renderParameterRow(entry, schema.required));
        }
    }

    /**
     * Holds the {@code properties} object and {@code required} array parsed from an input schema.
     * Either field may be {@code null} when absent or when the schema is malformed.
     */
    private static final class ParsedSchema
    {
        private JsonObject properties;
        private JsonArray required;
    }

    /**
     * Defensively parses the JSON {@code inputSchema}, extracting its {@code properties} object and
     * {@code required} array. Any malformed schema is swallowed and an empty result is returned (the
     * method never throws).
     *
     * @param inputSchema the tool's input schema as a JSON string (may be {@code null}/empty)
     * @return the parsed properties/required pair; fields are {@code null} when absent or on error
     */
    private static ParsedSchema parseSchema(String inputSchema)
    {
        ParsedSchema result = new ParsedSchema();
        try
        {
            if (inputSchema != null && !inputSchema.isEmpty())
            {
                JsonElement parsed = JsonParser.parseString(inputSchema);
                if (parsed != null && parsed.isJsonObject())
                {
                    JsonObject root = parsed.getAsJsonObject();
                    if (root.has(PROPERTIES) && root.get(PROPERTIES).isJsonObject())
                    {
                        result.properties = root.getAsJsonObject(PROPERTIES);
                    }
                    if (root.has(REQUIRED) && root.get(REQUIRED).isJsonArray())
                    {
                        result.required = root.getAsJsonArray(REQUIRED);
                    }
                }
            }
        }
        catch (RuntimeException e)
        {
            // Malformed schema: skip the table gracefully, never throw.
            result.properties = null;
            result.required = null;
        }

        return result;
    }

    /**
     * Renders a single parameter row (Parameter, Required, Type, Description) for one
     * {@code properties} entry, resolving its required flag against {@code required}.
     *
     * @param entry the property entry (name to schema)
     * @param required the schema's required array (may be {@code null})
     * @return the rendered Markdown table row
     */
    private static String renderParameterRow(Map.Entry<String, JsonElement> entry, JsonArray required)
    {
        String name = entry.getKey();
        JsonObject prop = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : new JsonObject();

        String requiredCell = isRequired(required, name) ? "yes" : "—"; //$NON-NLS-1$ //$NON-NLS-2$
        String type = prop.has("type") && prop.get("type").isJsonPrimitive() //$NON-NLS-1$ //$NON-NLS-2$
            ? prop.get("type").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$
        String typeCell = appendEnum(type, prop);
        String descriptionCell = prop.has(DESCRIPTION) && prop.get(DESCRIPTION).isJsonPrimitive()
            ? prop.get(DESCRIPTION).getAsString() : ""; //$NON-NLS-1$

        return MarkdownUtils.tableRow(name, requiredCell, typeCell, descriptionCell);
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

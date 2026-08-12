/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Drops the human-readable {@code description} strings from a tool's
 * {@code outputSchema} before it is serialized into {@code tools/list}, keeping the
 * SHAPE (types, nesting, {@code required}, enums) a client validates
 * {@code structuredContent} against.
 * <p>
 * <b>Why.</b> {@code tools/list} is loaded into the model's context on every session,
 * before the user has typed anything. The {@code outputSchema} descriptions are ~21K
 * characters of that payload (12% of the whole list) and are pure prose about a
 * response the model has not received yet: they take no part in choosing a tool or in
 * filling its arguments, and once the tool HAS been called the model reads the real
 * {@code structuredContent} instead. The schema shape stays intact, so a conformant
 * client can still validate a response against it.
 * <p>
 * The descriptions are deliberately kept in the Java sources ({@link JsonSchemaBuilder}
 * calls) — they document the response envelope for maintainers; this class is the wire
 * boundary where they stop.
 * <p>
 * {@code inputSchema} is NOT compacted: a parameter's description (its allowed values,
 * its format and its example) is what the model uses to build a correct call, and
 * removing it produces malformed calls.
 * <p>
 * <b>The walk is structural, not textual.</b> A schema keyword and a PROPERTY NAME
 * share one namespace in JSON, and this plugin really does declare properties called
 * {@code type} ({@code create_launch_config}, {@code evaluate_expression},
 * {@code set_variable}) and {@code items} ({@code delete_metadata}). So the values of a
 * {@code properties} / {@code $defs} / {@code definitions} map are recursed into as
 * SCHEMAS while their keys are never read as keywords — a blind "remove every
 * {@code description} member anywhere" pass would delete a property that happens to be
 * named {@code description}.
 */
public final class OutputSchemaCompactor
{
    /** JSON Schema {@code "description"} key. */
    private static final String KEY_DESCRIPTION = "description"; //$NON-NLS-1$

    /**
     * Keywords whose value is a MAP of name -> subschema. The map's KEYS are names
     * (never keywords) and only its VALUES are recursed into.
     */
    private static final List<String> SCHEMA_MAP_KEYWORDS =
        Arrays.asList("properties", "patternProperties", "$defs", "definitions"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    /**
     * Keywords whose value is a single subschema, or (for the applicator keywords) an
     * ARRAY of subschemas. Both shapes are handled.
     */
    private static final List<String> SCHEMA_VALUE_KEYWORDS = Arrays.asList("items", //$NON-NLS-1$
        "additionalProperties", "contains", "not", "if", "then", "else", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "allOf", "anyOf", "oneOf", "prefixItems"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private OutputSchemaCompactor()
    {
        // Utility class
    }

    /**
     * Returns a copy of {@code outputSchema} with every {@code description} keyword
     * removed, at any nesting depth.
     *
     * @param outputSchema the schema to compact; may be {@code null}
     * @return the compacted schema, or {@code null} when {@code outputSchema} is
     *         {@code null}
     */
    public static JsonElement compact(JsonElement outputSchema)
    {
        if (outputSchema == null)
        {
            return null;
        }
        JsonElement copy = outputSchema.deepCopy();
        stripSchema(copy);
        return copy;
    }

    /**
     * Removes the {@code description} keyword from {@code element} (treated as a
     * schema) and from every nested subschema, in place.
     *
     * @param element the schema node to strip; never {@code null}
     */
    private static void stripSchema(JsonElement element)
    {
        if (element.isJsonArray())
        {
            // An array in a schema position (allOf / anyOf / oneOf / prefixItems, or a
            // tuple-form 'items'): every entry is itself a schema.
            for (JsonElement item : element.getAsJsonArray())
            {
                stripSchema(item);
            }
            return;
        }
        if (!element.isJsonObject())
        {
            return;
        }

        JsonObject schema = element.getAsJsonObject();
        schema.remove(KEY_DESCRIPTION);

        for (String keyword : SCHEMA_MAP_KEYWORDS)
        {
            JsonElement map = schema.get(keyword);
            if (map != null && map.isJsonObject())
            {
                // Recurse into the VALUES only: a key here is a property name, which may
                // legitimately be "description", "type" or "items".
                JsonObject entries = map.getAsJsonObject();
                for (String name : entries.keySet())
                {
                    stripSchema(entries.get(name));
                }
            }
        }

        for (String keyword : SCHEMA_VALUE_KEYWORDS)
        {
            JsonElement value = schema.get(keyword);
            if (value != null && (value.isJsonObject() || value.isJsonArray()))
            {
                stripSchema(value);
            }
        }
    }
}

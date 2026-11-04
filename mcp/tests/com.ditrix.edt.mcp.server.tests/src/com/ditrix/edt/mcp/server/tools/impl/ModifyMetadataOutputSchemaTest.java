/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Validates production success envelopes, including the Role branch, against their schema. */
public class ModifyMetadataOutputSchemaTest
{
    @Test
    public void roleRightsSuccessConformsToDeclaredOutputSchema()
    {
        JsonElement response = JsonParser.parseString(
            ModifyMetadataTool.roleRightsSuccess("Role.SchemaProbe", 1, 2, 3, true));
        JsonObject counts = response.getAsJsonObject().getAsJsonObject("applied");
        assertEquals(1, counts.get("rights").getAsInt());
        assertEquals(2, counts.get("templates").getAsInt());
        assertEquals(3, counts.get("roleProperties").getAsInt());
        validate(response, schema(), "$");
        validate(response, com.ditrix.edt.mcp.server.protocol.OutputSchemaCompactor.compact(schema())
            .getAsJsonObject(), "$");
    }

    @Test
    public void propertyNamesRemainValidAndMalformedCountsAreRejected()
    {
        JsonObject response = JsonParser.parseString(
            ModifyMetadataTool.roleRightsSuccess("Role.SchemaProbe", 1, 0, 0, false)).getAsJsonObject();
        response.add("applied", JsonParser.parseString("[\"comment\",\"synonym\"]"));
        validate(response, schema(), "$");
        for (String invalid : new String[] {"[1]", "{}", "{\"rights\":1,\"templates\":0}",
            "{\"rights\":\"1\",\"templates\":0,\"roleProperties\":0}", "true"})
        {
            response.add("applied", JsonParser.parseString(invalid));
            try
            {
                validate(response, schema(), "$");
            }
            catch (AssertionError expected)
            {
                assertTrue(expected.getMessage(), expected.getMessage().contains("applied"));
                continue;
            }
            fail("Accepted malformed applied: " + invalid);
        }
    }

    private static JsonObject schema()
    {
        return JsonParser.parseString(new ModifyMetadataTool().getOutputSchema()).getAsJsonObject();
    }

    private static void validate(JsonElement value, JsonObject schema, String path)
    {
        if (schema.has("oneOf"))
        {
            int matches = 0;
            for (JsonElement alternative : schema.getAsJsonArray("oneOf"))
            {
                try
                {
                    validate(value, alternative.getAsJsonObject(), path);
                    matches++;
                }
                catch (AssertionError mismatch)
                {
                    // A union accepts exactly one matching alternative.
                }
            }
            assertEquals(path + " oneOf", 1, matches);
        }
        if (schema.has("type"))
        {
            String type = schema.get("type").getAsString();
            boolean matches = switch (type)
            {
                case "object" -> value.isJsonObject();
                case "array" -> value.isJsonArray();
                case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
                case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
                case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                    && value.getAsDouble() % 1 == 0;
                default -> throw new AssertionError("Unhandled schema type " + type);
            };
            assertTrue(path + " type " + type, matches);
        }
        if (value.isJsonObject())
        {
            JsonObject object = value.getAsJsonObject();
            if (schema.has("required"))
            {
                for (JsonElement required : schema.getAsJsonArray("required"))
                {
                    assertTrue(path + " required " + required, object.has(required.getAsString()));
                }
            }
            if (schema.has("properties"))
            {
                for (Map.Entry<String, JsonElement> property : schema.getAsJsonObject("properties").entrySet())
                {
                    if (object.has(property.getKey()))
                    {
                        validate(object.get(property.getKey()), property.getValue().getAsJsonObject(),
                            path + "." + property.getKey());
                    }
                }
            }
        }
        if (value.isJsonArray() && schema.has("items"))
        {
            int index = 0;
            for (JsonElement item : value.getAsJsonArray())
            {
                validate(item, schema.getAsJsonObject("items"), path + "[" + index++ + "]");
            }
        }
    }
}

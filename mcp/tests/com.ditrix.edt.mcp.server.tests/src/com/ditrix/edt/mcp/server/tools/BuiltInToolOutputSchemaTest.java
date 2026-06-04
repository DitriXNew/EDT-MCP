/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Ratchet for the {@code outputSchema} contract ({@link IMcpTool#getOutputSchema()}),
 * serialized into {@code tools/list} so a client knows the shape of a tool's
 * {@code structuredContent} without calling it.
 * <p>
 * The rule is keyed off {@link ResponseType}: only {@code JSON} tools put data in
 * {@code structuredContent}, so they MUST declare an {@code outputSchema}; every other
 * response type returns content (not structured data) and MUST leave it {@code null}.
 * This is bidirectional on purpose — a new JSON tool that forgets its schema fails here,
 * and a TEXT/MARKDOWN tool that declares a spurious one fails too.
 * <p>
 * The declared schema must stay PERMISSIVE: it describes the success envelope
 * ({@code type:object} with a non-empty {@code properties} that includes {@code success})
 * but must NOT set {@code additionalProperties:false} — an over-strict schema would make
 * a conformant client reject a valid response whose conditional (branch-specific) fields
 * are absent or extra. See {@link IMcpTool#getOutputSchema()}.
 */
public class BuiltInToolOutputSchemaTest
{
    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    @Test
    public void jsonToolsDeclareAPermissiveSuccessEnvelopeOutputSchema()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            if (tool.getResponseType() != ResponseType.JSON)
            {
                continue;
            }
            String where = tool.getClass().getSimpleName() + " (" + tool.getName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            String raw = tool.getOutputSchema();
            if (raw == null || raw.trim().isEmpty())
            {
                problems.add(where + ": JSON tool must declare a non-null outputSchema"); //$NON-NLS-1$
                continue;
            }
            JsonElement parsed;
            try
            {
                parsed = JsonParser.parseString(raw);
            }
            catch (RuntimeException e)
            {
                problems.add(where + ": outputSchema is not valid JSON: " + e.getMessage()); //$NON-NLS-1$
                continue;
            }
            if (!parsed.isJsonObject())
            {
                problems.add(where + ": outputSchema must be a JSON object"); //$NON-NLS-1$
                continue;
            }
            JsonObject schema = parsed.getAsJsonObject();
            JsonElement type = schema.get("type"); //$NON-NLS-1$
            if (type == null || !"object".equals(type.getAsString())) //$NON-NLS-1$
            {
                problems.add(where + ": outputSchema must have \"type\":\"object\""); //$NON-NLS-1$
            }
            JsonElement props = schema.get("properties"); //$NON-NLS-1$
            if (props == null || !props.isJsonObject() || props.getAsJsonObject().size() == 0)
            {
                problems.add(where + ": outputSchema must declare a non-empty \"properties\""); //$NON-NLS-1$
            }
            else if (!props.getAsJsonObject().has("success")) //$NON-NLS-1$
            {
                // Every ToolResult.success() payload carries success:true — the schema
                // must describe it, or it does not match the real structuredContent.
                problems.add(where + ": outputSchema \"properties\" must include the \"success\" field"); //$NON-NLS-1$
            }
            JsonElement addl = schema.get("additionalProperties"); //$NON-NLS-1$
            if (addl != null && addl.isJsonPrimitive() && addl.getAsJsonPrimitive().isBoolean()
                && !addl.getAsBoolean())
            {
                // additionalProperties:false would make a conformant client reject any
                // valid payload carrying a conditional field not listed here.
                problems.add(where + ": outputSchema must NOT set additionalProperties:false (stay permissive)"); //$NON-NLS-1$
            }
        }
        assertTrue("JSON outputSchema contract violations:\n  " + String.join("\n  ", problems), //$NON-NLS-1$ //$NON-NLS-2$
            problems.isEmpty());
    }

    @Test
    public void nonJsonToolsDeclareNoOutputSchema()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            if (tool.getResponseType() == ResponseType.JSON)
            {
                continue;
            }
            if (tool.getOutputSchema() != null)
            {
                problems.add(tool.getClass().getSimpleName() + " (" + tool.getName() //$NON-NLS-1$
                    + "): a non-JSON tool returns content, not structuredContent, so it must" //$NON-NLS-1$
                    + " leave outputSchema null (responseType=" + tool.getResponseType() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        assertTrue("non-JSON outputSchema contract violations:\n  " + String.join("\n  ", problems), //$NON-NLS-1$ //$NON-NLS-2$
            problems.isEmpty());
    }
}

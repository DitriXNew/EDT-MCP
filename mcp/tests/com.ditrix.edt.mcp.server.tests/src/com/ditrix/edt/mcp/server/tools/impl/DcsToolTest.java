/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.DcsAddress;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Exact public-contract tests for {@link DcsTool}. */
public class DcsToolTest
{
    private static final Set<String> PROPERTIES = new LinkedHashSet<>(Arrays.asList(
        "projectName", "fqn", "action", "type", "body", "expectedHash", "language", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
        "format", "limit", "offset")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final Set<String> ACTIONS = new LinkedHashSet<>(Arrays.asList(
        "get", "upsert", "update", "replace", "remove")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    private static final Set<String> TYPES = new LinkedHashSet<>(Arrays.asList(
        "schema", "dynamicList", "dataSource", "dataSet", "field", "parameter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "calculatedField", "totalField", "variant", "grouping", "selection", "filter", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "dataParameter", "order", "conditionalAppearance", "table", "userField", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        "outputParameter", "userSettings")); //$NON-NLS-1$ //$NON-NLS-2$

    @Test
    public void testNameDescriptionAndResponseType()
    {
        DcsTool tool = new DcsTool();
        assertEquals("dcs", tool.getName()); //$NON-NLS-1$
        assertEquals(DcsTool.NAME, tool.getName());
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
        // The description carries the protocol a caller cannot infer from the schema: read first,
        // carry the hash into a destructive or index-addressed write, and where the body shapes are.
        // Capability lists belong in the guide, so pin only these clauses.
        assertTrue(tool.getDescription().contains("get_tool_guide('dcs')")); //$NON-NLS-1$
        assertTrue(tool.getDescription().contains("expectedHash")); //$NON-NLS-1$
        assertTrue(tool.getDescription().contains("action='get'")); //$NON-NLS-1$

        Map<String, String> xmlGet = baseGet("Report.Sales", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        xmlGet.put("format", "xml"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ResponseType.JSON, tool.getResponseType(xmlGet));
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType(baseGet("Report.Sales", "schema"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExactSchemaPropertiesRequiredAndEnums()
    {
        JsonObject schema = JsonParser.parseString(new DcsTool().getInputSchema()).getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties"); //$NON-NLS-1$
        assertEquals(PROPERTIES, properties.keySet());
        assertEquals(new LinkedHashSet<>(Arrays.asList("projectName", "fqn", "action", "type")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            strings(schema.getAsJsonArray("required"))); //$NON-NLS-1$
        assertEquals(ACTIONS, strings(properties.getAsJsonObject("action").getAsJsonArray("enum"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(TYPES, strings(properties.getAsJsonObject("type").getAsJsonArray("enum"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(new LinkedHashSet<>(Arrays.asList("md", "xml")), //$NON-NLS-1$ //$NON-NLS-2$
            strings(properties.getAsJsonObject("format").getAsJsonArray("enum"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("object", properties.getAsJsonObject("body").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("integer", properties.getAsJsonObject("limit").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("integer", properties.getAsJsonObject("offset").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // The action prose must warn about the two destructive verbs, since their semantics are not
        // recoverable from the enum tokens alone.
        String actionDescription =
            properties.getAsJsonObject("action").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(actionDescription, actionDescription.contains("replace")); //$NON-NLS-1$
        assertTrue(actionDescription, actionDescription.contains("remove")); //$NON-NLS-1$
        String limitDescription =
            properties.getAsJsonObject("limit").get("description").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(limitDescription, limitDescription.contains("XML chunk characters")); //$NON-NLS-1$
    }

    @Test
    public void testIndexAddressedMutationIsRefusedWithoutExpectedHash()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("fqn", "Report.Sales#/variants/Main/settings/filter/items/0"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("action", "update"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("type", "filter"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("body", "{\"use\":false}"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DcsTool().execute(params);
        assertTrue(result.contains("expectedHash is required")); //$NON-NLS-1$
        assertTrue(result.contains("action='get'")); //$NON-NLS-1$
    }

    @Test
    public void testStaleHashErrorNamesBothHashesAndTheFix()
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(
            "Report.Sales#/variants/Main/settings/filter/items/0"); //$NON-NLS-1$
        assertTrue(parsed.isSuccess());
        String error = DcsTool.validateExpectedHash("aaaaaaaaaaaaaaaaaaaa", //$NON-NLS-1$
            "bbbbbbbbbbbbbbbbbbbb", parsed.address()); //$NON-NLS-1$
        assertNotNull(error);
        assertTrue(error.contains("aaaaaaaaaaaaaaaaaaaa")); //$NON-NLS-1$
        assertTrue(error.contains("bbbbbbbbbbbbbbbbbbbb")); //$NON-NLS-1$
        assertTrue(error.contains("Re-run dcs action='get'")); //$NON-NLS-1$
        assertTrue(error.contains("pass the new expectedHash")); //$NON-NLS-1$
        assertEquals(null, DcsTool.validateExpectedHash("bbbbbbbbbbbbbbbbbbbb", //$NON-NLS-1$
            "bbbbbbbbbbbbbbbbbbbb", parsed.address())); //$NON-NLS-1$
    }

    @Test
    public void testAnnotationsMatchFixedMixedReadWriteContract()
    {
        ToolAnnotations annotations = new DcsTool().getAnnotations();
        assertNotNull(annotations);
        assertEquals(Boolean.FALSE, annotations.getReadOnlyHint());
        assertEquals(Boolean.TRUE, annotations.getDestructiveHint());
        assertEquals(Boolean.FALSE, annotations.getOpenWorldHint());
    }

    @Test
    public void testXmlFormatRefusesFragmentAddress()
    {
        Map<String, String> params = baseGet("Report.Sales#/parameters/Period", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("format", "xml"); //$NON-NLS-1$ //$NON-NLS-2$

        assertXmlFormatRefusal(new DcsTool().execute(params), "action='get'", "type='schema'", //$NON-NLS-1$ //$NON-NLS-2$
            "#/parameters/Period"); //$NON-NLS-1$
    }

    @Test
    public void testXmlFormatRefusesNonSchemaType()
    {
        Map<String, String> params = baseGet("Report.Sales", "dataSet"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("format", "xml"); //$NON-NLS-1$ //$NON-NLS-2$

        assertXmlFormatRefusal(new DcsTool().execute(params), "action='get'", "type='dataSet'", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales"); //$NON-NLS-1$
    }

    @Test
    public void testXmlFormatRefusesWriteAction()
    {
        Map<String, String> params = baseGet("Report.Sales", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("action", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("format", "xml"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("body", "{\"xml\":\"<DataCompositionSchema/>\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expectedHash", "00000000000000000000"); //$NON-NLS-1$ //$NON-NLS-2$

        assertXmlFormatRefusal(new DcsTool().execute(params), "action='replace'", "type='schema'", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales"); //$NON-NLS-1$
    }

    @Test
    public void testXmlBodyIsMutuallyExclusiveWithStructuredSchemaMembers()
    {
        Map<String, String> params = baseGet("Report.Sales", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("action", "replace"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("body", "{\"xml\":\"<DataCompositionSchema/>\",\"parameters\":[]}"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expectedHash", "00000000000000000000"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = new DcsTool().execute(params);
        assertTrue(result, result.contains("body.xml is mutually exclusive")); //$NON-NLS-1$
        assertTrue(result, result.contains("parameters")); //$NON-NLS-1$
        assertTrue(result, result.contains("not both")); //$NON-NLS-1$
    }

    private static Map<String, String> baseGet(String fqn, String type)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("fqn", fqn); //$NON-NLS-1$
        params.put("action", "get"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("type", type); //$NON-NLS-1$
        return params;
    }

    private static void assertXmlFormatRefusal(String result, String action, String type, String address)
    {
        assertTrue(result, result.contains("format='xml' is not allowed")); //$NON-NLS-1$
        assertTrue(result, result.contains(action));
        assertTrue(result, result.contains(type));
        assertTrue(result, result.contains(address));
        assertTrue(result, result.contains("only with action='get', type='schema'")); //$NON-NLS-1$
        assertTrue(result, result.contains("bare root FQN")); //$NON-NLS-1$
    }

    private static Set<String> strings(JsonArray values)
    {
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement value : values)
        {
            result.add(value.getAsString());
        }
        return result;
    }
}

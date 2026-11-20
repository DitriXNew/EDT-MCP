/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.DcsAddress;
import com.ditrix.edt.mcp.server.utils.DcsTemplateWriter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * #662: typed writes at template and grouping-binding addresses pass the tool's shape check (they
 * are planned by the template writer); what cannot be written is refused there, before any model
 * work - field/resource templates, addresses inside a node, collection-level update/replace/remove,
 * insertPosition, another type, and a cells patch without the observed hash.
 */
public class DcsToolTemplateAddressTest
{
    @Test
    public void testTypedWritesAtTemplateAddressesPassTheShapeCheck()
    {
        String[][] cases = {
            {"remove", "Report.Sales#/templates/TotalRow", null},
            {"update", "Report.Sales#/templates/TotalRow", "{\"name\":\"Other\"}"},
            {"replace", "Report.Sales#/groupTemplates/0", "{\"groupName\":\"G\",\"template\":\"T\"}"},
            {"upsert", "Report.Sales#/groupHeaderTemplates", "{\"groupName\":\"Totals\",\"template\":\"T\"}"},
            {"upsert", "Report.Sales#/templates", "{\"name\":\"T\",\"rows\":[]}"},
        };
        for (String[] item : cases)
        {
            // limit is checked right after the template shape check and before any model or
            // display work, so its refusal proves the template check let the write through.
            Map<String, String> params = params(item[0], item[1], "schema", item[2], true); //$NON-NLS-1$
            params.put("limit", "1"); //$NON-NLS-1$ //$NON-NLS-2$
            String error = error(params);
            assertTrue(item[1] + ": " + error, error.startsWith("limit/offset apply only")); //$NON-NLS-1$
        }
    }

    @Test
    public void testWhatTheTypedApiDoesNotWriteIsRefusedBeforeModelWork()
    {
        assertContains(error("remove", "Report.Sales#/fieldTemplates/0", "schema", null, true),
            "field and resource templates are not authorable");
        assertContains(error("update", "Report.Sales#/totalFieldsTemplates/0", "schema", "{}", true),
            "action='replace', type='schema', body={xml:...}");
        assertContains(error("update", "Report.Sales#/templates/T/template/items/0", "schema", "{}", true),
            "points inside a template node");
        assertContains(error("update", "Report.Sales#/templates/T/template/items/0", "schema", "{}", true),
            "Report.Sales#/templates/T");
        assertContains(error("update", "Report.Sales#/templates", "schema", "{\"name\":\"T\"}", true),
            "action='upsert' at the collection");
        assertContains(error("replace", "Report.Sales#/groupTemplates", "schema", "{}", true),
            "groupTemplates:[...]");
        assertContains(error("update", "Report.Sales#/templates/T", "dataSet", "{}", true),
            "type='schema'");
        assertContains(error("upsert", "Report.Sales#/templates", "schema",
            "{\"name\":\"T\",\"insertPosition\":0}", true), "insertPosition is not accepted");
    }

    @Test
    public void testACellsPatchNeedsTheObservedHash()
    {
        String cells = "{\"cells\":[{\"row\":0,\"col\":0,\"text\":\"x\"}]}";
        assertContains(error("update", "Report.Sales#/templates/T", "schema", cells, false), "expectedHash is required");
        assertContains(error("update", "Report.Sales#/templates/T", "schema", cells, false), "body.cells");
        assertContains(error("upsert", "Report.Sales", "schema",
            "{\"templates\":[{\"name\":\"T\",\"cells\":[]}]}", false), "expectedHash is required");
        assertFalse(DcsTemplateWriter.requiresHash(DcsAddress.parse("Report.Sales#/templates/T").address(), //$NON-NLS-1$
            JsonParser.parseString("{\"rows\":[]}").getAsJsonObject())); //$NON-NLS-1$
    }

    @Test
    public void testOnlySchemaRootsAreTemplateAddresses()
    {
        String[][] cases = {
            {"Catalog.Products.Form.ListForm#/fieldTemplates/0", "conditionalAppearance"},
            {"Catalog.Products.Form.ListForm.Attribute.List#/templates/TotalRow", "dynamicList"},
            {"Catalog.Products#/fieldTemplates/0", "schema"},
        };
        for (String[] item : cases)
        {
            String error = error("remove", item[0], item[1], null, false);
            assertFalse(item[0] + ": " + error, error.contains("not authorable")); //$NON-NLS-1$
            assertTrue(item[0] + ": " + error, error.contains("expectedHash is required")); //$NON-NLS-1$
        }
        assertNull(DcsTemplateWriter.shapeRefusal("update", "schema", //$NON-NLS-1$ //$NON-NLS-2$
            DcsAddress.parse("Report.Sales#/dataSetLinks/0").address(), null)); //$NON-NLS-1$
        assertNull(DcsTemplateWriter.shapeRefusal("update", "schema", //$NON-NLS-1$ //$NON-NLS-2$
            DcsAddress.parse("Report.Sales#/parameters/templates").address(), null)); //$NON-NLS-1$
    }

    /**
     * Every case here is refused before the tool reaches the display: a call that passed every
     * check would create an SWT display on the test thread and leak it into later tests.
     */
    private static String error(String action, String fqn, String type, String body, boolean hash)
    {
        return error(params(action, fqn, type, body, hash));
    }

    private static Map<String, String> params(String action, String fqn, String type, String body, boolean hash)
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("fqn", fqn); //$NON-NLS-1$
        params.put("action", action); //$NON-NLS-1$
        params.put("type", type); //$NON-NLS-1$
        if (hash) params.put("expectedHash", "00000000000000000000"); //$NON-NLS-1$ //$NON-NLS-2$
        if (body != null) params.put("body", body); //$NON-NLS-1$
        return params;
    }

    private static String error(Map<String, String> params)
    {
        JsonObject result = JsonParser.parseString(new DcsTool().execute(params)).getAsJsonObject();
        assertFalse(params.get("fqn"), result.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        return result.get("error").getAsString(); //$NON-NLS-1$
    }

    private static void assertContains(String text, String expected)
    {
        assertTrue("expected <" + expected + "> in: " + text, text.contains(expected)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

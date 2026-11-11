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
import com.ditrix.edt.mcp.server.utils.DcsTemplateProjection;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * #662 exposes template and grouping-binding addresses to reads only. A typed write at one of them
 * is refused before any model work, with the lossless XML channel as the way to change them.
 */
public class DcsToolTemplateAddressTest
{
    @Test
    public void testEveryTypedWriteAtATemplateAddressIsRefusedWithTheXmlRoute()
    {
        String[][] cases = {
            {"remove", "Report.Sales#/templates/TotalRow", null},
            {"update", "Report.Sales#/templates/TotalRow", "{\"name\":\"Other\"}"},
            {"replace", "Report.Sales#/groupTemplates/0", "{\"templateType\":\"Footer\"}"},
            {"upsert", "Report.Sales#/groupHeaderTemplates", "{\"groupName\":\"Totals\"}"},
        };
        for (String[] item : cases)
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("fqn", item[1]); //$NON-NLS-1$
            params.put("action", item[0]); //$NON-NLS-1$
            params.put("type", "schema"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("expectedHash", "00000000000000000000"); //$NON-NLS-1$ //$NON-NLS-2$
            if (item[2] != null) params.put("body", item[2]); //$NON-NLS-1$

            JsonObject result = JsonParser.parseString(new DcsTool().execute(params)).getAsJsonObject();

            assertFalse(item[0], result.get("success").getAsBoolean()); //$NON-NLS-1$
            String error = result.get("error").getAsString(); //$NON-NLS-1$
            assertTrue(error, error.contains("Template address '" + item[1] + "' is read-only")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(error, error.contains("action='" + item[0] + "' was not run")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(error, error.contains("format='xml' on 'Report.Sales'")); //$NON-NLS-1$
            assertTrue(error, error.contains("action='replace', type='schema', body={xml:...}")); //$NON-NLS-1$
        }
    }

    @Test
    public void testOnlySchemaRootsAreGuardedOtherRootsKeepTheirOwnValidation()
    {
        // No expectedHash: a guarded schema root answers with the template refusal, every other
        // root keeps the validation it had before the guard (here, the missing-hash error).
        String[][] cases = {
            {"Report.Sales#/templates/TotalRow", "schema", "read-only"},
            {"CommonTemplate.Analytics#/templates/TotalRow", "schema", "read-only"},
            {"Report.Sales.Template.CustomDcs#/groupTemplates/0", "schema", "read-only"},
            {"Catalog.Products.Form.ListForm#/templates/TotalRow", "conditionalAppearance", null},
            {"Catalog.Products.Form.ListForm.Attribute.List#/templates/TotalRow", "dynamicList", null},
            {"Catalog.Products#/templates/TotalRow", "schema", null},
        };
        for (String[] item : cases)
        {
            Map<String, String> params = new HashMap<>();
            params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("fqn", item[0]); //$NON-NLS-1$
            params.put("action", "remove"); //$NON-NLS-1$ //$NON-NLS-2$
            params.put("type", item[1]); //$NON-NLS-1$

            String error = JsonParser.parseString(new DcsTool().execute(params)).getAsJsonObject()
                .get("error").getAsString(); //$NON-NLS-1$

            if (item[2] != null)
            {
                assertTrue(item[0] + ": " + error, error.contains("Template address '" + item[0] //$NON-NLS-1$
                    + "' is " + item[2])); //$NON-NLS-1$
            }
            else
            {
                assertFalse(item[0] + ": " + error, error.contains("Template address")); //$NON-NLS-1$
                assertTrue(item[0] + ": " + error, error.contains("expectedHash is required")); //$NON-NLS-1$
            }
        }
        assertNull(DcsTemplateProjection.writeRefusal("remove", //$NON-NLS-1$
            DcsAddress.parse("Catalog.Products.Form.ListForm#/templates/X").address())); //$NON-NLS-1$
    }

    @Test
    public void testOnlyTemplateCollectionsAreGuarded()
    {
        assertNull(DcsTemplateProjection.writeRefusal("update", //$NON-NLS-1$
            DcsAddress.parse("Report.Sales#/dataSetLinks/0").address())); //$NON-NLS-1$
        assertNull(DcsTemplateProjection.writeRefusal("upsert", //$NON-NLS-1$
            DcsAddress.parse("Report.Sales").address())); //$NON-NLS-1$
        assertNull(DcsTemplateProjection.writeRefusal("update", //$NON-NLS-1$
            DcsAddress.parse("Report.Sales#/parameters/templates").address())); //$NON-NLS-1$
        assertTrue(DcsTemplateProjection.writeRefusal("remove", //$NON-NLS-1$
            DcsAddress.parse("Report.Sales#/groupTemplates/0/groupFields").address()) != null); //$NON-NLS-1$
    }
}

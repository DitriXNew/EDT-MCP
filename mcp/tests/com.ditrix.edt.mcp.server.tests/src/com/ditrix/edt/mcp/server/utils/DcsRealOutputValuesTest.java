/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.junit.Test;

import com._1c.g5.v8.dt.platform.version.Version;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.OutputParameterCatalogue;

/** The value-list claim must be checked against EDT's catalogues, rather than a scalar mock. */
public class DcsRealOutputValuesTest
{
    @Test
    public void testNoRealCatalogueParameterAllowsAValueList() throws Exception
    {
        List<Enum<?>> kinds = new ArrayList<>(List.of(OutputParameterCatalogue.values()));
        kinds.addAll(List.of(AppearanceCatalogue.values()));
        var versions = Version.getPlatformSupportVersions();
        assertTrue(versions.toString(), versions.contains(Version.V8_3_27));
        assertTrue(versions.toString(), versions.contains(Version.LATEST));
        for (Version version : versions)
        {
            int count = 0;
            List<String> lists = new ArrayList<>();
            for (Enum<?> kind : kinds)
            {
                var catalogue = DcsReplaceTestCatalogues.load(kind, version, "en");
                count += count(catalogue, kind.toString(), lists,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            }
            System.out.println("VALUE_LIST_CATALOGUE " + version + " checked=" + count + " lists=" + lists);
            assertTrue("all real catalogue families were loaded", count > 100);
            assertEquals(version.toString(), List.of(), lists);
        }
    }
    @Test
    public void testRealOutputParametersUseOneTypedValueRatherThanTheScalarValuesArray() throws Exception
    {
        var catalogue = DcsReplaceTestCatalogues.load(OutputParameterCatalogue.SETTINGS, Version.V8_3_27, "en");
        for (String array : new String[] {"[]", "[\"A\"]", "[\"A\",\"B\"]"})
        {
            var body = com.google.gson.JsonParser.parseString(
                "{\"parameter\":{\"kind\":\"parameter\",\"value\":\"Title\"},\"values\":" + array + "}")
                .getAsJsonObject();
            var item = DcsStoredXmlTestSupport.SETTINGS.createSettingsParameterValue();
            String error = DcsSettingsWriter.applyOutputParameterItemForTest(item, body, null, catalogue);
            assertNotNull(array, error);
            assertTrue(error, error.contains("accepts one typed 'value', not a 'values' array"));
            assertTrue(error, error.contains("only data parameters accept 'values'"));
            body.addProperty("value", "A");
            error = DcsSettingsWriter.applyOutputParameterItemForTest(item, body, null, catalogue);
            assertNotNull("an output array is refused even beside a scalar value", error);
            assertTrue(error, error.contains("accepts one typed 'value', not a 'values' array"));
        }
        var body = com.google.gson.JsonParser.parseString(
            "{\"parameter\":{\"kind\":\"parameter\",\"value\":\"Title\"},\"value\":\"A\"}").getAsJsonObject();
        var item = DcsStoredXmlTestSupport.SETTINGS.createSettingsParameterValue();
        assertNull(DcsSettingsWriter.applyOutputParameterItemForTest(item, body, null, catalogue));
        assertEquals(1, item.getValues().size());
        assertTrue(item.getValues().get(0) instanceof com._1c.g5.v8.dt.dcs.model.core.LocalString);
    }

    private static int count(DcsAvailableParameterCollection catalogue, String path, List<String> lists,
        Set<DcsAvailableParameterCollection> visited)
    {
        if (catalogue == null || !visited.add(catalogue)) return 0;
        int result = catalogue.itemsCount();
        for (int i = 0; i < catalogue.itemsCount(); i++)
        {
            var entry = catalogue.getItemAt(i);
            String name = path + "/" + entry.key(0);
            if (entry.isValueListAllowed()) lists.add(name + "/"
                + (entry.defValue() == null ? "null" : entry.defValue().eClass().getName()));
            result += count(entry.getSubParameters(), name, lists, visited);
        }
        return result;
    }
}

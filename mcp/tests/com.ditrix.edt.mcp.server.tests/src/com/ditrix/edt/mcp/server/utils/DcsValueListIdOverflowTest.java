/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.TypedValueList;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Auto-assigned value-list IDs must stay within the int range and fail before attachment. */
public class DcsValueListIdOverflowTest
{
    @Test
    public void testOmittedIdAfterMaximumIsRefusedWithTheItemPath()
    {
        var result = DcsSettingsWriter.buildValue(list("[{id:2147483647},{}]"), "sample");
        assertOverflow(result.error, "sample.value.items[1]");
        assertNull("the partially built list must not escape", result.value);
    }

    @Test
    public void testMaximumRemainsTheCeilingAfterALowerExplicitId()
    {
        var result = DcsSettingsWriter.buildValue(list("[{id:2147483647},{id:5},{}]"), "sample");
        assertOverflow(result.error, "sample.value.items[2]");
        assertNull(result.value);
    }

    @Test
    public void testNullIdAlsoRequiresExplicitIdsWhenTheRangeIsExhausted()
    {
        var result = DcsSettingsWriter.buildValue(list("[{id:2147483647},{id:null},{}]"), "sample");
        assertOverflow(result.error, "sample.value.items[1]");
        assertNull(result.value);
    }

    @Test
    public void testOrdinaryAutoAssignmentStillUsesTheHighestPreviousId()
    {
        TypedValueList list = built("[{id:5},{}]");
        assertEquals(6, list.getItems().get(1).getId());
        assertEquals(6, list.getLastId());
        list = built("[{id:5},{id:2},{}]");
        assertEquals(6, list.getItems().get(2).getId());
        assertEquals(6, list.getLastId());
        assertEquals(1, built("[{}]").getItems().get(0).getId());
    }

    @Test
    public void testLastSupportedAutomaticIdIsAccepted()
    {
        TypedValueList list = built("[{id:2147483646},{}]");
        assertEquals(Integer.MAX_VALUE, list.getItems().get(1).getId());
        assertEquals(Integer.MAX_VALUE, list.getLastId());
    }

    @Test
    public void testExplicitIdsStillWorkAfterTheMaximum()
    {
        TypedValueList list = built("[{id:2147483647},{id:5},{id:6}]");
        assertEquals(5, list.getItems().get(1).getId());
        assertEquals(6, list.getItems().get(2).getId());
        assertEquals(Integer.MAX_VALUE, list.getLastId());
    }

    @Test
    public void testOverflowInAParameterValuesArrayDoesNotPartiallyAttach()
    {
        var settings = DcsFactory.eINSTANCE.createDataCompositionSettings();
        var holder = DcsFactory.eINSTANCE.createDataCompositionDataParameterValues();
        var item = DcsFactory.eINSTANCE.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("P"));
        var previous = McoreFactory.eINSTANCE.createStringValue();
        previous.setValue("keep this value");
        item.getValues().add(previous);
        holder.getItems().add(item);
        settings.setDataParameters(holder);
        var before = EcoreUtil.copy(settings);
        JsonObject body = new JsonObject();
        var values = new com.google.gson.JsonArray();
        values.add(JsonParser.parseString("{kind:'number',value:1}"));
        values.add(list("[{id:2147483647},{}]"));
        body.add("values", values);
        var planned = DcsSettingsWriter.planSettings(settings,
            List.of("dataParameters", "items", "0"), "update", "dataParameter", body, null);
        assertOverflow(planned.error(), "values[1].value.items[1]");
        assertTrue("a rejected list must leave the original settings untouched", EcoreUtil.equals(before, settings));
        assertEquals(1, item.getValues().size());
        assertSame(previous, item.getValues().get(0));
    }

    private static TypedValueList built(String items)
    {
        var result = DcsSettingsWriter.buildValue(list(items), "sample");
        assertNull(result.error, result.error);
        return (TypedValueList)result.value;
    }

    private static JsonObject list(String items)
    {
        return JsonParser.parseString("{kind:'valueList',value:{items:" + items + "}}")
            .getAsJsonObject();
    }

    private static void assertOverflow(String error, String path)
    {
        assertNotNull("the exhausted ID range must be refused", error);
        assertTrue(error, error.contains(path));
        assertTrue(error, error.contains("2147483647"));
        assertTrue(error, error.contains("Supply explicit ids"));
    }
}

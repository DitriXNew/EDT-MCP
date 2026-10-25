/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StandardBeginningDateValue;
import com._1c.g5.v8.dt.mcore.StandardBeginningDateVariant;
import com._1c.g5.v8.dt.mcore.StandardPeriodValue;
import com._1c.g5.v8.dt.mcore.StandardPeriodVariant;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.TypedValueList;
import com._1c.g5.v8.dt.mcore.TypedValueListItem;
import com._1c.g5.v8.dt.mcore.Value;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * #682 round 5: the untyped ValueSpec writer value() builds the three value kinds EDT stores in DCS
 * settings beyond the scalar ones - a standard period, a standard beginning date and a value list -
 * and a parameter item carries several values in 'values'.
 */
public class DcsStandardValueKindsTest
{
    private static final String DATE = "2026-01-31T23:59:59"; //$NON-NLS-1$

    @Test
    public void testAStandardPeriodBodyBuildsTheVariantAndTheDates()
    {
        StandardPeriodValue month = (StandardPeriodValue)built(
            "{\"kind\":\"standardPeriod\",\"value\":{\"variant\":\"ThisMonth\"}}");
        assertSame(StandardPeriodVariant.THIS_MONTH, month.getValue().getVariant());
        assertNull(month.getValue().getStartDate());
        assertNull(month.getValue().getEndDate());

        StandardPeriodValue custom = (StandardPeriodValue)built("{\"kind\":\"standardPeriod\",\"value\":"
            + "{\"variant\":\"Custom\",\"startDate\":\"2026-01-01T00:00:00\",\"endDate\":\"" + DATE + "\"}}");
        assertSame(StandardPeriodVariant.CUSTOM, custom.getValue().getVariant());
        assertEquals("2026-01-01T00:00:00", custom.getValue().getStartDate().toString());
        assertEquals(DATE, custom.getValue().getEndDate().toString());

        StandardPeriodValue omitted = (StandardPeriodValue)built(
            "{\"kind\":\"standardPeriod\",\"value\":{\"startDate\":\"2026-01-01T00:00:00\"}}");
        assertSame("an omitted variant is the model default", StandardPeriodVariant.CUSTOM,
            omitted.getValue().getVariant());
        StandardPeriodValue anyCase = (StandardPeriodValue)built(
            "{\"kind\":\"standardPeriod\",\"value\":{\"variant\":\"lastyear\"}}");
        assertSame(StandardPeriodVariant.LAST_YEAR, anyCase.getValue().getVariant());
    }

    @Test
    public void testAStandardBeginningDateBodyBuildsTheVariantAndTheDate()
    {
        StandardBeginningDateValue day = (StandardBeginningDateValue)built(
            "{\"kind\":\"standardBeginningDate\",\"value\":{\"variant\":\"BeginningOfThisDay\"}}");
        assertSame(StandardBeginningDateVariant.BEGINNING_OF_THIS_DAY, day.getValue().getVariant());
        assertNull(day.getValue().getDate());
        StandardBeginningDateValue custom = (StandardBeginningDateValue)built(
            "{\"kind\":\"standardBeginningDate\",\"value\":{\"date\":\"" + DATE + "\"}}");
        assertSame(StandardBeginningDateVariant.CUSTOM, custom.getValue().getVariant());
        assertEquals(DATE, custom.getValue().getDate().toString());
    }

    @Test
    public void testAValueListBodyBuildsItemsIdsAndTheDesignersEmptyValueType()
    {
        TypedValueList empty = (TypedValueList)built("{\"kind\":\"valueList\",\"value\":{}}");
        assertTrue(empty.getItems().isEmpty());
        assertEquals("the designer stores -1 for an empty list", -1, empty.getLastId());
        assertNotNull(empty.getValueType());
        assertTrue(empty.getValueType().getTypes().isEmpty());
        assertNull(empty.getAvailableValues());

        TypedValueList list = (TypedValueList)built("{\"kind\":\"valueList\",\"value\":{\"items\":["
            + "{\"value\":{\"kind\":\"number\",\"value\":1},\"presentation\":\"One\"},"
            + "{\"value\":{\"kind\":\"standardPeriod\",\"value\":{\"variant\":\"Today\"}},\"checkState\":1},"
            + "{\"id\":7},{}]}}");
        assertEquals(4, list.getItems().size());
        TypedValueListItem first = list.getItems().get(0);
        assertEquals(1, first.getId());
        assertEquals("One", first.getPresentation());
        assertEquals(1, ((NumberValue)first.getValue()).getValue().intValue());
        TypedValueListItem second = list.getItems().get(1);
        assertEquals(2, second.getId());
        assertEquals(1, second.getCheckState());
        assertSame(StandardPeriodVariant.TODAY,
            ((StandardPeriodValue)second.getValue()).getValue().getVariant());
        assertEquals(7, list.getItems().get(2).getId());
        assertNull(list.getItems().get(2).getValue());
        assertEquals("an item without id takes one more than the highest before it", 8,
            list.getItems().get(3).getId());
        assertEquals("lastId defaults to the highest item id", 8, list.getLastId());

        TypedValueList explicit = (TypedValueList)built("{\"kind\":\"valueList\",\"value\":{\"lastId\":12,"
            + "\"items\":[{\"id\":3,\"value\":{\"kind\":\"string\",\"value\":\"x\"}}]}}");
        assertEquals(12, explicit.getLastId());
        assertEquals(3, explicit.getItems().get(0).getId());
        assertEquals("x", ((StringValue)explicit.getItems().get(0).getValue()).getValue());
    }

    @Test
    public void testMalformedBodiesOfTheNewKindsAreRefusedWithTheirShape()
    {
        assertError("{\"kind\":\"standardPeriod\",\"value\":\"ThisMonth\"}",
            "needs an object in 'value': {variant?, startDate?, endDate?}");
        assertError("{\"kind\":\"standardPeriod\",\"value\":{\"variant\":\"NotAVariant\"}}",
            "Enum value 'NotAVariant'", "ThisMonth", "LastYear");
        assertError("{\"kind\":\"standardPeriod\",\"value\":{\"startDate\":\"2026-01-31T23:59:59Z\"}}",
            "is not a platform date", "startDate");
        assertError("{\"kind\":\"standardPeriod\",\"value\":{\"from\":\"" + DATE + "\"}}",
            "Unknown member 'from'", "variant, startDate, endDate");
        assertError("{\"kind\":\"standardBeginningDate\",\"value\":{\"variant\":\"ThisMonth\"}}",
            "Enum value 'ThisMonth'", "BeginningOfThisDay");
        assertError("{\"kind\":\"standardBeginningDate\",\"value\":{\"date\":5}}",
            "must be a platform date literal string");
        assertError("{\"kind\":\"valueList\",\"value\":{\"items\":{}}}", "must be a JSON array");
        assertError("{\"kind\":\"valueList\",\"value\":{\"items\":[{\"picture\":\"x\"}]}}",
            "Unknown member 'picture'", "value, presentation, checkState, id");
        assertError("{\"kind\":\"valueList\",\"value\":{\"items\":[{\"presentation\":{\"en\":\"x\"}}]}}",
            "presentation' must be a string");
        assertError("{\"kind\":\"valueList\",\"value\":{\"items\":[{\"checkState\":1.5}]}}",
            "checkState' must be a whole number");
        assertError("{\"kind\":\"valueList\",\"value\":{\"lastId\":\"3\"}}", "lastId' must be a whole number");
        assertError("{\"kind\":\"valueList\",\"value\":{\"items\":[{\"value\":{\"kind\":\"field\",\"value\":\"\"}}]}}",
            "needs a non-empty string");
        assertError("{\"kind\":\"list\",\"value\":[]}",
            "Use one of: field, parameter, expression, string, number, boolean, date, null, standardPeriod, "
                + "standardBeginningDate, valueList.");
    }

    @Test
    public void testADataParameterItemCarriesEveryValueInValues()
    {
        DcsSettingsWriter.SettingsResult written = writeDataParameter("{\"parameter\":"
            + "{\"kind\":\"parameter\",\"value\":\"P\"},\"values\":[{\"kind\":\"number\",\"value\":1},"
            + "{\"kind\":\"standardPeriod\",\"value\":{\"variant\":\"ThisYear\"}}]}");
        assertTrue(written.error(), written.isSuccess());
        List<Value> values = written.settings().getDataParameters().getItems().get(0).getValues();
        assertEquals(2, values.size());
        assertTrue(values.get(0) instanceof NumberValue);
        assertSame(StandardPeriodVariant.THIS_YEAR, ((StandardPeriodValue)values.get(1)).getValue().getVariant());

        DcsSettingsWriter.SettingsResult cleared = writeDataParameter("{\"values\":[]}");
        assertTrue(cleared.error(), cleared.isSuccess());
        assertTrue("an empty array clears the values",
            cleared.settings().getDataParameters().getItems().get(0).getValues().isEmpty());

        String both = writeDataParameter("{\"value\":{\"kind\":\"null\"},\"values\":[]}").error();
        assertNotNull(both);
        assertTrue(both, both.contains("takes 'value' or 'values', not both"));
        String bad = writeDataParameter(
            "{\"values\":[{\"kind\":\"number\",\"value\":1},{\"kind\":\"field\",\"value\":\"\"}]}").error();
        assertNotNull(bad);
        assertTrue(bad, bad.contains(".values[1]"));
    }

    /** An update of the one data parameter P, through the settings planner every dataParameter write uses. */
    static DcsSettingsWriter.SettingsResult writeDataParameter(String body)
    {
        var settings = DcsFactory.eINSTANCE.createDataCompositionSettings();
        var holder = DcsFactory.eINSTANCE.createDataCompositionDataParameterValues();
        SettingsParameterValue item = DcsFactory.eINSTANCE.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("P"));
        item.getValues().add(McoreFactory.eINSTANCE.createNullValue());
        holder.getItems().add(item);
        settings.setDataParameters(holder);
        return DcsSettingsWriter.planSettings(settings, List.of("dataParameters", "items", "0"), "update", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "dataParameter", json(body), null); //$NON-NLS-1$
    }

    private static Value built(String body)
    {
        DcsSettingsWriter.ValueResult result = DcsSettingsWriter.buildValue(JsonParser.parseString(body), "value");
        assertNull(body, result.error);
        return result.value;
    }

    private static void assertError(String body, String... fragments)
    {
        DcsSettingsWriter.ValueResult result = DcsSettingsWriter.buildValue(JsonParser.parseString(body), "value");
        assertNotNull(body, result.error);
        for (String fragment : fragments) assertTrue(result.error, result.error.contains(fragment));
    }

    private static JsonObject json(String text)
    {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}

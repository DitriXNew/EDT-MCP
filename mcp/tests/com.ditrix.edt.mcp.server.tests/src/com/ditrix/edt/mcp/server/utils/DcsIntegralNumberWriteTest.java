/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.TypedValueList;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Numeric ValueSpecs keep EDT's integral spelling after the nested Gson argument transport. */
public class DcsIntegralNumberWriteTest
{
    private static final Gson GSON = new Gson();

    @Test
    public void testGsonDeliversAnIntegralDoubleButTheWriterStoresScaleZero()
    {
        JsonObject body = transport("{kind:'number',value:701}");
        assertEquals("701.0", body.get("value").getAsString());
        assertNumber("701", untyped(body));
    }

    @Test
    public void testTypedNumbersUseTheSameNormalizationAndKeepRealFractions()
    {
        for (String literal : new String[] {"701", "1.5", "-0.0"})
        {
            JsonObject body = transport("{kind:'number',value:" + literal + "}");
            var result = DcsSettingsWriter.buildTypedParameterValue(body,
                McoreFactory.eINSTANCE.createNumberValue(), null, "value");
            assertNull(result.error);
            assertNumber("-0.0".equals(literal) ? "0" : literal, (NumberValue)result.value);
        }
    }

    @Test
    public void testExactFractionDigitsAndLargeIntegralValuesArePreserved()
    {
        for (String[] sample : new String[][] {{"1.50", "1.50"},
            {"1.234567890123456789", "1.234567890123456789"},
            {"9223372036854775808.000", "9223372036854775808"}, {"7.01e2", "701"}})
        {
            assertNumber(sample[1], untyped(JsonParser.parseString(
                "{kind:'number',value:" + sample[0] + "}").getAsJsonObject()));
        }
    }

    @Test
    public void testEveryDataParameterValueIsNormalizedAfterGsonTransport()
    {
        JsonObject body = transport("{values:[{kind:'number',value:701},{kind:'number',value:1.5}]}");
        var written = DcsStandardValueKindsTest.writeDataParameter(body.toString());
        assertTrue(written.error(), written.isSuccess());
        var values = written.settings().getDataParameters().getItems().get(0).getValues();
        assertNumber("701", (NumberValue)values.get(0));
        assertNumber("1.5", (NumberValue)values.get(1));
    }

    @Test
    public void testNestedValueListNumbersUseTheSameWriter()
    {
        JsonObject body = transport("{kind:'valueList',value:{items:["
            + "{value:{kind:'number',value:701}},{value:{kind:'number',value:1.5}}]}}");
        var result = DcsSettingsWriter.buildValue(body, "value");
        assertNull(result.error);
        var items = ((TypedValueList)result.value).getItems();
        assertNumber("701", (NumberValue)items.get(0).getValue());
        assertNumber("1.5", (NumberValue)items.get(1).getValue());
    }

    @Test
    public void testSchemaDefaultsAvailableValuesAndInputParametersShareTheNumberWriter()
    {
        var schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchema();
        var body = transport("{parameters:[{name:'P',valueType:{types:[{kind:'Number'}]},"
            + "values:[{kind:'number',value:701}],availableValues:[{value:{kind:'number',value:702}}],"
            + "inputParameters:{items:[{parameter:{kind:'parameter',value:'Input'},"
            + "values:[{kind:'number',value:703}]}]}}]}");
        var result = DcsWriter.apply(schema, body, spec -> {
            var type = McoreFactory.eINSTANCE.createTypeDescription();
            type.setNumberQualifiers(McoreFactory.eINSTANCE.createNumberQualifiers());
            return DcsWriter.TypeResolution.of(type);
        });
        assertFalse(result.error, result.hasError());
        var parameter = schema.getParameters().get(0);
        assertNumber("701", (NumberValue)parameter.getValues().get(0));
        assertNumber("702", (NumberValue)parameter.getAvailableValues().get(0).getValue());
        assertNumber("703", (NumberValue)parameter.getInputParameters().getItems().get(0).getValues().get(0));
    }

    private static JsonObject transport(String json)
    {
        // The protocol's default Gson Map transport boxes nested numbers as Double.
        Map<?, ?> arguments = GSON.fromJson(json, Map.class);
        return GSON.toJsonTree(arguments).getAsJsonObject();
    }

    private static NumberValue untyped(JsonObject body)
    {
        var result = DcsSettingsWriter.buildValue(body, "value");
        assertNull(result.error);
        return (NumberValue)result.value;
    }

    private static void assertNumber(String expected, NumberValue number)
    {
        BigDecimal decimal = new BigDecimal(expected);
        assertEquals("BigDecimal equality includes scale", decimal, number.getValue());
        assertEquals(decimal.scale(), number.getValue().scale());
    }
}

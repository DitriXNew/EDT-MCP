/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.ValueResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Compact exponents must be bounded before integral normalization can allocate their digits. */
public class DcsNumberExpansionLimitTest
{
    @Test(timeout = 2000)
    public void testCompactHugeExponentIsRefusedBeforeAnyIntegerExpansion()
    {
        JsonObject body = number("1e100000000");
        BigDecimal received = new BigDecimal(body.get("value").getAsString());
        assertEquals(1, received.precision());
        assertEquals(-100000000, received.scale());
        assertRefusedWithoutExpansion(body);
        // Only exercise the uninstrumented request after the probe has proved it cannot expand.
        assertLimit(DcsSettingsWriter.buildValue(body, "value").error, "1e100000000");
    }

    @Test(timeout = 2000)
    public void testIntegerDigitCountCannotOverflowBeforeTheLimitCheck()
    {
        JsonObject body = number("1e2147483647");
        assertRefusedWithoutExpansion(body);
        assertLimit(DcsSettingsWriter.buildValue(body, "value").error, "1e2147483647");
    }

    @Test(timeout = 2000)
    public void testTinyFractionsDoNotExpandWhileTestingWhetherTheyAreIntegral()
    {
        DcsNumberExpansionProbe received = new DcsNumberExpansionProbe("1e-38");
        JsonObject body = number("1e-38");
        body.add("value", new JsonPrimitive(received));
        ValueResult result = DcsSettingsWriter.buildValue(body, "value");
        assertNull(result.error, result.error);
        assertSame(received, ((NumberValue)result.value).getValue());
        assertFalse(received.expansionAttempted);
        ValueResult ordinary = DcsSettingsWriter.buildValue(number("1e-38"), "value");
        assertNull(ordinary.error, ordinary.error);
        assertEquals(new BigDecimal("1e-38"), ((NumberValue)ordinary.value).getValue());
    }

    @Test
    public void testThe38DigitBoundaryStillNormalizesExactly()
    {
        for (String literal : List.of("1e37", "-1e37", "99999999999999999999999999999999999999.000"))
        {
            ValueResult result = DcsSettingsWriter.buildValue(number(literal), "value");
            assertNull(literal + ": " + result.error, result.error);
            BigDecimal stored = ((NumberValue)result.value).getValue();
            assertEquals(new BigDecimal(literal).setScale(0), stored);
            assertEquals(0, stored.scale());
        }
    }

    @Test
    public void testTheFirstUnsupportedIntegerDigitIsRefusedWithTheValueAndLimit()
    {
        for (String literal : List.of("1e38", "-1e38", "999999999999999999999999999999999999999"))
        {
            ValueResult result = DcsSettingsWriter.buildValue(number(literal), "value");
            assertLimit(result.error, literal);
            assertNull("a refused number must not be attached", result.value);
        }
    }

    @Test
    public void testFractionsTransportIntegersAndExponentZeroKeepTheirExistingNormalization()
    {
        for (String[] sample : new String[][] {{"701.0", "701"}, {"1.50", "1.50"},
            {"1.234567890123456789", "1.234567890123456789"}, {"-0.0", "0"}})
        {
            ValueResult result = DcsSettingsWriter.buildValue(number(sample[0]), "value");
            assertNull(result.error, result.error);
            assertEquals(new BigDecimal(sample[1]), ((NumberValue)result.value).getValue());
        }
        JsonObject zero = number("0");
        zero.add("value", new JsonPrimitive(new BigDecimal("0e100000000")));
        ValueResult normalized = DcsSettingsWriter.buildValue(zero, "value");
        assertNull(normalized.error, normalized.error);
        assertEquals(BigDecimal.ZERO, ((NumberValue)normalized.value).getValue());
    }

    @Test
    public void testTypedOutputAndAppearanceNumbersUseTheSameLimit()
    {
        var declared = McoreFactory.eINSTANCE.createNumberValue();
        assertLimit(DcsSettingsWriter.buildTypedParameterValue(number("1e38"), declared,
            null, "value").error, "1e38");
        var available = DcsReplaceTestCatalogues.available("Known", "KnownAlias", declared);
        var item = DcsStoredXmlTestSupport.SETTINGS.createSettingsParameterValue();
        assertLimit(DcsSettingsWriter.applyOutputParameterItemForTest(item,
            json("{parameter:{kind:'parameter',value:'Known'},value:{kind:'number',value:1e38}}"),
            null, available), "1e38");
        assertTrue(item.getValues().isEmpty());
        var appearance = DcsSettingsWriter.buildAppearanceForTest(
            json("{Known:{kind:'number',value:1e38}}"), null, null, available);
        assertLimit(appearance.error, "1e38");
    }

    @Test
    public void testDataParameterArraysAndNestedValueListsUseTheSameLimit()
    {
        var result = DcsStandardValueKindsTest.writeDataParameter(
            "{values:[{kind:'number',value:1},{kind:'number',value:1e38}]}");
        assertLimit(result.error(), "1e38");
        assertTrue(result.error(), result.error().contains("values[1]"));
        ValueResult list = DcsSettingsWriter.buildValue(JsonParser.parseString(
            "{kind:'valueList',value:{items:[{value:{kind:'number',value:1e38}}]}}"), "value");
        assertLimit(list.error, "1e38");
        assertTrue(list.error, list.error.contains("items[0].value"));
    }

    @Test
    public void testSchemaDefaultsAvailableValuesAndInputParametersUseTheSameLimit()
    {
        for (String member : List.of("values:[{kind:'number',value:1e38}]",
            "availableValues:[{value:{kind:'number',value:1e38}}]",
            "inputParameters:{items:[{parameter:{kind:'parameter',value:'Input'},"
                + "values:[{kind:'number',value:1e38}]}]}"))
        {
            var schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
                .createDataCompositionSchema();
            var result = DcsWriter.apply(schema,
                json("{parameters:[{name:'P',valueType:{types:[{kind:'Number'}]}," + member + "}]}"),
                spec -> {
                    var type = McoreFactory.eINSTANCE.createTypeDescription();
                    type.setNumberQualifiers(McoreFactory.eINSTANCE.createNumberQualifiers());
                    return DcsWriter.TypeResolution.of(type);
                });
            assertTrue(member, result.hasError());
            assertLimit(result.error, "1e38");
            assertTrue("validation must not attach a partial schema", schema.getParameters().isEmpty());
        }
    }

    @Test(timeout = 2000)
    public void testStoredNumbersAlsoRefuseBeforeTheReplaceDryRunCanExpand()
    {
        DcsNumberExpansionProbe received = new DcsNumberExpansionProbe("1e100000000");
        NumberValue stored = McoreFactory.eINSTANCE.createNumberValue();
        stored.setValue(received);
        assertLimit(DcsSettingsWriter.untypedValueRefusal(stored), "1E+100000000");
        assertFalse(received.expansionAttempted);
    }

    private static void assertRefusedWithoutExpansion(JsonObject original)
    {
        DcsNumberExpansionProbe received = new DcsNumberExpansionProbe(original.get("value").getAsString());
        JsonObject instrumented = original.deepCopy();
        instrumented.add("value", new JsonPrimitive(received));
        ValueResult result = DcsSettingsWriter.buildValue(instrumented, "value");
        assertLimit(result.error, received.toString());
        assertFalse("setScale must not run for an oversized exponent", received.expansionAttempted);
    }

    private static void assertLimit(String error, String literal)
    {
        assertNotNull(literal + " must be refused before expansion", error);
        assertTrue(error, error.toLowerCase(java.util.Locale.ROOT).contains(literal.toLowerCase(java.util.Locale.ROOT)));
        assertTrue(error, error.contains("38"));
        assertTrue(error, error.contains("1C Number"));
        assertTrue(error, error.contains("integer digits"));
        assertTrue(error, error.contains("Pass"));
    }

    private static JsonObject number(String literal)
    {
        return json("{kind:'number',value:" + literal + "}");
    }

    private static JsonObject json(String text)
    {
        return JsonParser.parseString(text).getAsJsonObject();
    }

}

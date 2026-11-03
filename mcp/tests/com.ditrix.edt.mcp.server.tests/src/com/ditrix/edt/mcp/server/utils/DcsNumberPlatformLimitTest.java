/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.ValueResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** Every numeric writer and replace dry run must enforce the whole platform Number range. */
public class DcsNumberPlatformLimitTest
{
    @Test(timeout = 2000)
    public void testCompactHugeFractionIsRefusedBeforeDecimalExpansion()
    {
        assertRefused(new DcsNumberExpansionProbe(BigInteger.ONE, 100000000));
        // The probe must pass before exercising an ordinary request on any supported Gson version.
        assertLimit(DcsSettingsWriter.buildValue(number("1e-100000000"), "sample").error,
            "1e-100000000", "sample");
    }

    @Test(timeout = 2000)
    public void testMaximumIntScaleIsRefusedBeforeDecimalExpansion()
    {
        // A BigDecimal also covers scales which Gson's string parser cannot accept.
        assertRefused(new DcsNumberExpansionProbe(BigInteger.ONE, Integer.MAX_VALUE));
    }

    @Test(timeout = 2000)
    public void testStoredHugeFractionIsRefusedByTheReplaceDryRun()
    {
        DcsNumberExpansionProbe decimal = new DcsNumberExpansionProbe(BigInteger.ONE, 100000000);
        NumberValue stored = McoreFactory.eINSTANCE.createNumberValue();
        stored.setValue(decimal);
        assertLimit(DcsSettingsWriter.untypedValueRefusal(stored), decimal.toString(), "value");
        decimal.assertNotExpanded();
        assertSame(decimal, stored.getValue());
    }

    @Test(timeout = 2000)
    public void testEnclosingReplaceRefusesAStoredMaximumScaleWithoutChangingIt()
    {
        DcsNumberExpansionProbe decimal = new DcsNumberExpansionProbe(BigInteger.ONE, Integer.MAX_VALUE);
        NumberValue stored = McoreFactory.eINSTANCE.createNumberValue();
        stored.setValue(decimal);
        var schema = DcsStoredXmlTestSupport.schema();
        var holder = DcsFactory.eINSTANCE.createDataCompositionDataParameterValues();
        var item = DcsFactory.eINSTANCE.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("P"));
        item.getValues().add(stored);
        holder.getItems().add(item);
        schema.getDefaultSettings().setDataParameters(holder);
        String refusal = DcsMutationGuard.replaceError(schema,
            DcsAddress.parse("Report.Sales#/defaultSettings").address(),
            DcsReplaceTestCatalogues.schema());
        assertLimit(refusal, decimal.toString(), "value");
        assertTrue(refusal, refusal.contains("/defaultSettings/dataParameters/items/0"));
        assertTrue(refusal, refusal.contains("action='update'"));
        assertSame(stored, item.getValues().get(0));
        assertSame(decimal, stored.getValue());
        decimal.assertNotExpanded();
    }

    @Test
    public void testExactly38FractionalDigitsAreSupported()
    {
        for (String literal : List.of("1e-38", "-1e-38", "0.12345678901234567890123456789012345678"))
        {
            ValueResult result = DcsSettingsWriter.buildValue(number(literal), "sample");
            assertNull(literal + ": " + result.error, result.error);
            BigDecimal stored = ((NumberValue)result.value).getValue();
            assertEquals(new BigDecimal(literal), stored);
            assertEquals(38, stored.scale());
        }
    }

    @Test
    public void testThe39thFractionalDigitIsRefused()
    {
        for (String literal : List.of("1e-39", "-1e-39", "1e-9999"))
        {
            ValueResult result = DcsSettingsWriter.buildValue(number(literal), "sample");
            assertLimit(result.error, literal, "sample");
            assertNull("a refused number must not be constructed", result.value);
        }
    }

    @Test
    public void testExactly38SignificantDigitsAcrossTheDecimalPointAreSupported()
    {
        for (String literal : List.of("1234567890123456789.1234567890123456789",
            "-1234567890123456789.1234567890123456789"))
        {
            ValueResult result = DcsSettingsWriter.buildValue(number(literal), "sample");
            assertNull(result.error, result.error);
            assertEquals(new BigDecimal(literal), ((NumberValue)result.value).getValue());
        }
    }

    @Test
    public void test39SignificantDigitsAreRefusedEvenWhenIntegerDigitsAndScaleFit()
    {
        for (String literal : List.of("12345678901234567890.1234567890123456789",
            "-12345678901234567890.1234567890123456789"))
        {
            ValueResult result = DcsSettingsWriter.buildValue(number(literal), "sample");
            assertLimit(result.error, literal, "sample");
            assertNull(result.value);
        }
    }

    @Test
    public void testTrailingZerosDoNotCountAsSignificantDigitsOrChangeSupportedFractions()
    {
        BigDecimal decimal = new BigDecimal("12345678901234567890.1234567890123456780");
        assertEquals(39, decimal.precision());
        assertEquals(38, decimal.stripTrailingZeros().precision());
        ValueResult result = DcsSettingsWriter.buildValue(number(decimal), "sample");
        assertNull(result.error, result.error);
        assertSame("supported fractional spelling and scale must be preserved", decimal,
            ((NumberValue)result.value).getValue());
    }

    @Test(timeout = 2000)
    public void testZeroAtExtremeScalesNormalizesWithoutSetScaleOrPlainRendering()
    {
        for (int scale : new int[] {100000000, Integer.MAX_VALUE, -100000000, Integer.MIN_VALUE})
        {
            DcsNumberExpansionProbe zero = new DcsNumberExpansionProbe(BigInteger.ZERO, scale);
            ValueResult result = DcsSettingsWriter.buildValue(number(zero), "sample");
            assertNull(result.error, result.error);
            assertEquals(BigDecimal.ZERO, ((NumberValue)result.value).getValue());
            zero.assertNotExpanded();
        }
    }

    @Test(timeout = 2000)
    public void testIntegerDigitLimitStillRunsBeforeIntegralExpansion()
    {
        assertRefused(new DcsNumberExpansionProbe(BigInteger.ONE, -38));
        assertRefused(new DcsNumberExpansionProbe(BigInteger.ONE, Integer.MIN_VALUE));
        ValueResult boundary = DcsSettingsWriter.buildValue(number("1e37"), "sample");
        assertNull(boundary.error, boundary.error);
        assertEquals(new BigDecimal("10000000000000000000000000000000000000"),
            ((NumberValue)boundary.value).getValue());
    }

    @Test
    public void testTypedOutputAndAppearanceWritersShareTheFractionalLimit()
    {
        var declared = McoreFactory.eINSTANCE.createNumberValue();
        assertLimit(DcsSettingsWriter.buildTypedParameterValue(number("1e-39"), declared,
            null, "sample").error, "1e-39", "sample");
        var available = DcsReplaceTestCatalogues.available("Known", "KnownAlias", declared);
        var item = DcsFactory.eINSTANCE.createSettingsParameterValue();
        var previous = McoreFactory.eINSTANCE.createNumberValue();
        previous.setValue(BigDecimal.ONE);
        item.getValues().add(previous);
        String error = DcsSettingsWriter.applyOutputParameterItemForTest(item,
            json("{parameter:{kind:'parameter',value:'Known'},value:{kind:'number',value:1e-39}}"),
            null, available);
        assertLimit(error, "1e-39", "outputParameters.items[0].value");
        assertEquals(1, item.getValues().size());
        assertSame(previous, item.getValues().get(0));
        var appearance = DcsSettingsWriter.buildAppearanceForTest(
            json("{Known:{kind:'number',value:1e-39}}"), null, null, available);
        assertLimit(appearance.error, "1e-39", "Known");
    }

    @Test
    public void testDataParameterArraysAndNestedListsShareTheSignificantDigitLimit()
    {
        String literal = "12345678901234567890.1234567890123456789";
        var result = DcsStandardValueKindsTest.writeDataParameter(
            "{values:[{kind:'number',value:1},{kind:'number',value:" + literal + "}]}");
        assertLimit(result.error(), literal, "values[1]");
        ValueResult list = DcsSettingsWriter.buildValue(JsonParser.parseString(
            "{kind:'valueList',value:{items:[{value:{kind:'number',value:" + literal + "}}]}}"), "sample");
        assertLimit(list.error, literal, "items[0].value");
        assertNull(list.value);
    }

    @Test
    public void testSchemaDefaultsAvailableValuesAndInputsShareTheFractionalLimit()
    {
        for (String member : List.of("values:[{kind:'number',value:1e-39}]",
            "availableValues:[{value:{kind:'number',value:1e-39}}]",
            "inputParameters:{items:[{parameter:{kind:'parameter',value:'Input'},"
                + "values:[{kind:'number',value:1e-39}]}]}"))
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
            assertLimit(result.error, "1e-39", "parameters[0]");
            assertTrue("validation must not attach a partial schema", schema.getParameters().isEmpty());
        }
    }

    private static void assertRefused(DcsNumberExpansionProbe decimal)
    {
        ValueResult result = DcsSettingsWriter.buildValue(number(decimal), "sample");
        assertLimit(result.error, decimal.toString(), "sample");
        assertNull("a refused NumberValue must never be attached", result.value);
        decimal.assertNotExpanded();
    }

    private static void assertLimit(String error, String literal, String path)
    {
        assertNotNull(literal + " must be refused before decimal expansion", error);
        assertTrue(error, error.toLowerCase(java.util.Locale.ROOT)
            .contains(literal.toLowerCase(java.util.Locale.ROOT)));
        assertTrue(error, error.contains(path));
        assertTrue(error, error.contains("1C Number"));
        assertTrue(error, error.contains("38"));
        assertTrue(error, error.contains("Pass a finite JSON number"));
    }

    private static JsonObject number(String literal)
    {
        return json("{kind:'number',value:" + literal + "}");
    }

    private static JsonObject number(BigDecimal decimal)
    {
        JsonObject body = new JsonObject();
        body.addProperty("kind", "number");
        body.add("value", new JsonPrimitive(decimal));
        return body;
    }

    private static JsonObject json(String text)
    {
        return JsonParser.parseString(text).getAsJsonObject();
    }

}

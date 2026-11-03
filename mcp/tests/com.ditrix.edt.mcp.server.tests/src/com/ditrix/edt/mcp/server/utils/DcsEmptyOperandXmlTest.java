/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com.google.gson.JsonParser;

/** Empty XML operands are judged after EDT has read them, in both untyped containers. */
public class DcsEmptyOperandXmlTest
{
    @Test
    public void testAnEmptyStringOperandPassesAfterEdtsReader() throws Exception
    {
        probe("string", "\"sentinel\"", "{\"kind\":\"string\",\"value\":\"\"}", true);
    }

    @Test
    public void testAnEmptyNumberOperandPassesAfterEdtsReader() throws Exception
    {
        probe("number", "0", "{\"kind\":\"number\",\"value\":0}", false);
    }

    @Test
    public void testAnEmptyDateOperandPassesAfterEdtsReader() throws Exception
    {
        probe("date", "\"0001-01-01T00:00:00\"", "{\"kind\":\"date\",\"value\":\"0001-01-01T00:00:00\"}", false);
    }

    @Test
    public void testEmptyDateAndNumberTagsAreRejectedByEdtBeforeThereIsAStoredOperand() throws Exception
    {
        for (String kind : new String[] {"date", "number"})
        {
            for (boolean filter : new boolean[] {false, true})
            {
                String payload = kind.equals("date") ? "\"0001-01-01T00:00:00\"" : "0";
                var built = DcsSettingsWriter.buildValue(JsonParser.parseString(
                    "{\"kind\":\"" + kind + "\",\"value\":" + payload + "}"), "value");
                assertNull(built.error);
                String original = DcsStoredXmlTestSupport.serialize(DcsStoredXmlTestSupport.operand(filter, built.value));
                String tag = filter ? "dcsset:right" : "dcscor:value";
                String empty = original.replaceAll("(?s)(<" + tag + "\\b[^>]*>)[^<]*(</" + tag + ">)", "$1$2");
                assertNotEquals(original, empty);
                try
                {
                    DcsStoredXmlTestSupport.deserialize(empty);
                    fail("EDT accepted the empty " + kind + " tag");
                }
                catch (IllegalArgumentException expected)
                {
                    System.out.println("EMPTY_TAG " + (filter ? "filter" : "data") + " " + kind
                        + " rejected=" + expected.getClass().getSimpleName());
                }
            }
        }
    }

    @Test
    public void testFactoryFreshEmptyScalarsReloadWithoutAFalseRefusal() throws Exception
    {
        var factory = com._1c.g5.v8.dt.mcore.McoreFactory.eINSTANCE;
        for (boolean filter : new boolean[] {false, true})
        {
            for (var empty : new com._1c.g5.v8.dt.mcore.Value[] {factory.createStringValue(),
                factory.createNumberValue(), factory.createDateValue()})
            {
                var stored = DcsStoredXmlTestSupport.deserialize(DcsStoredXmlTestSupport.serialize(
                    DcsStoredXmlTestSupport.operand(filter, empty)));
                var operands = DcsStoredXmlTestSupport.operands(stored, filter);
                assertEquals(empty.eClass().getName(), empty instanceof DateValue ? 0 : 1, operands.size());
                assertNull(empty.eClass().getName(), DcsStoredXmlTestSupport.guard(stored));
            }
        }
    }

    private static void probe(String kind, String payload, String restatement, boolean erase) throws Exception
    {
        for (boolean filter : new boolean[] {false, true})
        {
            var built = DcsSettingsWriter.buildValue(JsonParser.parseString(
                "{\"kind\":\"" + kind + "\",\"value\":" + payload + "}"), "value");
            assertNull(built.error);
            String original = DcsStoredXmlTestSupport.serialize(DcsStoredXmlTestSupport.operand(filter, built.value));
            String tag = filter ? "dcsset:right" : "dcscor:value";
            String empty = erase ? original.replaceAll("(?s)(<" + tag + "\\b[^>]*>)[^<]*(</" + tag + ">)", "$1$2") : original;
            if (erase) assertNotEquals("the scalar payload was removed", original, empty);
            var read = DcsStoredXmlTestSupport.deserialize(empty);
            var values = DcsStoredXmlTestSupport.operands(read, filter);
            assertEquals("EDT must retain one " + kind + " operand", 1, values.size());
            var stored = values.get(0);
            Object actual = stored instanceof StringValue ? ((StringValue)stored).getValue()
                : stored instanceof NumberValue ? ((NumberValue)stored).getValue()
                : ((DateValue)stored).getValue();
            System.out.println("EMPTY_OPERAND " + (filter ? "filter" : "data") + " " + kind + " " + actual);
            assertNull(kind + ": " + actual, DcsStoredXmlTestSupport.guard(read));
            var rebuilt = DcsSettingsWriter.buildValue(JsonParser.parseString(restatement), "value");
            assertNull(restatement, rebuilt.error);
            assertTrue("the body rebuilds exactly " + kind, EcoreUtil.equals(stored, rebuilt.value));
            var reloaded = DcsStoredXmlTestSupport.deserialize(DcsStoredXmlTestSupport.serialize(read));
            assertNull(DcsStoredXmlTestSupport.guard(reloaded));
        }
    }
}

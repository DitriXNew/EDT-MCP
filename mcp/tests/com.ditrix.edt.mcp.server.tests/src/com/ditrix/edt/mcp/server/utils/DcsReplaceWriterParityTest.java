/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionField;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameter;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.DesignTimeValue;
import com._1c.g5.v8.dt.dcs.model.core.DesignTimeValueValue;
import com._1c.g5.v8.dt.dcs.model.core.InputParameters;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaParameter;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionDataParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOutputParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * #682 round 3: for every stored parameter value the replace guard's verdict is the writer's
 * verdict on the same value. Each case runs the writer that would have to restate the value and the
 * guard over that value, and requires them to agree - refusing with the writer's own words when the
 * writer refuses.
 */
public class DcsReplaceWriterParityTest
{
    private static final String ROOT = "Report.Sales";
    private static final McoreFactory VALUES = McoreFactory.eINSTANCE;
    private static final com._1c.g5.v8.dt.dcs.model.core.DcsFactory CORE =
        com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE;
    private static final com._1c.g5.v8.dt.dcs.model.settings.DcsFactory SETTINGS =
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;

    /** A typed color writes its RGB channels through the color builder, which keeps them 0-255. */
    @Test
    public void testAColorDefOutsideTheRgbRangeRefusesWithTheColorWritersWords()
    {
        ColorValue declared = VALUES.createColorValue();
        for (int[] channels : List.of(new int[] {300, 0, 0}, new int[] {0, -1, 0}, new int[] {0, 0, 256}))
        {
            ColorValue stored = color(channels);
            String writer = DcsSettingsWriter.buildTypedParameterValue(json("{\"color\":{\"red\":"
                + channels[0] + ",\"green\":" + channels[1] + ",\"blue\":" + channels[2] + "}}"),
                declared, null, "value").error;
            assertNotNull("the color writer refuses the channels", writer);
            String guard = DcsMutationGuard.replaceError(output("Known", stored), address(), typed("Known", declared));
            assertParity(writer, guard);
        }
        ColorValue inRange = color(new int[] {12, 34, 255});
        assertNull(DcsSettingsWriter.buildTypedParameterValue(json("{\"color\":{\"red\":12,\"green\":34,\"blue\":255}}"),
            declared, null, "value").error);
        assertNull(DcsMutationGuard.replaceError(output("Known", inRange), address(), typed("Known", declared)));
    }

    /** An untyped ValueSpec names its field, parameter or expression with a non-empty string. */
    @Test
    public void testUntypedPayloadsTheValueParserRejectsRefuseWithItsWords()
    {
        for (String text : new String[] {"", null})
        {
            String body = text == null ? "null" : "\"\"";
            assertUntypedParity(field(text), "{\"kind\":\"field\",\"value\":" + body + "}");
            assertUntypedParity(parameter(text), "{\"kind\":\"parameter\",\"value\":" + body + "}");
            assertUntypedParity(expression(text), "{\"kind\":\"expression\",\"value\":" + body + "}");
        }
        DesignTimeValueValue empty = CORE.createDesignTimeValueValue();
        assertUntypedParity(empty, "{\"kind\":\"expression\",\"value\":null}");
        assertUntypedParity(field("Amount"), "{\"kind\":\"field\",\"value\":\"Amount\"}");
        assertUntypedParity(parameter("Period"), "{\"kind\":\"parameter\",\"value\":\"Period\"}");
        assertUntypedParity(expression("&Period"), "{\"kind\":\"expression\",\"value\":\"&Period\"}");
    }

    /** The same payload rule holds inside a typed holder whose declared type is untyped. */
    @Test
    public void testAnEmptyFieldUnderAnUntypedDeclaredTypeRefusesLikeTheTypedWriter()
    {
        DataCompositionField declared = CORE.createDataCompositionField();
        String writer = DcsSettingsWriter.buildTypedParameterValue(json("{\"kind\":\"field\",\"value\":\"\"}"),
            declared, null, "value").error;
        assertNotNull(writer);
        assertParity(writer, DcsMutationGuard.replaceError(output("Known", field("")), address(),
            typed("Known", declared)));
        assertNull(DcsMutationGuard.replaceError(output("Known", field("Amount")), address(),
            typed("Known", declared)));
    }

    /** An input-parameter item is written from a non-empty parameter ValueSpec; there is no other form. */
    @Test
    public void testAnInputParameterWithoutAWritableNameRefusesLikeTheInputWriter()
    {
        DcsWriter.Result missing = DcsWriter.apply(newSchema(), json(inputParameters("")));
        assertTrue("the input writer needs a parameter", missing.hasError());
        assertTrue(missing.error, missing.error.contains("needs a parameter ValueSpec"));
        String guard = DcsMutationGuard.replaceError(schemaWithInput(null), address(), DcsReplaceTestCatalogues.schema());
        assertNotNull("an item without a parameter has no restatement", guard);
        assertTrue(guard, guard.contains("the item has no parameter; an input-parameter body needs a parameter ValueSpec"));

        for (String name : new String[] {"", null})
        {
            String writer = DcsSettingsWriter.buildValue(json("{\"kind\":\"parameter\",\"value\":"
                + (name == null ? "null" : "\"\"") + "}"), "value").error;
            assertNotNull(writer);
            assertParity(writer, DcsMutationGuard.replaceError(schemaWithInput(parameter(name)), address(),
                DcsReplaceTestCatalogues.schema()));
        }
        DcsWriter.Result named = DcsWriter.apply(newSchema(),
            json(inputParameters("\"parameter\":{\"kind\":\"parameter\",\"value\":\"P\"},")));
        assertFalse(named.error, named.hasError());
        assertNull(DcsMutationGuard.replaceError(schemaWithInput(parameter("P")), address(),
            DcsReplaceTestCatalogues.schema()));
    }

    /** A data parameter's name goes through the same ValueSpec, so an empty one has no restatement. */
    @Test
    public void testADataParameterWithAnEmptyNameRefusesLikeTheParameterWriter()
    {
        String writer = DcsSettingsWriter.buildValue(json("{\"kind\":\"parameter\",\"value\":\"\"}"),
            "value").error;
        assertNotNull(writer);
        DataCompositionSchema schema = schema();
        SettingsParameterValue item = dataItem(schema, number(1));
        item.setParameter(parameter(""));
        assertParity(writer, DcsMutationGuard.replaceError(schema, address(), DcsReplaceTestCatalogues.schema()));
        item.setParameter(null);
        assertNull("a data parameter body may omit 'parameter'",
            DcsMutationGuard.replaceError(schema, address(), DcsReplaceTestCatalogues.schema()));
    }

    /** Every appearance key writes exactly one typed value: no body restates a valueless item. */
    @Test
    public void testAValuelessAppearanceItemHasNoBodyAndRefuses()
    {
        DcsAvailableParameterCollection available = DcsReplaceTestCatalogues.available("Known", "Alias",
            VALUES.createBooleanValue());
        for (String body : new String[] {"{\"Known\":{\"use\":false}}", "{\"Known\":{\"use\":true}}",
            "{\"Known\":{}}", "{\"Known\":null}"})
        {
            DcsSettingsWriter.AppearanceResult written =
                DcsSettingsWriter.buildAppearanceForTest(json(body), null, null, available);
            if (written.error == null)
            {
                for (DataCompositionParameterValue item : written.value.getItems())
                {
                    assertEquals(body + " writes one value per key", 1, item.getValues().size());
                }
            }
        }
        DcsSettingsWriter.AppearanceResult valued = DcsSettingsWriter.buildAppearanceForTest(
            json("{\"Known\":{\"use\":false,\"value\":{\"kind\":\"boolean\",\"value\":true}}}"), null, null, available);
        assertNull(valued.error, valued.error);
        assertEquals(1, valued.value.getItems().get(0).getValues().size());

        DataCompositionAppearance holder = CORE.createDataCompositionAppearance();
        SettingsParameterValue item = SETTINGS.createSettingsParameterValue();
        item.setParameter(parameter("Known"));
        item.setUse(false);
        holder.getItems().add(item);
        DcsWriterCatalogues context = typed("Known", VALUES.createBooleanValue());
        String guard = DcsMutationGuard.replaceError(holder, address(), context);
        assertNotNull("a valueless appearance item cannot be restated", guard);
        assertTrue(guard, guard.contains("an appearance body has no valueless form"));

        // An output parameter's body may omit 'value', so the same valueless item restates there.
        DataCompositionOutputParameterValues outputs = SETTINGS.createDataCompositionOutputParameterValues();
        SettingsParameterValue valueless = SETTINGS.createSettingsParameterValue();
        valueless.setParameter(parameter("Known"));
        outputs.getItems().add(valueless);
        assertNull(DcsMutationGuard.replaceError(outputs, address(), context));
    }

    /** The rebuilt value must equal the stored one: a member no body can carry is not dropped. */
    @Test
    public void testAFormattedLocalizedTextRefusesBecauseNoBodyCarriesTheFlag()
    {
        LocalString declared = CORE.createLocalString();
        LocalString stored = CORE.createLocalString();
        stored.getContent().put("en", "Sales");
        assertNull(DcsMutationGuard.replaceError(output("Known", stored), address(), typed("Known", declared)));
        stored.setFormattedString(true);
        String guard = DcsMutationGuard.replaceError(output("Known", stored), address(), typed("Known", declared));
        assertNotNull("formattedString has no body member", guard);
        assertTrue(guard, guard.contains("the writer would rebuild it as a different LocalString"));
    }

    private static void assertUntypedParity(Value stored, String body)
    {
        String writer = DcsSettingsWriter.buildValue(json(body), "value").error;
        DataCompositionSchema schema = schema();
        dataItem(schema, stored);
        String guard = DcsMutationGuard.replaceError(schema, address(), DcsReplaceTestCatalogues.schema());
        assertParity(writer, guard);
    }

    /** The guard refuses exactly when the writer does, and then quotes the writer's own refusal. */
    private static void assertParity(String writer, String guard)
    {
        assertEquals("writer: " + writer + "; guard: " + guard, writer == null, guard == null);
        if (writer != null) assertTrue(guard, guard.contains(writer));
    }

    private static ColorValue color(int[] channels)
    {
        ColorDef definition = VALUES.createColorDef();
        definition.setRed(channels[0]);
        definition.setGreen(channels[1]);
        definition.setBlue(channels[2]);
        ColorValue value = VALUES.createColorValue();
        value.setValue(definition);
        return value;
    }

    private static DataCompositionField field(String text)
    {
        DataCompositionField value = CORE.createDataCompositionField();
        value.setValue(text);
        return value;
    }

    private static DataCompositionParameter parameter(String text)
    {
        DataCompositionParameter value = CORE.createDataCompositionParameter();
        value.setValue(text);
        return value;
    }

    private static DesignTimeValueValue expression(String text)
    {
        DesignTimeValue design = CORE.createDesignTimeValue();
        design.setValue(text);
        DesignTimeValueValue value = CORE.createDesignTimeValueValue();
        value.setValue(design);
        return value;
    }

    private static Value number(int value)
    {
        var number = VALUES.createNumberValue();
        number.setValue(java.math.BigDecimal.valueOf(value));
        return number;
    }

    private static DataCompositionOutputParameterValues output(String name, Value value)
    {
        DataCompositionOutputParameterValues holder = SETTINGS.createDataCompositionOutputParameterValues();
        SettingsParameterValue item = SETTINGS.createSettingsParameterValue();
        item.setParameter(parameter(name));
        item.getValues().add(value);
        holder.getItems().add(item);
        return holder;
    }

    private static DataCompositionSchema schema()
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        schema.setDefaultSettings(SETTINGS.createDataCompositionSettings());
        return schema;
    }

    private static DataCompositionSchema newSchema()
    {
        return com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchema();
    }

    private static SettingsParameterValue dataItem(DataCompositionSchema schema, Value value)
    {
        DataCompositionDataParameterValues holder = SETTINGS.createDataCompositionDataParameterValues();
        SettingsParameterValue item = SETTINGS.createSettingsParameterValue();
        item.setParameter(parameter("P"));
        item.getValues().add(value);
        holder.getItems().add(item);
        schema.getDefaultSettings().setDataParameters(holder);
        return item;
    }

    private static DataCompositionSchema schemaWithInput(DataCompositionParameter name)
    {
        DataCompositionSchema schema = newSchema();
        DataCompositionSchemaParameter parameter = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchemaParameter();
        parameter.setName("Owner");
        InputParameters holder = CORE.createInputParameters();
        DataCompositionParameterValue item = CORE.createDataCompositionParameterValue();
        item.setParameter(name);
        item.getValues().add(number(1));
        holder.getItems().add(item);
        parameter.setInputParameters(holder);
        schema.getParameters().add(parameter);
        return schema;
    }

    /** A data-set field whose single input-parameter item carries {@code parameterMember} verbatim. */
    private static String inputParameters(String parameterMember)
    {
        return "{\"dataSets\":[{\"name\":\"DS\",\"type\":\"query\",\"query\":\"SELECT Code\",\"fields\":[{"
            + "\"dataPath\":\"Code\",\"inputParameters\":{\"items\":[{" + parameterMember
            + "\"values\":[{\"kind\":\"number\",\"value\":1}]}]}}]}]}";
    }

    private static DcsWriterCatalogues typed(String name, Value declared)
    {
        return new DcsWriterCatalogues(Version.V8_3_27, AppearanceCatalogue.SCHEMA, "en",
            (kind, version, language) -> DcsReplaceTestCatalogues.available(name, "Alias", declared));
    }

    private static DcsAddress address()
    {
        DcsAddress.ParseResult parsed = DcsAddress.parse(ROOT);
        assertTrue(ROOT, parsed.isSuccess());
        return parsed.address();
    }

    private static JsonObject json(String text)
    {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.Enumerator;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionAppearance;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearance;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionConditionalAppearanceItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionDataParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionOutputParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameter;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.dcs.util.DcsV8Serializer;
import com._1c.g5.v8.dt.mcore.BooleanValue;
import com._1c.g5.v8.dt.mcore.ColorDef;
import com._1c.g5.v8.dt.mcore.ColorValue;
import com._1c.g5.v8.dt.mcore.DateValue;
import com._1c.g5.v8.dt.mcore.EnumValue;
import com._1c.g5.v8.dt.mcore.FontValue;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.NumberValue;
import com._1c.g5.v8.dt.mcore.StringValue;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.platform.version.Version;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.OutputParameterCatalogue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * #682 round 4: the replace guard demands that the writer rebuild a stored parameter value exactly.
 * These tests make sure that demand does not refuse a valid value: every value the writer builds,
 * saved and reloaded through EDT's own DCS XML serializer - which is how a stored value reaches
 * the guard - passes it, and so do EDT's empty localized text and automatic color. Only values no
 * body can carry still refuse.
 */
public class DcsReplaceStoredValueRoundTripTest
{
    private static final Version VERSION = Version.V8_3_27;
    private static final McoreFactory VALUES = McoreFactory.eINSTANCE;
    private static final com._1c.g5.v8.dt.dcs.model.settings.DcsFactory SETTINGS =
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;
    private static final String OUTPUT = "output";
    private static final String APPEARANCE = "appearance";
    /** Prodazhi, the Russian word for Sales. */
    private static final String RU_SALES = "\u041f\u0440\u043e\u0434\u0430\u0436\u0438";
    /** Platform defaults of kinds the writer has no body for; their refusal predates #682 round 3. */
    private static final Set<String> NO_BODY_KINDS = Set.of("LineValue", "ChartTrendlinesValue",
        "UsedDatabaseCopiesValue");

    /**
     * For every parameter of the settings output catalogue and the schema appearance catalogue,
     * each body the writer accepts for its declared type - every enum literal, every localized and
     * color form, numbers, booleans and strings - survives EDT's XML unchanged and passes the guard.
     */
    @Test
    public void testEveryTypedValueTheWriterBuildsSurvivesEdtsXmlAndPassesTheGuard() throws Exception
    {
        List<String> failures = new ArrayList<>();
        int probes = probeCatalogue(OUTPUT, DcsReplaceTestCatalogues.load(OutputParameterCatalogue.SETTINGS, VERSION,
            "en"), failures);
        probes += probeCatalogue(APPEARANCE, DcsReplaceTestCatalogues.load(AppearanceCatalogue.SCHEMA, VERSION, "en"),
            failures);
        assertTrue("the catalogues were probed: " + probes, probes > 200);
        assertTrue(failures.size() + " of " + probes + " probes:\n" + String.join("\n", failures), failures.isEmpty());
    }

    /**
     * The platform's own default of each catalogue parameter - what EDT stores when a box is ticked -
     * passes the guard after EDT's XML, unless the writer has no body for its kind at all. Title,
     * Format and Text default to the empty localized text.
     */
    @Test
    public void testThePlatformDefaultOfEveryCatalogueParameterPassesUnlessItsKindHasNoBody() throws Exception
    {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String where : new String[] {OUTPUT, APPEARANCE})
        {
            DcsAvailableParameterCollection catalogue = OUTPUT.equals(where)
                ? DcsReplaceTestCatalogues.load(OutputParameterCatalogue.SETTINGS, VERSION, "en")
                : DcsReplaceTestCatalogues.load(AppearanceCatalogue.SCHEMA, VERSION, "en");
            for (int i = 0; i < catalogue.itemsCount(); i++)
            {
                DcsAvailableParameter item = catalogue.getItemAt(i);
                Value declared = item.defValue();
                // A font default is a FontRef with no font, which EDT cannot even serialize.
                if (declared == null || declared instanceof FontValue
                    || NO_BODY_KINDS.contains(declared.eClass().getName()))
                {
                    continue;
                }
                checked++;
                String failure = roundTrip(where, item.key(0), EcoreUtil.copy(declared));
                if (failure != null) failures.add(item.key(0) + " " + failure);
            }
        }
        assertTrue("defaults checked: " + checked, checked > 30);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    /**
     * Untyped values keep exactly what EDT read: a number's scale (1.50, 10.000), a large precision,
     * the empty date, and an empty string. EDT's reader itself rejects a date with fractions or a
     * zone, so such a date is never stored.
     */
    @Test
    public void testUntypedValuesSurviveEdtsXmlAndPassTheGuard() throws Exception
    {
        String[] bodies = {"{\"kind\":\"string\",\"value\":\"\"}", "{\"kind\":\"string\",\"value\":\"abc\"}",
            "{\"kind\":\"number\",\"value\":1.50}", "{\"kind\":\"number\",\"value\":10.0}",
            "{\"kind\":\"number\",\"value\":-3.25}", "{\"kind\":\"number\",\"value\":0}",
            "{\"kind\":\"number\",\"value\":123456789012345678901234567890.123456789}",
            "{\"kind\":\"number\",\"value\":1E+3}", "{\"kind\":\"boolean\",\"value\":true}",
            "{\"kind\":\"boolean\",\"value\":false}", "{\"kind\":\"date\",\"value\":\"0001-01-01T00:00:00\"}",
            "{\"kind\":\"date\",\"value\":\"2026-01-31T23:59:59\"}", "{\"kind\":\"field\",\"value\":\"Amount\"}",
            "{\"kind\":\"parameter\",\"value\":\"Period\"}", "{\"kind\":\"expression\",\"value\":\"&Period\"}"};
        for (String body : bodies)
        {
            DcsSettingsWriter.ValueResult built = DcsSettingsWriter.buildValue(JsonParser.parseString(body), "value");
            assertNull(body, built.error);
            DataCompositionSchema read = deserialize(serialize(dataSchema(built.value)));
            if (body.contains("1E+3"))
            {
                // EDT writes the plain form, so 1E+3 comes back as 1000: the same number, a new scale.
                assertEquals(0, ((NumberValue)built.value).getValue().compareTo(
                    ((NumberValue)dataValue(read)).getValue()));
            }
            else
            {
                assertTrue(body, EcoreUtil.equals(built.value, dataValue(read)));
            }
            assertNull(body, guard(dataSchema(built.value)));
            assertNull(body, guard(read));
        }
        String[][] edits = {{">1.5<", ">1.50<"}, {">1.5<", ">1.500<"}, {">1.5<", ">+1.5<"}};
        for (String[] edit : edits)
        {
            String xml = serialize(dataSchema(number("1.5")));
            assertTrue(xml, xml.contains(edit[0]));
            DataCompositionSchema read = deserialize(xml.replace(edit[0], edit[1]));
            assertNull(edit[1], guard(read));
        }
        NumberValue scaled = (NumberValue)dataValue(deserialize(serialize(dataSchema(number("1.5")))
            .replace(">1.5<", ">1.50<")));
        assertEquals("the probe reached a stored 1.50", 2, scaled.getValue().scale());
        for (String date : new String[] {"2026-01-31T23:59:59.123", "2026-01-31T23:59:59+03:00", "2026-01-31T23:59:59Z"})
        {
            String xml = serialize(dataSchema(date("2026-01-31T23:59:59"))).replace("2026-01-31T23:59:59", date);
            try
            {
                deserialize(xml);
                fail("EDT's reader accepted " + date);
            }
            catch (IllegalArgumentException expected)
            {
                // A date is whole seconds without a zone, so this text never becomes a stored value.
            }
        }
    }

    /**
     * A String, Number or Date with no payload has no body, so the guard refuses it in memory; EDT's
     * XML never carries one: the empty string reads back as "", the number as 0, and the date is not
     * written at all.
     */
    @Test
    public void testNullPayloadsRefuseInMemoryAndNeverComeBackFromEdtsXml() throws Exception
    {
        Value[] empty = {VALUES.createStringValue(), VALUES.createNumberValue(), VALUES.createDateValue()};
        for (Value value : empty)
        {
            DataCompositionSchema schema = dataSchema(value);
            assertNotNull(value.eClass().getName(), guard(schema));
            DataCompositionSchema read = deserialize(serialize(schema));
            assertNull(value.eClass().getName(), guard(read));
        }
        assertEquals("", ((StringValue)dataValue(deserialize(serialize(dataSchema(VALUES.createStringValue())))))
            .getValue());
        assertEquals(0, ((NumberValue)dataValue(deserialize(serialize(dataSchema(VALUES.createNumberValue())))))
            .getValue().signum());
        assertNull(dataValue(deserialize(serialize(dataSchema(VALUES.createDateValue())))));
    }

    /**
     * The empty localized text EDT stores for a cleared Title is restated by the body {}: the writer
     * builds a value equal to it, EDT saves both as the same element, and the guard passes it.
     */
    @Test
    public void testAnEmptyLocalizedTextIsRestatedByAnEmptyObject() throws Exception
    {
        LocalString stored = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createLocalString();
        String xml = serialize(outputSchema("Title", stored));
        assertTrue(xml, xml.contains("<dcscor:value xsi:type=\"v8:LocalStringType\"/>"));
        DataCompositionSchema read = deserialize(xml);
        Value reread = outputValue(read);
        assertTrue(reread instanceof LocalString && ((LocalString)reread).getContent().isEmpty());
        assertNull(guard(read));

        SettingsParameterValue written = SETTINGS.createSettingsParameterValue();
        String error = DcsSettingsWriter.applyOutputParameterItemForTest(written,
            json("{\"parameter\":{\"kind\":\"parameter\",\"value\":\"Title\"},\"value\":{}}"), languages(),
            DcsReplaceTestCatalogues.load(OutputParameterCatalogue.SETTINGS, VERSION, "en"));
        assertNull(error);
        assertTrue(EcoreUtil.equals(reread, written.getValues().get(0)));
        DataCompositionSchema rewritten = schema();
        DataCompositionOutputParameterValues holder = SETTINGS.createDataCompositionOutputParameterValues();
        holder.getItems().add(written);
        rewritten.getDefaultSettings().setOutputParameters(holder);
        String rewrittenXml = serialize(rewritten);
        assertTrue(rewrittenXml, rewrittenXml.contains("<dcscor:value xsi:type=\"v8:LocalStringType\"/>"));

        // An empty plain string is a text in the call's language, not the empty localized text.
        DcsSettingsWriter.ValueResult plain = DcsSettingsWriter.buildTypedParameterValue(
            JsonParser.parseString("\"\""), stored, languages(), "value");
        assertNull(plain.error);
        assertFalse(EcoreUtil.equals(reread, plain.value));
        String missing = DcsSettingsWriter.buildTypedParameterValue(com.google.gson.JsonNull.INSTANCE, stored,
            languages(), "value").error;
        assertNotNull(missing);
        assertTrue(missing, missing.contains("or {} for an empty localized text"));
    }

    /**
     * EDT's automatic color is a color value with no color ("auto" in Template.dcs); the guard passes
     * it and {color:'auto'} rebuilds it, instead of an AutoColor that EDT would save as #000000.
     */
    @Test
    public void testEdtsAutomaticColorIsRestatedByAuto() throws Exception
    {
        String xml = serialize(appearanceSchema("TextColor", color(1, 2, 3)));
        assertTrue(xml, xml.contains(">#010203<"));
        DataCompositionSchema read = deserialize(xml.replace(">#010203<", ">auto<"));
        ColorValue stored = (ColorValue)appearanceValue(read);
        assertNull("EDT reads 'auto' as a color value with no color", stored.getValue());
        assertNull(guard(read));

        DcsSettingsWriter.ValueResult auto = DcsSettingsWriter.buildTypedParameterValue(
            JsonParser.parseString("{\"color\":\"auto\"}"), VALUES.createColorValue(), languages(), "value");
        assertNull(auto.error);
        assertTrue(EcoreUtil.equals(stored, auto.value));
        String written = serialize(appearanceSchema("TextColor", auto.value));
        assertTrue(written, written.contains(">auto<"));
        assertFalse(written, written.contains("#000000"));

        ColorValue legacy = VALUES.createColorValue();
        legacy.setValue(VALUES.createAutoColor());
        String refusal = guard(appearanceSchema("TextColor", legacy));
        assertNotNull("an AutoColor is saved as black, so 'auto' does not restate it", refusal);
        assertTrue(refusal, refusal.contains("the writer would rebuild it as a different ColorValue"));
    }

    /** formattedString survives EDT's XML and no body carries it, so the guard keeps refusing it. */
    @Test
    public void testAFormattedLocalizedTextStillRefusesAfterEdtsXml() throws Exception
    {
        LocalString stored = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createLocalString();
        stored.getContent().put("en", "Sales");
        stored.setFormattedString(true);
        String xml = serialize(outputSchema("Title", stored));
        assertTrue(xml, xml.toLowerCase().contains("formatted"));
        DataCompositionSchema read = deserialize(xml);
        LocalString reread = (LocalString)outputValue(read);
        assertTrue("EDT keeps the formatted flag", reread.isFormattedString());
        String refusal = guard(read);
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("the writer would rebuild it as a different LocalString"));
    }

    private int probeCatalogue(String where, DcsAvailableParameterCollection catalogue, List<String> failures)
        throws Exception
    {
        int probes = 0;
        for (int i = 0; i < catalogue.itemsCount(); i++)
        {
            DcsAvailableParameter item = catalogue.getItemAt(i);
            Value declared = item.defValue();
            if (declared == null) continue;
            for (String body : bodies(declared))
            {
                probes++;
                DcsSettingsWriter.ValueResult built = DcsSettingsWriter.buildTypedParameterValue(
                    JsonParser.parseString(body), declared, languages(), "value");
                if (built.error != null)
                {
                    failures.add(item.key(0) + " " + body + ": the writer refused it: " + built.error);
                    continue;
                }
                String failure = roundTrip(where, item.key(0), built.value);
                if (failure != null) failures.add(item.key(0) + " " + body + ": " + failure);
            }
        }
        return probes;
    }

    /** Saves the value through EDT's XML, reloads it, and reports a changed value or a refusal. */
    private static String roundTrip(String where, String name, Value value) throws Exception
    {
        DataCompositionSchema schema = OUTPUT.equals(where) ? outputSchema(name, value) : appearanceSchema(name, value);
        DataCompositionSchema read = deserialize(serialize(schema));
        Value reread = OUTPUT.equals(where) ? outputValue(read) : appearanceValue(read);
        if (!EcoreUtil.equals(value, reread)) return "EDT's XML changed the value";
        String refusal = guard(read);
        return refusal == null ? null : "refused: " + refusal;
    }

    private static List<String> bodies(Value declared)
    {
        List<String> result = new ArrayList<>();
        if (declared instanceof LocalString)
        {
            result.add("\"Sales\"");
            result.add("{\"ru\":\"" + RU_SALES + "\",\"en\":\"Sales\"}");
            result.add("{\"en\":\"Sales\",\"ru\":\"" + RU_SALES + "\"}");
            result.add("{\"ru\":\"\"}");
            result.add("{}");
        }
        else if (declared instanceof EnumValue)
        {
            Enumerator literal = ((EnumValue)declared).getValue();
            if (literal instanceof Enum<?>)
            {
                for (Object constant : ((Enum<?>)literal).getDeclaringClass().getEnumConstants())
                {
                    result.add("\"" + ((Enumerator)constant).getLiteral() + "\"");
                }
            }
        }
        else if (declared instanceof ColorValue)
        {
            result.add("{\"color\":\"auto\"}");
            result.add("{\"color\":{\"red\":0,\"green\":0,\"blue\":0}}");
            result.add("{\"color\":{\"red\":255,\"green\":128,\"blue\":1}}");
        }
        else if (declared instanceof FontValue)
        {
            result.add("{\"font\":{\"faceName\":\"Arial\",\"height\":10,\"bold\":true}}");
        }
        else if (declared instanceof BooleanValue)
        {
            result.add("{\"kind\":\"boolean\",\"value\":true}");
            result.add("{\"kind\":\"boolean\",\"value\":false}");
        }
        else if (declared instanceof NumberValue)
        {
            result.add("{\"kind\":\"number\",\"value\":0}");
            result.add("{\"kind\":\"number\",\"value\":1.50}");
            result.add("{\"kind\":\"number\",\"value\":-3}");
        }
        else if (declared instanceof StringValue)
        {
            result.add("{\"kind\":\"string\",\"value\":\"\"}");
            result.add("{\"kind\":\"string\",\"value\":\"abc\"}");
        }
        else if (declared instanceof DateValue)
        {
            result.add("{\"kind\":\"date\",\"value\":\"2026-01-31T23:59:59\"}");
        }
        return result;
    }

    private static NumberValue number(String text)
    {
        NumberValue value = VALUES.createNumberValue();
        value.setValue(new java.math.BigDecimal(text));
        return value;
    }

    private static DateValue date(String text)
    {
        DateValue value = VALUES.createDateValue();
        value.setValue(com._1c.g5.v8.dt.mcore.util.Date.fromString(text));
        return value;
    }

    private static ColorValue color(int red, int green, int blue)
    {
        ColorDef definition = VALUES.createColorDef();
        definition.setRed(red);
        definition.setGreen(green);
        definition.setBlue(blue);
        ColorValue value = VALUES.createColorValue();
        value.setValue(definition);
        return value;
    }

    private static SettingsParameterValue item(String name, Value value)
    {
        SettingsParameterValue item = SETTINGS.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter(name));
        item.getValues().add(value);
        return item;
    }

    private static DataCompositionSchema schema()
    {
        DataCompositionSchema schema = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE
            .createDataCompositionSchema();
        schema.setDefaultSettings(SETTINGS.createDataCompositionSettings());
        return schema;
    }

    private static DataCompositionSchema dataSchema(Value value)
    {
        DataCompositionSchema schema = schema();
        DataCompositionDataParameterValues holder = SETTINGS.createDataCompositionDataParameterValues();
        holder.getItems().add(item("P", value));
        schema.getDefaultSettings().setDataParameters(holder);
        return schema;
    }

    private static DataCompositionSchema outputSchema(String name, Value value)
    {
        DataCompositionSchema schema = schema();
        DataCompositionOutputParameterValues holder = SETTINGS.createDataCompositionOutputParameterValues();
        holder.getItems().add(item(name, value));
        schema.getDefaultSettings().setOutputParameters(holder);
        return schema;
    }

    private static DataCompositionSchema appearanceSchema(String name, Value value)
    {
        DataCompositionSchema schema = schema();
        DataCompositionConditionalAppearance appearance = SETTINGS.createDataCompositionConditionalAppearance();
        DataCompositionConditionalAppearanceItem rule = SETTINGS.createDataCompositionConditionalAppearanceItem();
        DataCompositionAppearance holder =
            com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionAppearance();
        holder.getItems().add(item(name, value));
        rule.setAppearance(holder);
        appearance.getItems().add(rule);
        schema.getDefaultSettings().setConditionalAppearance(appearance);
        return schema;
    }

    private static Value dataValue(DataCompositionSchema schema)
    {
        return first(schema.getDefaultSettings().getDataParameters().getItems().get(0).getValues());
    }

    private static Value outputValue(DataCompositionSchema schema)
    {
        return first(schema.getDefaultSettings().getOutputParameters().getItems().get(0).getValues());
    }

    private static Value appearanceValue(DataCompositionSchema schema)
    {
        DataCompositionSettings settings = schema.getDefaultSettings();
        return first(settings.getConditionalAppearance().getItems().get(0).getAppearance().getItems().get(0)
            .getValues());
    }

    private static Value first(List<Value> values)
    {
        return values.isEmpty() ? null : values.get(0);
    }

    private static DcsPresentationParser.LanguageContext languages()
    {
        return new DcsPresentationParser.LanguageContext(List.of("ru", "en"), null, "ru", false);
    }

    private static String guard(DataCompositionSchema schema)
    {
        DcsWriterCatalogues catalogues = new DcsWriterCatalogues(VERSION, AppearanceCatalogue.SCHEMA, "en",
            languages(), DcsReplaceTestCatalogues::load);
        return DcsMutationGuard.replaceError(schema, DcsAddress.parse("Report.Sales").address(), catalogues);
    }

    private static String serialize(DataCompositionSchema schema) throws Exception
    {
        DcsV8Serializer serializer = new DcsV8Serializer((IDtProject)null, VERSION, (IResourceLookup)null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        serializer.serializeXML(schema, output, serializer.createExportContext((IProject)null, VERSION),
            new HashMap<String, UUID>(), "\n");
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static DataCompositionSchema deserialize(String xml) throws Exception
    {
        DcsV8Serializer reader = new DcsV8Serializer((IDtProject)null, VERSION, (IResourceLookup)null);
        return reader.deserializeXML(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject json(String text)
    {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}

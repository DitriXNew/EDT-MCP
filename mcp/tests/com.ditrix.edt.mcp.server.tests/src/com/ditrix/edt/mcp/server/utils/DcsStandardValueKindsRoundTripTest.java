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
import java.util.UUID;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.dcs.model.core.DataCompositionParameterValue;
import com._1c.g5.v8.dt.dcs.model.core.InputParameters;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionDataParameterValues;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionFilterItem;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionSettings;
import com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue;
import com._1c.g5.v8.dt.dcs.util.DcsV8Serializer;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.StandardBeginningDateVariant;
import com._1c.g5.v8.dt.mcore.StandardPeriodVariant;
import com._1c.g5.v8.dt.mcore.TypedValueList;
import com._1c.g5.v8.dt.mcore.Value;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonParser;

/**
 * #682 round 5: each value the untyped writer now builds - every standard period and standard
 * beginning date variant, value lists, several values of one data parameter - is written, saved and
 * reloaded through EDT's own DcsV8Serializer, comes back equal, and passes the replace guard; the
 * members a body does not carry still refuse.
 */
public class DcsStandardValueKindsRoundTripTest
{
    private static final Version VERSION = Version.V8_3_27;
    private static final McoreFactory VALUES = McoreFactory.eINSTANCE;
    private static final com._1c.g5.v8.dt.dcs.model.settings.DcsFactory SETTINGS =
        com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE;

    @Test
    public void testEveryStandardPeriodVariantSurvivesEdtsXmlAndPassesTheGuard() throws Exception
    {
        List<String> failures = new ArrayList<>();
        for (StandardPeriodVariant variant : StandardPeriodVariant.values())
        {
            probe("{\"kind\":\"standardPeriod\",\"value\":{\"variant\":\"" + variant.getLiteral() + "\"}}",
                failures);
        }
        probe("{\"kind\":\"standardPeriod\",\"value\":{\"variant\":\"Custom\",\"startDate\":"
            + "\"2026-01-01T00:00:00\",\"endDate\":\"2026-01-31T23:59:59\"}}", failures);
        probe("{\"kind\":\"standardPeriod\",\"value\":{\"variant\":\"ThisMonth\",\"startDate\":"
            + "\"2026-01-01T00:00:00\"}}", failures);
        probe("{\"kind\":\"standardPeriod\",\"value\":{}}", failures);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void testEveryStandardBeginningDateVariantSurvivesEdtsXmlAndPassesTheGuard() throws Exception
    {
        List<String> failures = new ArrayList<>();
        for (StandardBeginningDateVariant variant : StandardBeginningDateVariant.values())
        {
            probe("{\"kind\":\"standardBeginningDate\",\"value\":{\"variant\":\"" + variant.getLiteral() + "\"}}",
                failures);
        }
        probe("{\"kind\":\"standardBeginningDate\",\"value\":{\"variant\":\"Custom\",\"date\":"
            + "\"2026-01-01T00:00:00\"}}", failures);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void testValueListsSurviveEdtsXmlAndPassTheGuard() throws Exception
    {
        List<String> failures = new ArrayList<>();
        probe("{\"kind\":\"valueList\",\"value\":{}}", failures);
        probe("{\"kind\":\"valueList\",\"value\":{\"items\":[{\"value\":{\"kind\":\"number\",\"value\":1}},"
            + "{\"value\":{\"kind\":\"string\",\"value\":\"b\"},\"presentation\":\"Bee\",\"checkState\":1},"
            + "{\"id\":9,\"value\":{\"kind\":\"date\",\"value\":\"2026-01-31T23:59:59\"}},"
            + "{\"value\":{\"kind\":\"boolean\",\"value\":true}}],\"lastId\":10}}", failures);
        probe("{\"kind\":\"valueList\",\"value\":{\"items\":[{\"value\":{\"kind\":\"standardPeriod\","
            + "\"value\":{\"variant\":\"LastMonth\"}}}]}}", failures);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    /** The designer's empty list as EDT itself serializes and reads it is the writer's empty body. */
    @Test
    public void testTheDesignersEmptyValueListIsRestatedByAnEmptyBody() throws Exception
    {
        TypedValueList designer = VALUES.createTypedValueList();
        designer.setValueType(VALUES.createTypeDescription());
        designer.setLastId(-1);
        DataCompositionSchema read = deserialize(serialize(filterSchema(designer)));
        Value stored = filterRight(read);
        assertTrue(stored instanceof TypedValueList);
        assertNull(guard(read));
        assertTrue(EcoreUtil.equals(stored, built("{\"kind\":\"valueList\",\"value\":{}}")));
    }

    @Test
    public void testValueListMembersNoBodyCarriesStillRefuse()
    {
        TypedValueList typed = (TypedValueList)built("{\"kind\":\"valueList\",\"value\":{}}");
        typed.getValueType().setStringQualifiers(VALUES.createStringQualifiers());
        assertRefusal(typed, "the value list has a value type");

        TypedValueList available = (TypedValueList)built("{\"kind\":\"valueList\",\"value\":{}}");
        available.setAvailableValues(VALUES.createTypedValueList());
        assertRefusal(available, "the value list has available values");

        TypedValueList formatted = (TypedValueList)built("{\"kind\":\"valueList\",\"value\":{\"items\":[{}]}}");
        formatted.getItems().get(0).setFormatPresentation("F");
        assertRefusal(formatted, "value-list item #0 has a format presentation");

        TypedValueList nested = (TypedValueList)built("{\"kind\":\"valueList\",\"value\":{\"items\":[{}]}}");
        nested.getItems().get(0).setValue(VALUES.createReferenceValue());
        assertRefusal(nested, "value-list item #0: ReferenceValue is not an untyped ValueSpec kind");

        var noPeriod = VALUES.createStandardPeriodValue();
        assertRefusal(noPeriod, "the StandardPeriodValue holds no period");
    }

    @Test
    public void testSeveralDataParameterValuesSurviveEdtsXmlAndPassTheGuard() throws Exception
    {
        DcsSettingsWriter.SettingsResult written = DcsStandardValueKindsTest.writeDataParameter(
            "{\"parameter\":{\"kind\":\"parameter\",\"value\":\"Period\"},\"values\":["
                + "{\"kind\":\"standardPeriod\",\"value\":{\"variant\":\"ThisYear\"}},"
                + "{\"kind\":\"string\",\"value\":\"a\"},{\"kind\":\"expression\",\"value\":\"&P\"}]}");
        assertTrue(written.error(), written.isSuccess());
        DataCompositionSchema schema = schema();
        schema.setDefaultSettings(written.settings());
        SettingsParameterValue item = (SettingsParameterValue)written.settings().getDataParameters().getItems().get(0);
        assertNull(guard(schema));
        DataCompositionSchema read = deserialize(serialize(schema));
        SettingsParameterValue reread = (SettingsParameterValue)read.getDefaultSettings().getDataParameters()
            .getItems().get(0);
        assertEquals(3, reread.getValues().size());
        for (int i = 0; i < 3; i++)
        {
            assertTrue("value #" + i, EcoreUtil.equals(item.getValues().get(i), reread.getValues().get(i)));
        }
        assertNull(guard(read));
    }

    @Test
    public void testAnInputParameterAcceptsTheNewKinds()
    {
        DataCompositionSchema schema = schema();
        var parameter = com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE.createDataCompositionSchemaParameter();
        parameter.setName("P");
        InputParameters holder = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createInputParameters();
        DataCompositionParameterValue item = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE
            .createDataCompositionParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("Owner"));
        item.getValues().add(built("{\"kind\":\"standardBeginningDate\",\"value\":{\"variant\":\"BeginningOfThisDay\"}}"));
        item.getValues().add(built("{\"kind\":\"valueList\",\"value\":{}}"));
        holder.getItems().add(item);
        parameter.setInputParameters(holder);
        schema.getParameters().add(parameter);
        assertNull(guard(schema));
    }

    /** Builds a body, puts it in a data parameter and a filter's right side, and round-trips both. */
    private static void probe(String body, List<String> failures) throws Exception
    {
        Value value = built(body);
        for (boolean filter : new boolean[] {false, true})
        {
            DataCompositionSchema schema = filter ? filterSchema(EcoreUtil.copy(value))
                : dataSchema(EcoreUtil.copy(value));
            String where = (filter ? "filter right " : "data parameter ") + body;
            String direct = guard(schema);
            if (direct != null)
            {
                failures.add(where + ": refused before EDT's XML: " + direct);
                continue;
            }
            DataCompositionSchema read = deserialize(serialize(schema));
            Value reread = filter ? filterRight(read) : dataValue(read);
            if (!EcoreUtil.equals(value, reread))
            {
                failures.add(where + ": EDT's XML changed the value");
                continue;
            }
            String refusal = guard(read);
            if (refusal != null) failures.add(where + ": refused after EDT's XML: " + refusal);
        }
    }

    private static void assertRefusal(Value value, String reason)
    {
        String refusal = guard(dataSchema(value));
        assertNotNull(reason, refusal);
        assertTrue(refusal, refusal.contains(reason));
        String right = guard(filterSchema(EcoreUtil.copy(value)));
        assertNotNull(reason, right);
        assertTrue(right, right.contains(reason));
    }

    private static Value built(String body)
    {
        DcsSettingsWriter.ValueResult result = DcsSettingsWriter.buildValue(JsonParser.parseString(body), "value");
        assertNull(body, result.error);
        return result.value;
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
        SettingsParameterValue item = SETTINGS.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter("P"));
        item.getValues().add(value);
        holder.getItems().add(item);
        schema.getDefaultSettings().setDataParameters(holder);
        return schema;
    }

    private static DataCompositionSchema filterSchema(Value value)
    {
        DataCompositionSchema schema = schema();
        DataCompositionGroup group = SETTINGS.createDataCompositionGroup();
        schema.getDefaultSettings().getItems().add(group);
        var filter = SETTINGS.createDataCompositionFilter();
        group.setFilter(filter);
        DataCompositionFilterItem item = SETTINGS.createDataCompositionFilterItem();
        var left = com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createDataCompositionField();
        left.setValue("Amount");
        item.setLeft(left);
        item.getRight().add(value);
        filter.getItems().add(item);
        return schema;
    }

    private static Value dataValue(DataCompositionSchema schema)
    {
        List<Value> values = schema.getDefaultSettings().getDataParameters().getItems().get(0).getValues();
        return values.isEmpty() ? null : values.get(0);
    }

    private static Value filterRight(DataCompositionSchema schema)
    {
        DataCompositionSettings settings = schema.getDefaultSettings();
        DataCompositionFilterItem item = (DataCompositionFilterItem)((DataCompositionGroup)settings.getItems().get(0))
            .getFilter().getItems().get(0);
        return item.getRight().isEmpty() ? null : item.getRight().get(0);
    }

    private static String guard(DataCompositionSchema schema)
    {
        return DcsMutationGuard.replaceError(schema, DcsAddress.parse("Report.Sales").address(),
            DcsReplaceTestCatalogues.schema(VERSION));
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
}

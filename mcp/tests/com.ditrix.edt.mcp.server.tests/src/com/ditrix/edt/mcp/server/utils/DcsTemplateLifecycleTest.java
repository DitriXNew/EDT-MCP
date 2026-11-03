/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.eclipse.emf.ecore.EEnum;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.core.V8Commands;
import com._1c.g5.v8.dt.core.naming.ITopObjectFqnGenerator;
import com._1c.g5.v8.dt.dcs.DcsDataSetInfoAvailableFieldInfo;
import com._1c.g5.v8.dt.dcs.DcsDataSetInfoAvailableParameterInfo;
import com._1c.g5.v8.dt.dcs.DcsDataSetInfoV8LocalQuery;
import com._1c.g5.v8.dt.dcs.model.core.LocalString;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.schema.DcsFactory;
import com._1c.g5.v8.dt.dcs.path.DcsPath;
import com._1c.g5.v8.dt.dcs.parameters.DcsAvailableParameterCollection;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.TemplateType;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.platform.version.Version;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Issue #664: persisted empty schemas, synchronous fill, and both typed parameter grammars. */
public class DcsTemplateLifecycleTest
{
    private static final DcsPresentationParser.LanguageContext LANGUAGES =
        new DcsPresentationParser.LanguageContext(Arrays.asList("en", "ru"), "en");

    @Test public void emptyRetypeAttachesExternalSchemaAndReturnsItsExportFqn()
    {
        var template = MdClassFactory.eINSTANCE.createTemplate();
        template.setName("Empty");
        template.setTemplateType(TemplateType.TEXT_DOCUMENT);
        var tx = mock(IBmTransaction.class);
        var names = mock(ITopObjectFqnGenerator.class);
        when(names.generateExternalPropertyFqn(any(), any())).thenReturn("Template.Empty.Content");
        String exported = TemplateContentSupport.retype(template, TemplateType.DATA_COMPOSITION_SCHEMA,
            tx, null, names, null);
        assertEquals("Template.Empty.Content", exported);
        assertTrue(template.getTemplate() instanceof DataCompositionSchema);
        assertTrue(((DataCompositionSchema)template.getTemplate()).getDataSets().isEmpty());
        verify(tx).attachTopObject(same((com._1c.g5.v8.bm.core.IBmObject)template.getTemplate()), eq(exported));
    }

    @Test public void emptyXmlUsesNativeSerializerWithoutMaterializingTheTemplate() throws Exception
    {
        var serializer = mock(com._1c.g5.v8.dt.dcs.util.DcsV8Serializer.class);
        doAnswer(call -> {
            DataCompositionSchema schema = call.getArgument(0);
            assertNotNull(schema);
            assertTrue(schema.getDataSets().isEmpty());
            ((java.io.OutputStream)call.getArgument(1)).write("<DataCompositionSchema/>".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
            return null;
        }).when(serializer).serializeXML(any(), any(java.io.OutputStream.class), anyString(),
            any(com._1c.g5.v8.dt.core.platform.IDtProject.class));
        // Null project is legal for the headless seam.
        doAnswer(call -> {
            assertTrue(((DataCompositionSchema)call.getArgument(0)).getParameters().isEmpty());
            ((java.io.OutputStream)call.getArgument(1)).write("<DataCompositionSchema/>".getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
            return null;
        }).when(serializer).serializeXML(any(), any(java.io.OutputStream.class), anyString(),
            isNull(com._1c.g5.v8.dt.core.platform.IDtProject.class));
        var result = new DcsXmlCodec(serializer, null).serialize(null);
        assertTrue(result.error(), result.isSuccess());
        assertEquals("DataCompositionSchema", javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder().parse(new java.io.ByteArrayInputStream(result.xml().getBytes(
                java.nio.charset.StandardCharsets.UTF_8))).getDocumentElement().getNodeName());
    }

    @Test public void nativeFieldFillWritesFieldsInTheInvokingCall() throws Exception
    {
        for (int alias = 0; alias < 2; alias++)
        {
            var dataSet = query("Q", true);
            var info = mock(DcsDataSetInfoV8LocalQuery.class);
            var field = new DcsDataSetInfoAvailableFieldInfo();
            field.dataPath[0] = new DcsPath("Amount");
            field.dataPath[1] = new DcsPath("Сумма");
            field.availableAsField = true;
            field.availableAsCondition = true;
            field.availableAsGroup = true;
            field.availableAsOrder = true;
            when(info.getAvailableUserFields(any())).thenReturn(List.of(field));
            DcsQueryAutoFill.fillFields(dataSet, info, alias == 0 ? "en" : "ru", alias,
                new V8Commands.NoTransactionEditingContext());
            assertEquals(1, dataSet.getFields().size());
            var stored = (com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField)
                dataSet.getFields().get(0);
            assertEquals(field.dataPath[alias].toString(), stored.getDataPath());
            assertTrue(DcsStoredXmlTestSupport.serialize(schema(dataSet)).contains(stored.getDataPath()));
        }
    }

    @Test public void parameterFillAddsQueryParametersAndPreservesAuthoredDefaults() throws Exception
    {
        var schema = schema();
        var source = new DcsDataSetInfoAvailableParameterInfo();
        source.name[0] = new DcsPath("Minimum");
        source.name[1] = new DcsPath("Минимум");
        source.defaultValue = McoreFactory.eINSTANCE.createStringValue();
        ((com._1c.g5.v8.dt.mcore.StringValue)source.defaultValue).setValue("Original");
        DcsQueryAutoFill.addParameters(schema, List.of(source), "en", 0);
        assertEquals(1, schema.getParameters().size());
        assertEquals("Minimum", schema.getParameters().get(0).getName());
        assertNotSame(source.defaultValue, schema.getParameters().get(0).getValues().get(0));
        schema.getParameters().get(0).setExpression("KeepMe");
        DcsQueryAutoFill.addParameters(schema, List.of(source), "en", 0);
        assertEquals(1, schema.getParameters().size());
        assertEquals("KeepMe", schema.getParameters().get(0).getExpression());
        assertTrue(DcsStoredXmlTestSupport.serialize(schema).contains("Minimum"));
    }

    @Test public void fillTargetsOnlySubmittedQueriesIncludingUnionMembers()
    {
        var yes = query("Yes", true);
        var no = query("No", false);
        var untouched = query("Untouched", true);
        var nested = query("Nested", true);
        var union = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetUnion();
        union.setName("Union");
        union.getItems().add(nested);
        var schema = schema(yes, no, untouched, union);
        assertEquals(List.of(yes, nested), DcsQueryAutoFill.targets(schema, address("Report.Probe"),
            "schema", json("""
                {"dataSets":[{"name":"Yes","query":"SELECT 1"},
                 {"name":"No","query":"SELECT 2"},
                 {"name":"Union","items":[{"name":"Nested","autoFillFields":true}]}]}
                """)));
        assertEquals(List.of(nested), DcsQueryAutoFill.targets(schema,
            address("Report.Probe#/dataSets/Union/items/Nested"), "dataSet",
            json("{\"autoFillFields\":true}")));
        assertTrue(DcsQueryAutoFill.targets(schema, address("Report.Probe"), "field",
            json("{\"dataPath\":\"Amount\"}")).isEmpty());
    }

    @Test public void localStringOutputAcceptsAllThreeFormsAndEmptyMap() throws Exception
    {
        var available = catalogue("Title", com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE.createLocalString());
        for (String value : List.of("\"Text\"", "{\"kind\":\"string\",\"value\":\"Text\"}",
            "{\"en\":\"Text\",\"ru\":\"Текст\"}", "{}"))
        {
            var item = com._1c.g5.v8.dt.dcs.model.settings.DcsFactory.eINSTANCE.createSettingsParameterValue();
            String error = DcsSettingsWriter.applyOutputParameterItemForTest(item,
                json("{\"parameter\":{\"kind\":\"parameter\",\"value\":\"Title\"},\"value\":"
                    + value + "}"), LANGUAGES, available);
            assertNull(error, error);
            assertTrue(item.getValues().get(0) instanceof LocalString);
            assertEquals(value.equals("{}") ? null : "Text", ((LocalString)item.getValues().get(0))
                .getContent().get("en"));
        }
    }

    @Test public void appearanceAcceptsBareAndTypedValuesThroughOneParser() throws Exception
    {
        for (boolean typed : List.of(false, true))
        {
            var available = catalogue("MarkNegatives", McoreFactory.eINSTANCE.createBooleanValue());
            String value = typed ? "{\"kind\":\"boolean\",\"value\":true}" : "true";
            var result = DcsSettingsWriter.buildAppearanceForTest(
                json("{\"MarkNegatives\":" + value + "}"), null, LANGUAGES, available);
            assertNull(result.error, result.error);
            assertTrue(((com._1c.g5.v8.dt.mcore.BooleanValue)result.value.getItems().get(0)
                .getValues().get(0)).isValue());
        }
    }

    @Test public void fieldOptionsExposeModelEnumsAndRestrictionFlags()
    {
        var result = DcsOptions.render("Report.Probe", DcsTargetResolver.TargetKind.REPORT_MAIN_DCS,
            null, address("Report.Probe"), "field", "en", Version.V8_3_27, 100, 0);
        assertTrue(result.error(), result.isSuccess());
        var role = com._1c.g5.v8.dt.dcs.model.common.DcsPackage.Literals.DATA_COMPOSITION_DATA_SET_FIELD_ROLE;
        var period = (EEnum)role.getEStructuralFeature("periodType").getEType();
        for (var literal : period.getELiterals()) assertTrue(result.markdown(),
            result.markdown().contains(literal.getLiteral()));
        assertTrue(result.markdown(), result.markdown().contains("role.periodType"));
        for (String prefix : List.of("useRestriction.", "attributeUseRestriction."))
            for (var feature : com._1c.g5.v8.dt.dcs.model.schema.DcsPackage.Literals
                .DATA_COMPOSITION_SCHEMA_FIELD_USE_RESTRICTION.getEAllAttributes())
                assertTrue(result.markdown(), result.markdown().contains(prefix + feature.getName()));
    }

    @Test public void fieldAndConditionalAppearanceAcceptBothFormsAndRefuseWrongTypes() throws Exception
    {
        try (var runtime = DcsCatalogueTestRuntime.prepareCatalogues(Version.V8_3_27))
        {
            for (boolean typed : List.of(false, true))
            {
                String appearance = typed ? """
                    {"MarkNegatives":{"kind":"boolean","value":true},
                     "Indent":{"kind":"number","value":7},
                     "Text":{"kind":"string","value":"Painted"},
                     "TextColor":{"kind":"color","value":{"red":12,"green":34,"blue":56}},
                     "Font":{"kind":"font","value":{"faceName":"Arial","height":11}}}
                    """ : """
                    {"MarkNegatives":true,"Indent":7,"Text":"Painted",
                     "TextColor":{"color":{"red":12,"green":34,"blue":56}},
                     "Font":{"font":{"faceName":"Arial","height":11}}}
                    """;
                var schema = schema(query("Q", false));
                var prepared = DcsSchemaWriter.prepare("upsert", "field",
                    address("Report.Probe#/dataSets/Q/fields"),
                    json("{\"dataPath\":\"Amount\",\"appearance\":" + appearance + "}"), LANGUAGES);
                assertTrue(prepared.error(), prepared.isSuccess());
                var applied = DcsSchemaWriter.apply(schema, prepared.request(), null, Version.V8_3_27, null);
                assertTrue(applied.error(), applied.isSuccess());
                var settings = DcsSettingsWriter.planSchema(schema, "upsert", "conditionalAppearance",
                    address("Report.Probe#/defaultSettings/conditionalAppearance"),
                    json("{\"items\":[{\"appearance\":" + appearance + "}]}"), LANGUAGES,
                    Version.V8_3_27, null);
                assertTrue(settings.error(), settings.isSuccess());
                settings.plan().commit(schema);
                var field = (com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetField)
                    schema.getDataSets().get(0).getFields().get(0);
                assertTrue(org.eclipse.emf.ecore.util.EcoreUtil.equals(field.getAppearance(),
                    schema.getDefaultSettings().getConditionalAppearance().getItems().get(0).getAppearance()));
                assertTrue(DcsStoredXmlTestSupport.serialize(schema).contains("Painted"));

                String hash = DcsHash.compute(schema);
                var invalid = DcsSettingsWriter.planSchema(schema, "upsert", "conditionalAppearance",
                    address("Report.Probe#/defaultSettings/conditionalAppearance"),
                    json("{\"items\":[{\"appearance\":{\"MarkNegatives\":\"bad\"}}]}"),
                    LANGUAGES, Version.V8_3_27, null);
                assertFalse(invalid.isSuccess());
                assertEquals(hash, DcsHash.compute(schema));
            }
        }
    }

    @Test public void fieldUpdateAndConditionalAppearanceBothSurviveNativeXmlSerialization() throws Exception
    {
        try (var runtime = DcsCatalogueTestRuntime.prepareCatalogues(Version.V8_3_27))
        {
            for (boolean typed : List.of(false, true))
            {
                var schema = schema(query("Q", false));
                var original = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetField();
                original.setDataPath("Amount");
                original.setField("Amount");
                schema.getDataSets().get(0).getFields().add(original);
                String marker = typed ? "TypedAppearance664" : "BareAppearance664";
                String appearance = typed ? "{\"Text\":{\"kind\":\"string\",\"value\":\"" + marker + "\"}}"
                    : "{\"Text\":\"" + marker + "\"}";
                var prepared = DcsSchemaWriter.prepare("update", "field",
                    address("Report.Probe#/dataSets/Q/fields/Amount"),
                    json("{\"appearance\":" + appearance + "}"), LANGUAGES);
                assertTrue(prepared.error(), prepared.isSuccess());
                var applied = DcsSchemaWriter.apply(schema, prepared.request(), null, Version.V8_3_27, null);
                assertTrue(applied.error(), applied.isSuccess());
                var settings = DcsSettingsWriter.planSchema(schema, "upsert", "conditionalAppearance",
                    address("Report.Probe#/defaultSettings/conditionalAppearance"),
                    json("{\"items\":[{\"appearance\":" + appearance + "}]}"), LANGUAGES,
                    Version.V8_3_27, null);
                assertTrue(settings.error(), settings.isSuccess());
                settings.plan().commit(schema);
                String xml = DcsStoredXmlTestSupport.serialize(schema);
                var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                    xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var owners = document.getElementsByTagNameNS("*", "appearance");
                int matched = 0;
                for (int i = 0; i < owners.getLength(); i++)
                    if (owners.item(i).getTextContent().contains(marker)) matched++;
                assertEquals(xml, 2, matched);
            }
        }
    }

    private static DcsAvailableParameterCollection catalogue(String name, com._1c.g5.v8.dt.mcore.Value value)
        throws Exception
    {
        var available = new DcsAvailableParameterCollection();
        available.addItem().init(new String[] {name, name}, null, null, name, value,
            false, null, true, false, null, Version.V8_3_27, null);
        return available;
    }

    private static DataCompositionSchema schema(com._1c.g5.v8.dt.dcs.model.schema.DataSet... sets)
    {
        var schema = DcsFactory.eINSTANCE.createDataCompositionSchema();
        schema.getDataSets().addAll(List.of(sets));
        return schema;
    }
    private static DataCompositionSchemaDataSetQuery query(String name, boolean fill)
    {
        var result = DcsFactory.eINSTANCE.createDataCompositionSchemaDataSetQuery();
        result.setName(name);
        result.setQuery("SELECT 1 AS Amount");
        result.setAutoFillAvailableFields(fill);
        return result;
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static DcsAddress address(String text) { return DcsAddress.parse(text).address(); }
}

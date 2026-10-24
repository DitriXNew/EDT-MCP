/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.core.ParameterValues;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com.ditrix.edt.mcp.server.utils.DcsSettingsWriter.AppearanceCatalogue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Collection keys must survive the same constructors that the value guard dry-runs. */
public class DcsKeyRepresentabilityGuardTest
{
    private static final String ROOT = "Report.Sales";
    private static final com._1c.g5.v8.dt.dcs.model.core.DcsFactory CORE =
        com._1c.g5.v8.dt.dcs.model.core.DcsFactory.eINSTANCE;
    private static final com._1c.g5.v8.dt.dcs.model.schema.DcsFactory SCHEMA =
        com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE;

    @Test
    public void testXmlAppearanceDuplicateKeysRefuseAtTheHolder() throws Exception
    {
        for (String second : List.of("Known", "Alias"))
        {
            var schema = DcsStoredXmlTestSupport.schema();
            var conditional = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearance();
            var item = DcsFactory.eINSTANCE.createDataCompositionConditionalAppearanceItem();
            var appearance = CORE.createDataCompositionAppearance();
            appearance.getItems().add(parameterItem("Known"));
            appearance.getItems().add(parameterItem(second));
            item.setAppearance(appearance);
            conditional.getItems().add(item);
            schema.getDefaultSettings().setConditionalAppearance(conditional);
            schema = DcsStoredXmlTestSupport.deserialize(DcsStoredXmlTestSupport.serialize(schema));
            assertEquals(2, schema.getDefaultSettings().getConditionalAppearance().getItems().get(0)
                .getAppearance().getItems().size());
            var snapshot = EcoreUtil.copy(schema);
            for (AppearanceCatalogue kind : AppearanceCatalogue.values())
            {
                String refusal = DcsMutationGuard.replaceError(schema, address(""), context(kind));
                assertNotNull("two resolved appearance keys have no JSON-object restatement", refusal);
                assertTrue(refusal, refusal.contains("duplicate resolved appearance parameter 'Known'"));
                assertTrue(refusal, refusal.contains("#/defaultSettings/conditionalAppearance/items/0/appearance"));
            }
            assertTrue(EcoreUtil.equals(snapshot, schema));
            var written = DcsSettingsWriter.buildAppearanceForTest(
                json("{Known:{kind:'string',value:'a'},Alias:{kind:'string',value:'b'}}"), null, null,
                DcsReplaceTestCatalogues.available("Known", "Alias", McoreFactory.eINSTANCE.createStringValue()));
            assertNull(written.error, written.error);
            assertEquals("the writer merges aliases of the same resolved key", 1, written.value.getItems().size());
        }
    }

    @Test
    public void testAdditionalPropertyEmptyNamesHaveNoBody()
    {
        for (String key : new String[] {null, ""})
        {
            var schema = properties(key, "Other");
            String refusal = guard(schema);
            assertNotNull("an empty additional-property key cannot be written", refusal);
            assertTrue(refusal, refusal.contains("empty 'name'"));
            var written = DcsSettingsWriter.planSettings(null, List.of(), "replace", "userSettings",
                json("{additionalProperties:{'':{kind:'string',value:'text'}}}"), null);
            assertFalse(written.isSuccess());
            assertTrue(written.error(), written.error().contains("must be non-empty strings"));
        }
    }

    @Test
    public void testAdditionalPropertyDuplicateNamesHaveNoBody() throws Exception
    {
        var schema = properties("Same", "Same");
        assertEquals(2, schema.getDefaultSettings().getAdditionalProperties().getProperty().size());
        String refusal = guard(schema);
        assertNotNull("duplicate property names cannot be carried by a JSON object", refusal);
        assertTrue(refusal, refusal.contains("duplicate 'name' key 'Same'"));
        assertTrue(refusal, refusal.contains("#/defaultSettings/additionalProperties"));
        assertNull("the property writer compares keys case-sensitively", guard(properties("Same", "same")));
    }

    @Test public void testDataSourceNaturalKeysMustBeRepresentable() { assertKeys("dataSources", "DataCompositionSchemaDataSource", "name"); }
    @Test public void testDataSetNaturalKeysMustBeRepresentable() { assertKeys("dataSets", "DataCompositionSchemaDataSetQuery", "name"); }
    @Test public void testParameterNaturalKeysMustBeRepresentable() { assertKeys("parameters", "DataCompositionSchemaParameter", "name"); }
    @Test public void testCalculatedFieldNaturalKeysMustBeRepresentable() { assertKeys("calculatedFields", "DataCompositionSchemaCalculatedField", "dataPath"); }
    @Test public void testTotalFieldNaturalKeysMustBeRepresentable() { assertKeys("totalFields", "DataCompositionSchemaTotalField", "dataPath"); }
    @Test public void testVariantNaturalKeysMustBeRepresentable() { assertKeys("settingsVariants", "SettingsVariant", "name"); }

    @Test
    public void testUnionMemberNaturalKeysMustBeRepresentable()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var union = SCHEMA.createDataCompositionSchemaDataSetUnion();
        union.setName("Union");
        schema.getDataSets().add(union);
        assertKeysAt(schema, union, "items", "DataCompositionSchemaDataSetQuery", "name",
            "#/dataSets/Union/items");
    }

    @Test
    public void testFlatAndFolderFieldKeysMustBeRepresentable()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var dataSet = SCHEMA.createDataCompositionSchemaDataSetQuery();
        dataSet.setName("Sales");
        schema.getDataSets().add(dataSet);
        assertKeysAt(schema, dataSet, "fields", "DataCompositionSchemaDataSetField", "dataPath",
            "#/dataSets/Sales/fields");
        assertKeysAt(schema, dataSet, "fields", "DataCompositionSchemaDataSetFieldFolder", "dataPath",
            "#/dataSets/Sales/fields");
    }

    @Test public void testDuplicateDataSourcesCannotBeRestated() { assertDuplicateKeys("dataSources", "DataCompositionSchemaDataSource", "name"); }
    @Test public void testDuplicateDataSetsCannotBeRestated() { assertDuplicateKeys("dataSets", "DataCompositionSchemaDataSetQuery", "name"); }
    @Test public void testDuplicateParametersCannotBeRestated() { assertDuplicateKeys("parameters", "DataCompositionSchemaParameter", "name"); }
    @Test public void testDuplicateCalculatedFieldsCannotBeRestated() { assertDuplicateKeys("calculatedFields", "DataCompositionSchemaCalculatedField", "dataPath"); }
    @Test public void testDuplicateTotalFieldsCannotBeRestated() { assertDuplicateKeys("totalFields", "DataCompositionSchemaTotalField", "dataPath"); }
    @Test public void testDuplicateSettingsVariantsCannotBeRestated() { assertDuplicateKeys("settingsVariants", "SettingsVariant", "name"); }

    @Test
    public void testDuplicateUnionMembersCannotBeRestated()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var union = SCHEMA.createDataCompositionSchemaDataSetUnion();
        union.setName("Union");
        schema.getDataSets().add(union);
        assertKeysAt(schema, union, "items", "DataCompositionSchemaDataSetQuery", "name",
            "#/dataSets/Union/items", new String[] {"Same"});
    }

    @Test
    public void testDuplicateFolderDescendantsAreGuardedAtTheirCanonicalFolderAddress()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var dataSet = SCHEMA.createDataCompositionSchemaDataSetQuery();
        dataSet.setName("Sales");
        schema.getDataSets().add(dataSet);
        var folder = SCHEMA.createDataCompositionSchemaDataSetFieldFolder();
        folder.setDataPath("Folder");
        dataSet.getFields().add(folder);
        for (int i = 0; i < 2; i++)
        {
            var field = SCHEMA.createDataCompositionSchemaDataSetField();
            field.setDataPath("Folder.Amount");
            dataSet.getFields().add(field);
        }
        String refusal = DcsMutationGuard.replaceError(schema,
            address("#/dataSets/Sales/fields/Folder"), context(AppearanceCatalogue.SCHEMA));
        assertNotNull("a folder replacement must guard its physically flat descendants", refusal);
        assertTrue(refusal, refusal.contains("duplicate 'dataPath' key 'Folder.Amount'"));
    }

    @Test
    public void testUnrepresentableSiblingKeysDoNotBlockANarrowReplacement()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        for (String name : List.of("Good", "Other", "Other"))
        {
            var parameter = SCHEMA.createDataCompositionSchemaParameter();
            parameter.setName(name);
            schema.getParameters().add(parameter);
        }
        assertNull(DcsMutationGuard.replaceError(schema, address("#/parameters/Good"), context(AppearanceCatalogue.SCHEMA)));
    }

    @Test
    public void testAKeyFailureDoesNotHideAnUnwritableDescendantInAnExactReplace()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        for (int i = 0; i < 2; i++)
        {
            var parameter = SCHEMA.createDataCompositionSchemaParameter();
            parameter.setName("P");
            var value = McoreFactory.eINSTANCE.createNumberValue();
            value.setValue(new java.math.BigDecimal("1e-39"));
            parameter.getValues().add(value);
            schema.getParameters().add(parameter);
        }
        assertNotNull(DcsMutationGuard.replaceError(schema, address("#/parameters/P"), context(AppearanceCatalogue.SCHEMA)));
    }

    @Test
    public void testWriterProducedBlankOutputParametersReloadAndPassTheGuard() throws Exception
    {
        var schema = DcsStoredXmlTestSupport.schema();
        try (var ignored = DcsCatalogueTestRuntime.prepareCatalogues(DcsStoredXmlTestSupport.VERSION))
        {
            var written = DcsSettingsWriter.planSettings(null, List.of(), "replace", "userSettings",
                json("{outputParameters:{items:[{}, {use:false}]}}"), null, DcsStoredXmlTestSupport.VERSION);
            assertTrue(written.error(), written.isSuccess());
            schema.setDefaultSettings(written.settings());
        }
        schema = DcsStoredXmlTestSupport.deserialize(DcsStoredXmlTestSupport.serialize(schema));
        assertEquals(2, schema.getDefaultSettings().getOutputParameters().getItems().size());
        for (var item : schema.getDefaultSettings().getOutputParameters().getItems())
        {
            assertNull(item.getParameter());
            assertTrue(item.getValues().isEmpty());
        }
        assertNull("the guard must accept a blank output item written and reloaded by EDT", guard(schema));
    }

    @Test
    public void testAllSixOutputHoldersAcceptTheValuelessFormButNeedAnIdentityForValues()
    {
        List<ParameterValues> holders = List.of(DcsFactory.eINSTANCE.createDataCompositionOutputParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionGroupOutputParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionTableOutputParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionTableGroupOutputParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionChartOutputParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionChartGroupOutputParameterValues());
        for (var holder : holders)
        {
            var blank = DcsFactory.eINSTANCE.createSettingsParameterValue();
            String writer = DcsSettingsWriter.applyOutputParameterItemForTest(blank, json("{}"), null,
                DcsReplaceTestCatalogues.available("Known", "Alias", McoreFactory.eINSTANCE.createStringValue()));
            assertNull(writer, writer);
            holder.getItems().add(blank);
            assertNull("blank " + holder.eClass().getName(), guard(holder));
            blank.getValues().add(McoreFactory.eINSTANCE.createStringValue());
            assertNotNull("a value still needs a typed parameter identity", guard(holder));
        }
    }

    @Test
    public void testParameterArraysKeepDuplicateNamesAndOptionalDataItems()
    {
        for (ParameterValues holder : List.of(CORE.createInputParameters(),
            DcsFactory.eINSTANCE.createDataCompositionDataParameterValues(),
            DcsFactory.eINSTANCE.createDataCompositionOutputParameterValues()))
        {
            holder.getItems().add(parameterItem("Known"));
            holder.getItems().add(parameterItem("Known"));
            assertNull("parameter arrays preserve duplicates: " + holder.eClass().getName(), guard(holder));
        }
        var schema = DcsStoredXmlTestSupport.schema();
        var written = DcsSettingsWriter.planSettings(null, List.of(), "replace", "userSettings",
            json("{dataParameters:{items:[{}, {values:[]}]},userFields:{items:[{kind:'expression'},{kind:'expression'}]}}"), null);
        assertTrue(written.error(), written.isSuccess());
        schema.setDefaultSettings(written.settings());
        assertNull("indexed user fields and optional data identities keep their writer-produced shapes", guard(schema));
    }

    private static void assertKeys(String collection, String modelClass, String key)
    {
        var schema = DcsStoredXmlTestSupport.schema();
        assertKeysAt(schema, schema, collection, modelClass, key,
            "#/" + (collection.equals("settingsVariants") ? "variants" : collection));
    }

    private static void assertDuplicateKeys(String collection, String modelClass, String key)
    {
        var schema = DcsStoredXmlTestSupport.schema();
        assertKeysAt(schema, schema, collection, modelClass, key,
            "#/" + (collection.equals("settingsVariants") ? "variants" : collection), new String[] {"Same"});
    }

    private static void assertKeysAt(DataCompositionSchema schema, EObject owner, String collection,
        String modelClass, String key, String pointer)
    {
        assertKeysAt(schema, owner, collection, modelClass, key, pointer, new String[] {null, "", "Same"});
    }

    @SuppressWarnings("unchecked")
    private static void assertKeysAt(DataCompositionSchema schema, EObject owner, String collection,
        String modelClass, String key, String pointer, String[] badKeys)
    {
        var items = (List<EObject>)owner.eGet(owner.eClass().getEStructuralFeature(collection));
        var factory = modelClass.equals("SettingsVariant") ? DcsFactory.eINSTANCE : SCHEMA;
        var type = (org.eclipse.emf.ecore.EClass)factory.getEPackage().getEClassifier(modelClass);
        for (String bad : badKeys)
        {
            items.clear();
            for (int i = 0; i < 2; i++)
            {
                var item = factory.create(type);
                item.eSet(type.getEStructuralFeature(key), i == 0 ? bad : "Same");
                items.add(item);
            }
            var snapshot = EcoreUtil.copy(schema);
            String refusal = guard(schema);
            assertNotNull(collection + " has an unrepresentable " + key + " key", refusal);
            assertTrue(refusal, refusal.contains(pointer));
            assertTrue(refusal, refusal.contains(bad == null || bad.isEmpty() ? "empty '" + key + "'" : "duplicate '" + key + "'"));
            assertTrue(EcoreUtil.equals(snapshot, schema));
        }
        items.clear();
        for (String valid : List.of("Same", "same"))
        {
            var item = factory.create(type);
            item.eSet(type.getEStructuralFeature(key), valid);
            items.add(item);
        }
        assertNull("natural keys use the writer's exact string equality", guard(schema));
        items.clear();
    }

    private static DataCompositionSchema properties(String first, String second)
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var structure = McoreFactory.eINSTANCE.createStructure();
        for (String name : new String[] {first, second})
        {
            var property = McoreFactory.eINSTANCE.createStructureProperty();
            property.setName(name);
            var value = McoreFactory.eINSTANCE.createStringValue();
            value.setValue("text");
            property.setValue(value);
            structure.getProperty().add(property);
        }
        schema.getDefaultSettings().setAdditionalProperties(structure);
        return schema;
    }

    private static com._1c.g5.v8.dt.dcs.model.settings.SettingsParameterValue parameterItem(String name)
    {
        var item = DcsFactory.eINSTANCE.createSettingsParameterValue();
        item.setParameter(DcsReplaceTestCatalogues.parameter(name));
        var value = McoreFactory.eINSTANCE.createStringValue();
        value.setValue("text");
        item.getValues().add(value);
        return item;
    }

    private static String guard(EObject object)
    {
        return DcsMutationGuard.replaceError(object, address(""), context(AppearanceCatalogue.SCHEMA));
    }

    private static DcsWriterCatalogues context(AppearanceCatalogue kind)
    {
        return new DcsWriterCatalogues(DcsStoredXmlTestSupport.VERSION, kind, "en",
            (requested, version, language) -> DcsReplaceTestCatalogues.available("Known", "Alias",
                McoreFactory.eINSTANCE.createStringValue()));
    }

    private static DcsAddress address(String pointer) { return DcsAddress.parse(ROOT + pointer).address(); }
    private static JsonObject json(String body) { return JsonParser.parseString(body).getAsJsonObject(); }
}

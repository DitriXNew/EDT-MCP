/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.math.BigDecimal;

import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchemaDataSetQuery;
import com._1c.g5.v8.dt.dcs.model.settings.DataCompositionGroup;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com.google.gson.JsonParser;

/** Indexed replacements must distinguish siblings that share their rendered natural key. */
public class DcsDuplicateIndexReplacementGuardTest
{
    private static final String ROOT = "Report.Sales";
    private static final com._1c.g5.v8.dt.dcs.model.schema.DcsFactory SCHEMA =
        com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE;

    @Test
    public void testIndexedParameterReplacementPreservesItsDuplicateSibling()
    {
        var schema = parameters();
        var sibling = EcoreUtil.copy(schema.getParameters().get(1));
        assertAllowed(schema, "#/parameters/0");
        assertApplied(schema, "parameter", "#/parameters/0", "{}");
        assertEquals(2, schema.getParameters().size());
        assertEquals("0", schema.getParameters().get(1).getName());
        assertTrue("the unchanged duplicate sibling survives the real writer",
            EcoreUtil.equals(sibling, schema.getParameters().get(0)));
    }

    @Test
    public void testParameterCollectionReplacementStillRefusesDuplicateKeys()
    {
        var schema = parameters();
        assertRefusedWithoutChange(schema, "parameter", "#/parameters", "{name:'New'}");
        assertDuplicateRefusal(schema, "");
    }

    @Test
    public void testIndexedDataSetScopeExcludesItsDuplicateSibling()
    {
        var schema = dataSets();
        assertAllowed(schema, "#/dataSets/0");
    }

    @Test
    public void testDataSetCollectionReplacementStillRefusesDuplicateKeys()
    {
        assertRefusedWithoutChange(dataSets(), "dataSet", "#/dataSets", "{name:'New',type:'query',query:'SELECT 1'}");
    }

    @Test
    public void testIndexedFieldReplacementPreservesItsDuplicateSibling()
    {
        var schema = fields(false);
        var sibling = EcoreUtil.copy(schema.getDataSets().get(0).getFields().get(1));
        assertAllowed(schema, "#/dataSets/Sales/fields/0");
        assertApplied(schema, "field", "#/dataSets/Sales/fields/0", "{}");
        var fields = schema.getDataSets().get(0).getFields();
        assertEquals(2, fields.size());
        assertTrue(EcoreUtil.equals(sibling, fields.get(0)));
    }

    @Test
    public void testFieldCollectionReplacementStillRefusesDuplicateKeys()
    {
        assertRefusedWithoutChange(fields(false), "field", "#/dataSets/Sales/fields", "{dataPath:'New'}");
    }

    @Test
    public void testIndexedFolderReplacementPreservesItsDuplicateSibling()
    {
        var schema = fields(true);
        var sibling = EcoreUtil.copy(schema.getDataSets().get(0).getFields().get(1));
        assertAllowed(schema, "#/dataSets/Sales/fields/0");
        assertApplied(schema, "fieldFolder", "#/dataSets/Sales/fields/0", "{}");
        var fields = schema.getDataSets().get(0).getFields();
        assertEquals(2, fields.size());
        assertTrue(EcoreUtil.equals(sibling, fields.get(0)));
    }

    @Test
    public void testFolderCollectionReplacementStillRefusesDuplicateKeys()
    {
        assertRefusedWithoutChange(fields(true), "fieldFolder", "#/dataSets/Sales/fields", "{dataPath:'New'}");
    }

    @Test
    public void testIndexedVariantSettingsPreserveTheDuplicateVariantsSettings()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        for (int i = 0; i < 2; i++)
        {
            var variant = DcsFactory.eINSTANCE.createSettingsVariant();
            variant.setName("Same");
            variant.setSettings(DcsStoredXmlTestSupport.operand(true, badNumber()).getDefaultSettings());
            schema.getSettingsVariants().add(variant);
        }
        var sibling = EcoreUtil.copy(schema.getSettingsVariants().get(1));
        // Only the untouched variant contains an unwritable value.
        ((DataCompositionGroup)schema.getSettingsVariants().get(0).getSettings().getItems().get(0))
            .getFilter().getItems().clear();
        assertAllowed(schema, "#/variants/0/settings");
        assertAllowed(schema, "#/variants/0/settings/items/0/filter");
        var plan = DcsSettingsWriter.planSchema(schema, "replace", "userSettings", address("#/variants/0/settings"),
            JsonParser.parseString("{}").getAsJsonObject(), null, DcsStoredXmlTestSupport.VERSION);
        assertTrue("the real planner accepts the indexed variant: " + plan.error(), plan.isSuccess());
        plan.plan().commit(schema);
        assertEquals(2, schema.getSettingsVariants().size());
        assertTrue(EcoreUtil.equals(sibling, schema.getSettingsVariants().get(1)));
        assertTrue(schema.getSettingsVariants().get(0).getSettings().getItems().isEmpty());
        assertDuplicateRefusal(schema, "#/variants");
        assertDuplicateRefusal(schema, "");
    }

    @Test
    public void testIndexedParentWithDuplicateKeyStillScopesANestedField()
    {
        var schema = dataSets();
        var first = SCHEMA.createDataCompositionSchemaDataSetField();
        first.setDataPath("Good");
        schema.getDataSets().get(0).getFields().add(first);
        var second = SCHEMA.createDataCompositionSchemaDataSetField();
        second.setDataPath("Good");
        var available = SCHEMA.createAvailableValue();
        available.setValue(badNumber());
        second.getAvailableValues().add(available);
        schema.getDataSets().get(1).getFields().add(second);
        assertAllowed(schema, "#/dataSets/0/fields/0");
        assertAllowed(schema, "#/dataSets/0/fields/Good");
    }

    @Test
    public void testUnwritableDuplicateSiblingIsPreservedByIndexedParameterRepair()
    {
        var schema = parameters();
        schema.getParameters().get(1).getValues().add(badNumber());
        var sibling = EcoreUtil.copy(schema.getParameters().get(1));
        assertAllowed(schema, "#/parameters/0");
        assertApplied(schema, "parameter", "#/parameters/0", "{}");
        assertTrue(EcoreUtil.equals(sibling, schema.getParameters().get(0)));
    }

    @Test
    public void testIndexedDuplicateParameterStillRefusesItsOwnUnwritableValue()
    {
        var schema = parameters();
        schema.getParameters().get(0).getValues().add(badNumber());
        assertRefusedWithoutChange(schema, "parameter", "#/parameters/0", "{}");
        assertTrue(guard(schema, "#/parameters/0").contains("#/parameters/Same/values/0"));
    }

    @Test
    public void testIndexedDuplicateFolderStillRefusesItsFlatDescendantValue()
    {
        var schema = fields(true);
        var field = SCHEMA.createDataCompositionSchemaDataSetField();
        field.setDataPath("Same.Amount");
        var available = SCHEMA.createAvailableValue();
        available.setValue(badNumber());
        field.getAvailableValues().add(available);
        schema.getDataSets().get(0).getFields().add(field);
        assertRefusedWithoutChange(schema, "fieldFolder", "#/dataSets/Sales/fields/0", "{}");
        assertTrue(guard(schema, "#/dataSets/Sales/fields/0").contains("Same.Amount/availableValues/0/value"));
    }

    private static DataCompositionSchema parameters()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        for (int i = 0; i < 2; i++)
        {
            var parameter = SCHEMA.createDataCompositionSchemaParameter();
            parameter.setName("Same");
            schema.getParameters().add(parameter);
        }
        return schema;
    }

    private static DataCompositionSchema dataSets()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        for (int i = 0; i < 2; i++)
        {
            var dataSet = SCHEMA.createDataCompositionSchemaDataSetQuery();
            dataSet.setName("Same");
            dataSet.setQuery("SELECT " + i);
            schema.getDataSets().add(dataSet);
        }
        return schema;
    }

    private static DataCompositionSchema fields(boolean folders)
    {
        var schema = DcsStoredXmlTestSupport.schema();
        DataCompositionSchemaDataSetQuery dataSet = SCHEMA.createDataCompositionSchemaDataSetQuery();
        dataSet.setName("Sales");
        dataSet.setQuery("SELECT 1");
        schema.getDataSets().add(dataSet);
        for (int i = 0; i < 2; i++)
        {
            var field = folders ? SCHEMA.createDataCompositionSchemaDataSetFieldFolder()
                : SCHEMA.createDataCompositionSchemaDataSetField();
            field.eSet(field.eClass().getEStructuralFeature("dataPath"), "Same");
            dataSet.getFields().add(field);
        }
        return schema;
    }

    private static void assertAllowed(DataCompositionSchema schema, String pointer)
    {
        var snapshot = EcoreUtil.copy(schema);
        String refusal = guard(schema, pointer);
        assertNull("an indexed target must exclude its unchanged duplicate sibling: " + refusal, refusal);
        assertTrue(EcoreUtil.equals(snapshot, schema));
    }

    private static void assertDuplicateRefusal(DataCompositionSchema schema, String pointer)
    {
        String refusal = guard(schema, pointer);
        assertNotNull("an enclosing replacement must still refuse duplicate keys", refusal);
        assertTrue(refusal, refusal.contains("duplicate '"));
    }

    private static void assertRefusedWithoutChange(DataCompositionSchema schema, String type, String pointer,
        String body)
    {
        var snapshot = EcoreUtil.copy(schema);
        assertNotNull(guard(schema, pointer));
        var result = apply(schema, type, pointer, body);
        assertFalse("replacement would discard unrepresentable content", result.isSuccess());
        assertTrue(result.error(), result.error().contains("this writer cannot model"));
        assertTrue(EcoreUtil.equals(snapshot, schema));
    }

    private static void assertApplied(DataCompositionSchema schema, String type, String pointer, String body)
    {
        var result = apply(schema, type, pointer, body);
        assertTrue("the actual schema writer must allow the repair: " + result.error(), result.isSuccess());
    }

    private static DcsSchemaWriter.Result apply(DataCompositionSchema schema, String type, String pointer,
        String body)
    {
        var prepared = DcsSchemaWriter.prepare("replace", type, address(pointer),
            JsonParser.parseString(body).getAsJsonObject(), null);
        assertTrue(prepared.error(), prepared.isSuccess());
        return DcsSchemaWriter.apply(schema, prepared.request(), null, DcsStoredXmlTestSupport.VERSION, null,
            DcsReplaceTestCatalogues.schema());
    }

    private static String guard(DataCompositionSchema schema, String pointer)
    {
        return DcsMutationGuard.replaceError(schema, address(pointer), DcsReplaceTestCatalogues.schema());
    }

    private static DcsAddress address(String pointer)
    {
        return DcsAddress.parse(ROOT + pointer).address();
    }

    private static com._1c.g5.v8.dt.mcore.NumberValue badNumber()
    {
        var value = McoreFactory.eINSTANCE.createNumberValue();
        value.setValue(new BigDecimal("1e-39"));
        return value;
    }
}

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
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com.google.gson.JsonParser;

/** Writer-supported index aliases must reach the same guard nodes as natural-key addresses. */
public class DcsIndexReplacementGuardTest
{
    private static final String ROOT = "Report.Sales";
    private static final com._1c.g5.v8.dt.dcs.model.schema.DcsFactory SCHEMA =
        com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE;

    @Test
    public void testParameterIndexReplaceCannotDropUnwritableDefaults()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var parameter = SCHEMA.createDataCompositionSchemaParameter();
        parameter.setName("Period");
        parameter.getValues().add(badNumber());
        schema.getParameters().add(parameter);
        assertSchemaRefusal(schema, "parameter", "#/parameters/0", "{}", "#/parameters/Period/values/0");
    }

    @Test
    public void testDataSetIndexReplaceCannotDropAnUnwritableFieldValue()
    {
        var schema = fieldSchema(false);
        assertSchemaRefusal(schema, "dataSet", "#/dataSets/0", "{type:'query',query:'SELECT 1'}",
            "#/dataSets/Sales/fields/Amount/availableValues/0/value");
    }

    @Test
    public void testFieldIndexBelowAnIndexedDataSetCannotDropItsAvailableValue()
    {
        var schema = fieldSchema(false);
        assertSchemaRefusal(schema, "field", "#/dataSets/0/fields/0", "{}",
            "#/dataSets/Sales/fields/Amount/availableValues/0/value");
    }

    @Test
    public void testFolderIndexReplaceCannotDropItsFlatDescendantValues()
    {
        var schema = fieldSchema(true);
        assertSchemaRefusal(schema, "fieldFolder", "#/dataSets/0/fields/0", "{}",
            "#/dataSets/Sales/fields/Folder/fields/Folder.Amount/availableValues/0/value");
    }

    @Test
    public void testVariantSettingsIndexReplaceCannotDropItsUnwritableValue()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        var variant = DcsFactory.eINSTANCE.createSettingsVariant();
        variant.setName("Main");
        var settings = DcsFactory.eINSTANCE.createDataCompositionSettings();
        var filter = DcsFactory.eINSTANCE.createDataCompositionFilter();
        var item = DcsFactory.eINSTANCE.createDataCompositionFilterItem();
        item.getRight().add(badNumber());
        filter.getItems().add(item);
        settings.setFilter(filter);
        variant.setSettings(settings);
        schema.getSettingsVariants().add(variant);
        var target = DcsAddress.parse(ROOT + "#/variants/0/settings").address();
        var plan = DcsSettingsWriter.planSchema(schema, "replace", "userSettings", target,
            JsonParser.parseString("{}").getAsJsonObject(), null, DcsStoredXmlTestSupport.VERSION);
        assertTrue("the real planner accepts this index alias: " + plan.error(), plan.isSuccess());
        var snapshot = EcoreUtil.copy(schema);
        String refusal = DcsMutationGuard.replaceError(schema, target, DcsReplaceTestCatalogues.schema());
        assertNotNull("the index alias must reach Main's actual settings", refusal);
        assertTrue(refusal, refusal.contains("#/variants/Main/settings/filter/items/0/right/0"));
        assertTrue(EcoreUtil.equals(snapshot, schema));
    }

    @Test
    public void testIndexedTargetsKeepCanonicalSiblingsOutsideTheirScope()
    {
        var schema = DcsStoredXmlTestSupport.schema();
        for (String name : new String[] {"Good", "Bad"})
        {
            var parameter = SCHEMA.createDataCompositionSchemaParameter();
            parameter.setName(name);
            if (name.equals("Bad")) parameter.getValues().add(badNumber());
            schema.getParameters().add(parameter);
        }
        assertNull(DcsMutationGuard.replaceError(schema, DcsAddress.parse(ROOT + "#/parameters/0").address(),
            DcsReplaceTestCatalogues.schema()));
        String refusal = DcsMutationGuard.replaceError(schema, DcsAddress.parse(ROOT + "#/parameters/1").address(),
            DcsReplaceTestCatalogues.schema());
        assertNotNull("only the selected bad parameter blocks replacement", refusal);
        assertTrue(refusal, refusal.contains("#/parameters/Bad/values/0"));
    }

    private static void assertSchemaRefusal(DataCompositionSchema schema, String type, String pointer,
        String body, String canonicalValue)
    {
        var snapshot = EcoreUtil.copy(schema);
        var prepared = DcsSchemaWriter.prepare("replace", type, DcsAddress.parse(ROOT + pointer).address(),
            JsonParser.parseString(body).getAsJsonObject(), null);
        assertTrue(prepared.error(), prepared.isSuccess());
        var result = DcsSchemaWriter.apply(schema, prepared.request(), null, DcsStoredXmlTestSupport.VERSION, null,
            DcsReplaceTestCatalogues.schema());
        assertFalse("an accepted index alias must not discard an unreproducible value", result.isSuccess());
        assertTrue(result.error(), result.error().contains("this writer cannot model"));
        assertTrue(result.error(), result.error().contains(canonicalValue));
        assertTrue(EcoreUtil.equals(snapshot, schema));
    }

    private static DataCompositionSchema fieldSchema(boolean folder)
    {
        var schema = DcsStoredXmlTestSupport.schema();
        DataCompositionSchemaDataSetQuery dataSet = SCHEMA.createDataCompositionSchemaDataSetQuery();
        dataSet.setName("Sales");
        dataSet.setQuery("SELECT 1");
        schema.getDataSets().add(dataSet);
        if (folder)
        {
            var parent = SCHEMA.createDataCompositionSchemaDataSetFieldFolder();
            parent.setDataPath("Folder");
            dataSet.getFields().add(parent);
        }
        var field = SCHEMA.createDataCompositionSchemaDataSetField();
        field.setDataPath(folder ? "Folder.Amount" : "Amount");
        var available = SCHEMA.createAvailableValue();
        available.setValue(badNumber());
        field.getAvailableValues().add(available);
        dataSet.getFields().add(field);
        return schema;
    }

    private static com._1c.g5.v8.dt.mcore.NumberValue badNumber()
    {
        var value = McoreFactory.eINSTANCE.createNumberValue();
        value.setValue(new BigDecimal("1e-39"));
        return value;
    }
}

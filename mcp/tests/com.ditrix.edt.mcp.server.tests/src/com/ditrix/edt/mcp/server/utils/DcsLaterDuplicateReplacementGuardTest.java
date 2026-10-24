/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.dcs.model.schema.DataCompositionSchema;
import com._1c.g5.v8.dt.dcs.model.settings.DcsFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com.google.gson.JsonParser;

/** Numeric targets must work in every position among duplicate natural keys. */
public class DcsLaterDuplicateReplacementGuardTest
{
    private static final String ROOT = "Report.Sales";
    private static final com._1c.g5.v8.dt.dcs.model.schema.DcsFactory SCHEMA =
        com._1c.g5.v8.dt.dcs.model.schema.DcsFactory.eINSTANCE;

    @Test public void testSecondParameterReplacement() { assertReplacement("parameter", 1); }
    @Test public void testThirdParameterReplacement() { assertReplacement("parameter", 2); }
    @Test public void testSecondDataSetReplacement() { assertReplacement("dataSet", 1); }
    @Test public void testThirdDataSetReplacement() { assertReplacement("dataSet", 2); }
    @Test public void testSecondFieldReplacement() { assertReplacement("field", 1); }
    @Test public void testThirdFieldReplacement() { assertReplacement("field", 2); }
    @Test public void testSecondFolderReplacement() { assertReplacement("fieldFolder", 1); }
    @Test public void testThirdFolderReplacement() { assertReplacement("fieldFolder", 2); }
    @Test public void testSecondVariantSettingsReplacement() { assertReplacement("variant", 1); }
    @Test public void testThirdVariantSettingsReplacement() { assertReplacement("variant", 2); }

    @Test
    public void testCollectionsAndEnclosingRootsStillRefuseDuplicates()
    {
        for (String kind : List.of("parameter", "dataSet", "field", "fieldFolder", "variant"))
        {
            var schema = duplicates(kind, 3);
            var snapshot = EcoreUtil.copy(schema);
            for (String pointer : List.of(collection(kind), ""))
            {
                String refusal = guard(schema, pointer);
                assertNotNull(kind + pointer, refusal);
                assertTrue(refusal, refusal.contains("duplicate '"));
            }
            assertTrue(EcoreUtil.equals(snapshot, schema));
        }
    }

    @Test
    public void testLaterSelectedParameterStillRefusesItsOwnBadValue()
    {
        var schema = duplicates("parameter", 3);
        var bad = McoreFactory.eINSTANCE.createNumberValue();
        bad.setValue(new BigDecimal("1e-39"));
        schema.getParameters().get(2).getValues().add(bad);
        var snapshot = EcoreUtil.copy(schema);
        String refusal = guard(schema, "#/parameters/2");
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("#/parameters/Same/values/0"));
        assertTrue(EcoreUtil.equals(snapshot, schema));
    }

    @Test
    public void testLaterDuplicateAncestorScopesNestedFields()
    {
        var schema = duplicates("dataSet", 3);
        for (int i = 0; i < 3; i++)
        {
            var field = SCHEMA.createDataCompositionSchemaDataSetField();
            field.setDataPath("Amount");
            if (i != 2)
            {
                var available = SCHEMA.createAvailableValue();
                var bad = McoreFactory.eINSTANCE.createNumberValue();
                bad.setValue(new BigDecimal("1e-39"));
                available.setValue(bad);
                field.getAvailableValues().add(available);
            }
            schema.getDataSets().get(i).getFields().add(field);
        }
        assertNull(guard(schema, "#/dataSets/2/fields/0"));
        assertNull(guard(schema, "#/dataSets/2/fields/Amount"));
    }

    private static void assertReplacement(String kind, int selected)
    {
        var schema = duplicates(kind, selected + 1);
        var siblings = new ArrayList<EObject>();
        for (int i = 0; i < selected; i++) siblings.add(EcoreUtil.copy(entries(schema, kind).get(i)));
        var snapshot = EcoreUtil.copy(schema);
        String pointer = collection(kind) + "/" + selected + (kind.equals("variant") ? "/settings" : "");
        String refusal = guard(schema, pointer);
        assertNull(kind + " index " + selected + ": " + refusal, refusal);
        assertTrue("the guard is read-only", EcoreUtil.equals(snapshot, schema));
        // Data-set cases exercise guard scope. Third-entry cases also isolate that scope from
        // the schema writer's separate validation of the remaining duplicate keys.
        if (kind.equals("dataSet") || selected == 2 && !kind.equals("variant")) return;
        if (kind.equals("variant"))
        {
            var plan = DcsSettingsWriter.planSchema(schema, "replace", "userSettings", address(pointer),
                JsonParser.parseString("{}").getAsJsonObject(), null, DcsStoredXmlTestSupport.VERSION);
            assertTrue(plan.error(), plan.isSuccess());
            plan.plan().commit(schema);
        }
        else
        {
            String body = "{}";
            var prepared = DcsSchemaWriter.prepare("replace", kind, address(pointer),
                JsonParser.parseString(body).getAsJsonObject(), null);
            assertTrue(prepared.error(), prepared.isSuccess());
            var written = DcsSchemaWriter.apply(schema, prepared.request(), null,
                DcsStoredXmlTestSupport.VERSION, null, DcsReplaceTestCatalogues.schema());
            assertTrue(written.error(), written.isSuccess());
        }
        var remaining = entries(schema, kind);
        assertEquals(selected + 1, remaining.size());
        for (int i = 0; i < selected; i++)
            assertTrue(kind + " sibling " + i + " survives", EcoreUtil.equals(siblings.get(i), remaining.get(i)));
    }

    private static DataCompositionSchema duplicates(String kind, int count)
    {
        var schema = DcsStoredXmlTestSupport.schema();
        if (kind.equals("field") || kind.equals("fieldFolder"))
        {
            var dataSet = SCHEMA.createDataCompositionSchemaDataSetQuery();
            dataSet.setName("Sales");
            dataSet.setQuery("SELECT 1");
            schema.getDataSets().add(dataSet);
        }
        for (int i = 0; i < count; i++)
        {
            switch (kind)
            {
                case "parameter":
                    var parameter = SCHEMA.createDataCompositionSchemaParameter();
                    parameter.setName("Same");
                    schema.getParameters().add(parameter);
                    break;
                case "dataSet":
                    var dataSet = SCHEMA.createDataCompositionSchemaDataSetQuery();
                    dataSet.setName("Same");
                    dataSet.setQuery("SELECT " + i);
                    schema.getDataSets().add(dataSet);
                    break;
                case "field":
                case "fieldFolder":
                    var field = kind.equals("field") ? SCHEMA.createDataCompositionSchemaDataSetField()
                        : SCHEMA.createDataCompositionSchemaDataSetFieldFolder();
                    field.eSet(field.eClass().getEStructuralFeature("dataPath"), "Same");
                    schema.getDataSets().get(0).getFields().add(field);
                    break;
                case "variant":
                    var variant = DcsFactory.eINSTANCE.createSettingsVariant();
                    variant.setName("Same");
                    variant.setSettings(DcsFactory.eINSTANCE.createDataCompositionSettings());
                    schema.getSettingsVariants().add(variant);
                    break;
                default: throw new AssertionError(kind);
            }
        }
        return schema;
    }

    private static List<? extends EObject> entries(DataCompositionSchema schema, String kind)
    {
        switch (kind)
        {
            case "parameter": return schema.getParameters();
            case "dataSet": return schema.getDataSets();
            case "variant": return schema.getSettingsVariants();
            default: return schema.getDataSets().get(0).getFields();
        }
    }

    private static String collection(String kind)
    {
        switch (kind)
        {
            case "parameter": return "#/parameters";
            case "dataSet": return "#/dataSets";
            case "variant": return "#/variants";
            default: return "#/dataSets/Sales/fields";
        }
    }

    private static String guard(DataCompositionSchema schema, String pointer)
    {
        return DcsMutationGuard.replaceError(schema, address(pointer), DcsReplaceTestCatalogues.schema());
    }

    private static DcsAddress address(String pointer) { return DcsAddress.parse(ROOT + pointer).address(); }
}

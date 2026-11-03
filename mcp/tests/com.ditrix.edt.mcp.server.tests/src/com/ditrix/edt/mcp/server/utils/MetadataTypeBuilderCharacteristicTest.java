/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Characteristic values share the chart's produced TypeSet, not its Ref type or expanded members. */
public class MetadataTypeBuilderCharacteristicTest
{
    private static final String RU_KIND = "Характеристика";

    @Test
    public void testExplicitKindSharesChartValueTypeInBothLanguages()
    {
        for (String name : new String[] {"ExpenseItems", "СтатьиРасходов"})
        {
            Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
            TypeSet expected = seed(config, name);
            for (String kind : new String[] {"Characteristic", RU_KIND})
            {
                assertBuilt(config, item(kind, name), expected);
            }
        }
    }

    @Test
    public void testInlineKindSharesChartValueTypeInBothLanguages()
    {
        for (String name : new String[] {"ExpenseItems", "СтатьиРасходов", "ContractorRef", "PriceList"})
        {
            Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
            TypeSet expected = seed(config, name);
            for (String kind : new String[] {"Characteristic", RU_KIND})
            {
                assertBuilt(config, item(kind + "." + name, null), expected);
            }
        }
    }

    @Test
    public void testUnknownNameAndSynonymAreActionableRefusals()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        seed(config, "ExpenseItems");
        config.getChartsOfCharacteristicTypes().get(0).getSynonym().put("en", "Expense label");
        for (String name : new String[] {"MissingChart", "Expense label", "НетТакогоПВХ"})
        {
            for (String kind : new String[] {"Characteristic", RU_KIND})
            {
                for (JsonObject candidate : new JsonObject[] {item(kind, name), item(kind + "." + name, null)})
                {
                    MetadataTypeBuilder.Result result = build(config, candidate);
                    assertNotNull(result.error);
                    assertFalse(result.error, result.platformFailure);
                    assertNull(result.typeDescription);
                    assertTrue(result.error, result.error.contains(name));
                    assertTrue(result.error, result.error.contains("ChartOfCharacteristicTypes"));
                    assertTrue(result.error, result.error.contains("get_metadata_objects"));
                }
            }
        }
    }

    @Test
    public void testMissingProducedChainIsPlatformFailure()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        ChartOfCharacteristicTypes chart = MdClassFactory.eINSTANCE.createChartOfCharacteristicTypes();
        chart.setName("NotReady");
        config.getChartsOfCharacteristicTypes().add(chart);
        for (int stage = 0; stage < 3; stage++)
        {
            MetadataTypeBuilder.Result result = build(config, item("Characteristic", "NotReady"));
            assertNotNull(result.error);
            assertTrue(result.error, result.platformFailure);
            assertTrue(result.error, result.error.contains("Characteristic.NotReady"));
            assertTrue(result.error, result.error.contains("producedTypes/containerType/typeSet"));
            assertTrue(result.error, result.error.contains("revalidate_objects"));
            if (stage == 0)
            {
                chart.setProducedTypes(MdTypeFactory.eINSTANCE.createChartOfCharacteristicTypesTypes());
            }
            else if (stage == 1)
            {
                chart.getProducedTypes().setContainerType(MdTypeFactory.eINSTANCE.createMdContainerType());
            }
        }
    }

    @Test
    public void testExplicitKindRequiresRefAndInlineKindRefusesIgnoredMembers()
    {
        for (String kind : new String[] {"Characteristic", RU_KIND})
        {
            for (String ref : new String[] {null, "", " "})
            {
                String error = MetadataTypeBuilder.validateShape(spec(item(kind, ref)));
                assertNotNull(error);
                assertTrue(error, error.contains("ref"));
            }
            String error = MetadataTypeBuilder.validateShape(spec(item(kind + ".ExpenseItems", "Other")));
            assertNotNull(error);
            assertTrue(error, error.contains("Unknown member 'ref'"));
            JsonObject qualified = item(kind, "ExpenseItems");
            qualified.addProperty("length", 30);
            assertTrue(MetadataTypeBuilder.validateShape(spec(qualified)).contains("Unknown member 'length'"));
        }
    }

    @Test
    public void testExtensionRefusalIncludesAdoptionAdvice()
    {
        MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(
            spec(item("Characteristic", "Missing")), mock(IEObjectProvider.class),
            MdClassFactory.eINSTANCE.createConfiguration(), null, true, MetadataTypeBuilder.TypeTarget.METADATA);
        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("adopt_metadata_object"));
    }

    private static void assertBuilt(Configuration config, JsonObject item, TypeSet expected)
    {
        MetadataTypeBuilder.Result result = build(config, item);
        assertNull(result.error, result.error);
        assertEquals(1, result.typeDescription.getTypes().size());
        assertSame(expected, result.typeDescription.getTypes().get(0));
        assertSame(config.getChartsOfCharacteristicTypes().get(0).getProducedTypes().getContainerType(),
            expected.eContainer());
        assertFalse(result.platformFailure);
    }

    private static MetadataTypeBuilder.Result build(Configuration config, JsonObject item)
    {
        return MetadataTypeBuilder.buildWithProvider(spec(item), mock(IEObjectProvider.class), config,
            null, false, MetadataTypeBuilder.TypeTarget.METADATA);
    }

    private static JsonObject item(String kind, String ref)
    {
        JsonObject item = new JsonObject();
        item.addProperty("kind", kind);
        if (ref != null)
        {
            item.addProperty("ref", ref);
        }
        return item;
    }

    private static JsonObject spec(JsonObject item)
    {
        JsonArray types = new JsonArray();
        types.add(item);
        JsonObject spec = new JsonObject();
        spec.add("types", types);
        return spec;
    }

    private static TypeSet seed(Configuration config, String name)
    {
        ChartOfCharacteristicTypes chart = MdClassFactory.eINSTANCE.createChartOfCharacteristicTypes();
        chart.setName(name);
        config.getChartsOfCharacteristicTypes().add(chart);
        chart.setProducedTypes(MdTypeFactory.eINSTANCE.createChartOfCharacteristicTypesTypes());
        chart.getProducedTypes().setContainerType(MdTypeFactory.eINSTANCE.createMdContainerType());
        TypeSet valueType = McoreFactory.eINSTANCE.createTypeSet();
        valueType.setName("Characteristic." + name);
        valueType.setNameRu(RU_KIND + "." + name);
        chart.getProducedTypes().getContainerType().setTypeSet(valueType);
        // Distinct members prevent confusing the value type with the chart's Ref or allowed values.
        valueType.getTypes().add(McoreFactory.eINSTANCE.createType());
        chart.getProducedTypes().setRefType(MdTypeFactory.eINSTANCE.createMdRefType());
        chart.getProducedTypes().getRefType().setType(McoreFactory.eINSTANCE.createType());
        return valueType;
    }
}

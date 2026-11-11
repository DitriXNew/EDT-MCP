/** Copyright (c) 2026 DitriX. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.junit.Test;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.dt.mcore.*;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.metadata.mdclass.EnumValue;
import com.google.gson.*;

public class MetadataValueCodecTest
{
    private static JsonElement json(String text) { return JsonParser.parseString(text); }
    private static MetadataValueCodec.Plan parse(String text)
    {
        return MetadataValueCodec.parse(json(text), "test", ref -> null);
    }

    @Test public void everyScalarRoundTripsIncludingCyrillicAndEmptyString()
    {
        for (String input : List.of("{kind:'string',value:'Товар'}", "{kind:'string',value:''}",
            "{kind:'number',value:12.50}", "{kind:'number',value:1e3}", "{kind:'boolean',value:false}",
            "{kind:'date',value:'2026-01-02T03:04:05'}", "{kind:'null'}", "{kind:'undefined'}"))
        {
            Value first = parse(input).create(null);
            JsonObject rendered = MetadataValueCodec.render(first);
            Value second = MetadataValueCodec.parse(rendered, "again", ref -> null).create(null);
            assertEquals(rendered, MetadataValueCodec.render(second));
        }
    }

    @Test public void fixedArrayPreservesOrderAndNestedValues()
    {
        FixedArrayValue array = (FixedArrayValue)parse("{kind:'fixedArray',values:[{kind:'string',value:'A'},"
            + "{kind:'number',value:2},{kind:'fixedArray',values:[{kind:'undefined'}]}]}").create(null);
        assertEquals(3, array.getValues().size());
        assertEquals("A", ((StringValue)array.getValues().get(0)).getValue());
        assertEquals("fixedArray", MetadataValueCodec.render(array).get("kind").getAsString());
    }

    private static Configuration configuration()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        config.setName("Config");
        new ResourceImpl(URI.createURI("test:/configuration")).getContents().add(config);
        return config;
    }

    @Test public void bilingualEnumReferencesUseNamesAndRefetchInWrite()
    {
        Configuration config = configuration();
        com._1c.g5.v8.dt.metadata.mdclass.Enum owner = MdClassFactory.eINSTANCE.createEnum();
        owner.setName("Цвета"); config.getEnums().add(owner);
        owner.getSynonym().put("ru", "НеИмя");
        EnumValue red = MdClassFactory.eINSTANCE.createEnumValue(); red.setName("Красный");
        owner.getEnumValues().add(red);
        MetadataScope scope = MetadataScope.ofConfiguration(config);
        MetadataValueCodec.Reference en = MetadataValueCodec.resolve(scope, "Enum.Цвета.EnumValue.Красный");
        MetadataValueCodec.Reference ru = MetadataValueCodec.resolve(scope, "Перечисление.Цвета.ЗначениеПеречисления.Красный");
        assertNotNull(en); assertEquals(en, ru);
        assertEquals("Enum.Цвета.EnumValue.Красный", en.canonical());
        assertNull(MetadataValueCodec.resolve(scope, "Enum.НеИмя.EnumValue.Красный"));
        IBmTransaction tx = mock(IBmTransaction.class);
        IBmObject fresh = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(tx.getObjectByUri(en.uri())).thenReturn(fresh);
        MetadataValueCodec.Plan plan = MetadataValueCodec.parse(json("{kind:'ref',ref:'Перечисление.Цвета.ЗначениеПеречисления.Красный'}"),
            "fillValue", ref -> MetadataValueCodec.resolve(scope, ref));
        assertSame(fresh, ((ReferenceValue)plan.create(tx)).getValue());
        verify(tx).getObjectByUri(en.uri());
    }

    @Test public void predefinedAndEmptyRefResolveThroughTheirOwnerInBothLanguages()
    {
        Configuration config = configuration();
        Catalog owner = MdClassFactory.eINSTANCE.createCatalog(); owner.setName("Товары");
        config.getCatalogs().add(owner);
        CatalogPredefinedItem item = MdClassFactory.eINSTANCE.createCatalogPredefinedItem(); item.setName("Основной");
        CatalogPredefined items = MdClassFactory.eINSTANCE.createCatalogPredefined();
        items.getItems().add(item); owner.setPredefined(items);
        // Produced types are derived by EDT live; construct their shape for this resolver unit.
        com._1c.g5.v8.dt.metadata.mdtype.CatalogTypes types = com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory.eINSTANCE.createCatalogTypes();
        com._1c.g5.v8.dt.metadata.mdtype.MdRefType ref = com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory.eINSTANCE.createMdRefType();
        ref.setEmptyRef(com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory.eINSTANCE.createEmptyRef());
        types.setRefType(ref); owner.setProducedTypes(types);
        MetadataScope scope = MetadataScope.ofConfiguration(config);
        for (String suffix : List.of("Основной", "EmptyRef"))
        {
            MetadataValueCodec.Reference en = MetadataValueCodec.resolve(scope, "Catalog.Товары." + suffix);
            MetadataValueCodec.Reference ru = MetadataValueCodec.resolve(scope, "Справочник.Товары." + suffix);
            assertNotNull(suffix, en); assertEquals(en, ru);
            assertEquals("CatalogRef.Товары", en.typeName());
        }
        CatalogPredefinedItem keyword = MdClassFactory.eINSTANCE.createCatalogPredefinedItem();
        keyword.setName("Реквизит"); items.getItems().add(keyword);
        assertEquals("Catalog.Товары.Реквизит", MetadataValueCodec.resolve(scope, "Справочник.Товары.Реквизит").canonical());
        assertEquals(MetadataValueCodec.resolve(scope, "Catalog.Товары.EmptyRef"),
            MetadataValueCodec.resolve(scope, "Справочник.Товары.ПустаяСсылка"));
    }

    @Test public void referencesInArraysRefetchAndExplicitReferenceTypeMismatchesRefuse()
    {
        var reference = new MetadataValueCodec.Reference("Enum.Colors.EnumValue.Red",
            URI.createURI("bm:/test/Red"), "EnumRef.Colors");
        var ref = MetadataValueCodec.parse(json("{kind:'ref',ref:'Enum.Colors.EnumValue.Red'}"), "fillValue", r -> reference);
        TypeDescription type = McoreFactory.eINSTANCE.createTypeDescription();
        Type item = McoreFactory.eINSTANCE.createType(); item.setName("CatalogRef.Other"); type.getTypes().add(item);
        assertTrue(MetadataValueCodec.fillRefusal(ref, type).contains("EnumRef.Colors"));
        item.setName("EnumRef.Colors"); assertNull(MetadataValueCodec.fillRefusal(ref, type));
        var array = MetadataValueCodec.parse(json("{kind:'fixedArray',values:[{kind:'ref',ref:'Enum.Colors.EnumValue.Red'}]}"),
            "choiceParameters", r -> reference);
        IBmTransaction tx = mock(IBmTransaction.class);
        IBmObject fresh = mock(IBmObject.class, withSettings().extraInterfaces(EObject.class));
        when(tx.getObjectByUri(reference.uri())).thenReturn(fresh);
        assertSame(fresh, ((ReferenceValue)((FixedArrayValue)array.create(tx)).getValues().get(0)).getValue());
        verify(tx).getObjectByUri(reference.uri());
        when(tx.getObjectByUri(reference.uri())).thenReturn(null);
        try { ref.create(tx); fail(); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage(), e.getMessage().contains("Read the metadata and retry")); }
    }

    @Test public void unreadableTypeDoesNotCauseASpeculativeFillRefusal()
    {
        TypeDescription type = McoreFactory.eINSTANCE.createTypeDescription();
        Type unresolved = McoreFactory.eINSTANCE.createType();
        type.getTypes().add(unresolved);
        assertNull(MetadataValueCodec.fillRefusal(parse("{kind:'boolean',value:true}"), type));
    }

    @Test public void malformedSpecsNameTheValueAndExplainTheRepair()
    {
        for (String text : List.of("[]", "{kind:'string',value:2}", "{kind:'number',value:'2'}",
            "{kind:'boolean',value:1}", "{kind:'date',value:'bad'}", "{kind:'null',value:1}",
            "{kind:'undefined',value:false}", "{kind:'fixedArray',values:1}", "{kind:'ref',ref:'Enum.Missing.EnumValue.X'}",
            "{kind:'ref',ref:'Перечисление.Нет.ЗначениеПеречисления.Х'}", "{kind:'string',value:'x',typo:1}",
            "{kind:'wrong'}", "{kind:'number',value:1e1000}"))
        {
            try { parse(text); fail(text); }
            catch (IllegalArgumentException e)
            {
                assertTrue(e.getMessage(), e.getMessage().contains("test"));
                assertTrue(e.getMessage(), e.getMessage().length() > 45);
            }
        }
    }

    @Test public void onlyExplicitTypeMismatchAndArrayFillAreRefused()
    {
        TypeDescription type = McoreFactory.eINSTANCE.createTypeDescription();
        Type string = McoreFactory.eINSTANCE.createType(); string.setName("String"); type.getTypes().add(string);
        assertNull(MetadataValueCodec.fillRefusal(parse("{kind:'string',value:'x'}"), type));
        assertNull(MetadataValueCodec.fillRefusal(parse("{kind:'undefined'}"), type));
        assertTrue(MetadataValueCodec.fillRefusal(parse("{kind:'number',value:1}"), type).contains("does not match"));
        assertTrue(MetadataValueCodec.fillRefusal(parse("{kind:'fixedArray',values:[]}"), type).contains("fixedArray"));
    }
}

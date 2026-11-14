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

import java.util.Map;

import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.mcore.TypeSet;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypes;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdtype.MdTypeFactory;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Issue #920: one reference syntax across the type grammar, and the DCS parameter's StandardPeriod.
 * <ul>
 * <li>A reference is also named by the reference TYPE the platform stores and every read prints:
 * {@code {kind:'Ref', ref:'CatalogRef.Goods'}} (or the Russian {@code СправочникСсылка.Goods}) builds the
 * same model-owned Ref type as {@code {kind:'Ref', ref:'Catalog.Goods'}} and
 * {@code {kind:'CatalogRef', ref:'Goods'}} - for every target, because the grammar is shared by
 * {@code modify_metadata}, {@code create_metadata} and {@code dcs}.</li>
 * <li>A DCS parameter's {@code StandardPeriod} is matched case-insensitively: the value spec spells it
 * {@code standardPeriod}, and the platform type index knows only the exact spellings.</li>
 * </ul>
 */
public class MetadataTypeBuilderRefTypeNameTest
{
    /** Spravochnik (Catalog). */
    private static final String RU_CATALOG = "Справочник"; //$NON-NLS-1$
    /** Dokument (Document). */
    private static final String RU_DOCUMENT = "Документ"; //$NON-NLS-1$
    /** Ssylka (Ref). */
    private static final String RU_REF = "Ссылка"; //$NON-NLS-1$
    /** StandartnyjPeriod (StandardPeriod). */
    private static final String RU_STANDARD_PERIOD =
        "СтандартныйПериод"; //$NON-NLS-1$

    private static final MetadataTypeBuilder.TypeTarget[] REFERENCE_TARGETS = {
        MetadataTypeBuilder.TypeTarget.METADATA,
        MetadataTypeBuilder.TypeTarget.DCS_PARAMETER,
        MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE,
        MetadataTypeBuilder.TypeTarget.SESSION_PARAMETER };

    // ---- the reference type name -----------------------------------------------------------------

    @Test
    public void testARefNamedByItsReferenceTypeBuildsTheSameTypeAsTheObjectAddress()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type goodsRef = seed(config, "Catalog", "Goods", "refType", "CatalogRef.Goods"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (MetadataTypeBuilder.TypeTarget target : REFERENCE_TARGETS)
        {
            for (JsonObject item : new JsonObject[] { item("Ref", "CatalogRef.Goods"), //$NON-NLS-1$ //$NON-NLS-2$
                item("Ref", "Catalog.Goods"), item("CatalogRef", "Goods") }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            {
                MetadataTypeBuilder.Result result = build(config, target, item);
                assertNull(target + " " + item + ": " + result.error, result.error); //$NON-NLS-1$ //$NON-NLS-2$
                assertEquals(1, result.typeDescription.getTypes().size());
                assertSame(target + " " + item, goodsRef, result.typeDescription.getTypes().get(0)); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void testTheRussianReferenceTypeNameResolvesToTheSameType()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type goodsRef = seed(config, "Catalog", "Goods", "refType", "CatalogRef.Goods"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Type invoiceRef = seed(config, "Document", "Invoice", "refType", "DocumentRef.Invoice"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (MetadataTypeBuilder.TypeTarget target : REFERENCE_TARGETS)
        {
            MetadataTypeBuilder.Result catalog = build(config, target, item("Ref", RU_CATALOG + RU_REF + ".Goods")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(target + ": " + catalog.error, catalog.error); //$NON-NLS-1$
            assertSame(goodsRef, catalog.typeDescription.getTypes().get(0));

            MetadataTypeBuilder.Result document = build(config, target, item("ref", RU_DOCUMENT + RU_REF + ".Invoice")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(target + ": " + document.error, document.error); //$NON-NLS-1$
            assertSame(invoiceRef, document.typeDescription.getTypes().get(0));

            MetadataTypeBuilder.Result english = build(config, target, item("Ref", "DocumentRef.Invoice")); //$NON-NLS-1$ //$NON-NLS-2$
            assertNull(target + ": " + english.error, english.error); //$NON-NLS-1$
            assertSame(invoiceRef, english.typeDescription.getTypes().get(0));
        }
    }

    @Test
    public void testAnEnumReferenceTypeNameResolvesThroughTheEnumRoute()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type colorsRef = seed(config, "Enum", "Colors", "refType", "EnumRef.Colors"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        MetadataTypeBuilder.Result result = build(config, MetadataTypeBuilder.TypeTarget.DCS_PARAMETER,
            item("Ref", "EnumRef.Colors")); //$NON-NLS-1$ //$NON-NLS-2$

        assertNull(result.error, result.error);
        assertSame(colorsRef, result.typeDescription.getTypes().get(0));
    }

    @Test
    public void testAReferenceTypeNameOfAMissingObjectIsRefusedNamingItAndTheAcceptedForms()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        seed(config, "Catalog", "Goods", "refType", "CatalogRef.Goods"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (MetadataTypeBuilder.TypeTarget target : REFERENCE_TARGETS)
        {
            MetadataTypeBuilder.Result result = build(config, target, item("Ref", "CatalogRef.Missing")); //$NON-NLS-1$ //$NON-NLS-2$

            assertNull(result.typeDescription);
            assertFalse(result.platformFailure);
            assertTrue(result.error, result.error.startsWith(
                "Cannot resolve the reference target for kind 'Ref' ref 'CatalogRef.Missing'.")); //$NON-NLS-1$
            assertTrue("the advice names the reference-type spelling as an accepted form: " + result.error, //$NON-NLS-1$
                result.error.contains("ref:'CatalogRef.Name'")); //$NON-NLS-1$
        }
    }

    @Test
    public void testOnlyAReferenceTypeTokenIsReadAsTheObjectType()
    {
        // CatalogObject.Goods names the object type, not the reference: Ref must not strip any produced
        // suffix but its own. A member address under a reference type names no reference either, and
        // DefinedType has no reference type - each of them would otherwise resolve to a seeded object.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        seed(config, "Catalog", "Goods", "refType", "CatalogRef.Goods"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        CatalogAttribute weight = MdClassFactory.eINSTANCE.createCatalogAttribute();
        weight.setName("Weight"); //$NON-NLS-1$
        config.getCatalogs().get(0).getAttributes().add(weight);
        MetadataTypeBuilderTest.seedDefinedType(config, "Goods", true); //$NON-NLS-1$
        for (String ref : new String[] { "CatalogObject.Goods", "CatalogRef.Goods.Attribute.Weight", //$NON-NLS-1$ //$NON-NLS-2$
            "CatalogRef.", "Ref.Goods", "DefinedTypeRef.Goods" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            MetadataTypeBuilder.Result result = build(config, MetadataTypeBuilder.TypeTarget.METADATA, item("Ref", ref)); //$NON-NLS-1$
            assertNull(ref, result.typeDescription);
            assertNotNull(ref, result.error);
            assertTrue(ref + ": " + result.error, result.error.contains("Cannot resolve the reference target")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testAnObjectWhoseNameEndsInRefIsStillAddressedByItsObjectAddress()
    {
        // Only the leading TYPE token is read as a reference type; an object Name is never touched.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type contractorRef = seed(config, "Catalog", "ContractorRef", "refType", "CatalogRef.ContractorRef"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (String ref : new String[] { "Catalog.ContractorRef", "CatalogRef.ContractorRef" }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            MetadataTypeBuilder.Result result = build(config, MetadataTypeBuilder.TypeTarget.METADATA, item("Ref", ref)); //$NON-NLS-1$
            assertNull(ref + ": " + result.error, result.error); //$NON-NLS-1$
            assertSame(ref, contractorRef, result.typeDescription.getTypes().get(0));
        }
    }

    // ---- StandardPeriod on a DCS parameter -------------------------------------------------------

    @Test
    public void testAnyCaseOfStandardPeriodBuildsThePlatformTypeForADcsParameter()
    {
        Type standardPeriod = named("StandardPeriod"); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(Map.of("StandardPeriod", standardPeriod, //$NON-NLS-1$
            RU_STANDARD_PERIOD, standardPeriod));
        for (String kind : new String[] { "standardPeriod", "STANDARDPERIOD", "standardperiod", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            RU_STANDARD_PERIOD.toLowerCase(java.util.Locale.ROOT), "StandardPeriod", RU_STANDARD_PERIOD }) //$NON-NLS-1$
        {
            MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(spec(kind(kind)), provider,
                MdClassFactory.eINSTANCE.createConfiguration(), null, false,
                MetadataTypeBuilder.TypeTarget.DCS_PARAMETER);
            assertNull(kind + ": " + result.error, result.error); //$NON-NLS-1$
            assertSame(kind, standardPeriod, result.typeDescription.getTypes().get(0));
        }
    }

    @Test
    public void testAnyCaseOfStandardPeriodStaysRefusedOnAStoredFeatureAsAKnownType()
    {
        // Elsewhere StandardPeriod remains a form-only platform value: the case-insensitive spelling
        // must surface as that refusal, not as an unknown kind.
        Type standardPeriod = named("StandardPeriod"); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(Map.of("StandardPeriod", standardPeriod)); //$NON-NLS-1$

        MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(spec(kind("standardPeriod")), //$NON-NLS-1$
            provider, MdClassFactory.eINSTANCE.createConfiguration(), null, false,
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNull(result.typeDescription);
        assertNotNull(result.error);
        assertFalse(result.error, result.error.contains("Unknown type kind")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("stored metadata feature")); //$NON-NLS-1$
    }

    @Test
    public void testADcsParameterUnknownKindRefusalOffersStandardPeriodAndTheReferenceForms()
    {
        IEObjectProvider provider = providerKnowing(Map.of("StandardPeriod", named("StandardPeriod"))); //$NON-NLS-1$ //$NON-NLS-2$

        MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(spec(kind("Nonsense920")), //$NON-NLS-1$
            provider, MdClassFactory.eINSTANCE.createConfiguration(), null, false,
            MetadataTypeBuilder.TypeTarget.DCS_PARAMETER);

        assertNull(result.typeDescription);
        assertEquals("Unknown type kind 'Nonsense920'. A data-composition parameter or field takes String / " //$NON-NLS-1$
            + "Number / Boolean / Date / ValueStorage / UUID, StandardPeriod (" + RU_STANDARD_PERIOD + "), a " //$NON-NLS-1$ //$NON-NLS-2$
            + "DefinedType ({kind:'DefinedType', ref:'Name'}), a characteristic value ({kind:'Characteristic', " //$NON-NLS-1$
            + "ref:'Name'}), or a reference ({kind:'Ref', ref:'Catalog.Name'}, " //$NON-NLS-1$
            + "{kind:'Ref', ref:'CatalogRef.Name'} or {kind:'CatalogRef', ref:'Name'}) - this one names no " //$NON-NLS-1$
            + "type this platform version knows.", result.error); //$NON-NLS-1$
    }

    @Test
    public void testEveryKindTheDcsRefusalOffersIsBuiltForADcsParameter()
    {
        // The refusal is the caller's only inventory: each kind it names must really build here.
        Type standardPeriod = named("StandardPeriod"); //$NON-NLS-1$
        Type valueStorage = named("ValueStorage"); //$NON-NLS-1$
        Type uuid = named("UUID"); //$NON-NLS-1$
        Type string = named("String"); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(Map.of("StandardPeriod", standardPeriod, //$NON-NLS-1$
            "ValueStorage", valueStorage, "UUID", uuid, "String", string)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type goodsRef = seed(config, "Catalog", "Goods", "refType", "CatalogRef.Goods"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        TypeItem money = MetadataTypeBuilderTest.seedDefinedType(config, "Money", true); //$NON-NLS-1$
        TypeSet expenses = seedCharacteristic(config, "ExpenseItems"); //$NON-NLS-1$

        Object[][] offered = {
            { kind("String"), string }, { kind("ValueStorage"), valueStorage }, { kind("UUID"), uuid }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            { kind(RU_STANDARD_PERIOD), standardPeriod }, { item("DefinedType", "Money"), money }, //$NON-NLS-1$ //$NON-NLS-2$
            { item("Characteristic", "ExpenseItems"), expenses }, { item("Ref", "Catalog.Goods"), goodsRef }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            { item("Ref", "CatalogRef.Goods"), goodsRef }, { item("CatalogRef", "Goods"), goodsRef } }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (Object[] row : offered)
        {
            MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(spec((JsonObject)row[0]),
                provider, config, MetadataScope.ofConfiguration(config), false,
                MetadataTypeBuilder.TypeTarget.DCS_PARAMETER);
            assertNull(row[0] + ": " + result.error, result.error); //$NON-NLS-1$
            assertSame(row[0].toString(), row[1], result.typeDescription.getTypes().get(0));
        }
    }

    @Test
    public void testADcsParameterFormOnlyRefusalOffersStandardPeriod()
    {
        IEObjectProvider provider = providerKnowing(Map.of("Chart", named("Chart"))); //$NON-NLS-1$ //$NON-NLS-2$

        MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(spec(kind("Chart")), //$NON-NLS-1$
            provider, MdClassFactory.eINSTANCE.createConfiguration(), null, false,
            MetadataTypeBuilder.TypeTarget.DCS_PARAMETER);

        assertNotNull(result.error);
        assertTrue(result.error, result.error.contains("data-composition parameter")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("StandardPeriod")); //$NON-NLS-1$
        assertFalse(result.error, result.error.contains("ValueStorage")); //$NON-NLS-1$
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static MetadataTypeBuilder.Result build(Configuration config, MetadataTypeBuilder.TypeTarget target,
        JsonObject item)
    {
        return MetadataTypeBuilder.buildWithProvider(spec(item), Mockito.mock(IEObjectProvider.class), config,
            MetadataScope.ofConfiguration(config), false, target);
    }

    private static Type seed(Configuration config, String englishMetadataType, String name, String producedFeature,
        String typeName)
    {
        Type type = named(typeName);
        MetadataTypeBuilderTest.seedProducedType(config, englishMetadataType, name, producedFeature, type);
        return type;
    }

    /** A chart of characteristic types whose produced value type set is {@code Characteristic.<name>}. */
    private static TypeSet seedCharacteristic(Configuration config, String name)
    {
        ChartOfCharacteristicTypes chart = MdClassFactory.eINSTANCE.createChartOfCharacteristicTypes();
        chart.setName(name);
        config.getChartsOfCharacteristicTypes().add(chart);
        chart.setProducedTypes(MdTypeFactory.eINSTANCE.createChartOfCharacteristicTypesTypes());
        chart.getProducedTypes().setContainerType(MdTypeFactory.eINSTANCE.createMdContainerType());
        TypeSet valueType = McoreFactory.eINSTANCE.createTypeSet();
        valueType.setName("Characteristic." + name); //$NON-NLS-1$
        chart.getProducedTypes().getContainerType().setTypeSet(valueType);
        return valueType;
    }

    private static Type named(String name)
    {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(name);
        return type;
    }

    /** A platform type provider that knows exactly these names and THROWS for any other, as EDT's does. */
    private static IEObjectProvider providerKnowing(Map<String, Type> known)
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalArgumentException("Can't create proxy for unknown name")) //$NON-NLS-1$
            .when(provider).createProxy(Mockito.anyString());
        known.forEach((name, type) -> Mockito.doReturn(type).when(provider).createProxy(name));
        return provider;
    }

    private static JsonObject item(String kind, String ref)
    {
        JsonObject item = kind(kind);
        item.addProperty("ref", ref); //$NON-NLS-1$
        return item;
    }

    private static JsonObject kind(String kind)
    {
        JsonObject item = new JsonObject();
        item.addProperty("kind", kind); //$NON-NLS-1$
        return item;
    }

    private static JsonObject spec(JsonObject... items)
    {
        JsonArray types = new JsonArray();
        for (JsonObject item : items)
        {
            types.add(item);
        }
        JsonObject spec = new JsonObject();
        spec.add("types", types); //$NON-NLS-1$
        return spec;
    }
}

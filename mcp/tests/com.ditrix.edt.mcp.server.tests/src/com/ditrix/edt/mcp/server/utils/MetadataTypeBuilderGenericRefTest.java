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
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Issue #966: a {@code <Type>Ref} kind WITHOUT {@code ref} is the generic reference type - "any document"
 * ({@code DocumentRef} / {@code ДокументСсылка}), the platform type set EDT's type editor lists and a
 * {@code .mdo} stores as {@code <types>DocumentRef</types>}. One grammar: {@code {kind:'Ref', ref:'Document'}}
 * is refused with a pointer to it, and the Russian Ref suffix names a reference like the English one.
 */
public class MetadataTypeBuilderGenericRefTest
{
    /** Dokument (Document). */
    private static final String RU_DOCUMENT = "Документ"; //$NON-NLS-1$
    /** Spravochnik (Catalog). */
    private static final String RU_CATALOG = "Справочник"; //$NON-NLS-1$
    /** Ssylka (Ref). */
    private static final String RU_REF = "Ссылка"; //$NON-NLS-1$
    /** LyubayaSsylka (AnyRef). */
    private static final String RU_ANY_REF = "ЛюбаяСсылка"; //$NON-NLS-1$

    private static final MetadataTypeBuilder.TypeTarget[] GENERIC_TARGETS = {
        MetadataTypeBuilder.TypeTarget.METADATA,
        MetadataTypeBuilder.TypeTarget.SESSION_PARAMETER,
        MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE };

    // ---- the generic reference -------------------------------------------------------------------

    @Test
    public void testAReferenceKindWithoutRefBuildsThePlatformGenericTypeSet()
    {
        TypeSet documentRef = typeSet("DocumentRef"); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(Map.of("DocumentRef", documentRef)); //$NON-NLS-1$
        for (MetadataTypeBuilder.TypeTarget target : GENERIC_TARGETS)
        {
            MetadataTypeBuilder.Result result = build(provider, target, kind("DocumentRef")); //$NON-NLS-1$
            assertNull(target + ": " + result.error, result.error); //$NON-NLS-1$
            assertEquals(1, result.typeDescription.getTypes().size());
            assertSame(target.toString(), documentRef, result.typeDescription.getTypes().get(0));
        }
    }

    @Test
    public void testTheRussianSpellingAndAnyCaseNameTheSameGenericType()
    {
        // The provider indexes the platform name; a Russian or lower-case token is mapped to it.
        TypeSet documentRef = typeSet("DocumentRef"); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(Map.of("DocumentRef", documentRef)); //$NON-NLS-1$
        for (String spelling : new String[] { RU_DOCUMENT + RU_REF, "documentref", "DOCUMENTREF", //$NON-NLS-1$ //$NON-NLS-2$
            (RU_DOCUMENT + RU_REF).toLowerCase(java.util.Locale.ROOT) })
        {
            MetadataTypeBuilder.Result result = build(provider, MetadataTypeBuilder.TypeTarget.METADATA, kind(spelling));
            assertNull(spelling + ": " + result.error, result.error); //$NON-NLS-1$
            assertSame(spelling, documentRef, result.typeDescription.getTypes().get(0));
        }
    }

    @Test
    public void testAnyRefIsLookedUpAsSpelledInEitherLanguage()
    {
        TypeSet anyRef = typeSet("AnyRef"); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(Map.of("AnyRef", anyRef, RU_ANY_REF, anyRef)); //$NON-NLS-1$
        for (String spelling : new String[] { "AnyRef", RU_ANY_REF }) //$NON-NLS-1$
        {
            MetadataTypeBuilder.Result result = build(provider, MetadataTypeBuilder.TypeTarget.METADATA, kind(spelling));
            assertNull(spelling + ": " + result.error, result.error); //$NON-NLS-1$
            assertSame(spelling, anyRef, result.typeDescription.getTypes().get(0));
        }
    }

    @Test
    public void testAGenericAndAConcreteReferenceMixInOneCompositeType()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type goodsRef = named("CatalogRef.Goods"); //$NON-NLS-1$
        MetadataTypeBuilderTest.seedProducedType(config, "Catalog", "Goods", "refType", goodsRef); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        TypeSet documentRef = typeSet("DocumentRef"); //$NON-NLS-1$
        IEObjectProvider provider = providerKnowing(Map.of("DocumentRef", documentRef)); //$NON-NLS-1$

        MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(
            spec(kind("DocumentRef"), item("CatalogRef", "Goods")), provider, config, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            MetadataScope.ofConfiguration(config), false, MetadataTypeBuilder.TypeTarget.METADATA);

        assertNull(result.error, result.error);
        assertEquals(2, result.typeDescription.getTypes().size());
        assertSame(documentRef, result.typeDescription.getTypes().get(0));
        assertSame(goodsRef, result.typeDescription.getTypes().get(1));
    }

    @Test
    public void testAReferenceKindThePlatformDoesNotDeclareAsAGenericTypeIsRefused()
    {
        // Unknown to the provider, and known but not a type set: neither is a generic reference.
        IEObjectProvider provider = providerKnowing(Map.of("StrangeRef", named("StrangeRef"))); //$NON-NLS-1$ //$NON-NLS-2$
        for (String spelling : new String[] { "Foo966Ref", "StrangeRef", "InformationRegisterRef" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            MetadataTypeBuilder.Result result = build(provider, MetadataTypeBuilder.TypeTarget.METADATA, kind(spelling));
            assertNull(spelling, result.typeDescription);
            assertFalse(spelling, result.platformFailure);
            assertTrue(spelling + ": " + result.error, //$NON-NLS-1$
                result.error.startsWith("Type kind '" + spelling + "' without ref names no generic reference type")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(result.error, result.error.contains("{kind:'" + spelling + "', ref:'Name'}")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testADeclaredGenericNameTheProviderFailsOnIsItsFailureEvenWhenItsLookupIsEmpty()
    {
        // Review of #970: an incomplete index may throw on createProxy AND answer null to the lookup; a
        // generic reference the metamodel declares (Document has a Ref type) is then the platform's failure.
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalStateException("index not ready")).when(provider).createProxy(Mockito.anyString()); //$NON-NLS-1$
        for (String spelling : new String[] { "DocumentRef", "documentref", RU_DOCUMENT + RU_REF }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            MetadataTypeBuilder.Result result = build(provider, MetadataTypeBuilder.TypeTarget.METADATA, kind(spelling));
            assertNull(spelling, result.typeDescription);
            assertTrue(spelling + ": " + result.error, result.platformFailure); //$NON-NLS-1$
            assertNotNull(spelling, result.cause);
        }
        // A metadata type whose objects have no Ref type stays an unknown name, not a platform failure.
        MetadataTypeBuilder.Result register = build(provider, MetadataTypeBuilder.TypeTarget.METADATA,
            kind("InformationRegisterRef")); //$NON-NLS-1$
        assertFalse(register.error, register.platformFailure);
    }

    @Test
    public void testWhichRefNamesTheMetamodelDeclares()
    {
        assertTrue(MetadataTypeBuilder.hasRefType("DocumentRef")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasRefType("CatalogRef")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasRefType("ChartOfCharacteristicTypesRef")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.hasRefType("InformationRegisterRef")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.hasRefType("AnyRef")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.hasRefType("Document")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.hasRefType(null));
    }

    @Test
    public void testAKnownGenericNameTheProviderFailsToBuildIsThePlatformsFailure()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalStateException("index not ready")).when(provider).createProxy("DocumentRef"); //$NON-NLS-1$ //$NON-NLS-2$
        Mockito.doReturn(Mockito.mock(org.eclipse.xtext.resource.IEObjectDescription.class))
            .when(provider).getEObjectDescription("DocumentRef"); //$NON-NLS-1$

        MetadataTypeBuilder.Result result = build(provider, MetadataTypeBuilder.TypeTarget.METADATA, kind("DocumentRef")); //$NON-NLS-1$

        assertNull(result.typeDescription);
        assertTrue(result.error, result.platformFailure);
        assertNotNull(result.cause);
    }

    @Test
    public void testADcsParameterAndAnEventSourceRefuseTheGenericReferenceNamingTheConcreteForm()
    {
        IEObjectProvider provider = providerKnowing(Map.of("DocumentRef", typeSet("DocumentRef"))); //$NON-NLS-1$ //$NON-NLS-2$
        for (MetadataTypeBuilder.TypeTarget target : new MetadataTypeBuilder.TypeTarget[] {
            MetadataTypeBuilder.TypeTarget.DCS_PARAMETER, MetadataTypeBuilder.TypeTarget.EVENT_SOURCE })
        {
            MetadataTypeBuilder.Result result = build(provider, target, kind("DocumentRef")); //$NON-NLS-1$
            assertNull(target.toString(), result.typeDescription);
            assertFalse(result.platformFailure);
            assertTrue(target + ": " + result.error, result.error.contains("does not build for")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(result.error, result.error.contains("{kind:'DocumentRef', ref:'Name'}")); //$NON-NLS-1$
        }
    }

    // ---- one grammar ---------------------------------------------------------------------------

    @Test
    public void testALiteralRefNamingAMetadataTypePointsToTheGenericKind()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        String[][] cases = { { "Document", "DocumentRef" }, { RU_DOCUMENT, "DocumentRef" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            { "DocumentRef", "DocumentRef" }, { RU_CATALOG + RU_REF, "CatalogRef" }, { "Catalogs", "CatalogRef" } }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        for (String[] row : cases)
        {
            MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(spec(item("Ref", row[0])), //$NON-NLS-1$
                Mockito.mock(IEObjectProvider.class), config, MetadataScope.ofConfiguration(config), false,
                MetadataTypeBuilder.TypeTarget.METADATA);
            assertNull(row[0], result.typeDescription);
            assertTrue(row[0] + ": " + result.error, //$NON-NLS-1$
                result.error.startsWith("Cannot resolve the reference target for kind 'Ref' ref '" + row[0] + "'.")); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(row[0] + ": " + result.error, //$NON-NLS-1$
                result.error.contains("is {kind:'" + row[1] + "'} without ref.")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testALiteralRefNamingAnObjectGetsNoGenericPointer()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        for (String ref : new String[] { "Catalog.Missing", "Missing966", "DefinedType" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(spec(item("Ref", ref)), //$NON-NLS-1$
                Mockito.mock(IEObjectProvider.class), config, MetadataScope.ofConfiguration(config), false,
                MetadataTypeBuilder.TypeTarget.METADATA);
            assertFalse(ref + ": " + result.error, result.error.contains("without ref")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testTheRussianRefSuffixNamesOneObjectOnAStoredFeature()
    {
        // СправочникСсылка + ref is a reference like CatalogRef + ref - not a runtime produced type.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type goodsRef = named("CatalogRef.Goods"); //$NON-NLS-1$
        MetadataTypeBuilderTest.seedProducedType(config, "Catalog", "Goods", "refType", goodsRef); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (MetadataTypeBuilder.TypeTarget target : new MetadataTypeBuilder.TypeTarget[] {
            MetadataTypeBuilder.TypeTarget.METADATA, MetadataTypeBuilder.TypeTarget.SESSION_PARAMETER,
            MetadataTypeBuilder.TypeTarget.DCS_PARAMETER, MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE })
        {
            MetadataTypeBuilder.Result result = MetadataTypeBuilder.buildWithProvider(
                spec(item(RU_CATALOG + RU_REF, "Goods")), Mockito.mock(IEObjectProvider.class), config, //$NON-NLS-1$
                MetadataScope.ofConfiguration(config), false, target);
            assertNull(target + ": " + result.error, result.error); //$NON-NLS-1$
            assertSame(target.toString(), goodsRef, result.typeDescription.getTypes().get(0));
        }
    }

    @Test
    public void testTheGenericKindClassification()
    {
        assertTrue(MetadataTypeBuilder.isTypedRefKind("DocumentRef")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.isTypedRefKind(RU_DOCUMENT + RU_REF));
        assertTrue(MetadataTypeBuilder.isTypedRefKind("AnyRef")); //$NON-NLS-1$
        assertFalse("the literal Ref kind needs its ref", MetadataTypeBuilder.isTypedRefKind("Ref")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(MetadataTypeBuilder.isTypedRefKind(RU_REF));
        assertFalse(MetadataTypeBuilder.isTypedRefKind("DefinedType")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.isRefKind(RU_CATALOG + RU_REF));

        assertEquals("DocumentRef", MetadataTypeBuilder.canonicalGenericReferenceName(RU_DOCUMENT + RU_REF)); //$NON-NLS-1$
        assertEquals("ChartOfCharacteristicTypesRef", //$NON-NLS-1$
            MetadataTypeBuilder.canonicalGenericReferenceName("chartofcharacteristictypesref")); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.canonicalGenericReferenceName("AnyRef")); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.canonicalGenericReferenceName("Ref")); //$NON-NLS-1$
    }

    @Test
    public void testTheShapeAcceptsAnOmittedRefButNotAnEmptyOne()
    {
        assertNull(MetadataTypeBuilder.validateShape(spec(kind("DocumentRef")))); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.validateShape(spec(kind(RU_DOCUMENT + RU_REF))));
        String empty = MetadataTypeBuilder.validateShape(spec(item("DocumentRef", " "))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(empty);
        assertTrue(empty, empty.contains("Invalid member 'ref'")); //$NON-NLS-1$
        String literal = MetadataTypeBuilder.validateShape(spec(kind("Ref"))); //$NON-NLS-1$
        assertNotNull("the literal Ref kind still needs its ref", literal); //$NON-NLS-1$
        String definedType = MetadataTypeBuilder.validateShape(spec(kind("DefinedType"))); //$NON-NLS-1$
        assertNotNull("a DefinedType still needs its ref", definedType); //$NON-NLS-1$
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static MetadataTypeBuilder.Result build(IEObjectProvider provider, MetadataTypeBuilder.TypeTarget target,
        JsonObject item)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        return MetadataTypeBuilder.buildWithProvider(spec(item), provider, config,
            MetadataScope.ofConfiguration(config), false, target);
    }

    private static TypeSet typeSet(String name)
    {
        TypeSet set = McoreFactory.eINSTANCE.createTypeSet();
        set.setName(name);
        return set;
    }

    private static Type named(String name)
    {
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(name);
        return type;
    }

    /** A platform type provider that knows exactly these names and THROWS for any other, as EDT's does. */
    private static IEObjectProvider providerKnowing(Map<String, ? extends TypeItem> known)
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

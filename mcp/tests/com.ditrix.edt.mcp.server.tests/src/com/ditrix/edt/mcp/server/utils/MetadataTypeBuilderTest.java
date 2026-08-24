/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests the platform-independent parts of {@link MetadataTypeBuilder}: spec shape validation and the
 * kind / fractions parsing. The {@code build()} happy path needs the platform type provider and is
 * covered by the e2e suite.
 */
public class MetadataTypeBuilderTest
{
    private static JsonElement json(String s)
    {
        return JsonParser.parseString(s);
    }

    @Test
    public void testValidShapeAccepted()
    {
        assertNull(MetadataTypeBuilder.validateShape(json("{\"types\":[{\"kind\":\"String\"}]}"))); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.validateShape(
            json("{\"types\":[{\"kind\":\"String\",\"length\":50,\"fixed\":true}," //$NON-NLS-1$
                + "{\"kind\":\"Number\",\"precision\":10,\"scale\":2,\"nonNegative\":true}," //$NON-NLS-1$
                + "{\"kind\":\"Date\",\"fractions\":\"DateTime\"},{\"kind\":\"Boolean\"}," //$NON-NLS-1$
                + "{\"kind\":\"Ref\",\"ref\":\"Catalog.X\"}]}"))); //$NON-NLS-1$
    }

    @Test
    public void testNullAndNonObjectRejected()
    {
        assertNotNull(MetadataTypeBuilder.validateShape(null));
        assertNotNull(MetadataTypeBuilder.validateShape(json("[]"))); //$NON-NLS-1$
        assertNotNull(MetadataTypeBuilder.validateShape(json("\"String\""))); //$NON-NLS-1$
    }

    @Test
    public void testMissingOrEmptyTypesRejected()
    {
        assertNotNull(MetadataTypeBuilder.validateShape(json("{}"))); //$NON-NLS-1$
        assertNotNull(MetadataTypeBuilder.validateShape(json("{\"types\":[]}"))); //$NON-NLS-1$
        assertNotNull(MetadataTypeBuilder.validateShape(json("{\"types\":\"String\"}"))); //$NON-NLS-1$
    }

    @Test
    public void testMalformedItemRejected()
    {
        assertNotNull(MetadataTypeBuilder.validateShape(json("{\"types\":[\"String\"]}"))); //$NON-NLS-1$
        assertNotNull(MetadataTypeBuilder.validateShape(json("{\"types\":[{}]}"))); //$NON-NLS-1$
        assertNotNull(MetadataTypeBuilder.validateShape(json("{\"types\":[{\"kind\":\"\"}]}"))); //$NON-NLS-1$
    }

    @Test
    public void testStringItemRejectsNumberMember()
    {
        assertUnknownMember("{\"types\":[{\"kind\":\"String\",\"precision\":10}]}", //$NON-NLS-1$
            "precision", 0, "kind, length, fixed"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNumberItemRejectsStringMember()
    {
        // A real member of ANOTHER kind is just as invalid as an invented member.
        assertUnknownMember("{\"types\":[{\"kind\":\"Number\",\"length\":10}]}", //$NON-NLS-1$
            "length", 0, "kind, precision, scale, nonNegative"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNumberItemRejectsXmlSpellingsNestedShapeAndBogusMember()
    {
        for (String member : new String[] {
            "Digits", "FractionDigits", "AllowedSign", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "digits", "fractionDigits", "allowedSign", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "numberQualifiers", "zzz_bogus_member"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertUnknownMember("{\"types\":[{\"kind\":\"Number\",\"" + member + "\":{}}]}", //$NON-NLS-1$ //$NON-NLS-2$
                member, 0, "kind, precision, scale, nonNegative"); //$NON-NLS-1$
        }
    }

    @Test
    public void testDateItemRejectsNumberMember()
    {
        assertUnknownMember("{\"types\":[{\"kind\":\"Date\",\"scale\":2}]}", //$NON-NLS-1$
            "scale", 0, "kind, fractions"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBooleanItemRejectsQualifierAtItsCompositeIndex()
    {
        assertUnknownMember("{\"types\":[{\"kind\":\"String\"},{\"kind\":\"Boolean\",\"fixed\":true}]}", //$NON-NLS-1$
            "fixed", 1, "kind"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testReferenceItemRejectsPrimitiveMember()
    {
        assertUnknownMember("{\"types\":[{\"kind\":\"CatalogRef\",\"ref\":\"Catalog\",\"length\":10}]}", //$NON-NLS-1$
            "length", 0, "kind, ref"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertUnknownMember(String spec, String member, int index, String accepted)
    {
        assertEquals("Unknown member '" + member + "' in type.types[" + index //$NON-NLS-1$ //$NON-NLS-2$
            + "]. Accepted members: " + accepted + ". Remove '" + member + "' or use one of them.", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            MetadataTypeBuilder.validateShape(json(spec)));
    }

    @Test
    public void testNormalizePrimitive()
    {
        assertEquals("String", MetadataTypeBuilder.normalizePrimitive("string")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("String", MetadataTypeBuilder.normalizePrimitive("String")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Number", MetadataTypeBuilder.normalizePrimitive("NUMBER")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Boolean", MetadataTypeBuilder.normalizePrimitive("bool")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Boolean", MetadataTypeBuilder.normalizePrimitive("boolean")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Date", MetadataTypeBuilder.normalizePrimitive("date")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(MetadataTypeBuilder.normalizePrimitive("CatalogRef")); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.normalizePrimitive("nonsense")); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.normalizePrimitive(null));
        // ValueStorage/UUID are NOT legacy primitives - they go through platformSimpleTypeCandidates,
        // never normalizePrimitive (issue #279); the two mechanisms must not overlap.
        assertNull(MetadataTypeBuilder.normalizePrimitive("ValueStorage")); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.normalizePrimitive("UUID")); //$NON-NLS-1$
    }

    // ---- ValueStorage / UUID platform simple types (issue #279) -----------------------------------

    @Test
    public void testPlatformSimpleTypeCandidates()
    {
        assertArrayEquals(new String[] { "ValueStorage" }, //$NON-NLS-1$
            MetadataTypeBuilder.platformSimpleTypeCandidates("ValueStorage")); //$NON-NLS-1$
        assertArrayEquals(new String[] { "ValueStorage" }, //$NON-NLS-1$
            MetadataTypeBuilder.platformSimpleTypeCandidates("valuestorage")); //$NON-NLS-1$
        assertArrayEquals(new String[] { "ValueStorage" }, //$NON-NLS-1$
            MetadataTypeBuilder.platformSimpleTypeCandidates("ХранилищеЗначения")); //$NON-NLS-1$

        assertArrayEquals(new String[] { "UUID", "UniqueIdentifier" }, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeBuilder.platformSimpleTypeCandidates("uuid")); //$NON-NLS-1$
        assertArrayEquals(new String[] { "UUID", "UniqueIdentifier" }, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeBuilder.platformSimpleTypeCandidates("UNIQUEIDENTIFIER")); //$NON-NLS-1$
        assertArrayEquals(new String[] { "UUID", "UniqueIdentifier" }, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeBuilder.platformSimpleTypeCandidates("уникальныйидентификатор")); //$NON-NLS-1$

        assertEquals(0, MetadataTypeBuilder.platformSimpleTypeCandidates("String").length); //$NON-NLS-1$
        assertEquals(0, MetadataTypeBuilder.platformSimpleTypeCandidates("nonsense").length); //$NON-NLS-1$
        assertEquals(0, MetadataTypeBuilder.platformSimpleTypeCandidates(null).length);
    }

    @Test
    public void testAddTypeValueStorageResolvesSingleCandidate()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Type valueStorageType = McoreFactory.eINSTANCE.createType();
        Mockito.doReturn(valueStorageType).when(provider).createProxy("ValueStorage"); //$NON-NLS-1$

        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"valuestorage\"}").getAsJsonObject(); //$NON-NLS-1$
        String err = MetadataTypeBuilder.addType(td, item, "valuestorage", provider, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNull(err);
        assertEquals(1, td.getTypes().size());
        assertSame(valueStorageType, td.getTypes().get(0));
    }

    @Test
    public void testAddTypeUuidCandidateLoopFirstWins()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Type uuidType = McoreFactory.eINSTANCE.createType();
        Mockito.doReturn(uuidType).when(provider).createProxy("UUID"); //$NON-NLS-1$

        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"UUID\"}").getAsJsonObject(); //$NON-NLS-1$
        String err = MetadataTypeBuilder.addType(td, item, "UUID", provider, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNull(err);
        assertEquals(1, td.getTypes().size());
        assertSame(uuidType, td.getTypes().get(0));
        // the first candidate resolved, so the second name must never even be tried
        Mockito.verify(provider, Mockito.never()).createProxy("UniqueIdentifier"); //$NON-NLS-1$
    }

    @Test
    public void testAddTypeUuidCandidateLoopFallsBackWhenFirstNameThrows()
    {
        // createProxy THROWS (not returns null) for a name the provider does not know (issue #262) -
        // the loop must catch it and try the next candidate name.
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalArgumentException("unknown name 'UUID'")) //$NON-NLS-1$
            .when(provider).createProxy("UUID"); //$NON-NLS-1$
        Type uniqueIdentifierType = McoreFactory.eINSTANCE.createType();
        Mockito.doReturn(uniqueIdentifierType).when(provider).createProxy("UniqueIdentifier"); //$NON-NLS-1$

        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"uuid\"}").getAsJsonObject(); //$NON-NLS-1$
        String err = MetadataTypeBuilder.addType(td, item, "uuid", provider, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNull(err);
        assertEquals(1, td.getTypes().size());
        assertSame(uniqueIdentifierType, td.getTypes().get(0));
    }

    @Test
    public void testAddTypeUuidAllCandidatesFailingIsActionableError()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Mockito.doThrow(new IllegalArgumentException("unknown name")) //$NON-NLS-1$
            .when(provider).createProxy("UUID"); //$NON-NLS-1$
        Mockito.doThrow(new IllegalArgumentException("unknown name")) //$NON-NLS-1$
            .when(provider).createProxy("UniqueIdentifier"); //$NON-NLS-1$

        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"uuid\"}").getAsJsonObject(); //$NON-NLS-1$
        String err = MetadataTypeBuilder.addType(td, item, "uuid", provider, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNotNull(err);
        assertTrue("the error must name every tried candidate", //$NON-NLS-1$
            err.contains("UUID") && err.contains("UniqueIdentifier")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testValueStorageItemRefusesStrayQualifierFields()
    {
        // This test used to require SUCCESS and merely assert that no StringQualifiers attached. That
        // encoded the silent-accept defect: ValueStorage consumes no qualifier, so the member is now
        // refused before any platform type is built.
        assertUnknownMember("{\"types\":[{\"kind\":\"ValueStorage\",\"length\":50}]}", //$NON-NLS-1$
            "length", 0, "kind"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testUnknownKindErrorListsValueStorageAndUuid()
    {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"nonsense\"}").getAsJsonObject(); //$NON-NLS-1$
        // A null provider is safe here: the unknown-kind branch is reached only after the platform
        // probe (issue #369) has answered "no such type", and that probe treats a missing provider as
        // "resolves nothing" rather than failing.
        String err = MetadataTypeBuilder.addType(td, item, "nonsense", null, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNotNull(err);
        assertTrue(err.contains("nonsense")); //$NON-NLS-1$
        assertTrue(err.contains("ValueStorage")); //$NON-NLS-1$
        assertTrue(err.contains("UUID")); //$NON-NLS-1$
    }

    // ---- ValueTable / ValueTree in-memory collections (issue #295) --------------------------------

    @Test
    public void testPlatformCollectionTypeCandidates()
    {
        // Same no-qualifier mechanism as ValueStorage/UUID, so the same bilingual/case tolerance.
        assertArrayEquals(new String[] { "ValueTable" }, //$NON-NLS-1$
            MetadataTypeBuilder.platformSimpleTypeCandidates("ValueTable")); //$NON-NLS-1$
        assertArrayEquals(new String[] { "ValueTable" }, //$NON-NLS-1$
            MetadataTypeBuilder.platformSimpleTypeCandidates("VALUETABLE")); //$NON-NLS-1$
        assertArrayEquals(new String[] { "ValueTable" }, //$NON-NLS-1$
            MetadataTypeBuilder.platformSimpleTypeCandidates("ТаблицаЗначений")); //$NON-NLS-1$

        assertArrayEquals(new String[] { "ValueTree" }, //$NON-NLS-1$
            MetadataTypeBuilder.platformSimpleTypeCandidates("valuetree")); //$NON-NLS-1$
        assertArrayEquals(new String[] { "ValueTree" }, //$NON-NLS-1$
            MetadataTypeBuilder.platformSimpleTypeCandidates("ДеревоЗначений")); //$NON-NLS-1$

        // A collection kind is not a legacy primitive - the two mechanisms must not overlap.
        assertNull(MetadataTypeBuilder.normalizePrimitive("ValueTable")); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.normalizePrimitive("ValueTree")); //$NON-NLS-1$
    }

    @Test
    public void testAddTypeValueTableResolves()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Type valueTableType = McoreFactory.eINSTANCE.createType();
        Mockito.doReturn(valueTableType).when(provider).createProxy("ValueTable"); //$NON-NLS-1$

        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"ТаблицаЗначений\"}").getAsJsonObject(); //$NON-NLS-1$
        String err = MetadataTypeBuilder.addType(td, item, "ТаблицаЗначений", provider, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNull(err);
        assertEquals(1, td.getTypes().size());
        assertSame(valueTableType, td.getTypes().get(0));
    }

    @Test
    public void testAddTypeValueTreeResolves()
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        Type valueTreeType = McoreFactory.eINSTANCE.createType();
        Mockito.doReturn(valueTreeType).when(provider).createProxy("ValueTree"); //$NON-NLS-1$

        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"ValueTree\"}").getAsJsonObject(); //$NON-NLS-1$
        String err = MetadataTypeBuilder.addType(td, item, "ValueTree", provider, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNull(err);
        assertEquals(1, td.getTypes().size());
        assertSame(valueTreeType, td.getTypes().get(0));
    }

    @Test
    public void testIsCollectionKind()
    {
        assertTrue(MetadataTypeBuilder.isCollectionKind("ValueTable")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.isCollectionKind("ТаблицаЗначений")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.isCollectionKind("valuetree")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.isCollectionKind("ДеревоЗначений")); //$NON-NLS-1$
        // the OTHER no-qualifier kinds are persistable, so they are not collections
        assertFalse(MetadataTypeBuilder.isCollectionKind("ValueStorage")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.isCollectionKind("UUID")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.isCollectionKind("String")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.isCollectionKind(null));
    }

    @Test
    public void testAddTypeCollectionRefusedOnStoredMetadata()
    {
        // EDT does NOT catch this: a ValueTable written into a .mdo attribute survives a full
        // revalidation and only breaks later, in the platform (verified live for #295). So the
        // refusal has to come from here - and it must say where the kind IS allowed.
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);

        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"ValueTable\"}").getAsJsonObject(); //$NON-NLS-1$
        String err = MetadataTypeBuilder.addType(td, item, "ValueTable", provider, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNotNull("a stored metadata feature must refuse an in-memory collection", err); //$NON-NLS-1$
        assertTrue(err.contains("ValueTable")); //$NON-NLS-1$
        assertTrue("the error must point at the form attribute FQN shape", //$NON-NLS-1$
            err.contains("Form.FormName.Attribute")); //$NON-NLS-1$
        assertTrue("the error must offer the persistable alternative", //$NON-NLS-1$
            err.contains("ValueStorage")); //$NON-NLS-1$
        assertTrue("nothing may be added when the kind is refused", td.getTypes().isEmpty()); //$NON-NLS-1$
        // refused BEFORE any platform call
        Mockito.verify(provider, Mockito.never()).createProxy(Mockito.anyString());
    }

    @Test
    public void testUnknownKindErrorListsCollectionKinds()
    {
        // The unknown-kind message is the ONLY inventory an agent has - it must advertise the
        // collection kinds too, or ValueTable stays undiscoverable (the very complaint in #295).
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"nonsense\"}").getAsJsonObject(); //$NON-NLS-1$
        String err = MetadataTypeBuilder.addType(td, item, "nonsense", null, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNotNull(err);
        assertTrue(err.contains("ValueTable")); //$NON-NLS-1$
        assertTrue(err.contains("ValueTree")); //$NON-NLS-1$
    }

    @Test
    public void testParseFractions()
    {
        assertEquals(DateFractions.DATE, MetadataTypeBuilder.parseFractions("Date")); //$NON-NLS-1$
        assertEquals(DateFractions.TIME, MetadataTypeBuilder.parseFractions("time")); //$NON-NLS-1$
        assertEquals(DateFractions.DATE_TIME, MetadataTypeBuilder.parseFractions("DateTime")); //$NON-NLS-1$
        assertEquals(DateFractions.DATE_TIME, MetadataTypeBuilder.parseFractions(null));
        assertEquals(DateFractions.DATE_TIME, MetadataTypeBuilder.parseFractions("weird")); //$NON-NLS-1$
    }

    @Test
    public void testIsRefKind()
    {
        assertTrue(MetadataTypeBuilder.isRefKind("Ref")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.isRefKind("ref")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.isRefKind("CatalogRef")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.isRefKind("documentref")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.isRefKind("String")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.isRefKind("Reference")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.isRefKind(null));
    }

    @Test
    public void testHasObjectFormMainAttribute()
    {
        // Object-form types (a <Type>Object main attribute on their object form) - issue #208 gate.
        assertTrue(MetadataTypeBuilder.hasObjectFormMainAttribute("Catalog")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasObjectFormMainAttribute("Document")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasObjectFormMainAttribute("ChartOfCharacteristicTypes")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasObjectFormMainAttribute("ChartOfAccounts")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasObjectFormMainAttribute("ChartOfCalculationTypes")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasObjectFormMainAttribute("ExchangePlan")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasObjectFormMainAttribute("BusinessProcess")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasObjectFormMainAttribute("Task")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasObjectFormMainAttribute("Report")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.hasObjectFormMainAttribute("DataProcessor")); //$NON-NLS-1$
        // Record-based owners (registers) and other non-object types carry NO <Type>Object attribute.
        assertFalse(MetadataTypeBuilder.hasObjectFormMainAttribute("InformationRegister")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.hasObjectFormMainAttribute("AccumulationRegister")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.hasObjectFormMainAttribute("AccountingRegister")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.hasObjectFormMainAttribute("CalculationRegister")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.hasObjectFormMainAttribute("Constant")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.hasObjectFormMainAttribute("Enum")); //$NON-NLS-1$
        // The gate expects the canonical English-singular token (the caller resolves it first), so a
        // Russian / plural spelling is NOT recognized here, and null is safe.
        assertFalse(MetadataTypeBuilder.hasObjectFormMainAttribute("Catalogs")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.hasObjectFormMainAttribute(null));
    }

    @Test
    public void testObjectTypeGracefulWithoutModelOwner()
    {
        // objectType now takes the owner MdObject and reads its OWN produced object type
        // (MdClassUtil.getProducedTypes -> BasicDbObjectTypes.getObjectType). It must NEVER throw and must
        // return null for an owner that cannot supply an object type: a null owner, or a non-MdObject
        // EObject. The REAL value-type build needs a model-resolved owner with computed produced-types
        // derived data, so the byte-exact value type (<Type>Object.<Name>) is proven by the e2e/live
        // byte-diff, not headless here (issue #208).
        assertNull(MetadataTypeBuilder.objectType(null));
        EObject notAnMdObject = EcoreFactory.eINSTANCE.createEObject();
        assertNull(MetadataTypeBuilder.objectType(notAnMdObject));
    }

    // ---- extension-adopt hint on an unresolved reference target (issue #262 "Мелочь (UX)") ------

    @Test
    public void testExtensionAdoptHintOnlyForExtensionProject()
    {
        assertEquals("", MetadataTypeBuilder.extensionAdoptHint(false)); //$NON-NLS-1$
        String hint = MetadataTypeBuilder.extensionAdoptHint(true);
        assertTrue("the hint must point at adopt_metadata_object", //$NON-NLS-1$
            hint.contains("adopt_metadata_object")); //$NON-NLS-1$
        assertTrue("the hint must mention the base configuration", hint.contains("base")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAddTypeUnresolvedRefKeepsSentinelAndAppendsHintOnlyForExtension()
    {
        // The Ref branch never touches `provider` (only the primitive branch does), so this exercises
        // the real not-found path headlessly, with no registered platform type provider. The sentinel
        // "Cannot resolve the reference target" must stay a continuous substring either way (an e2e
        // regex matches it); the adopt hint is appended ONLY for an extension project.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"Ref\",\"ref\":\"Catalog.NoSuchThing\"}").getAsJsonObject(); //$NON-NLS-1$

        String baseErr = MetadataTypeBuilder.addType(td, item, "Ref", null, config, false, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.METADATA);
        assertNotNull(baseErr);
        assertTrue("the sentinel must be present", //$NON-NLS-1$
            baseErr.contains("Cannot resolve the reference target")); //$NON-NLS-1$
        assertFalse("a base-configuration project must get no adopt hint", //$NON-NLS-1$
            baseErr.contains("adopt_metadata_object")); //$NON-NLS-1$

        String extErr = MetadataTypeBuilder.addType(td, item, "Ref", null, config, true, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.METADATA);
        assertNotNull(extErr);
        assertTrue("the sentinel must stay a continuous substring when the hint is appended", //$NON-NLS-1$
            extErr.contains("Cannot resolve the reference target")); //$NON-NLS-1$
        assertTrue("an extension project must get the adopt hint", //$NON-NLS-1$
            extErr.contains("adopt_metadata_object")); //$NON-NLS-1$
    }

    // ---- the form-attribute platform-type vocabulary (issue #369) --------------------------------
    //
    // A form attribute's type is not a short fixed list: a production configuration uses ~30 distinct
    // platform types on form attributes (ValueList, SpreadsheetDocument, Chart, StandardPeriod, ...).
    // The builder therefore asks the PLATFORM whether the kind names a type, instead of carrying a
    // catalogue that will always lag. These tests pin that probe and both of its gates.

    /** A provider that knows exactly {@code name} - the shape the real one has for a real type. */
    private static IEObjectProvider providerKnowing(String name, Type answer)
    {
        IEObjectProvider provider = Mockito.mock(IEObjectProvider.class);
        // The real provider THROWS for a name it does not know (AbstractEObjectProvider.createProxy),
        // it does not return null - so the probe must survive the throw, not just a null.
        Mockito.doThrow(new IllegalArgumentException("Can't create proxy for unknown name")) //$NON-NLS-1$
            .when(provider).createProxy(Mockito.anyString());
        Mockito.doReturn(answer).when(provider).createProxy(name);
        return provider;
    }

    private static String addKind(String kind, IEObjectProvider provider, TypeDescription td,
        MetadataTypeBuilder.TypeTarget target)
    {
        JsonObject item = json("{\"kind\":\"" + kind + "\"}").getAsJsonObject(); //$NON-NLS-1$ //$NON-NLS-2$
        return MetadataTypeBuilder.addType(td, item, kind, provider,
            MdClassFactory.eINSTANCE.createConfiguration(), false, target);
    }

    @Test
    public void testFormAttributeAcceptsAnyPlatformTypeTheVersionKnows()
    {
        // ValueList is issue #369 itself: a type every real configuration uses, which the old fixed
        // vocabulary called "Unknown type kind".
        Type valueList = McoreFactory.eINSTANCE.createType();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String err = addKind("ValueList", providerKnowing("ValueList", valueList), td, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNull(err);
        assertEquals(1, td.getTypes().size());
        assertSame(valueList, td.getTypes().get(0));
    }

    @Test
    public void testFormAttributeAcceptsTheRussianSpellingOfAPlatformType()
    {
        // The platform type provider indexes every type under BOTH names, so the Russian spelling
        // resolves through the SAME probe - this bundle carries no ru->en alias table for it.
        // SpisokZnachenij = ValueList.
        String ruValueList = new String(new int[] {0x0421, 0x043f, 0x0438, 0x0441, 0x043e, 0x043a,
            0x0417, 0x043d, 0x0430, 0x0447, 0x0435, 0x043d, 0x0438, 0x0439}, 0, 14);
        Type valueList = McoreFactory.eINSTANCE.createType();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String err = addKind(ruValueList, providerKnowing(ruValueList, valueList), td,
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNull(err);
        assertSame(valueList, td.getTypes().get(0));
    }

    @Test
    public void testStoredMetadataRefusesAFormOnlyPlatformType()
    {
        Type spreadsheet = McoreFactory.eINSTANCE.createType();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String err = addKind("SpreadsheetDocument", //$NON-NLS-1$
            providerKnowing("SpreadsheetDocument", spreadsheet), td, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNotNull("a stored metadata feature must refuse a form-only platform type", err); //$NON-NLS-1$
        assertTrue(err.contains("SpreadsheetDocument")); //$NON-NLS-1$
        assertTrue("the refusal must point at the form attribute FQN shape", //$NON-NLS-1$
            err.contains("Form.FormName.Attribute")); //$NON-NLS-1$
        assertFalse("a RECOGNIZED type must not be reported as unknown - that wording sent " //$NON-NLS-1$
            + "callers hunting a spelling mistake that was not there (issue #369)", //$NON-NLS-1$
            err.contains("Unknown type kind")); //$NON-NLS-1$
        assertTrue("nothing may be added when the kind is refused", td.getTypes().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testDcsParameterRefusesAFormOnlyPlatformTypeInItsOwnWords()
    {
        Type chart = McoreFactory.eINSTANCE.createType();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String err = addKind("Chart", providerKnowing("Chart", chart), td, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeBuilder.TypeTarget.DCS_PARAMETER);

        assertNotNull(err);
        assertTrue(err.contains("data-composition parameter")); //$NON-NLS-1$
        assertFalse("a DCS parameter is neither stored nor served by ValueStorage, so it must not " //$NON-NLS-1$
            + "repeat the stored-metadata advice", err.contains("ValueStorage")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testDynamicListKindRefusedEvenOnAFormAttribute()
    {
        // DynamicList resolves like any other platform type, but a list is not just a value type -
        // it needs its query, which the queryText property owns (and which prompts its own consent).
        Type dynamicList = McoreFactory.eINSTANCE.createType();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String err = addKind("DynamicList", providerKnowing("DynamicList", dynamicList), td, //$NON-NLS-1$ //$NON-NLS-2$
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNotNull("a bare DynamicList type spec would build a list with no query", err); //$NON-NLS-1$
        assertTrue("the refusal must name the property that DOES build one", //$NON-NLS-1$
            err.contains("queryText")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testIsDynamicListKind()
    {
        assertTrue(MetadataTypeBuilder.isDynamicListKind("DynamicList")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.isDynamicListKind("dynamiclist")); //$NON-NLS-1$
        // DinamicheskijSpisok = DynamicList
        assertTrue(MetadataTypeBuilder.isDynamicListKind(new String(new int[] {0x0414, 0x0438, 0x043d,
            0x0430, 0x043c, 0x0438, 0x0447, 0x0435, 0x0441, 0x043a, 0x0438, 0x0439, 0x0421, 0x043f,
            0x0438, 0x0441, 0x043e, 0x043a}, 0, 18)));
        assertFalse(MetadataTypeBuilder.isDynamicListKind("ValueList")); //$NON-NLS-1$
        assertFalse(MetadataTypeBuilder.isDynamicListKind(null));
    }

    @Test
    public void testATypeTheVersionDoesNotKnowStaysUnknown()
    {
        // The probe must not turn every typo into "a real type on the wrong target": a name the
        // platform does not know is still an unknown kind, and the message still lists the vocabulary.
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String err = addKind("NoSuchPlatformType", //$NON-NLS-1$
            providerKnowing("ValueList", McoreFactory.eINSTANCE.createType()), td, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNotNull(err);
        assertTrue(err.contains("Unknown type kind")); //$NON-NLS-1$
        assertTrue("the message is the only inventory an agent has - it must say a form attribute " //$NON-NLS-1$
            + "takes platform type names too", err.contains("ValueList")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testCuratedKindsKeepTheirOwnGateAheadOfTheProbe()
    {
        // ValueTable is BOTH a curated collection kind and a resolvable platform type. The curated
        // branch must win, so the stored-metadata refusal keeps its collection wording (and its
        // "refused before any platform call" guarantee) instead of the generic form-only one.
        IEObjectProvider provider = providerKnowing("ValueTable", McoreFactory.eINSTANCE.createType()); //$NON-NLS-1$
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String err = addKind("ValueTable", provider, td, MetadataTypeBuilder.TypeTarget.METADATA); //$NON-NLS-1$

        assertNotNull(err);
        assertTrue("the collection wording must survive the platform probe", //$NON-NLS-1$
            err.contains("IN-MEMORY collection")); //$NON-NLS-1$
        Mockito.verify(provider, Mockito.never()).createProxy(Mockito.anyString());
    }
}

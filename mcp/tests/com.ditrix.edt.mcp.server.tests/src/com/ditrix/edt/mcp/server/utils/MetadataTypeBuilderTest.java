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

import java.util.Collections;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;
import org.mockito.Mockito;

import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.mcore.TypeItem;
import com._1c.g5.v8.dt.metadata.mdclass.CatalogAttribute;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdtype.MdType;
import com._1c.g5.v8.dt.platform.IEObjectProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests the platform-independent parts of {@link MetadataTypeBuilder}: spec shape validation and the
 * kind / fractions parsing. Primitive/platform {@code build()} happy paths need the platform type
 * provider and are covered by e2e; the model-owned DefinedType path is reachable headlessly.
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
                + "{\"kind\":\"Ref\",\"ref\":\"Catalog.X\"}," //$NON-NLS-1$
                + "{\"kind\":\"DefinedType\",\"ref\":\"MoneyAmount\"}," //$NON-NLS-1$
                + "{\"kind\":\"DefinedType.MoneyAmount\"}]}"))); //$NON-NLS-1$
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

    @Test
    public void testKindMustBeANonEmptyString()
    {
        for (String value : new String[] { "1", "true", "{}", "[]", "null", "\"   \"" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertInvalidMember("{\"types\":[{\"kind\":" + value + "}]}", //$NON-NLS-1$ //$NON-NLS-2$
                "kind", "a non-empty string"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testStringQualifierValuesAndCombinationsAreValidated()
    {
        for (String value : new String[] { "\"50\"", "1.5", "-1", "1025", "{}", "true" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertInvalidMember("{\"types\":[{\"kind\":\"String\",\"length\":" + value + "}]}", //$NON-NLS-1$ //$NON-NLS-2$
                "length", "integer from 0 to 1024"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String value : new String[] { "\"true\"", "1", "{}", "null" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertInvalidMember("{\"types\":[{\"kind\":\"String\",\"length\":10,\"fixed\":" //$NON-NLS-1$
                + value + "}]}", "fixed", "true or false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        assertInvalidMember("{\"types\":[{\"kind\":\"String\",\"fixed\":true}]}", //$NON-NLS-1$
            "fixed", "together with a 'length' member"); //$NON-NLS-1$ //$NON-NLS-2$
        assertInvalidMember("{\"types\":[{\"kind\":\"String\",\"length\":0,\"fixed\":true}]}", //$NON-NLS-1$
            "fixed", "true only with a positive 'length'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(MetadataTypeBuilder.validateShape(json(
            "{\"types\":[{\"kind\":\"String\",\"length\":0,\"fixed\":false}," //$NON-NLS-1$
                + "{\"kind\":\"String\",\"length\":1024,\"fixed\":true}]}"))); //$NON-NLS-1$
    }

    @Test
    public void testNumberQualifierValuesRangesAndCombinationsAreValidated()
    {
        for (String value : new String[] { "\"10\"", "1.5", "0", "39", "{}", "true" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertInvalidMember("{\"types\":[{\"kind\":\"Number\",\"precision\":" + value + "}]}", //$NON-NLS-1$ //$NON-NLS-2$
                "precision", "integer from 1 to 38"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String value : new String[] { "\"2\"", "1.5", "-1", "11", "{}", "true" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertInvalidMember("{\"types\":[{\"kind\":\"Number\",\"precision\":10,\"scale\":" //$NON-NLS-1$
                + value + "}]}", "scale", "integer from 0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        assertInvalidMember("{\"types\":[{\"kind\":\"Number\",\"scale\":2}]}", //$NON-NLS-1$
            "scale", "together with a 'precision' member"); //$NON-NLS-1$ //$NON-NLS-2$
        for (String value : new String[] { "\"true\"", "1", "{}", "null" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertInvalidMember("{\"types\":[{\"kind\":\"Number\",\"precision\":10," //$NON-NLS-1$
                + "\"nonNegative\":" + value + "}]}", "nonNegative", "true or false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        assertInvalidMember("{\"types\":[{\"kind\":\"Number\",\"nonNegative\":true}]}", //$NON-NLS-1$
            "nonNegative", "together with a 'precision' member"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(MetadataTypeBuilder.validateShape(json(
            "{\"types\":[{\"kind\":\"Number\",\"precision\":1,\"scale\":0," //$NON-NLS-1$
                + "\"nonNegative\":false},{\"kind\":\"Number\",\"precision\":38," //$NON-NLS-1$
                + "\"scale\":38,\"nonNegative\":true}]}"))); //$NON-NLS-1$
    }

    @Test
    public void testDateFractionsValueIsValidatedForTypeAndEnumMembership()
    {
        for (String value : new String[] { "\"bogus\"", "\"\"", "1", "true", "{}", "null" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        {
            assertInvalidMember("{\"types\":[{\"kind\":\"Date\",\"fractions\":" + value + "}]}", //$NON-NLS-1$ //$NON-NLS-2$
                "fractions", "DateTime, Date, or Time"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        assertNull(MetadataTypeBuilder.validateShape(json(
            "{\"types\":[{\"kind\":\"Date\",\"fractions\":\"datetime\"}," //$NON-NLS-1$
                + "{\"kind\":\"Date\",\"fractions\":\" Date \"}," //$NON-NLS-1$
                + "{\"kind\":\"Date\",\"fractions\":\"TIME\"}]}"))); //$NON-NLS-1$
    }

    @Test
    public void testReferenceTargetMustBeANonEmptyString()
    {
        // <Type>Ref keeps its OWN grammar, unchanged by the produced-type family: a ref is required,
        // and an omitted one is the first case in this list. The family covers the Object / Manager /
        // Record / RecordSet / ... types a Ref cannot name, and deliberately does not include Ref itself.
        for (String suffix : new String[] { "", ",\"ref\":\"\"", ",\"ref\":\"   \"", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            ",\"ref\":1", ",\"ref\":true", ",\"ref\":{}", ",\"ref\":null" }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertInvalidMember("{\"types\":[{\"kind\":\"CatalogRef\"" + suffix + "}]}", //$NON-NLS-1$ //$NON-NLS-2$
                "ref", "non-empty reference target string"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testDefinedTypeKindRequiresANonEmptyReferenceTarget()
    {
        String error = MetadataTypeBuilder.validateShape(
            json("{\"types\":[{\"kind\":\"DefinedType\"}]}")); //$NON-NLS-1$

        assertNotNull(error);
        assertTrue(error, error.contains("Invalid member 'ref'")); //$NON-NLS-1$
        assertTrue(error, error.contains("MoneyAmount")); //$NON-NLS-1$
        assertTrue(error, error.contains("DefinedType")); //$NON-NLS-1$
    }

    private static void assertUnknownMember(String spec, String member, int index, String accepted)
    {
        assertEquals("Unknown member '" + member + "' in type.types[" + index //$NON-NLS-1$ //$NON-NLS-2$
            + "]. Accepted members: " + accepted + ". Remove '" + member + "' or use one of them.", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            MetadataTypeBuilder.validateShape(json(spec)));
    }

    private static void assertInvalidMember(String spec, String member, String expected)
    {
        String error = MetadataTypeBuilder.validateShape(json(spec));
        assertNotNull(spec, error);
        assertTrue(error, error.contains("Invalid member '" + member + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.contains(expected));
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
        assertTrue(err.contains("DefinedType")); //$NON-NLS-1$
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
        assertTrue(MetadataTypeBuilder.isRefKind("DefinedType")); //$NON-NLS-1$
        assertTrue(MetadataTypeBuilder.isRefKind(russianDefinedTypeToken()));
        assertFalse(MetadataTypeBuilder.isRefKind("DefinedType.MoneyAmount")); //$NON-NLS-1$
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

    // ---- model-owned produced types (issue #543) ------------------------------------------------

    @Test
    public void testProducedTypeKindSplitUsesLongestBilingualMetadataPrefix()
    {
        assertProducedTypeSplit("DocumentObject", "Document", "Document", "Object", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "objectType"); //$NON-NLS-1$
        assertProducedTypeSplit("ChartOfCalculationTypesObject", "ChartOfCalculationTypes", //$NON-NLS-1$ //$NON-NLS-2$
            "ChartOfCalculationTypes", "Object", "objectType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertProducedTypeSplit("InformationRegisterRecordSet", "InformationRegister", //$NON-NLS-1$ //$NON-NLS-2$
            "InformationRegister", "RecordSet", "recordSetType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertProducedTypeSplit("InformationRegisterRecordManager", "InformationRegister", //$NON-NLS-1$ //$NON-NLS-2$
            "InformationRegister", "RecordManager", "recordManagerType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertProducedTypeSplit("ConstantValueManager", "Constant", "Constant", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "ValueManager", "valueManagerType"); //$NON-NLS-1$ //$NON-NLS-2$
        // A bare suffix carries no metadata prefix, so it names no produced type. Both entries whose
        // spelling ENDS in a shorter entry are checked, because that is where a shortest-suffix match
        // would silently invent a prefix ("Value", "Record") the caller never typed.
        assertNull("a bare suffix carries no metadata prefix", //$NON-NLS-1$
            MetadataTypeBuilder.splitProducedTypeKind("ValueManager")); //$NON-NLS-1$
        assertNull("a bare suffix carries no metadata prefix", //$NON-NLS-1$
            MetadataTypeBuilder.splitProducedTypeKind("RecordManager")); //$NON-NLS-1$

        String russianDocument = "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442"; //$NON-NLS-1$
        String russianObject = "\u041E\u0431\u044A\u0435\u043A\u0442"; //$NON-NLS-1$
        assertProducedTypeSplit(russianDocument + russianObject, russianDocument, "Document", //$NON-NLS-1$
            "Object", "objectType"); //$NON-NLS-1$ //$NON-NLS-2$

        String russianInformationRegister = "\u0420\u0435\u0433\u0438\u0441\u0442\u0440" //$NON-NLS-1$
            + "\u0421\u0432\u0435\u0434\u0435\u043D\u0438\u0439"; //$NON-NLS-1$
        String russianRecordManager = "\u041C\u0435\u043D\u0435\u0434\u0436\u0435\u0440" //$NON-NLS-1$
            + "\u0417\u0430\u043F\u0438\u0441\u0438"; //$NON-NLS-1$
        assertProducedTypeSplit(russianInformationRegister + russianRecordManager,
            russianInformationRegister, "InformationRegister", "RecordManager", //$NON-NLS-1$ //$NON-NLS-2$
            "recordManagerType"); //$NON-NLS-1$
    }

    @Test
    public void testInformationRegisterRecordFamilySplitsToExactFeaturesInEnglish()
    {
        assertProducedTypeSplit("InformationRegisterRecord", "InformationRegister", //$NON-NLS-1$ //$NON-NLS-2$
            "InformationRegister", "Record", "recordType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertProducedTypeSplit("InformationRegisterRecordSet", "InformationRegister", //$NON-NLS-1$ //$NON-NLS-2$
            "InformationRegister", "RecordSet", "recordSetType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertProducedTypeSplit("InformationRegisterRecordManager", "InformationRegister", //$NON-NLS-1$ //$NON-NLS-2$
            "InformationRegister", "RecordManager", "recordManagerType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertProducedTypeSplit("InformationRegisterRecordKey", "InformationRegister", //$NON-NLS-1$ //$NON-NLS-2$
            "InformationRegister", "RecordKey", "recordKeyType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testInformationRegisterRecordFamilySplitsToExactFeaturesInRussian()
    {
        String russianInformationRegister = "\u0420\u0435\u0433\u0438\u0441\u0442\u0440" //$NON-NLS-1$
            + "\u0421\u0432\u0435\u0434\u0435\u043D\u0438\u0439"; //$NON-NLS-1$
        String russianRecord = "\u0417\u0430\u043F\u0438\u0441\u044C"; //$NON-NLS-1$
        String russianRecordSet = "\u041D\u0430\u0431\u043E\u0440" //$NON-NLS-1$
            + "\u0417\u0430\u043F\u0438\u0441\u0435\u0439"; //$NON-NLS-1$
        String russianRecordManager = "\u041C\u0435\u043D\u0435\u0434\u0436\u0435\u0440" //$NON-NLS-1$
            + "\u0417\u0430\u043F\u0438\u0441\u0438"; //$NON-NLS-1$
        String russianRecordKey = "\u041A\u043B\u044E\u0447" //$NON-NLS-1$
            + "\u0417\u0430\u043F\u0438\u0441\u0438"; //$NON-NLS-1$

        assertProducedTypeSplit(russianInformationRegister + russianRecord,
            russianInformationRegister, "InformationRegister", "Record", "recordType"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertProducedTypeSplit(russianInformationRegister + russianRecordSet,
            russianInformationRegister, "InformationRegister", "RecordSet", //$NON-NLS-1$ //$NON-NLS-2$
            "recordSetType"); //$NON-NLS-1$
        assertProducedTypeSplit(russianInformationRegister + russianRecordManager,
            russianInformationRegister, "InformationRegister", "RecordManager", //$NON-NLS-1$ //$NON-NLS-2$
            "recordManagerType"); //$NON-NLS-1$
        assertProducedTypeSplit(russianInformationRegister + russianRecordKey,
            russianInformationRegister, "InformationRegister", "RecordKey", //$NON-NLS-1$ //$NON-NLS-2$
            "recordKeyType"); //$NON-NLS-1$
    }

    @Test
    public void testBareProducedSuffixIsReportedAsAnUnknownKindNotAPhantomPrefix()
    {
        // The observable half of the rule above. A caller who types the suffix alone must be told that
        // KIND is unknown - not be handed a complaint about "Record", a token absent from the request,
        // which is what a shortest-suffix match produces once RecordManager joins Manager in the table.
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String error = addKind("RecordManager", providerKnowing("NothingElse", //$NON-NLS-1$ //$NON-NLS-2$
            McoreFactory.eINSTANCE.createType()), td, MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

        assertNotNull(error);
        assertTrue(error, error.contains("RecordManager")); //$NON-NLS-1$
        assertFalse("the refusal must not blame a prefix the caller never typed: " + error, //$NON-NLS-1$
            error.contains("'Record'")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testProducedTypeShapesAcceptConcreteAndAbstractForms()
    {
        assertNull(MetadataTypeBuilder.validateShape(json(
            "{\"types\":[{\"kind\":\"DocumentObject\",\"ref\":\"Invoice\"}]}"))); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.validateShape(json(
            "{\"types\":[{\"kind\":\"ExchangePlanObject\"}]}"))); //$NON-NLS-1$
    }

    @Test
    public void testInlineDefinedTypeNameEndingInAProducedSuffixStillRefusesRef()
    {
        String error = MetadataTypeBuilder.validateShape(json(
            "{\"types\":[{\"kind\":\"DefinedType.PriceList\",\"ref\":\"DifferentType\"}]}")); //$NON-NLS-1$

        assertNotNull(error);
        assertTrue(error, error.contains("Unknown member 'ref'")); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.splitProducedTypeKind("DefinedType.PriceList")); //$NON-NLS-1$
        assertNull(MetadataTypeBuilder.splitProducedTypeKind(
            russianDefinedTypeToken() + ".PriceList")); //$NON-NLS-1$
        assertProducedTypeSplit("CatalogList", "Catalog", "Catalog", "List", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "listType"); //$NON-NLS-1$
    }

    @Test
    public void testRecalculationProducedTypeSplitsThroughTheNestedKindCatalogue()
    {
        assertProducedTypeSplit("RecalculationRecordSet", "Recalculation", "Recalculation", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "RecordSet", "recordSetType"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(MetadataTypeBuilder.splitProducedTypeKind(
            "RecalculationRecordSet").isNested()); //$NON-NLS-1$

        String russianRecalculation =
            "\u041F\u0435\u0440\u0435\u0440\u0430\u0441\u0447\u0435\u0442"; //$NON-NLS-1$
        String russianRecordSet = "\u041D\u0430\u0431\u043E\u0440" //$NON-NLS-1$
            + "\u0417\u0430\u043F\u0438\u0441\u0435\u0439"; //$NON-NLS-1$
        String russianKind = russianRecalculation + russianRecordSet;
        assertProducedTypeSplit(russianKind, russianRecalculation, "Recalculation", //$NON-NLS-1$
            "RecordSet", "recordSetType"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(MetadataTypeBuilder.splitProducedTypeKind(russianKind).isNested());
    }

    @Test
    public void testConcreteRecalculationProducedTypeIsRefusedByName()
    {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"RecalculationRecordSet\"," //$NON-NLS-1$
            + "\"ref\":\"CalculationRegister.R.Recalculation.Rc\"}").getAsJsonObject(); //$NON-NLS-1$

        String error = MetadataTypeBuilder.addType(td, item, "RecalculationRecordSet", null, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNotNull(error);
        assertTrue(error, error.contains("RecalculationRecordSet")); //$NON-NLS-1$
        assertTrue(error, error.contains("NESTED")); //$NON-NLS-1$
        assertTrue(error, error.contains(
            "Pass {kind:'RecalculationRecordSet'} without ref")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testFormAttributeProducedTypeShapeAcceptsRef()
    {
        MetadataTypeBuilder.Result result = MetadataTypeBuilder.build(json(
            "{\"types\":[{\"kind\":\"DocumentObject\",\"ref\":\"Invoice\"}]}"), //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(),
            Mockito.mock(com._1c.g5.v8.dt.platform.version.Version.class), false,
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNotNull(result.error);
        assertFalse(result.error, result.error.contains("Unknown member 'ref'")); //$NON-NLS-1$
    }

    @Test
    public void testConcreteProducedTypeSharesModelOwnedTypeForBareQualifiedAndRussianRefs()
    {
        assertConcreteProducedTypeSharesModelOwnedTypeForBareQualifiedAndRussianRefs(
            MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);
    }

    @Test
    public void testFormAttributeAcceptsConcreteAndAbstractProducedTypes()
    {
        assertConcreteProducedTypeSharesModelOwnedTypeForBareQualifiedAndRussianRefs(
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        for (String[] one : new String[][] {
            {"CatalogList", "listType"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CatalogSelection", "selectionType"} }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
            Type expected = McoreFactory.eINSTANCE.createType();
            seedProducedType(config, "Catalog", "Products", one[1], expected); //$NON-NLS-1$ //$NON-NLS-2$
            TypeDescription concreteTd = McoreFactory.eINSTANCE.createTypeDescription();
            JsonObject item = json("{\"kind\":\"" + one[0] //$NON-NLS-1$
                + "\",\"ref\":\"Products\"}").getAsJsonObject(); //$NON-NLS-1$

            String concreteError = MetadataTypeBuilder.addType(concreteTd, item, one[0], null,
                config, false, MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

            assertNull(one[0], concreteError);
            assertEquals(one[0], 1, concreteTd.getTypes().size());
            assertSame(one[0], expected, concreteTd.getTypes().get(0));
        }

        Type abstractObject = McoreFactory.eINSTANCE.createType();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        String error = addKind("ExchangePlanObject", //$NON-NLS-1$
            providerKnowing("ExchangePlanObject", abstractObject), td, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNull(error);
        assertEquals(1, td.getTypes().size());
        assertSame(abstractObject, td.getTypes().get(0));
    }

    @Test
    public void testConcreteProducedTypeUsesScopeAndKeepsConfigurationPathsEquivalent()
    {
        Configuration linkedParent = MdClassFactory.eINSTANCE.createConfiguration();
        Type expected = McoreFactory.eINSTANCE.createType();
        MdObject processor = seedProducedType("ExternalDataProcessor", "Processor", //$NON-NLS-1$ //$NON-NLS-2$
            "objectType", expected); //$NON-NLS-1$
        IExternalObjectProject externalProject = Mockito.mock(IExternalObjectProject.class);
        Mockito.when(externalProject.getExternalObjects())
            .thenReturn(Collections.singletonList(processor));
        MetadataScope scope = MetadataScope.ofExternalObjectProject(null, linkedParent,
            externalProject);

        for (String ref : new String[] {"Processor", "ExternalDataProcessor.Processor"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
            JsonObject item = json("{\"kind\":\"ExternalDataProcessorObject\",\"ref\":\"" //$NON-NLS-1$
                + ref + "\"}").getAsJsonObject(); //$NON-NLS-1$

            String error = MetadataTypeBuilder.addType(td, item,
                "ExternalDataProcessorObject", null, linkedParent, scope, false, //$NON-NLS-1$
                MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

            assertNull(ref, error);
            assertEquals(ref, 1, td.getTypes().size());
            assertSame(ref, expected, td.getTypes().get(0));
        }

        // A configuration project's explicit scope is the same direct-Configuration resolution the
        // builder used before scopes existed, and the old signature must derive that exact scope.
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type configurationExpected = McoreFactory.eINSTANCE.createType();
        seedProducedType(config, "Document", "Invoice", "objectType", configurationExpected); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonObject item = json(
            "{\"kind\":\"DocumentObject\",\"ref\":\"Document.Invoice\"}").getAsJsonObject(); //$NON-NLS-1$

        TypeDescription scoped = McoreFactory.eINSTANCE.createTypeDescription();
        String scopedError = MetadataTypeBuilder.addType(scoped, item, "DocumentObject", null, //$NON-NLS-1$
            config, MetadataScope.ofConfiguration(config), false,
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);
        assertNull(scopedError);
        assertSame(configurationExpected, scoped.getTypes().get(0));

        // This old signature is the path DcsWriter / PredefinedWriter keep using.
        TypeDescription scopeLess = McoreFactory.eINSTANCE.createTypeDescription();
        String scopeLessError = MetadataTypeBuilder.addType(scopeLess, item, "DocumentObject", null, //$NON-NLS-1$
            config, false, MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);
        assertNull(scopeLessError);
        assertSame(configurationExpected, scopeLess.getTypes().get(0));

        // A configuration scope already resolves against config. A genuine miss must keep the exact
        // pre-scope error through both signatures; no linked-configuration fallback applies here.
        for (String missingRef : new String[] {"Missing", "Document.Missing"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            JsonObject missing = json("{\"kind\":\"DocumentObject\",\"ref\":\"" //$NON-NLS-1$
                + missingRef + "\"}").getAsJsonObject(); //$NON-NLS-1$
            TypeDescription scopedMissing = McoreFactory.eINSTANCE.createTypeDescription();
            String scopedMissingError = MetadataTypeBuilder.addType(scopedMissing, missing,
                "DocumentObject", null, config, MetadataScope.ofConfiguration(config), false, //$NON-NLS-1$
                MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);
            TypeDescription scopeLessMissing = McoreFactory.eINSTANCE.createTypeDescription();
            String scopeLessMissingError = MetadataTypeBuilder.addType(scopeLessMissing, missing,
                "DocumentObject", null, config, false, //$NON-NLS-1$
                MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

            String expectedError = "Cannot resolve the reference target for kind 'DocumentObject' " //$NON-NLS-1$
                + "ref '" + missingRef + "'. Use {kind:'DocumentObject', ref:'Name'} or pass " //$NON-NLS-1$ //$NON-NLS-2$
                + "ref:'Document.Name', and check the object exists."; //$NON-NLS-1$
            assertEquals(missingRef, expectedError, scopedMissingError);
            assertEquals(missingRef, expectedError, scopeLessMissingError);
            assertTrue(scopedMissing.getTypes().isEmpty());
            assertTrue(scopeLessMissing.getTypes().isEmpty());
        }
    }

    @Test
    public void testExternalScopeResolvesLinkedConfigurationProducedTypeWithBareRef()
    {
        assertExternalScopeResolvesLinkedConfigurationProducedType("Products"); //$NON-NLS-1$
    }

    @Test
    public void testExternalScopeResolvesLinkedConfigurationProducedTypeWithQualifiedRef()
    {
        assertExternalScopeResolvesLinkedConfigurationProducedType("Catalog.Products"); //$NON-NLS-1$
    }

    private static void assertExternalScopeResolvesLinkedConfigurationProducedType(String ref)
    {
        Configuration linkedConfiguration = MdClassFactory.eINSTANCE.createConfiguration();
        Type expected = McoreFactory.eINSTANCE.createType();
        seedProducedType(linkedConfiguration, "Catalog", "Products", "objectType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            expected);
        IExternalObjectProject externalProject = Mockito.mock(IExternalObjectProject.class);
        Mockito.when(externalProject.getExternalObjects()).thenReturn(Collections.emptyList());
        MetadataScope scope = MetadataScope.ofExternalObjectProject(null, linkedConfiguration,
            externalProject);
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json("{\"kind\":\"CatalogObject\",\"ref\":\"" + ref + "\"}") //$NON-NLS-1$ //$NON-NLS-2$
            .getAsJsonObject();

        String error = MetadataTypeBuilder.addType(td, item, "CatalogObject", null, //$NON-NLS-1$
            linkedConfiguration, scope, false, MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNull(ref, error);
        assertEquals(ref, 1, td.getTypes().size());
        assertSame(ref, expected, td.getTypes().get(0));
    }

    private static void assertConcreteProducedTypeSharesModelOwnedTypeForBareQualifiedAndRussianRefs(
        MetadataTypeBuilder.TypeTarget typeTarget)
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type expected = McoreFactory.eINSTANCE.createType();
        seedProducedType(config, "Document", "Invoice", "objectType", expected); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        String russianDocument = "\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442"; //$NON-NLS-1$
        String russianObject = "\u041E\u0431\u044A\u0435\u043A\u0442"; //$NON-NLS-1$
        String[][] cases = {
            {"DocumentObject", "Invoice"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"DocumentObject", "Document.Invoice"}, //$NON-NLS-1$ //$NON-NLS-2$
            {russianDocument + russianObject, russianDocument + ".Invoice"} }; //$NON-NLS-1$

        for (String[] one : cases)
        {
            TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
            JsonObject item = new JsonObject();
            item.addProperty("kind", one[0]); //$NON-NLS-1$
            item.addProperty("ref", one[1]); //$NON-NLS-1$

            String error = MetadataTypeBuilder.addType(td, item, one[0], null, config, false,
                typeTarget);

            assertNull(one[0] + " / " + one[1], error); //$NON-NLS-1$
            assertEquals(1, td.getTypes().size());
            assertSame("the owner's model-owned produced Type must be shared", //$NON-NLS-1$
                expected, td.getTypes().get(0));
        }
    }

    @Test
    public void testConcreteInformationRegisterRecordManagerSharesModelOwnedType()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type expected = McoreFactory.eINSTANCE.createType();
        seedProducedType(config, "InformationRegister", "Stock", "recordManagerType", expected); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json(
            "{\"kind\":\"InformationRegisterRecordManager\",\"ref\":\"Stock\"}") //$NON-NLS-1$
                .getAsJsonObject();

        String error = MetadataTypeBuilder.addType(td, item, "InformationRegisterRecordManager", //$NON-NLS-1$
            null, config, false, MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

        assertNull(error);
        assertEquals(1, td.getTypes().size());
        assertSame(expected, td.getTypes().get(0));
    }

    @Test
    public void testConcreteInformationRegisterRecordResolvesOnFormAndIsRefusedForEventSource()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        Type expected = McoreFactory.eINSTANCE.createType();
        seedProducedType(config, "InformationRegister", "Prices", "recordType", expected); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonElement spec = json(
            "{\"types\":[{\"kind\":\"InformationRegisterRecord\",\"ref\":\"Prices\"}]}"); //$NON-NLS-1$
        JsonObject item = spec.getAsJsonObject().getAsJsonArray("types").get(0) //$NON-NLS-1$
            .getAsJsonObject();

        MetadataTypeBuilder.Result shapeResult = MetadataTypeBuilder.build(spec, config,
            Mockito.mock(com._1c.g5.v8.dt.platform.version.Version.class), false,
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);
        assertFalse(shapeResult.error,
            shapeResult.error != null && shapeResult.error.contains("Unknown member 'ref'")); //$NON-NLS-1$

        TypeDescription formTd = McoreFactory.eINSTANCE.createTypeDescription();
        String formError = MetadataTypeBuilder.addType(formTd, item,
            "InformationRegisterRecord", null, config, false, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.FORM_ATTRIBUTE);

        assertNull(formError);
        assertEquals(1, formTd.getTypes().size());
        assertSame(expected, formTd.getTypes().get(0));

        TypeDescription eventTd = McoreFactory.eINSTANCE.createTypeDescription();
        String eventError = MetadataTypeBuilder.addType(eventTd, item,
            "InformationRegisterRecord", null, config, false, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

        assertEquals("Type kind 'InformationRegisterRecord' cannot be used as an event " //$NON-NLS-1$
            + "subscription's source: an event subscription's source is an object that " //$NON-NLS-1$
            + "publishes write events. Accepted produced-type suffixes: Object, Manager, " //$NON-NLS-1$
            + "RecordSet, RecordManager, ValueManager.", eventError); //$NON-NLS-1$
        assertTrue(eventTd.getTypes().isEmpty());
    }

    @Test
    public void testAccountingRegisterRecordManagerReportsUnsupportedProducedType()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        seedProducedType(config, "AccountingRegister", "Ledger", "recordSetType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            McoreFactory.eINSTANCE.createType());
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json(
            "{\"kind\":\"AccountingRegisterRecordManager\",\"ref\":\"Ledger\"}") //$NON-NLS-1$
                .getAsJsonObject();

        String error = MetadataTypeBuilder.addType(td, item, "AccountingRegisterRecordManager", //$NON-NLS-1$
            null, config, false, MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

        assertNotNull(error);
        assertTrue(error, error.contains("AccountingRegister.Ledger")); //$NON-NLS-1$
        assertTrue(error, error.contains("does not offer produced type 'RecordManager'")); //$NON-NLS-1$
        assertTrue(error, error.contains("AccountingRegisterRecordSet")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testConcreteProducedTypeRejectsQualifiedRefWithDifferentMetadataToken()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json(
            "{\"kind\":\"CatalogObject\",\"ref\":\"Document.Invoice\"}").getAsJsonObject(); //$NON-NLS-1$

        String error = MetadataTypeBuilder.addType(td, item, "CatalogObject", null, config, false, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

        assertNotNull(error);
        assertTrue(error, error.contains("CatalogObject")); //$NON-NLS-1$
        assertTrue(error, error.contains("Document.Invoice")); //$NON-NLS-1$
        assertTrue(error, error.contains("Catalog")); //$NON-NLS-1$
        assertTrue(error, error.contains("Document")); //$NON-NLS-1$
        assertTrue(error, error.contains("match")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testConcreteProducedTypeRejectsUnknownMetadataPrefixActionably()
    {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json(
            "{\"kind\":\"DocumentBogusObject\",\"ref\":\"Invoice\"}").getAsJsonObject(); //$NON-NLS-1$

        String error = MetadataTypeBuilder.addType(td, item, "DocumentBogusObject", null, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

        assertNotNull(error);
        assertTrue(error, error.contains("DocumentBogusObject")); //$NON-NLS-1$
        assertTrue(error, error.contains("DocumentBogus")); //$NON-NLS-1$
        assertTrue(error, error.contains("not a known metadata type token")); //$NON-NLS-1$
        assertTrue(error, error.contains("DocumentObject")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testConcreteProducedTypeRefusesFeatureTheObjectDoesNotOfferAndListsAlternatives()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        seedProducedType(config, "Catalog", "Products", "objectType", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            McoreFactory.eINSTANCE.createType());
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json(
            "{\"kind\":\"CatalogRecordSet\",\"ref\":\"Products\"}").getAsJsonObject(); //$NON-NLS-1$

        String error = MetadataTypeBuilder.addType(td, item, "CatalogRecordSet", null, config, //$NON-NLS-1$
            false, MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

        assertNotNull(error);
        assertTrue(error, error.contains("Catalog.Products")); //$NON-NLS-1$
        assertTrue(error, error.contains("does not offer")); //$NON-NLS-1$
        assertTrue(error, error.contains("RecordSet")); //$NON-NLS-1$
        assertTrue(error, error.contains("CatalogObject")); //$NON-NLS-1$
        assertTrue(error, error.contains("CatalogManager")); //$NON-NLS-1$
        assertTrue(error, error.contains("CatalogRef")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testStoredMetadataRefusesAbstractProducedTypeActionably()
    {
        Type abstractObject = McoreFactory.eINSTANCE.createType();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String error = addKind("ExchangePlanObject", //$NON-NLS-1$
            providerKnowing("ExchangePlanObject", abstractObject), td, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertEquals("Type kind 'ExchangePlanObject' is a runtime object type: it belongs on an " //$NON-NLS-1$
            + "event subscription's 'source' or on a FORM attribute (fqn " //$NON-NLS-1$
            + "'Type.Object.Form.FormName.Attribute.Name'). A stored metadata feature takes a " //$NON-NLS-1$
            + "reference ({kind:'Ref', ref:'Type.Name'}) or a primitive " //$NON-NLS-1$
            + "(String / Number / Boolean / Date) instead.", error); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testEventSourceAcceptsAbstractProducedTypeThePlatformKnows()
    {
        for (String kind : new String[] {"ExchangePlanObject", "InformationRegisterRecordSet", //$NON-NLS-1$ //$NON-NLS-2$
            "InformationRegisterRecordManager", "ConstantValueManager", //$NON-NLS-1$ //$NON-NLS-2$
            "RecalculationRecordSet"}) //$NON-NLS-1$
        {
            Type abstractType = McoreFactory.eINSTANCE.createType();
            TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

            String error = addKind(kind, providerKnowing(kind, abstractType), td,
                MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

            assertNull(kind, error);
            assertEquals(kind, 1, td.getTypes().size());
            assertSame(kind, abstractType, td.getTypes().get(0));
        }
    }

    @Test
    public void testEventSourceAcceptsConcreteCatalogAndDocumentManagers()
    {
        for (String[] one : new String[][] {
            {"Catalog", "Products", "CatalogManager"}, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {"Document", "Invoice", "DocumentManager"} }) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
            Type expected = McoreFactory.eINSTANCE.createType();
            seedProducedType(config, one[0], one[1], "managerType", expected); //$NON-NLS-1$
            TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
            JsonObject item = json("{\"kind\":\"" + one[2] + "\",\"ref\":\"" //$NON-NLS-1$ //$NON-NLS-2$
                + one[1] + "\"}").getAsJsonObject(); //$NON-NLS-1$

            String error = MetadataTypeBuilder.addType(td, item, one[2], null, config, false,
                MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

            assertNull(one[2], error);
            assertEquals(one[2], 1, td.getTypes().size());
            assertSame(one[2], expected, td.getTypes().get(0));
        }
    }

    @Test
    public void testEventSourceAcceptsAbstractCatalogAndDocumentManagers()
    {
        for (String kind : new String[] {"CatalogManager", "DocumentManager"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Type abstractType = McoreFactory.eINSTANCE.createType();
            TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

            String error = addKind(kind, providerKnowing(kind, abstractType), td,
                MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

            assertNull(kind, error);
            assertEquals(kind, 1, td.getTypes().size());
            assertSame(kind, abstractType, td.getTypes().get(0));
        }
    }

    @Test
    public void testEventSourceRefusesListAndSelectionProducedTypesAndListsAcceptedSuffixes()
    {
        for (String[] one : new String[][] {
            {"CatalogList", "listType"}, //$NON-NLS-1$ //$NON-NLS-2$
            {"CatalogSelection", "selectionType"} }) //$NON-NLS-1$ //$NON-NLS-2$
        {
            Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
            seedProducedType(config, "Catalog", "Products", one[1], //$NON-NLS-1$ //$NON-NLS-2$
                McoreFactory.eINSTANCE.createType());
            TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
            JsonObject item = json("{\"kind\":\"" + one[0] //$NON-NLS-1$
                + "\",\"ref\":\"Products\"}").getAsJsonObject(); //$NON-NLS-1$

            String error = MetadataTypeBuilder.addType(td, item, one[0], null, config, false,
                MetadataTypeBuilder.TypeTarget.EVENT_SOURCE);

            String expected = "Type kind '" + one[0] + "' cannot be used as an event " //$NON-NLS-1$ //$NON-NLS-2$
                + "subscription's source: an event subscription's source is an object that " //$NON-NLS-1$
                + "publishes write events. Accepted produced-type suffixes: Object, Manager, " //$NON-NLS-1$
                + "RecordSet, RecordManager, ValueManager."; //$NON-NLS-1$
            assertEquals(one[0], expected, error);
            for (String suffix : new String[] {"Object", "Manager", "RecordSet", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "RecordManager", "ValueManager"}) //$NON-NLS-1$ //$NON-NLS-2$
            {
                assertTrue(one[0] + " refusal must name accepted suffix " + suffix, //$NON-NLS-1$
                    error.contains(suffix));
            }
            assertTrue(one[0], td.getTypes().isEmpty());
        }
    }

    @Test
    public void testStoredMetadataRefusesConcreteProducedTypeActionably()
    {
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json(
            "{\"kind\":\"DocumentObject\",\"ref\":\"Invoice\"}").getAsJsonObject(); //$NON-NLS-1$

        String error = MetadataTypeBuilder.addType(td, item, "DocumentObject", null, //$NON-NLS-1$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertEquals("Type kind 'DocumentObject' is a runtime object type: it belongs on an event " //$NON-NLS-1$
            + "subscription's 'source' or on a FORM attribute (fqn " //$NON-NLS-1$
            + "'Type.Object.Form.FormName.Attribute.Name'). A stored metadata feature takes a " //$NON-NLS-1$
            + "reference ({kind:'Ref', ref:'Type.Name'}) or a primitive " //$NON-NLS-1$
            + "(String / Number / Boolean / Date) instead.", error); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testDcsParameterStillRefusesAbstractProducedType()
    {
        Type abstractObject = McoreFactory.eINSTANCE.createType();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String error = addKind("ExchangePlanObject", //$NON-NLS-1$
            providerKnowing("ExchangePlanObject", abstractObject), td, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.DCS_PARAMETER);

        assertNotNull(error);
        assertTrue(error, error.contains("data-composition parameter")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());

        TypeDescription concreteTd = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject concreteItem = json(
            "{\"kind\":\"ExchangePlanObject\",\"ref\":\"MainExchange\"}") //$NON-NLS-1$
                .getAsJsonObject();
        String concreteError = MetadataTypeBuilder.addType(concreteTd, concreteItem,
            "ExchangePlanObject", providerKnowing("ExchangePlanObject", abstractObject), //$NON-NLS-1$ //$NON-NLS-2$
            MdClassFactory.eINSTANCE.createConfiguration(), false,
            MetadataTypeBuilder.TypeTarget.DCS_PARAMETER);

        assertNotNull(concreteError);
        assertTrue(concreteError, concreteError.contains("data-composition parameter")); //$NON-NLS-1$
        assertTrue(concreteTd.getTypes().isEmpty());
    }

    // ---- DefinedType model-owned TypeSet (issue #498) -------------------------------------------

    @Test
    public void testAddTypeDefinedTypeKindSharesProducedTypeSetForEveryTarget()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        TypeItem expected = seedDefinedType(config, "MoneyAmount", true); //$NON-NLS-1$
        JsonObject item = json(
            "{\"kind\":\"DefinedType\",\"ref\":\"MoneyAmount\"}").getAsJsonObject(); //$NON-NLS-1$

        for (MetadataTypeBuilder.TypeTarget target : MetadataTypeBuilder.TypeTarget.values())
        {
            TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
            String error = MetadataTypeBuilder.addType(td, item, "DefinedType", null, config, false, //$NON-NLS-1$
                target);

            assertNull("DefinedType must be accepted for " + target, error); //$NON-NLS-1$
            assertEquals(1, td.getTypes().size());
            assertSame("the model-owned TypeSet must be shared", expected, td.getTypes().get(0)); //$NON-NLS-1$
        }
    }

    @Test
    public void testAddTypeRefToDefinedTypeSharesTheSameProducedTypeSetInBothLanguages()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        TypeItem expected = seedDefinedType(config, "MoneyAmount", true); //$NON-NLS-1$
        String ruKind = russianDefinedTypeToken();

        for (String ref : new String[] {"DefinedType.MoneyAmount", ruKind + ".MoneyAmount"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
            JsonObject item = new JsonObject();
            item.addProperty("kind", "Ref"); //$NON-NLS-1$ //$NON-NLS-2$
            item.addProperty("ref", ref); //$NON-NLS-1$
            String error = MetadataTypeBuilder.addType(td, item, "Ref", null, config, false, //$NON-NLS-1$
                MetadataTypeBuilder.TypeTarget.METADATA);

            assertNull(ref, error);
            assertSame(ref, expected, td.getTypes().get(0));
        }
    }

    @Test
    public void testAddTypeRussianDefinedTypeKindResolvesThroughSharedTypeCatalog()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        TypeItem expected = seedDefinedType(config, "MoneyAmount", true); //$NON-NLS-1$
        String ruKind = russianDefinedTypeToken();
        JsonObject item = new JsonObject();
        item.addProperty("kind", ruKind); //$NON-NLS-1$
        item.addProperty("ref", "MoneyAmount"); //$NON-NLS-1$ //$NON-NLS-2$
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();

        String error = MetadataTypeBuilder.addType(td, item, ruKind, null, config, false,
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNull(error);
        assertSame(expected, td.getTypes().get(0));
    }

    @Test
    public void testInlineDefinedTypeKindRoundTripsRenderedTypeNameInBothLanguages()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        TypeItem expected = seedDefinedType(config, "MoneyAmount", true); //$NON-NLS-1$
        String ruKind = russianDefinedTypeToken();

        for (String kind : new String[] {
            "DefinedType.MoneyAmount", ruKind + ".MoneyAmount"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
            JsonObject item = new JsonObject();
            item.addProperty("kind", kind); //$NON-NLS-1$
            String error = MetadataTypeBuilder.addType(td, item, kind, null, config, false,
                MetadataTypeBuilder.TypeTarget.METADATA);

            assertNull(kind, error);
            assertSame(kind, expected, td.getTypes().get(0));
        }

        TypeDescription current = McoreFactory.eINSTANCE.createTypeDescription();
        current.getTypes().add(expected);
        CatalogAttribute attribute = MdClassFactory.eINSTANCE.createCatalogAttribute();
        attribute.eSet(attribute.eClass().getEStructuralFeature("type"), current); //$NON-NLS-1$
        assertEquals("DefinedType.MoneyAmount", //$NON-NLS-1$
            MetadataPropertyIntrospector.find(attribute, "type").currentValue); //$NON-NLS-1$
    }

    @Test
    public void testUnknownDefinedTypeNameReturnsActionableReferenceError()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json(
            "{\"kind\":\"DefinedType\",\"ref\":\"NoSuchDefinedType\"}").getAsJsonObject(); //$NON-NLS-1$

        String error = MetadataTypeBuilder.addType(td, item, "DefinedType", null, config, false, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNotNull(error);
        assertTrue(error, error.contains("Cannot resolve the reference target")); //$NON-NLS-1$
        assertTrue(error, error.contains("DefinedType.NoSuchDefinedType")); //$NON-NLS-1$
        assertTrue(error, error.contains("check the object exists")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
    }

    @Test
    public void testDefinedTypeWithoutProducedTypeSetReportsUnavailableChain()
    {
        Configuration config = MdClassFactory.eINSTANCE.createConfiguration();
        seedDefinedType(config, "NotReady", false); //$NON-NLS-1$
        TypeDescription td = McoreFactory.eINSTANCE.createTypeDescription();
        JsonObject item = json(
            "{\"kind\":\"DefinedType\",\"ref\":\"NotReady\"}").getAsJsonObject(); //$NON-NLS-1$

        String error = MetadataTypeBuilder.addType(td, item, "DefinedType", null, config, false, //$NON-NLS-1$
            MetadataTypeBuilder.TypeTarget.METADATA);

        assertNotNull(error);
        assertTrue(error, error.contains("DefinedType.NotReady")); //$NON-NLS-1$
        assertTrue(error, error.contains("producedTypes/containerType/typeSet")); //$NON-NLS-1$
        assertTrue(error, error.contains("revalidate_objects")); //$NON-NLS-1$
        assertTrue(td.getTypes().isEmpty());
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

    private static void assertProducedTypeSplit(String kind, String prefix, String englishMetadataType,
        String producedSuffix, String featureName)
    {
        MetadataTypeBuilder.ProducedTypeKind split =
            MetadataTypeBuilder.splitProducedTypeKind(kind);
        assertNotNull(kind, split);
        assertEquals(kind, prefix, split.prefix);
        assertEquals(kind, englishMetadataType, split.englishMetadataType);
        assertEquals(kind, producedSuffix, split.producedSuffix);
        assertEquals(kind, featureName, split.featureName);
    }

    /** Seeds one top-level object and one generated produced-type wrapper in its model holder. */
    @SuppressWarnings("unchecked")
    private static MdObject seedProducedType(Configuration config, String englishMetadataType,
        String name, String producedFeature, Type expectedType)
    {
        MdObject object = seedProducedType(englishMetadataType, name, producedFeature, expectedType);

        String collectionName = MetadataTypeUtils.getConfigReferenceName(englishMetadataType);
        EStructuralFeature collection = config.eClass().getEStructuralFeature(collectionName);
        assertNotNull(collectionName, collection);
        ((java.util.List<MdObject>)config.eGet(collection)).add(object);
        return object;
    }

    /** Seeds one standalone top-level object and one generated produced-type wrapper. */
    private static MdObject seedProducedType(String englishMetadataType, String name,
        String producedFeature, Type expectedType)
    {
        Object classifier = MdClassPackage.eINSTANCE.getEClassifier(englishMetadataType);
        assertTrue(englishMetadataType, classifier instanceof EClass);
        MdObject object = (MdObject)EcoreUtil.create((EClass)classifier);
        object.setName(name);

        EObject producedTypes = newContainedValue(object, "producedTypes"); //$NON-NLS-1$
        MdType producedType = (MdType)newContainedValue(producedTypes, producedFeature);
        producedType.setType(expectedType);
        return object;
    }

    private static String russianDefinedTypeToken()
    {
        return MetadataLanguageUtils.cp(0x041E, 0x043F, 0x0440, 0x0435, 0x0434, 0x0435, 0x043B,
            0x044F, 0x0435, 0x043C, 0x044B, 0x0439, 0x0422, 0x0438, 0x043F);
    }

    /** Seeds a generated DefinedType and, optionally, its full produced TypeSet chain. */
    @SuppressWarnings("unchecked")
    private static TypeItem seedDefinedType(Configuration config, String name, boolean withProducedTypeSet)
    {
        MdObject definedType = (MdObject)EcoreUtil.create(MdClassPackage.Literals.DEFINED_TYPE);
        definedType.setName(name);
        EStructuralFeature collection = config.eClass().getEStructuralFeature("definedTypes"); //$NON-NLS-1$
        assertNotNull(collection);
        ((java.util.List<MdObject>)config.eGet(collection)).add(definedType);
        if (!withProducedTypeSet)
        {
            return null;
        }

        EObject producedTypes = newContainedValue(definedType, "producedTypes"); //$NON-NLS-1$
        EObject containerType = newContainedValue(producedTypes, "containerType"); //$NON-NLS-1$
        TypeItem typeSet = (TypeItem)newContainedValue(containerType, "typeSet"); //$NON-NLS-1$
        EStructuralFeature typeSetName = typeSet.eClass().getEStructuralFeature("name"); //$NON-NLS-1$
        assertNotNull(typeSetName);
        typeSet.eSet(typeSetName, "DefinedType." + name); //$NON-NLS-1$
        return typeSet;
    }

    private static EObject newContainedValue(EObject owner, String featureName)
    {
        EStructuralFeature feature = owner.eClass().getEStructuralFeature(featureName);
        assertTrue(featureName, feature instanceof EReference);
        EReference reference = (EReference)feature;
        assertTrue(featureName, reference.isContainment());
        EObject value = EcoreUtil.create(reference.getEReferenceType());
        owner.eSet(reference, value);
        return value;
    }
}

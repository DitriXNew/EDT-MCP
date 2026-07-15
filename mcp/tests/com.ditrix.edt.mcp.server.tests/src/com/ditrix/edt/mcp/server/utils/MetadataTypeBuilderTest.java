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
import static org.junit.Assert.assertTrue;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.Test;

import com._1c.g5.v8.dt.mcore.DateFractions;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
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
            json("{\"types\":[{\"kind\":\"Number\",\"precision\":10},{\"kind\":\"Ref\",\"ref\":\"Catalog.X\"}]}"))); //$NON-NLS-1$
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

        String baseErr = MetadataTypeBuilder.addType(td, item, "Ref", null, config, false); //$NON-NLS-1$
        assertNotNull(baseErr);
        assertTrue("the sentinel must be present", //$NON-NLS-1$
            baseErr.contains("Cannot resolve the reference target")); //$NON-NLS-1$
        assertFalse("a base-configuration project must get no adopt hint", //$NON-NLS-1$
            baseErr.contains("adopt_metadata_object")); //$NON-NLS-1$

        String extErr = MetadataTypeBuilder.addType(td, item, "Ref", null, config, true); //$NON-NLS-1$
        assertNotNull(extErr);
        assertTrue("the sentinel must stay a continuous substring when the hint is appended", //$NON-NLS-1$
            extErr.contains("Cannot resolve the reference target")); //$NON-NLS-1$
        assertTrue("an extension project must get the adopt hint", //$NON-NLS-1$
            extErr.contains("adopt_metadata_object")); //$NON-NLS-1$
    }
}

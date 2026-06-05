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

import org.junit.Test;

import com._1c.g5.v8.dt.mcore.DateFractions;
import com.google.gson.JsonElement;
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
}

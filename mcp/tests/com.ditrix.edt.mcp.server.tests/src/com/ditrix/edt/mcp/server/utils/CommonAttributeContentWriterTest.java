/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.CommonAttributeUse;
import com.google.gson.JsonObject;

/**
 * Tests the pure, model-independent and UI-independent logic of {@link CommonAttributeContentWriter}:
 * the content op normalization ({@code contentOp}) and the {@code use} token mapping ({@code mapUse})
 * through the {@code COMMON_ATTRIBUTE_CONTENT_ITEM__USE} EAttribute's EEnum literals (default
 * {@code Use}, case-insensitivity, rejection of an unknown token). The model-touching apply path (the
 * BM write boundary, the MdPlugin factory create, the in-transaction owner re-resolution) is covered
 * by the e2e suite against a live common attribute.
 *
 * <p>A representative bilingual owner-FQN normalization is asserted through the shared
 * {@link MetadataTypeUtils#normalizeFqn} the writer uses, so the Russian type token maps to the
 * canonical English singular the in-transaction resolution expects. The Russian token is built from
 * code points so the assertion verifies the real Cyrillic mapping, not a round-trip of the same
 * literal.</p>
 */
public class CommonAttributeContentWriterTest
{
    private static JsonObject entryWithOp(String op)
    {
        JsonObject entry = new JsonObject();
        if (op != null)
        {
            entry.addProperty("op", op); //$NON-NLS-1$
        }
        return entry;
    }

    private static String fromCp(int... cps)
    {
        return new String(cps, 0, cps.length);
    }

    // ---- contentOp --------------------------------------------------------------------------

    @Test
    public void testContentOpDefaultsToAdd()
    {
        assertEquals("add", CommonAttributeContentWriter.contentOp(new JsonObject())); //$NON-NLS-1$
        assertEquals("add", CommonAttributeContentWriter.contentOp(entryWithOp("  "))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testContentOpNormalizesCase()
    {
        assertEquals("remove", CommonAttributeContentWriter.contentOp(entryWithOp("REMOVE"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("remove", CommonAttributeContentWriter.contentOp(entryWithOp(" Remove "))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("add", CommonAttributeContentWriter.contentOp(entryWithOp("Add"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- mapUse (via the EAttribute's EEnum literals) ---------------------------------------

    @Test
    public void testMapUseDefaultsToUse()
    {
        assertSame(CommonAttributeUse.USE, CommonAttributeContentWriter.mapUse(null));
        assertSame(CommonAttributeUse.USE, CommonAttributeContentWriter.mapUse("")); //$NON-NLS-1$
        assertSame(CommonAttributeUse.USE, CommonAttributeContentWriter.mapUse("   ")); //$NON-NLS-1$
    }

    @Test
    public void testMapUseMapsEachLiteral()
    {
        assertSame(CommonAttributeUse.USE, CommonAttributeContentWriter.mapUse("Use")); //$NON-NLS-1$
        assertSame(CommonAttributeUse.DONT_USE, CommonAttributeContentWriter.mapUse("DontUse")); //$NON-NLS-1$
        assertSame(CommonAttributeUse.AUTO, CommonAttributeContentWriter.mapUse("Auto")); //$NON-NLS-1$
    }

    @Test
    public void testMapUseIsCaseInsensitive()
    {
        assertSame(CommonAttributeUse.USE, CommonAttributeContentWriter.mapUse("use")); //$NON-NLS-1$
        assertSame(CommonAttributeUse.USE, CommonAttributeContentWriter.mapUse("USE")); //$NON-NLS-1$
        assertSame(CommonAttributeUse.DONT_USE, CommonAttributeContentWriter.mapUse("dontuse")); //$NON-NLS-1$
        assertSame(CommonAttributeUse.DONT_USE, CommonAttributeContentWriter.mapUse(" DontUse ")); //$NON-NLS-1$
        assertSame(CommonAttributeUse.AUTO, CommonAttributeContentWriter.mapUse("auto")); //$NON-NLS-1$
    }

    @Test
    public void testMapUseRejectsUnknown()
    {
        assertNull(CommonAttributeContentWriter.mapUse("maybe")); //$NON-NLS-1$
        assertNull(CommonAttributeContentWriter.mapUse("Enabled")); //$NON-NLS-1$
    }

    // ---- bilingual owner-FQN normalization (via the shared MetadataTypeUtils) ---------------

    @Test
    public void testRussianOwnerTypeNormalizesToEnglishSingular()
    {
        // "Справочник.Товары" -> "Catalog.Товары": the Russian TYPE token maps to the canonical
        // English singular the in-transaction owner resolution expects (only the type token is
        // bilingual; the object name is preserved verbatim).
        String ruCatalog = fromCp(0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447, 0x043d,
            0x0438, 0x043a); // Справочник
        String ruGoods = fromCp(0x0422, 0x043e, 0x0432, 0x0430, 0x0440, 0x044b); // Товары
        String normalized = MetadataTypeUtils.normalizeFqn(ruCatalog + "." + ruGoods); //$NON-NLS-1$
        assertEquals("Catalog." + ruGoods, normalized); //$NON-NLS-1$
    }
}

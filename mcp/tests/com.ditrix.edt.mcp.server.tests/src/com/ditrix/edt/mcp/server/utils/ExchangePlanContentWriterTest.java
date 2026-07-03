/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.AutoRegistrationChanges;
import com.google.gson.JsonObject;

/**
 * Tests the pure, model-independent and UI-independent logic of {@link ExchangePlanContentWriter}: the
 * content op normalization ({@code contentOp}) and the {@code autoRecord} token mapping
 * ({@code isAutoRecordOmitted} + {@code mapAutoRecord}) through the
 * {@code EXCHANGE_PLAN_CONTENT_ITEM__AUTO_RECORD} EAttribute's EEnum literals. Unlike the CommonAttribute
 * {@code use} flag, {@code autoRecord} is OPTIONAL: an omitted / blank token leaves the value unset (NOT
 * a default literal), a supplied token maps case-insensitively to {@code Allow} / {@code Deny}, and an
 * unknown token is rejected. The model-touching apply path (the BM write boundary, the MdPlugin factory
 * create, the in-transaction object re-resolution) is covered by the e2e suite against a live exchange
 * plan.
 *
 * <p>A representative bilingual object-FQN normalization is asserted through the shared
 * {@link MetadataTypeUtils#normalizeFqn} the writer uses, so the Russian type token maps to the
 * canonical English singular the in-transaction resolution expects. The Russian token is built from
 * code points so the assertion verifies the real Cyrillic mapping, not a round-trip of the same
 * literal.</p>
 */
public class ExchangePlanContentWriterTest
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
        assertEquals("add", ExchangePlanContentWriter.contentOp(new JsonObject())); //$NON-NLS-1$
        assertEquals("add", ExchangePlanContentWriter.contentOp(entryWithOp("  "))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testContentOpNormalizesCase()
    {
        assertEquals("remove", ExchangePlanContentWriter.contentOp(entryWithOp("REMOVE"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("remove", ExchangePlanContentWriter.contentOp(entryWithOp(" Remove "))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("add", ExchangePlanContentWriter.contentOp(entryWithOp("Add"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- isAutoRecordOmitted (an omitted token leaves the value unset, NOT a default) -------

    @Test
    public void testAutoRecordOmittedForNullOrBlank()
    {
        assertTrue(ExchangePlanContentWriter.isAutoRecordOmitted(null));
        assertTrue(ExchangePlanContentWriter.isAutoRecordOmitted("")); //$NON-NLS-1$
        assertTrue(ExchangePlanContentWriter.isAutoRecordOmitted("   ")); //$NON-NLS-1$
    }

    @Test
    public void testAutoRecordNotOmittedForAValue()
    {
        assertFalse(ExchangePlanContentWriter.isAutoRecordOmitted("Allow")); //$NON-NLS-1$
        assertFalse(ExchangePlanContentWriter.isAutoRecordOmitted(" Deny ")); //$NON-NLS-1$
    }

    // ---- mapAutoRecord (via the EAttribute's EEnum literals) --------------------------------

    @Test
    public void testMapAutoRecordOmittedIsNull()
    {
        // An omitted / blank token maps to null here too, but the caller reads it as "leave unset"
        // (via isAutoRecordOmitted), not as "reject".
        assertNull(ExchangePlanContentWriter.mapAutoRecord(null));
        assertNull(ExchangePlanContentWriter.mapAutoRecord("")); //$NON-NLS-1$
        assertNull(ExchangePlanContentWriter.mapAutoRecord("   ")); //$NON-NLS-1$
    }

    @Test
    public void testMapAutoRecordMapsEachLiteral()
    {
        assertSame(AutoRegistrationChanges.ALLOW, ExchangePlanContentWriter.mapAutoRecord("Allow")); //$NON-NLS-1$
        assertSame(AutoRegistrationChanges.DENY, ExchangePlanContentWriter.mapAutoRecord("Deny")); //$NON-NLS-1$
    }

    @Test
    public void testMapAutoRecordIsCaseInsensitive()
    {
        assertSame(AutoRegistrationChanges.ALLOW, ExchangePlanContentWriter.mapAutoRecord("allow")); //$NON-NLS-1$
        assertSame(AutoRegistrationChanges.ALLOW, ExchangePlanContentWriter.mapAutoRecord("ALLOW")); //$NON-NLS-1$
        assertSame(AutoRegistrationChanges.ALLOW, ExchangePlanContentWriter.mapAutoRecord(" Allow ")); //$NON-NLS-1$
        assertSame(AutoRegistrationChanges.DENY, ExchangePlanContentWriter.mapAutoRecord("deny")); //$NON-NLS-1$
        assertSame(AutoRegistrationChanges.DENY, ExchangePlanContentWriter.mapAutoRecord("DENY")); //$NON-NLS-1$
    }

    @Test
    public void testMapAutoRecordRejectsUnknown()
    {
        assertNull(ExchangePlanContentWriter.mapAutoRecord("Maybe")); //$NON-NLS-1$
        assertNull(ExchangePlanContentWriter.mapAutoRecord("Auto")); //$NON-NLS-1$
        assertNull(ExchangePlanContentWriter.mapAutoRecord("Use")); //$NON-NLS-1$
    }

    // ---- bilingual object-FQN normalization (via the shared MetadataTypeUtils) --------------

    @Test
    public void testRussianObjectTypeNormalizesToEnglishSingular()
    {
        // "Справочник.Товары" -> "Catalog.Товары": the Russian TYPE token maps to the canonical
        // English singular the in-transaction object resolution expects (only the type token is
        // bilingual; the object name is preserved verbatim).
        String ruCatalog = fromCp(0x0421, 0x043f, 0x0440, 0x0430, 0x0432, 0x043e, 0x0447, 0x043d,
            0x0438, 0x043a); // Справочник
        String ruGoods = fromCp(0x0422, 0x043e, 0x0432, 0x0430, 0x0440, 0x044b); // Товары
        String normalized = MetadataTypeUtils.normalizeFqn(ruCatalog + "." + ruGoods); //$NON-NLS-1$
        assertEquals("Catalog." + ruGoods, normalized); //$NON-NLS-1$
    }
}

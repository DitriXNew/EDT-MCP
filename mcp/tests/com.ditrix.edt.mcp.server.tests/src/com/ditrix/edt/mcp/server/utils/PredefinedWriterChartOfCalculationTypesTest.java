/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypes;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypesCodeType;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCalculationTypesPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.google.gson.JsonPrimitive;

/**
 * Unit-tests {@link PredefinedWriter}'s {@code ChartOfCalculationTypes} (ПланВидовРасчета)
 * predefined-item arm (issue #296 phase 2), entirely in-memory against a bare
 * {@link MdClassFactory}-created {@link ChartOfCalculationTypes} - NO BM model / no live workbench.
 * What is UNIT-provable and covered here:
 * <ul>
 * <li>the FLAT model: {@code isFolder} and {@code parent} are rejected for a
 * {@code ChartOfCalculationTypes} owner (no folder/child nesting);</li>
 * <li>the owner-scoped property GUARDS: {@code base} / {@code displaced} / {@code leading} /
 * {@code actionPeriodIsBase} are rejected for a non-{@code ChartOfCalculationTypes} owner with an
 * actionable error (the same {@code applyValueType}-style gate Phase 1 uses);</li>
 * <li>{@code actionPeriodIsBase} boolean storage;</li>
 * <li>the {@code code} value dispatched on {@link ChartOfCalculationTypes#getCodeType()} reusing the
 * Catalog {@code mcore.Value} path VERBATIM: {@code Number} codes get the BigDecimal precision / scale
 * hardening (negative / fractional / huge-exponent / over-{@code codeLength} rejected, never
 * materialized), {@code String} codes take the strict-string / length path.</li>
 * <li>the read-side {@link PredefinedWriter#listAll} rendering flat rows with {@code isFolder=false}.</li>
 * </ul>
 * The platform-resolved SUCCESSFUL {@code base} / {@code displaced} / {@code leading} reference
 * resolution to SIBLING predefined calc types (and {@code delete_metadata}'s back-reference safety)
 * is E2E-covered (test_predefined_cocalc.py), mirroring the {@code valueType} build's documented
 * unit/e2e split in {@link PredefinedWriterTest}.
 */
public class PredefinedWriterChartOfCalculationTypesTest
{
    // ---- fixtures --------------------------------------------------------------------------------

    private static ChartOfCalculationTypes newCalcTypes(String name)
    {
        ChartOfCalculationTypes types = MdClassFactory.eINSTANCE.createChartOfCalculationTypes();
        types.setName(name);
        return types;
    }

    private static ChartOfCalculationTypes newNumberCalcTypes(String name, int codeLength)
    {
        ChartOfCalculationTypes types = newCalcTypes(name);
        types.setCodeType(ChartOfCalculationTypesCodeType.NUMBER);
        types.setCodeLength(codeLength);
        return types;
    }

    private static ChartOfCalculationTypes newStringCalcTypes(String name, int codeLength)
    {
        ChartOfCalculationTypes types = newCalcTypes(name);
        types.setCodeType(ChartOfCalculationTypesCodeType.STRING);
        types.setCodeLength(codeLength);
        return types;
    }

    private static Catalog newCatalog(String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        return catalog;
    }

    // ==================== FLAT model: isFolder / parent rejected =====================================

    @Test
    public void testIsFolderRejectedForCalcTypesOwnerFlatModel()
    {
        ChartOfCalculationTypes types = newCalcTypes("Charges"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.isFolder = Boolean.TRUE;
        props.isFolderSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Bonus", props, false); //$NON-NLS-1$
        assertTrue("a Chart of Calculation Types is flat - a folder item must be rejected", //$NON-NLS-1$
            result.isError());
        assertTrue("the error must be actionable about folders: " + result.error, //$NON-NLS-1$
            result.error.toLowerCase(Locale.ROOT).contains("folder")); //$NON-NLS-1$
    }

    @Test
    public void testParentRejectedForCalcTypesOwnerFlatModel()
    {
        ChartOfCalculationTypes types = newCalcTypes("Charges"); //$NON-NLS-1$
        // A sibling exists (so this is NOT a plain "parent not found") - nesting is what is rejected.
        PredefinedWriter.create(types, "Base", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        PredefinedWriter.ItemProps child = new PredefinedWriter.ItemProps();
        child.parentName = "Base"; //$NON-NLS-1$
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Bonus", child, false); //$NON-NLS-1$
        assertTrue("a Chart of Calculation Types is flat - nesting under a parent must be rejected", //$NON-NLS-1$
            result.isError());
        assertTrue("the error must mention parent/folder: " + result.error, //$NON-NLS-1$
            result.error.toLowerCase(Locale.ROOT).contains("parent") //$NON-NLS-1$
                || result.error.toLowerCase(Locale.ROOT).contains("folder")); //$NON-NLS-1$
    }

    // ==================== owner-scoped guards (rejected for a non-ChartOfCalculationTypes owner) =====

    @Test
    public void testBaseRejectedForNonCalcTypesOwner()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.base = List.of("Salary"); //$NON-NLS-1$
        props.baseSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue("must name the required owner type: " + result.error, //$NON-NLS-1$
            result.error.contains("Calculation")); //$NON-NLS-1$
    }

    @Test
    public void testDisplacedRejectedForNonCalcTypesOwner()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.displaced = List.of("Salary"); //$NON-NLS-1$
        props.displacedSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("Calculation")); //$NON-NLS-1$
    }

    @Test
    public void testLeadingRejectedForNonCalcTypesOwner()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.leading = List.of("Salary"); //$NON-NLS-1$
        props.leadingSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("Calculation")); //$NON-NLS-1$
    }

    @Test
    public void testActionPeriodIsBaseRejectedForNonCalcTypesOwner()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.actionPeriodIsBase = Boolean.TRUE;
        props.actionPeriodIsBaseSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("Calculation")); //$NON-NLS-1$
    }

    // ==================== actionPeriodIsBase storage ================================================

    @Test
    public void testActionPeriodIsBaseFlagStored()
    {
        ChartOfCalculationTypes types = newCalcTypes("Charges"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.actionPeriodIsBase = Boolean.TRUE;
        props.actionPeriodIsBaseSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Bonus", props, false); //$NON-NLS-1$
        assertFalse("actionPeriodIsBase must be accepted on a calc-type owner: " + result.error, //$NON-NLS-1$
            result.isError());
        assertTrue(((ChartOfCalculationTypesPredefinedItem)result.item).isActionPeriodIsBase());
    }

    // ==================== Number code: BigDecimal precision/scale hardening (Catalog path reused) ====

    @Test
    public void testNumberCodeValidStoredAndDisplayed()
    {
        ChartOfCalculationTypes types = newNumberCalcTypes("Charges", 4); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(42);
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Bonus", props, false); //$NON-NLS-1$
        assertFalse("a valid numeric code must be accepted: " + result.error, result.isError()); //$NON-NLS-1$
        assertEquals("42", PredefinedWriter.displayCode(result.item)); //$NON-NLS-1$
    }

    @Test
    public void testNumberCodeRejectsJsonString()
    {
        ChartOfCalculationTypes types = newNumberCalcTypes("Charges", 4); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive("42"); // a String for a Number codeType //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Bonus", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("must be a JSON number")); //$NON-NLS-1$
    }

    @Test
    public void testNumberCodeNegativeRejected()
    {
        ChartOfCalculationTypes types = newNumberCalcTypes("Charges", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(new BigDecimal("-7")); //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Neg", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("negative")); //$NON-NLS-1$
    }

    @Test
    public void testNumberCodeFractionalRejected()
    {
        ChartOfCalculationTypes types = newNumberCalcTypes("Charges", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(new BigDecimal("1.5")); //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Half", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("fractional")); //$NON-NLS-1$
    }

    @Test
    public void testNumberCodeHugeExponentRejectedWithoutMaterializing()
    {
        // A pathological exponent must be rejected via digit-count MATH, never expanded (the same
        // guard the Catalog numeric path carries). Completes instantly on a bounded codeLength.
        ChartOfCalculationTypes types = newNumberCalcTypes("Charges", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(new BigDecimal("1E+100000000")); //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Big", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("digit(s)")); //$NON-NLS-1$
    }

    @Test
    public void testNumberCodeTooManyDigitsRejected()
    {
        ChartOfCalculationTypes types = newNumberCalcTypes("Charges", 2); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(123); // 3 digits > codeLength 2
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Big", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("digit(s)")); //$NON-NLS-1$
    }

    // ==================== String code: strict-string / length path ==================================

    @Test
    public void testStringCodeStoredAndDisplayed()
    {
        ChartOfCalculationTypes types = newStringCalcTypes("Charges", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive("C001"); //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Bonus", props, false); //$NON-NLS-1$
        assertFalse("a valid string code must be accepted: " + result.error, result.isError()); //$NON-NLS-1$
        assertEquals("C001", PredefinedWriter.displayCode(result.item)); //$NON-NLS-1$
    }

    @Test
    public void testStringCodeRejectsJsonNumber()
    {
        ChartOfCalculationTypes types = newStringCalcTypes("Charges", 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(1); // a Number for a String codeType
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Bonus", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("must be a JSON string")); //$NON-NLS-1$
    }

    @Test
    public void testStringCodeTooLongRejectedNeverTruncated()
    {
        ChartOfCalculationTypes types = newStringCalcTypes("Charges", 2); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive("TOOLONG"); //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(types, "Bonus", props, false); //$NON-NLS-1$
        assertTrue("an over-length code must be REJECTED, never silently truncated", result.isError()); //$NON-NLS-1$
        assertTrue(result.error.contains("codeLength")); //$NON-NLS-1$
    }

    // ==================== read-side: flat listing, isFolder = '-' ===================================

    @Test
    public void testListAllFlatNoNestingIsFolderFalse()
    {
        ChartOfCalculationTypes types = newStringCalcTypes("Charges", 5); //$NON-NLS-1$
        PredefinedWriter.create(types, "Salary", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        PredefinedWriter.create(types, "Bonus", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$

        List<PredefinedWriter.ItemRow> rows = PredefinedWriter.listAll(types);
        assertEquals(2, rows.size());
        for (PredefinedWriter.ItemRow row : rows)
        {
            assertEquals("a Chart of Calculation Types is flat - every item is top-level", 0, row.depth); //$NON-NLS-1$
            assertNull(row.parentName);
            assertFalse("a Chart of Calculation Types item is never a folder (Folder column = '-')", //$NON-NLS-1$
                row.isFolder);
        }
    }
}

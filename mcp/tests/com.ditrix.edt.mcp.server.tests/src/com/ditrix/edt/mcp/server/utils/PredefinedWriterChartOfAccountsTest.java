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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.common.AccountType;
import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccounts;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfAccountsPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.ChartOfCharacteristicTypesPredefinedItem;
import com._1c.g5.v8.dt.metadata.mdclass.ExtDimensionType;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Unit-tests {@link PredefinedWriter}'s {@code ChartOfAccounts} (ПланСчетов) predefined-item arm
 * (issue #296 phase 3), entirely in-memory against a bare {@link MdClassFactory}-created
 * {@link ChartOfAccounts} - NO BM model / no live workbench. What is UNIT-provable and covered here:
 * <ul>
 * <li>the owner-scoped property GUARDS: {@code accountType} / {@code offBalance} / {@code order} /
 * {@code accountingFlags} / {@code extDimensionTypes} are rejected for a non-{@code ChartOfAccounts}
 * owner with an actionable error (the same {@code applyValueType}-style gate Phase 1/2 use);</li>
 * <li>{@code accountType} EXACT-token parsing ({@code ACTIVE} / {@code PASSIVE} /
 * {@code ACTIVE_PASSIVE}) including the {@code ACTIVE_PASSIVE}-vs-{@code ACTIVE} substring safety a
 * {@code contains()} match would get wrong, plus an actionable rejection of an unknown token;</li>
 * <li>{@code offBalance} boolean storage;</li>
 * <li>{@code code} / {@code order} length validation against {@code getCodeLength()} /
 * {@code getOrderLength()} - an over-length value is REJECTED, never truncated, and an at-limit value
 * is stored verbatim;</li>
 * <li>the read-side {@link PredefinedWriter#listAll} recursing the CONTAINMENT {@code childItems}
 * hierarchy and rendering {@code isFolder=false} (the "Folder = -" precedent).</li>
 * </ul>
 * The platform-resolved SUCCESSFUL reference-resolution write path ({@code accountingFlags} /
 * {@code extDimensionTypes} / {@code characteristicType} resolved to live in-resource EObjects, and
 * {@code delete_metadata}'s back-reference safety) needs a live Configuration resource and is
 * E2E-covered (test_predefined_coa.py), mirroring the {@code valueType} build's documented unit/e2e
 * split in {@link PredefinedWriterTest}.
 */
public class PredefinedWriterChartOfAccountsTest
{
    // ---- fixtures --------------------------------------------------------------------------------

    private static ChartOfAccounts newChartOfAccounts(String name)
    {
        ChartOfAccounts coa = MdClassFactory.eINSTANCE.createChartOfAccounts();
        coa.setName(name);
        return coa;
    }

    private static ChartOfAccounts newChartOfAccounts(String name, int codeLength, int orderLength)
    {
        ChartOfAccounts coa = newChartOfAccounts(name);
        coa.setCodeLength(codeLength);
        coa.setOrderLength(orderLength);
        return coa;
    }

    private static Catalog newCatalog(String name)
    {
        Catalog catalog = MdClassFactory.eINSTANCE.createCatalog();
        catalog.setName(name);
        return catalog;
    }

    // ==================== owner-scoped property guards (rejected for a non-ChartOfAccounts owner) ====

    @Test
    public void testAccountTypeRejectedForNonChartOfAccountsOwner()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.accountType = "ACTIVE"; //$NON-NLS-1$
        props.accountTypeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue("must name the required owner type: " + result.error, //$NON-NLS-1$
            result.error.contains("ChartOfAccounts")); //$NON-NLS-1$
    }

    @Test
    public void testOffBalanceRejectedForNonChartOfAccountsOwner()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.offBalance = Boolean.TRUE;
        props.offBalanceSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("ChartOfAccounts")); //$NON-NLS-1$
    }

    @Test
    public void testOrderRejectedForNonChartOfAccountsOwner()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.order = new JsonPrimitive("001"); //$NON-NLS-1$
        props.orderSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("ChartOfAccounts")); //$NON-NLS-1$
    }

    @Test
    public void testAccountingFlagsRejectedForNonChartOfAccountsOwner()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.accountingFlags = List.of("Quantity"); //$NON-NLS-1$
        props.accountingFlagsSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("ChartOfAccounts")); //$NON-NLS-1$
    }

    @Test
    public void testExtDimensionTypesRejectedForNonChartOfAccountsOwner()
    {
        Catalog catalog = newCatalog("Colors"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        // An empty (but SET) list still asks for a ChartOfAccounts-only feature - the gate rejects it
        // by the *Set flag exactly like an explicit valueType clear on a wrong owner (the nested holder
        // type is deliberately not named here - the diamond is target-typed to the field).
        props.extDimensionTypes = new ArrayList<>();
        props.extDimensionTypesSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(catalog, "Blue", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("ChartOfAccounts")); //$NON-NLS-1$
    }

    // ==================== accountType EXACT-token parsing (substring safety) =========================

    @Test
    public void testAccountTypeExactTokenActivePassiveNotConfusedWithActive()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.accountType = "ACTIVE_PASSIVE"; //$NON-NLS-1$
        props.accountTypeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(coa, "Settlements", props, false); //$NON-NLS-1$
        assertFalse("ACTIVE_PASSIVE is a valid token: " + result.error, result.isError()); //$NON-NLS-1$
        assertEquals("an EXACT-token match must resolve ACTIVE_PASSIVE - a naive contains() would pick " //$NON-NLS-1$
            + "the ACTIVE prefix instead", AccountType.ACTIVE_PASSIVE, //$NON-NLS-1$
            ((ChartOfAccountsPredefinedItem)result.item).getAccountType());
    }

    @Test
    public void testAccountTypeActiveResolvesExactly()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.accountType = "ACTIVE"; //$NON-NLS-1$
        props.accountTypeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(coa, "Cash", props, false); //$NON-NLS-1$
        assertFalse(result.isError());
        assertEquals(AccountType.ACTIVE, ((ChartOfAccountsPredefinedItem)result.item).getAccountType());
    }

    @Test
    public void testAccountTypePassiveResolvesExactly()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.accountType = "PASSIVE"; //$NON-NLS-1$
        props.accountTypeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(coa, "Payables", props, false); //$NON-NLS-1$
        assertFalse(result.isError());
        assertEquals(AccountType.PASSIVE, ((ChartOfAccountsPredefinedItem)result.item).getAccountType());
    }

    @Test
    public void testAccountTypeUnknownTokenRejectedActionably()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.accountType = "NOSUCHTYPE"; //$NON-NLS-1$
        props.accountTypeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(coa, "Weird", props, false); //$NON-NLS-1$
        assertTrue("an unrecognized accountType token must be rejected", result.isError()); //$NON-NLS-1$
        assertTrue("the error must echo the bad token: " + result.error, //$NON-NLS-1$
            result.error.contains("NOSUCHTYPE")); //$NON-NLS-1$
    }

    // ==================== offBalance ================================================================

    @Test
    public void testOffBalanceFlagStored()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main"); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.offBalance = Boolean.TRUE;
        props.offBalanceSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(coa, "MC", props, false); //$NON-NLS-1$
        assertFalse(result.isError());
        assertTrue(((ChartOfAccountsPredefinedItem)result.item).isOffBalance());
    }

    // ==================== code length (String, never truncated) =====================================

    @Test
    public void testCodeOverLengthRejectedNeverTruncated()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main", 3, 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive("LONG"); // 4 chars > codeLength 3 //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(coa, "X", props, false); //$NON-NLS-1$
        assertTrue("an over-length code must be REJECTED, never silently truncated", result.isError()); //$NON-NLS-1$
        assertTrue(result.error.contains("codeLength")); //$NON-NLS-1$
    }

    @Test
    public void testCodeAtLengthLimitStoredVerbatim()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main", 3, 5); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive("101"); // exactly 3 chars //$NON-NLS-1$
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(coa, "Cash", props, false); //$NON-NLS-1$
        assertFalse("exactly-at-limit must be accepted: " + result.error, result.isError()); //$NON-NLS-1$
        assertEquals("101", PredefinedWriter.displayCode(result.item)); //$NON-NLS-1$
        assertEquals("101", ((ChartOfAccountsPredefinedItem)result.item).getCode()); //$NON-NLS-1$
    }

    @Test
    public void testCodeRejectsJsonNumber()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main", 9, 9); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.code = new JsonPrimitive(42); // a ChartOfAccounts code is a String
        props.codeSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(coa, "Cash", props, false); //$NON-NLS-1$
        assertTrue(result.isError());
        assertTrue(result.error.contains("must be a JSON string")); //$NON-NLS-1$
    }

    // ==================== order length (String, never truncated) ====================================

    @Test
    public void testOrderOverLengthRejectedNeverTruncated()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main", 5, 3); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.order = new JsonPrimitive("LONG"); // 4 chars > orderLength 3 //$NON-NLS-1$
        props.orderSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(coa, "X", props, false); //$NON-NLS-1$
        assertTrue("an over-length order must be REJECTED, never silently truncated", result.isError()); //$NON-NLS-1$
        assertTrue("the error must name the order overflow: " + result.error, //$NON-NLS-1$
            result.error.toLowerCase(Locale.ROOT).contains("order")); //$NON-NLS-1$
    }

    @Test
    public void testOrderAtLengthLimitStoredVerbatim()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main", 5, 3); //$NON-NLS-1$
        PredefinedWriter.ItemProps props = new PredefinedWriter.ItemProps();
        props.order = new JsonPrimitive("010"); // exactly 3 chars //$NON-NLS-1$
        props.orderSet = true;
        PredefinedWriter.WriteResult result = PredefinedWriter.create(coa, "Cash", props, false); //$NON-NLS-1$
        assertFalse("exactly-at-limit must be accepted: " + result.error, result.isError()); //$NON-NLS-1$
        assertEquals("010", ((ChartOfAccountsPredefinedItem)result.item).getOrder()); //$NON-NLS-1$
    }

    // ==================== read-side: childItems hierarchy + Folder = '-' ============================

    @Test
    public void testListAllRecursesChildItemsAndIsFolderIsFalse()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main"); //$NON-NLS-1$
        PredefinedWriter.WriteResult parent =
            PredefinedWriter.create(coa, "Cash", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        assertFalse(parent.isError());

        // Hand-build a child account under the parent via the CONTAINMENT childItems list (mirrors the
        // Catalog test's hand-built getContent() chain) - the read-side must recurse it.
        ChartOfAccountsPredefinedItem child = MdClassFactory.eINSTANCE.createChartOfAccountsPredefinedItem();
        child.setName("PettyCash"); //$NON-NLS-1$
        ((ChartOfAccountsPredefinedItem)parent.item).getChildItems().add(child);

        List<PredefinedWriter.ItemRow> rows = PredefinedWriter.listAll(coa);
        assertEquals(2, rows.size());
        assertEquals("Cash", rows.get(0).name); //$NON-NLS-1$
        assertEquals(0, rows.get(0).depth);
        assertFalse("a Chart of Accounts item is never a folder (Folder column = '-')", //$NON-NLS-1$
            rows.get(0).isFolder);
        assertEquals("PettyCash", rows.get(1).name); //$NON-NLS-1$
        assertEquals(1, rows.get(1).depth);
        assertEquals("Cash", rows.get(1).parentName); //$NON-NLS-1$
        assertFalse(rows.get(1).isFolder);
    }

    // ==================== extDimensionTypes nested keys are case-insensitive (issue #296 P3) =========

    @Test
    public void testExtDimensionTypesCamelCaseKeysParse()
    {
        // The DOCUMENTED payload uses camelCase nested keys (characteristicType /
        // extDimensionAccountingFlags). JsonObject.get() is case-sensitive, so these must be matched
        // through the lower-cased view - a regression here would silently report the documented
        // characteristicType as missing (the same case tolerance the top-level property switch gives).
        JsonObject row = new JsonObject();
        row.addProperty("characteristicType", "Contractors"); //$NON-NLS-1$ //$NON-NLS-2$
        row.addProperty("turnover", true); //$NON-NLS-1$
        JsonArray rowFlags = new JsonArray();
        rowFlags.add("Sum"); //$NON-NLS-1$
        row.add("extDimensionAccountingFlags", rowFlags); //$NON-NLS-1$

        PredefinedWriter.ItemProps out = parseExtDimensionTypesProperty(row);
        assertEquals(1, out.extDimensionTypes.size());
        PredefinedWriter.ExtDimensionTypeSpec spec = out.extDimensionTypes.get(0);
        assertEquals("Contractors", spec.characteristicType); //$NON-NLS-1$
        assertTrue(spec.turnover);
        assertEquals(List.of("Sum"), spec.extDimensionAccountingFlags); //$NON-NLS-1$
    }

    @Test
    public void testExtDimensionTypesKeysAreCaseInsensitiveArbitraryCasing()
    {
        JsonObject row = new JsonObject();
        row.addProperty("CHARACTERISTICTYPE", "Products"); // arbitrary casing still resolves //$NON-NLS-1$ //$NON-NLS-2$
        PredefinedWriter.ItemProps out = parseExtDimensionTypesProperty(row);
        assertEquals("Products", out.extDimensionTypes.get(0).characteristicType); //$NON-NLS-1$
    }

    /** Wraps one row into the {@code {name:"extDimensionTypes", value:[row]}} property and parses it. */
    private static PredefinedWriter.ItemProps parseExtDimensionTypesProperty(JsonObject row)
    {
        JsonArray rows = new JsonArray();
        rows.add(row);
        JsonObject prop = new JsonObject();
        prop.addProperty("name", "extDimensionTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.add("value", rows); //$NON-NLS-1$
        PredefinedWriter.ItemProps out = new PredefinedWriter.ItemProps();
        String err = PredefinedWriter.parseProperties(List.of(prop), true, out);
        assertNull("documented camelCase extDimensionTypes keys must parse: " + err, err); //$NON-NLS-1$
        assertTrue(out.extDimensionTypesSet);
        return out;
    }

    // ==================== childItems tree walk is cycle-safe (issue #296 P3) =========================

    @Test(timeout = 5000)
    public void testChildItemsCycleTerminatesWalk()
    {
        ChartOfAccountsPredefinedItem a = MdClassFactory.eINSTANCE.createChartOfAccountsPredefinedItem();
        a.setName("A"); //$NON-NLS-1$
        ChartOfAccountsPredefinedItem b = MdClassFactory.eINSTANCE.createChartOfAccountsPredefinedItem();
        b.setName("B"); //$NON-NLS-1$
        // An in-memory containment cycle A <-> B. A disk-loaded .mdo cannot express this (nested XML
        // serializes each item under exactly one parent), so it is not reachable in production, but the
        // iterative item-rooted walks must still TERMINATE on it (skip-on-revisit) rather than loop
        // forever on the wire thread - the @Test timeout turns a regression into a failure, not a hang.
        a.getChildItems().add(b);
        b.getChildItems().add(a);

        // The root is pre-marked, so the back-edge to it is skipped and the OTHER node is visited once.
        assertEquals(1, PredefinedWriter.countDescendants(a));
        assertEquals(1, PredefinedWriter.descendants(a).size());
        assertEquals("B", PredefinedWriter.descendants(a).get(0).getName()); //$NON-NLS-1$
        assertEquals(1, PredefinedWriter.descendantRows(a).size());
    }

    // ==================== render is dangling-reference safe (issue #296 P3) =========================

    @Test
    public void testDisplayExtDimensionTypesDanglingCharacteristicTypeRendersMarkerNotNpe()
    {
        ChartOfAccounts coa = newChartOfAccounts("Main"); //$NON-NLS-1$
        PredefinedWriter.WriteResult account =
            PredefinedWriter.create(coa, "Settlements", new PredefinedWriter.ItemProps(), false); //$NON-NLS-1$
        assertFalse(account.isError());
        ChartOfAccountsPredefinedItem item = (ChartOfAccountsPredefinedItem)account.item;

        // A DANGLING characteristicType: the linked CCT predefined item was removed (force=true) and
        // left a null Name (an unresolved proxy behaves the same). The render must degrade to a "?"
        // marker, never throw new StringBuilder((String) null) and fail the whole get_metadata_details.
        ExtDimensionType ext = MdClassFactory.eINSTANCE.createExtDimensionType();
        ChartOfCharacteristicTypesPredefinedItem dangling =
            MdClassFactory.eINSTANCE.createChartOfCharacteristicTypesPredefinedItem();
        // dangling.getName() is deliberately left null
        ext.setCharacteristicType(dangling);
        item.getExtDimensionTypes().add(ext);

        assertEquals("?", PredefinedWriter.displayExtDimensionTypes(item)); //$NON-NLS-1$
    }
}

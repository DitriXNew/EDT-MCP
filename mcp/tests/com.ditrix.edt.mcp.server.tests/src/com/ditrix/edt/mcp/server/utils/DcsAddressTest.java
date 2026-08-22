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

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Tests the pure {@link DcsAddress} parser and canonical renderer. */
public class DcsAddressTest
{
    @Test
    public void testBareRootHasNoPointer()
    {
        DcsAddress address = success("Report.Sales"); //$NON-NLS-1$

        assertEquals("Report.Sales", address.rootFqn()); //$NON-NLS-1$
        assertTrue(address.segments().isEmpty());
        assertFalse(address.hasPointer());
        assertFalse(address.isIndexAddressed());
        assertEquals("Report.Sales", address.toString()); //$NON-NLS-1$
    }

    @Test
    public void testSplitsAtFirstHashOnly()
    {
        DcsAddress address = success("Report.Sales#/dataSets/A#B"); //$NON-NLS-1$

        assertEquals(Arrays.asList("dataSets", "A#B"), address.segments()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Report.Sales#/dataSets/A#B", address.toString()); //$NON-NLS-1$
    }

    @Test
    public void testDecodesEscapesInRfcOrder()
    {
        DcsAddress address = success("Report.Sales#/dataSets/a~1b~0c/~01/~10"); //$NON-NLS-1$

        assertEquals(Arrays.asList("dataSets", "a/b~c", "~1", "/0"), address.segments()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertEquals("Report.Sales#/dataSets/a~1b~0c/~01/~10", address.toString()); //$NON-NLS-1$
    }

    @Test
    public void testRendererEscapesSlashAndTildeForExactRoundTrip()
    {
        String rendered = DcsAddress.render("CommonTemplate.Analytics", //$NON-NLS-1$
            Arrays.asList("dataSets", "Sales/Returns~Current", "fields", "Item/Code~Raw")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        assertEquals("CommonTemplate.Analytics#/dataSets/Sales~1Returns~0Current/fields/Item~1Code~0Raw", //$NON-NLS-1$
            rendered);
        DcsAddress reparsed = success(rendered);
        assertEquals(Arrays.asList("dataSets", "Sales/Returns~Current", "fields", "Item/Code~Raw"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            reparsed.segments());
        assertEquals(rendered, reparsed.toString());
    }

    @Test
    public void testNaturalKeysAreContextualEvenWhenNumeric()
    {
        DcsAddress address = success("Report.Sales#/dataSets/0/fields/42"); //$NON-NLS-1$

        assertTrue(address.isNaturalKeySegment(1));
        assertTrue(address.isNaturalKeySegment(3));
        assertFalse(address.isIndexSegment(1));
        assertFalse(address.isIndexAddressed());
    }

    @Test
    public void testOrderedSettingsItemIsAnIndex()
    {
        DcsAddress address = success("Report.Sales#/defaultSettings/filter/items/12"); //$NON-NLS-1$

        assertTrue(address.isIndexSegment(3));
        assertEquals(12, address.indexAt(3).getAsInt());
        assertTrue(address.isIndexAddressed());
        assertFalse(address.isNaturalKeySegment(3));
    }

    @Test
    public void testNestedFilterItemRemainsIndexAddressed()
    {
        DcsAddress address = success("Report.Sales#/defaultSettings/filter/items/0/items/1"); //$NON-NLS-1$

        assertTrue(address.isIndexSegment(3));
        assertTrue(address.isIndexSegment(5));
        assertTrue(address.isIndexAddressed());
    }

    @Test
    public void testStructureItemRequiresIndexEvenWhenGroupingHasAName()
    {
        DcsAddress indexed = success("Report.Sales#/defaultSettings/items/0"); //$NON-NLS-1$

        assertTrue(indexed.isIndexSegment(2));
        assertTrue(indexed.isIndexAddressed());
        assertFailure("Report.Sales#/defaultSettings/items/ByCustomer", //$NON-NLS-1$
            DcsAddress.FailureCode.INVALID_INDEX, "ByCustomer", "'ByCustomer'", //$NON-NLS-1$ //$NON-NLS-2$
            "zero-based"); //$NON-NLS-1$
    }

    @Test
    public void testRowsAndColumnsRequireIndices()
    {
        DcsAddress row = success("Report.Sales#/defaultSettings/items/0/rows/3"); //$NON-NLS-1$
        DcsAddress column = success("Report.Sales#/defaultSettings/items/0/columns/4"); //$NON-NLS-1$

        assertTrue(row.isIndexSegment(4));
        assertTrue(column.isIndexSegment(4));
    }

    @Test
    public void testZeroBasedIndexLexicalHelper()
    {
        assertTrue(DcsAddress.isZeroBasedIndex("0")); //$NON-NLS-1$
        assertTrue(DcsAddress.isZeroBasedIndex("001")); //$NON-NLS-1$
        assertTrue(DcsAddress.isZeroBasedIndex(Integer.toString(Integer.MAX_VALUE)));
        assertFalse(DcsAddress.isZeroBasedIndex("-1")); //$NON-NLS-1$
        assertFalse(DcsAddress.isZeroBasedIndex("1.0")); //$NON-NLS-1$
        assertFalse(DcsAddress.isZeroBasedIndex("2147483648")); //$NON-NLS-1$
        assertFalse(DcsAddress.isZeroBasedIndex(null));
    }

    @Test
    public void testSegmentHelpersReturnFalseOutsideTheAddress()
    {
        DcsAddress address = success("Report.Sales#/dataSets/Sales"); //$NON-NLS-1$

        assertFalse(address.isNaturalKeySegment(-1));
        assertFalse(address.isNaturalKeySegment(2));
        assertFalse(address.isIndexSegment(-1));
        assertFalse(address.isIndexSegment(2));
        assertFalse(address.indexAt(2).isPresent());
    }

    @Test
    public void testValueEqualityUsesRootAndDecodedSegments()
    {
        DcsAddress first = success("Report.Sales#/dataSets/A~1B"); //$NON-NLS-1$
        DcsAddress second = success(DcsAddress.render("Report.Sales", Arrays.asList("dataSets", "A/B"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void testRenderBareRootForNullOrEmptySegments()
    {
        assertEquals("Report.Sales", DcsAddress.render("Report.Sales", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Report.Sales", DcsAddress.render("Report.Sales", Collections.<String> emptyList())); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMissingAddressIsStructuredFailure()
    {
        assertFailure(null, DcsAddress.FailureCode.MISSING_ADDRESS, null, "missing", "Pass"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFailure("", DcsAddress.FailureCode.MISSING_ADDRESS, "", "missing", "Pass"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testEmptyRootIsStructuredFailure()
    {
        assertFailure("#/dataSets/Sales", DcsAddress.FailureCode.MISSING_ROOT, "", //$NON-NLS-1$ //$NON-NLS-2$
            "#/dataSets/Sales", "before '#'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRootWhitespaceIsStructuredFailure()
    {
        assertFailure(" Report.Sales#/dataSets/Sales", DcsAddress.FailureCode.ROOT_WHITESPACE, //$NON-NLS-1$
            " Report.Sales", " Report.Sales", "Remove"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFailure("Report.Sales ", DcsAddress.FailureCode.ROOT_WHITESPACE, "Report.Sales ", //$NON-NLS-1$ //$NON-NLS-2$
            "Report.Sales ", "Remove"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testPointerMustStartWithSlash()
    {
        assertFailure("Report.Sales#dataSets/Sales", DcsAddress.FailureCode.INVALID_POINTER, //$NON-NLS-1$
            "dataSets/Sales", "dataSets/Sales", "start with '/'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFailure("Report.Sales#", DcsAddress.FailureCode.INVALID_POINTER, "", //$NON-NLS-1$ //$NON-NLS-2$
            "Pointer ''", "omit '#'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testEmptyPointerSegmentsAreRejected()
    {
        assertFailure("Report.Sales#/", DcsAddress.FailureCode.EMPTY_SEGMENT, "", //$NON-NLS-1$ //$NON-NLS-2$
            "position 0", "do not end"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFailure("Report.Sales#/dataSets//fields", DcsAddress.FailureCode.EMPTY_SEGMENT, "", //$NON-NLS-1$ //$NON-NLS-2$
            "position 1", "doubled '/'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFailure("Report.Sales#/dataSets/Sales/", DcsAddress.FailureCode.EMPTY_SEGMENT, "", //$NON-NLS-1$ //$NON-NLS-2$
            "position 2", "do not end"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testInvalidPointerEscapesAreRejected()
    {
        assertFailure("Report.Sales#/dataSets/A~2B", DcsAddress.FailureCode.INVALID_ESCAPE, "A~2B", //$NON-NLS-1$ //$NON-NLS-2$
            "~2", "'~0'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFailure("Report.Sales#/dataSets/A~", DcsAddress.FailureCode.INVALID_ESCAPE, "A~", //$NON-NLS-1$ //$NON-NLS-2$
            "invalid escape '~'", "'~1'"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNegativeIndexIsRejectedWhereIndexIsRequired()
    {
        assertFailure("Report.Sales#/defaultSettings/filter/items/-1", //$NON-NLS-1$
            DcsAddress.FailureCode.INVALID_INDEX, "-1", "'-1'", "non-negative"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testNonNumericIndexIsRejectedWhereIndexIsRequired()
    {
        assertFailure("Report.Sales#/defaultSettings/order/items/first", //$NON-NLS-1$
            DcsAddress.FailureCode.INVALID_INDEX, "first", "'first'", "zero-based"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFailure("Report.Sales#/defaultSettings/items/0/rows/last", //$NON-NLS-1$
            DcsAddress.FailureCode.INVALID_INDEX, "last", "'last'", "0 to"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testOutOfRangeIndexIsRejectedWhereIndexIsRequired()
    {
        assertFailure("Report.Sales#/defaultSettings/filter/items/2147483648", //$NON-NLS-1$
            DcsAddress.FailureCode.INVALID_INDEX, "2147483648", "2147483648", //$NON-NLS-1$ //$NON-NLS-2$
            Integer.toString(Integer.MAX_VALUE));
    }

    private static DcsAddress success(String raw)
    {
        DcsAddress.ParseResult result = DcsAddress.parse(raw);
        assertTrue(result.failure() == null ? "Expected parse success for " + raw //$NON-NLS-1$
            : result.failure().message(), result.isSuccess());
        assertNotNull(result.address());
        assertNull(result.failure());
        return result.address();
    }

    private static void assertFailure(String raw, DcsAddress.FailureCode code, String badValue,
        String messagePart, String fixPart)
    {
        DcsAddress.ParseResult result = DcsAddress.parse(raw);
        assertFalse("Expected parse failure for " + raw, result.isSuccess()); //$NON-NLS-1$
        assertNull(result.address());
        assertNotNull(result.failure());
        assertEquals(code, result.failure().code());
        assertEquals(badValue, result.failure().badValue());
        assertTrue(result.failure().message(), result.failure().message().contains(messagePart));
        assertTrue(result.failure().message(), result.failure().message().contains(fixPart));
    }
}

/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tags;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the PURE, string/FQN-only helpers of {@link TagUtils}.
 * <p>
 * Only the methods that take Strings/primitives and return deterministically WITHOUT touching an
 * {@code EObject}, {@code IProject}, an EDT model object or any platform service are covered here:
 * {@link TagUtils#buildNewFqn(String, String)}, {@link TagUtils#isFqnOfType(String, String)},
 * {@link TagUtils#getTypeFromFqn(String)} and {@link TagUtils#getTopNameFromFqn(String)}.
 * <p>
 * The EObject / IProject / service-based methods (extractFqn, getObjectName, getParentForFqn,
 * extractProject, extractProjectFromElement, unwrapToEObject, extractMdObject) need a live EDT
 * workspace / EMF model / OSGi platform and are exercised by the e2e suite, not here.
 */
public class TagUtilsTest
{
    // ==================== buildNewFqn ====================

    @Test
    public void testBuildNewFqnReplacesLastNameInTwoSegmentFqn()
    {
        assertEquals("Document.NewName", //$NON-NLS-1$
            TagUtils.buildNewFqn("Document.OldName", "NewName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBuildNewFqnReplacesLastNameInNestedFqn()
    {
        // Only the trailing name component is replaced; the type prefix is preserved.
        assertEquals("Catalog.Products.Attribute.NewName", //$NON-NLS-1$
            TagUtils.buildNewFqn("Catalog.Products.Attribute.OldName", "NewName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBuildNewFqnWithNoDotReturnsNewNameOnly()
    {
        // No dot in the input -> there is no type prefix to keep, so just the new name is returned.
        assertEquals("NewName", TagUtils.buildNewFqn("OldName", "NewName")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testBuildNewFqnWithLeadingDotReturnsNewNameOnly()
    {
        // lastDot is at index 0 (not > 0), so the prefix branch is not taken.
        assertEquals("NewName", TagUtils.buildNewFqn(".OldName", "NewName")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testBuildNewFqnWithTrailingDotKeepsPrefix()
    {
        // The last dot is the trailing one; everything up to and including it is kept.
        assertEquals("Document.NewName", //$NON-NLS-1$
            TagUtils.buildNewFqn("Document.", "NewName")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBuildNewFqnWithEmptyNewName()
    {
        assertEquals("Document.", TagUtils.buildNewFqn("Document.OldName", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testBuildNewFqnWithNullOldFqnReturnsNull()
    {
        assertNull(TagUtils.buildNewFqn(null, "NewName")); //$NON-NLS-1$
    }

    @Test
    public void testBuildNewFqnWithNullNewNameReturnsNull()
    {
        assertNull(TagUtils.buildNewFqn("Document.OldName", null)); //$NON-NLS-1$
    }

    @Test
    public void testBuildNewFqnWithBothNullReturnsNull()
    {
        assertNull(TagUtils.buildNewFqn(null, null));
    }

    // ==================== isFqnOfType ====================

    @Test
    public void testIsFqnOfTypeMatching()
    {
        assertTrue(TagUtils.isFqnOfType("Document.SalesOrder", "Document")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIsFqnOfTypeMatchingNestedFqn()
    {
        assertTrue(TagUtils.isFqnOfType("Catalog.Products.Attribute.Name", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIsFqnOfTypeNotMatchingDifferentType()
    {
        assertFalse(TagUtils.isFqnOfType("Document.SalesOrder", "Catalog")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIsFqnOfTypeRequiresTrailingDot()
    {
        // "Document" alone (no dot) must NOT match the type "Document": a separator is required.
        assertFalse(TagUtils.isFqnOfType("Document", "Document")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIsFqnOfTypePrefixWithoutDotIsNotAMatch()
    {
        // "DocumentSalesOrder" starts with "Document" but not "Document.", so it must not match.
        assertFalse(TagUtils.isFqnOfType("DocumentSalesOrder", "Document")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIsFqnOfTypeIsCaseSensitive()
    {
        // No normalization is performed; the comparison is a literal startsWith.
        assertFalse(TagUtils.isFqnOfType("document.SalesOrder", "Document")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIsFqnOfTypeWithNullFqnReturnsFalse()
    {
        assertFalse(TagUtils.isFqnOfType(null, "Document")); //$NON-NLS-1$
    }

    @Test
    public void testIsFqnOfTypeWithNullTypeReturnsFalse()
    {
        assertFalse(TagUtils.isFqnOfType("Document.SalesOrder", null)); //$NON-NLS-1$
    }

    @Test
    public void testIsFqnOfTypeWithBothNullReturnsFalse()
    {
        assertFalse(TagUtils.isFqnOfType(null, null));
    }

    // ==================== getTypeFromFqn ====================

    @Test
    public void testGetTypeFromFqnTwoSegments()
    {
        assertEquals("Document", TagUtils.getTypeFromFqn("Document.SalesOrder")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetTypeFromFqnNestedReturnsTopType()
    {
        // Only the segment before the FIRST dot is returned.
        assertEquals("Document", //$NON-NLS-1$
            TagUtils.getTypeFromFqn("Document.SalesOrder.Attribute.Name")); //$NON-NLS-1$
    }

    @Test
    public void testGetTypeFromFqnNoDotReturnsNull()
    {
        assertNull(TagUtils.getTypeFromFqn("Document")); //$NON-NLS-1$
    }

    @Test
    public void testGetTypeFromFqnLeadingDotReturnsNull()
    {
        // The first dot is at index 0 (not > 0), so no type is extracted.
        assertNull(TagUtils.getTypeFromFqn(".SalesOrder")); //$NON-NLS-1$
    }

    @Test
    public void testGetTypeFromFqnNullReturnsNull()
    {
        assertNull(TagUtils.getTypeFromFqn(null));
    }

    @Test
    public void testGetTypeFromFqnEmptyReturnsNull()
    {
        assertNull(TagUtils.getTypeFromFqn("")); //$NON-NLS-1$
    }

    // ==================== getTopNameFromFqn ====================

    @Test
    public void testGetTopNameFromFqnTwoSegments()
    {
        assertEquals("SalesOrder", TagUtils.getTopNameFromFqn("Document.SalesOrder")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGetTopNameFromFqnNestedReturnsSecondSegment()
    {
        // The second segment (the top object's name) is returned, ignoring deeper parts.
        assertEquals("SalesOrder", //$NON-NLS-1$
            TagUtils.getTopNameFromFqn("Document.SalesOrder.Attribute.Name")); //$NON-NLS-1$
    }

    @Test
    public void testGetTopNameFromFqnSingleSegmentReturnsNull()
    {
        // Only one segment -> there is no top object name.
        assertNull(TagUtils.getTopNameFromFqn("Document")); //$NON-NLS-1$
    }

    @Test
    public void testGetTopNameFromFqnNullReturnsNull()
    {
        assertNull(TagUtils.getTopNameFromFqn(null));
    }

    @Test
    public void testGetTopNameFromFqnEmptyReturnsNull()
    {
        assertNull(TagUtils.getTopNameFromFqn("")); //$NON-NLS-1$
    }

    @Test
    public void testGetTopNameFromFqnTrailingDotYieldsTopName()
    {
        // String.split drops trailing empty strings, but the second segment is still present here.
        assertEquals("SalesOrder", TagUtils.getTopNameFromFqn("Document.SalesOrder.")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}

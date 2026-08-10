/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.junit.Test;

/**
 * Unit tests for the SWT-free core of {@link NavigatorSearchState} - the decision that tells
 * {@link GroupedObjectsFilter} whether a text search is narrowing the Navigator tree.
 *
 * <p>The regression these lock down: detection used to be presence-based (a filter whose class
 * name contains "Search" is attached =&gt; a search is running). The EDT Comfort plugin keeps the
 * native EDT search filter attached to the CommonViewer permanently, with an empty pattern, so
 * group filtering stayed off forever and every grouped object showed up twice - inside its group
 * and in the root collection (issue #380).</p>
 *
 * <p>The stubs are top-level classes on purpose: as nested classes their binary names would carry
 * the "...SearchStateTest$..." prefix and every one of them would match the class-name heuristic.</p>
 *
 * <p>The live wiring (reading the pattern off the real EDT Navigator) is verified in EDT.</p>
 */
public class NavigatorSearchStateTest
{
    private static ViewerFilter[] filters(ViewerFilter... viewerFilters)
    {
        return viewerFilters;
    }

    // ------------------------------------------------------- the Navigator pattern decides

    @Test
    public void testAttachedNativeFilterWithEmptyPatternIsNotASearch()
    {
        // The EDT Comfort layout: the native search filter never leaves the viewer (issue #380).
        assertFalse(NavigatorSearchState.isSearchActive("", //$NON-NLS-1$
            filters(new NavigatorSearchFilterStub("")), null)); //$NON-NLS-1$
    }

    @Test
    public void testNavigatorPatternMakesItASearch()
    {
        assertTrue(NavigatorSearchState.isSearchActive("ГуглПереводчик", //$NON-NLS-1$
            filters(new NavigatorSearchFilterStub("ГуглПереводчик")), null)); //$NON-NLS-1$
    }

    @Test
    public void testBlankNavigatorPatternIsNotASearch()
    {
        assertFalse(NavigatorSearchState.isSearchActive("   ", filters(), null)); //$NON-NLS-1$
    }

    @Test
    public void testNavigatorPatternWinsOverAnOpaqueFilter()
    {
        // The Navigator answered "nothing is being searched" - an unreadable filter cannot veto it.
        assertFalse(NavigatorSearchState.isSearchActive("", filters(new OpaqueSearchFilterStub()), null)); //$NON-NLS-1$
    }

    // --------------------------------------------- no Navigator pattern: question the filters

    @Test
    public void testFilterPatternIsUsedWhenTheNavigatorCannotBeRead()
    {
        assertTrue(NavigatorSearchState.isSearchActive(null,
            filters(new NavigatorSearchFilterStub("Гугл")), null)); //$NON-NLS-1$
        assertFalse(NavigatorSearchState.isSearchActive(null, filters(new NavigatorSearchFilterStub("")), null)); //$NON-NLS-1$
    }

    @Test
    public void testPatternIsReadThroughAStateHolder()
    {
        assertTrue(NavigatorSearchState.isSearchActive(null,
            filters(new SearchFilterWithHistoryStub("Обмен")), null)); //$NON-NLS-1$
        assertFalse(NavigatorSearchState.isSearchActive(null, filters(new SearchFilterWithHistoryStub("")), null)); //$NON-NLS-1$
    }

    @Test
    public void testOpaqueSearchFilterKeepsTheConservativeAnswer()
    {
        // Nothing can be asked: assume a search rather than hide a grouped object from the results.
        assertTrue(NavigatorSearchState.isSearchActive(null, filters(new OpaqueSearchFilterStub()), null));
    }

    @Test
    public void testNonSearchFilterIsIgnored()
    {
        assertFalse(NavigatorSearchState.isSearchActive(null, filters(new SubsystemsFilterStub()), null));
    }

    @Test
    public void testNoFiltersMeansNoSearch()
    {
        assertFalse(NavigatorSearchState.isSearchActive(null, filters(), null));
    }

    // ------------------------------------------------------------------------ scan exclusions

    @Test
    public void testCallingFilterIsSkipped()
    {
        ViewerFilter self = new OpaqueSearchFilterStub();
        assertFalse(NavigatorSearchState.isSearchActive(null, filters(self), self));
    }

    @Test
    public void testNullFilterEntryIsSkipped()
    {
        assertFalse(NavigatorSearchState.isSearchActive(null, filters((ViewerFilter)null), null));
    }

    // -------------------------------------------------------------------------- readPattern

    @Test
    public void testReadPatternReadsDirectAccessor()
    {
        assertEquals("Справочник", //$NON-NLS-1$
            NavigatorSearchState.readPattern(new NavigatorSearchFilterStub("Справочник"))); //$NON-NLS-1$
    }

    @Test
    public void testReadPatternReadsStateHolder()
    {
        assertEquals("Справочник", //$NON-NLS-1$
            NavigatorSearchState.readPattern(new SearchFilterWithHistoryStub("Справочник"))); //$NON-NLS-1$
    }

    @Test
    public void testReadPatternReturnsNullWhenNothingCanBeAsked()
    {
        assertNull(NavigatorSearchState.readPattern(new OpaqueSearchFilterStub()));
        assertNull(NavigatorSearchState.readPattern(null));
    }

    @Test
    public void testReadNavigatorPatternIgnoresPlainViewers()
    {
        assertNull(NavigatorSearchState.readNavigatorPattern(null));
    }
}

/** Stands in for the native EDT search filter: named like one, answers with its own pattern. */
class NavigatorSearchFilterStub
    extends ViewerFilter
{
    private final String pattern;

    NavigatorSearchFilterStub(String pattern)
    {
        this.pattern = pattern;
    }

    public String getActivePattern()
    {
        return pattern;
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element)
    {
        return true;
    }
}

/** Named like a search filter but exposes no pattern at all - the unknown case. */
class OpaqueSearchFilterStub
    extends ViewerFilter
{
    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element)
    {
        return true;
    }
}

/** Not a search filter by name; must never be questioned. */
class SubsystemsFilterStub
    extends ViewerFilter
{
    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element)
    {
        return true;
    }
}

/** Exposes its pattern through a state holder, the way SearchFilterWithHistory does. */
class SearchFilterWithHistoryStub
    extends ViewerFilter
{
    private final String pattern;

    SearchFilterWithHistoryStub(String pattern)
    {
        this.pattern = pattern;
    }

    public Object getSearchHistory()
    {
        return new SearchHistoryStub(pattern);
    }

    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element)
    {
        return true;
    }
}

/** The state holder behind {@link SearchFilterWithHistoryStub}. */
class SearchHistoryStub
{
    private final String pattern;

    SearchHistoryStub(String pattern)
    {
        this.pattern = pattern;
    }

    public String getActivePattern()
    {
        return pattern;
    }
}

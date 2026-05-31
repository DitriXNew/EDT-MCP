/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.ditrix.edt.mcp.server.tools.impl.GetProjectErrorsTool.ErrorInfo;

/**
 * Unit tests for the marker filtering / building helpers of {@link GetProjectErrorsTool}.
 *
 * <p>Focuses on the review point 1 (PR #120) discrepancy: a marker whose location cannot
 * be resolved must be counted as {@code unresolvedShown} when it is still reported with a
 * placeholder, and as {@code unresolvedFilteredOut} when an explicit {@code objects} filter
 * excludes it from the result. These two cases must never overlap.</p>
 *
 * <p>{@link Marker} / {@link IProject} / {@link ICheckRepository} are mocked with Mockito.
 * The symbolic-check-id resolution success path goes through the platform
 * {@code ICheckRepository.getUidForShortUid} + {@code CheckUid} and is exercised by e2e; the
 * pure substring matching it feeds into is covered directly via {@link #checkIdMatches}.</p>
 */
public class GetProjectErrorsToolTest
{
    // ========== checkIdMatches (pure) ==========

    @Test
    public void testCheckIdMatchesByShortUid()
    {
        assertTrue(GetProjectErrorsTool.checkIdMatches("SU23", null, "su2")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCheckIdMatchesBySymbolicId()
    {
        assertTrue(GetProjectErrorsTool.checkIdMatches("SU23", "ql-temp-table-index", "temp")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testCheckIdMatchesCaseInsensitive()
    {
        assertTrue(GetProjectErrorsTool.checkIdMatches("Su23", null, "SU23")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GetProjectErrorsTool.checkIdMatches(null, "QL-Temp-Table", "ql-temp")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCheckIdMatchesNoMatch()
    {
        assertFalse(GetProjectErrorsTool.checkIdMatches("SU23", "ql-temp-table-index", "zzz")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testCheckIdMatchesBothNull()
    {
        assertFalse(GetProjectErrorsTool.checkIdMatches(null, null, "anything")); //$NON-NLS-1$
    }

    // ========== unresolvedPlaceholder ==========

    @Test
    public void testUnresolvedPlaceholderWithProject()
    {
        IProject project = project("MyProject"); //$NON-NLS-1$
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(project);
        assertEquals("<unresolved: MyProject>", GetProjectErrorsTool.unresolvedPlaceholder(marker)); //$NON-NLS-1$
    }

    @Test
    public void testUnresolvedPlaceholderNullProject()
    {
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(null);
        assertEquals("<unresolved: ?>", GetProjectErrorsTool.unresolvedPlaceholder(marker)); //$NON-NLS-1$
    }

    // ========== resolveSymbolicCheckId null-guards ==========

    @Test
    public void testResolveSymbolicCheckIdNullRepository()
    {
        Marker marker = mock(Marker.class);
        assertNull(GetProjectErrorsTool.resolveSymbolicCheckId(marker, "SU23", null)); //$NON-NLS-1$
    }

    @Test
    public void testResolveSymbolicCheckIdEmptyShortUid()
    {
        Marker marker = mock(Marker.class);
        ICheckRepository repo = mock(ICheckRepository.class);
        assertNull(GetProjectErrorsTool.resolveSymbolicCheckId(marker, "", repo)); //$NON-NLS-1$
    }

    @Test
    public void testResolveSymbolicCheckIdNullProject()
    {
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(null);
        ICheckRepository repo = mock(ICheckRepository.class);
        assertNull(GetProjectErrorsTool.resolveSymbolicCheckId(marker, "SU23", repo)); //$NON-NLS-1$
    }

    @Test
    public void testResolveSymbolicCheckIdSuccess()
    {
        IProject project = project("Proj"); //$NON-NLS-1$
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(project);
        CheckUid uid = checkUid("ql-temp-table-index"); //$NON-NLS-1$
        ICheckRepository repo = mock(ICheckRepository.class);
        when(repo.getUidForShortUid(eq("SU23"), any(IProject.class))).thenReturn(uid); //$NON-NLS-1$

        assertEquals("ql-temp-table-index", //$NON-NLS-1$
            GetProjectErrorsTool.resolveSymbolicCheckId(marker, "SU23", repo)); //$NON-NLS-1$
    }

    // ========== buildIfMatches: review point 1 counters ==========

    @Test
    public void testObjectsFilterUnresolvedCountedAsFilteredOut()
    {
        // Active objects filter + presentation cannot be resolved -> excluded, counted as
        // filteredOut only (NOT shown). This is the exact review point 1 discrepancy.
        Marker marker = markerThatThrowsOnPresentation(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int[] shown = {0};
        int[] filteredOut = {0};

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            singleton("catalog.foo"), null, shown, filteredOut); //$NON-NLS-1$

        assertNull(error);
        assertEquals(0, shown[0]);
        assertEquals(1, filteredOut[0]);
    }

    @Test
    public void testNoObjectsFilterUnresolvedCountedAsShown()
    {
        // No objects filter + presentation cannot be resolved -> reported with placeholder,
        // counted as shown only (NOT filteredOut).
        Marker marker = markerThatThrowsOnPresentation(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int[] shown = {0};
        int[] filteredOut = {0};

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            Collections.emptySet(), null, shown, filteredOut);

        assertNotNull(error);
        assertEquals("<unresolved: Proj>", error.objectPresentation); //$NON-NLS-1$
        assertEquals("SU23", error.checkCode); //$NON-NLS-1$
        assertNull(error.checkId);
        assertFalse(error.hasDocumentation);
        assertEquals(1, shown[0]);
        assertEquals(0, filteredOut[0]);
    }

    @Test
    public void testResolvedMarkerNoCountersIncremented()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$
        int[] shown = {0};
        int[] filteredOut = {0};

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            Collections.emptySet(), null, shown, filteredOut);

        assertNotNull(error);
        assertEquals("Catalog.Foo", error.objectPresentation); //$NON-NLS-1$
        assertEquals("msg", error.message); //$NON-NLS-1$
        assertEquals(0, shown[0]);
        assertEquals(0, filteredOut[0]);
    }

    @Test
    public void testObjectsFilterResolvedButEmptyPresentationExcludedWithoutCounter()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn(""); //$NON-NLS-1$
        int[] shown = {0};
        int[] filteredOut = {0};

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            singleton("catalog.foo"), null, shown, filteredOut); //$NON-NLS-1$

        assertNull(error);
        assertEquals(0, shown[0]);
        assertEquals(0, filteredOut[0]);
    }

    // ========== buildIfMatches: filters ==========

    @Test
    public void testSeverityFilterExcludes()
    {
        // Mismatching severity returns null before the presentation is ever read.
        Marker marker = markerThatThrowsOnPresentation(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int[] shown = {0};
        int[] filteredOut = {0};

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, MarkerSeverity.MAJOR, null,
            Collections.emptySet(), null, shown, filteredOut);

        assertNull(error);
        assertEquals(0, shown[0]);
        assertEquals(0, filteredOut[0]);
    }

    @Test
    public void testObjectsFilterMatchesSubstring()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            singleton("catalog.foo"), null, new int[]{0}, new int[]{0}); //$NON-NLS-1$

        assertNotNull(error);
    }

    @Test
    public void testObjectsFilterNoMatch()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, null,
            singleton("catalog.bar"), null, new int[]{0}, new int[]{0}); //$NON-NLS-1$

        assertNull(error);
    }

    @Test
    public void testCheckIdFilterMatchesShortUid()
    {
        Marker marker = marker(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, "su2",
            Collections.emptySet(), null, new int[]{0}, new int[]{0}); //$NON-NLS-1$

        assertNotNull(error);
    }

    @Test
    public void testCheckIdFilterMatchesSymbolicId()
    {
        // checkId matches only the resolved symbolic id, not the short UID. Exercises the
        // resolveSymbolicCheckId -> checkIdMatches integration inside buildIfMatches.
        IProject project = project("Proj"); //$NON-NLS-1$
        Marker marker = mock(Marker.class);
        when(marker.getSeverity()).thenReturn(MarkerSeverity.MINOR);
        when(marker.getCheckId()).thenReturn("SU23"); //$NON-NLS-1$
        when(marker.getMessage()).thenReturn("msg"); //$NON-NLS-1$
        when(marker.getProject()).thenReturn(project);
        when(marker.getObjectPresentation()).thenReturn("Catalog.Foo"); //$NON-NLS-1$
        CheckUid uid = checkUid("ql-temp-table-index"); //$NON-NLS-1$
        ICheckRepository repo = mock(ICheckRepository.class);
        when(repo.getUidForShortUid(eq("SU23"), any(IProject.class))).thenReturn(uid); //$NON-NLS-1$

        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, "temp",
            Collections.emptySet(), repo, new int[]{0}, new int[]{0}); //$NON-NLS-1$

        assertNotNull(error);
        assertEquals("SU23", error.checkCode); //$NON-NLS-1$
        assertEquals("ql-temp-table-index", error.checkId); //$NON-NLS-1$
    }

    @Test
    public void testCheckIdFilterExcludes()
    {
        Marker marker = markerThatThrowsOnPresentation(MarkerSeverity.MINOR, "SU23", "msg", "Proj"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        int[] shown = {0};
        int[] filteredOut = {0};

        // checkId does not match -> null before the presentation is read; no counter touched.
        ErrorInfo error = GetProjectErrorsTool.buildIfMatches(marker, null, "zzz",
            Collections.emptySet(), null, shown, filteredOut); //$NON-NLS-1$

        assertNull(error);
        assertEquals(0, shown[0]);
        assertEquals(0, filteredOut[0]);
    }

    // ========== helpers ==========

    private static Marker marker(MarkerSeverity severity, String checkId, String message, String projectName)
    {
        // Build the project mock first; stubbing one mock inside another's thenReturn() trips
        // Mockito's UnfinishedStubbingException.
        IProject project = project(projectName);
        Marker marker = mock(Marker.class);
        when(marker.getSeverity()).thenReturn(severity);
        when(marker.getCheckId()).thenReturn(checkId);
        when(marker.getMessage()).thenReturn(message);
        when(marker.getProject()).thenReturn(project);
        return marker;
    }

    private static Marker markerThatThrowsOnPresentation(MarkerSeverity severity, String checkId,
        String message, String projectName)
    {
        Marker marker = marker(severity, checkId, message, projectName);
        when(marker.getObjectPresentation()).thenThrow(new RuntimeException("cannot resolve")); //$NON-NLS-1$
        return marker;
    }

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static CheckUid checkUid(String symbolicCheckId)
    {
        CheckUid uid = mock(CheckUid.class);
        when(uid.getCheckId()).thenReturn(symbolicCheckId);
        return uid;
    }

    private static Set<String> singleton(String value)
    {
        Set<String> set = new HashSet<>();
        set.add(value);
        return set;
    }
}

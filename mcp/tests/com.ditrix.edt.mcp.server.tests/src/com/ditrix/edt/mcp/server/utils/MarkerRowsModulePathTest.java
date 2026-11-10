/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;

/**
 * The {@code modulePaths} filter shared by {@code get_project_errors} and {@code get_problem_summary}:
 * the normalised key, where in the row builder it applies, and the single marker grouping helper.
 */
public class MarkerRowsModulePathTest
{
    private static final String MODULE_A = "CommonModules/A/Module.bsl"; //$NON-NLS-1$
    private static final String MODULE_B = "CommonModules/B/Module.bsl"; //$NON-NLS-1$

    @Test
    public void testNormalizeModulePathStripsSrcPrefixAndBackslashesButKeepsCase()
    {
        assertEquals(MODULE_A, MarkerRows.normalizeModulePath(" src\\CommonModules\\A\\Module.bsl ")); //$NON-NLS-1$
        assertEquals(MODULE_A, MarkerRows.normalizeModulePath("/CommonModules/A/Module.bsl")); //$NON-NLS-1$
        assertEquals(MODULE_A, MarkerRows.normalizeModulePath("SRC/CommonModules/A/Module.bsl")); //$NON-NLS-1$
        assertNull(MarkerRows.normalizeModulePath("  ")); //$NON-NLS-1$
        assertNull(MarkerRows.normalizeModulePath(null));
    }

    @Test
    public void testModulePathKeysCompareCaseInsensitivelyAndDropBlanks()
    {
        Set<String> keys = MarkerRows.modulePathKeys(Arrays.asList("src/commonmodules/a/MODULE.BSL", " ", null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, keys.size());
        assertTrue(MarkerRows.modulePathMatches(MODULE_A, keys));
        assertFalse(MarkerRows.modulePathMatches(MODULE_B, keys));
        assertFalse("a marker without a module never matches an active filter", //$NON-NLS-1$
            MarkerRows.modulePathMatches(null, keys));
        assertTrue("no filter keeps everything, metadata-only markers included", //$NON-NLS-1$
            MarkerRows.modulePathMatches(null, Collections.<String> emptySet()));
    }

    @Test
    public void testRowBuilderDropsAMarkerOfAnotherModuleBeforeResolvingItsPresentation()
    {
        Marker other = marker(MODULE_B);
        int[] shown = {0};
        int[] filteredOut = {0};
        MarkerRows.Row row = MarkerRows.buildIfMatches(other, null, null, Collections.<String> emptySet(),
            MarkerRows.modulePathKeys(Collections.singletonList(MODULE_A)), null, null, shown, filteredOut,
            false, MarkerRows.Row::new);
        assertNull(row);
        verify(other, never()).getObjectPresentation();
        assertEquals("an excluded marker is not counted as shown", 0, shown[0]); //$NON-NLS-1$
    }

    @Test
    public void testRowBuilderExcludesAMetadataOnlyMarkerWhenModulePathsIsGiven()
    {
        Marker metadataOnly = marker(null);
        assertNull(MarkerRows.buildIfMatches(metadataOnly, null, null, Collections.<String> emptySet(),
            MarkerRows.modulePathKeys(Collections.singletonList(MODULE_A)), null, null, new int[1], new int[1],
            false, MarkerRows.Row::new));
    }

    @Test
    public void testRowBuilderKeepsTheMarkerOfARequestedModule()
    {
        Marker mine = marker(MODULE_A);
        MarkerRows.Row row = MarkerRows.buildIfMatches(mine, null, null, Collections.<String> emptySet(),
            MarkerRows.modulePathKeys(Collections.singletonList("src\\commonmodules\\a\\module.bsl")), null, null, //$NON-NLS-1$
            new int[1], new int[1], false, MarkerRows.Row::new);
        assertNotNull(row);
        assertEquals(MODULE_A, row.modulePath);
    }

    @Test
    public void testRowBuilderWithoutPresentationNeverReadsItAndCountsNothing()
    {
        Marker mine = marker(MODULE_A);
        int[] shown = {0};
        int[] filteredOut = {0};
        MarkerRows.Row row = MarkerRows.buildIfMatches(mine, null, null, Collections.<String> emptySet(),
            Collections.<String> emptySet(), null, null, shown, filteredOut, false, false, MarkerRows.Row::new);
        assertNotNull(row);
        assertNull(row.objectPresentation);
        assertEquals(MODULE_A, row.modulePath);
        assertEquals(0, shown[0]);
        assertEquals(0, filteredOut[0]);
        verify(mine, never()).getObjectPresentation();
    }

    @Test
    public void testRowBuilderDefaultOverloadStillReadsThePresentation()
    {
        // get_project_errors' overloads keep reading the presentation exactly as before.
        Marker mine = marker(MODULE_A);
        MarkerRows.Row row = MarkerRows.buildIfMatches(mine, null, null, Collections.<String> emptySet(),
            Collections.<String> emptySet(), null, null, new int[1], new int[1], false, MarkerRows.Row::new);
        assertEquals("CommonModule.A.Module", row.objectPresentation); //$NON-NLS-1$
        verify(mine).getObjectPresentation();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRowBuilderRefusesAnObjectsFilterWithoutThePresentation()
    {
        MarkerRows.buildIfMatches(marker(MODULE_A), null, null, Collections.singleton("catalog.x"), //$NON-NLS-1$
            Collections.<String> emptySet(), null, null, new int[1], new int[1], false, false, MarkerRows.Row::new);
    }

    @Test
    public void testUnusableModulePathsAreNamedAndBlanksAreNot()
    {
        String error = MarkerRows.unusableModulePathsError(Arrays.asList("src/", " ", MODULE_A, "/", "\\", "src\\")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertNotNull(error);
        assertTrue(error, error.contains("'src/', '/', '\\', 'src\\'")); //$NON-NLS-1$
        assertFalse("a usable entry is not named: " + error, error.contains("'" + MODULE_A + "'")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(error, error.contains("CommonModules/X/Module.bsl")); //$NON-NLS-1$
        assertNull(MarkerRows.unusableModulePathsError(Arrays.asList("", "  ", MODULE_A))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(MarkerRows.unusableModulePathsError(Collections.<String> emptyList()));
        assertNull(MarkerRows.unusableModulePathsError(null));
    }

    @Test
    public void testGroupMarkersByProjectHonoursTheNameFilterAndSkipsDeletedProjects()
    {
        IProject alive = project("Alive", true); //$NON-NLS-1$
        IProject other = project("Other", true); //$NON-NLS-1$
        IProject deleted = project("Deleted", false); //$NON-NLS-1$
        Marker a1 = markerIn(alive);
        Marker a2 = markerIn(alive);
        Marker o1 = markerIn(other);
        Marker d1 = markerIn(deleted);
        Marker orphan = markerIn(null);
        IMarkerManager manager = mock(IMarkerManager.class);
        when(manager.markers()).thenAnswer(inv -> Stream.of(a1, o1, d1, orphan, a2));

        Map<IProject, List<Marker>> all = MarkerRows.groupMarkersByProject(manager, null);
        assertEquals(Arrays.asList(alive, other), Arrays.asList(all.keySet().toArray()));
        assertEquals(Arrays.asList(a1, a2), all.get(alive));

        Map<IProject, List<Marker>> one = MarkerRows.groupMarkersByProject(manager, "Other"); //$NON-NLS-1$
        assertEquals(Collections.singleton(other), one.keySet());
    }

    @Test
    public void testModulePathsNotFoundWarningNamesTheProjectThePathsAndTheRecovery()
    {
        StringBuilder md = new StringBuilder();
        MarkerRows.appendModulePathsNotFoundWarning(md, "P", Arrays.asList("CommonModules/Nope/Module.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        String text = md.toString();
        assertTrue(text, text.contains("modulePaths not found in P: CommonModules/Nope/Module.bsl")); //$NON-NLS-1$
        assertTrue(text, text.contains("list_modules")); //$NON-NLS-1$

        StringBuilder empty = new StringBuilder();
        MarkerRows.appendModulePathsNotFoundWarning(empty, "P", Collections.<String> emptyList()); //$NON-NLS-1$
        assertEquals("", empty.toString()); //$NON-NLS-1$
    }

    private static Marker marker(String modulePath)
    {
        IProject project = project("P", true); //$NON-NLS-1$
        Marker marker = mock(Marker.class);
        when(marker.getSeverity()).thenReturn(MarkerSeverity.MAJOR);
        when(marker.getCheckId()).thenReturn("SU1"); //$NON-NLS-1$
        when(marker.getMessage()).thenReturn("msg"); //$NON-NLS-1$
        when(marker.getProject()).thenReturn(project);
        when(marker.getObjectPresentation()).thenReturn("CommonModule.A.Module"); //$NON-NLS-1$
        ExtraInfo info = new ExtraInfo();
        if (modulePath != null)
        {
            info.put("uriToProblem", "platform:/resource/P/src/" + modulePath + "#/0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            info.put("line", "3"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        when(marker.getExtraInfo()).thenReturn(info);
        return marker;
    }

    private static Marker markerIn(IProject project)
    {
        Marker marker = mock(Marker.class);
        when(marker.getProject()).thenReturn(project);
        return marker;
    }

    private static IProject project(String name, boolean exists)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        when(project.exists()).thenReturn(exists);
        return project;
    }

    /** Minimal {@link IExtraInfoMap} over a {@link HashMap}, as in {@code MarkerRowsRangeTest}. */
    private static final class ExtraInfo extends HashMap<String, String> implements IExtraInfoMap
    {
        private static final long serialVersionUID = 1L;
    }
}

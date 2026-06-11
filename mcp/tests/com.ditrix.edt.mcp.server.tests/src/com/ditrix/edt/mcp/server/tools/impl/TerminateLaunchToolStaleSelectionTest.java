/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/**
 * Runtime-free tests for {@link TerminateLaunchTool#selectStaleTerminated} —
 * the second-pass selection that finds already-terminated EDT launches still
 * lingering in the launch manager.
 *
 * <p>The live-selection helpers skip {@code isTerminated()} launches by
 * design, so without this pass the {@code already_terminated} eviction was
 * unreachable for its target scenario (a launch whose TERMINATE event was
 * missed). These tests prove the stale pass matches the same criteria as the
 * live lookup — config name, project + applicationId, all (with optional
 * project narrowing) — restricted to EDT/1C configs, and never re-selects a
 * launch the live pass already picked.
 */
public class TerminateLaunchToolStaleSelectionTest
{
    private static final String PROJECT_NAME = "MyProject";
    private static final String OTHER_PROJECT = "OtherProject";
    private static final String APP_ID = "real-app-uuid";

    /** A non-1C Eclipse launch type — must never be selected or evicted. */
    private static final String JAVA_TYPE_ID = "org.eclipse.jdt.launching.localJavaApplication";

    private static ILaunchConfiguration mockConfig(String typeId, String name,
            String projectName, String applicationId) throws Exception
    {
        ILaunchConfigurationType type = mock(ILaunchConfigurationType.class);
        when(type.getIdentifier()).thenReturn(typeId);

        ILaunchConfiguration cfg = mock(ILaunchConfiguration.class);
        when(cfg.getType()).thenReturn(type);
        when(cfg.getName()).thenReturn(name);
        when(cfg.getAttribute(eq(LaunchConfigUtils.ATTR_PROJECT_NAME), anyString()))
            .thenReturn(projectName);
        when(cfg.getAttribute(eq(LaunchConfigUtils.ATTR_APPLICATION_ID), nullable(String.class)))
            .thenReturn(applicationId);
        return cfg;
    }

    private static ILaunch mockLaunch(ILaunchConfiguration cfg, boolean terminated)
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.getLaunchConfiguration()).thenReturn(cfg);
        when(launch.isTerminated()).thenReturn(terminated);
        return launch;
    }

    private static ILaunch terminatedRuntimeLaunch(String name, String project, String appId)
            throws Exception
    {
        return mockLaunch(
            mockConfig(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID, name, project, appId), true);
    }

    private static List<ILaunch> none()
    {
        return Collections.emptyList();
    }

    // === By-name mode ===

    @Test
    public void testStaleByNameIsSelected() throws Exception
    {
        ILaunch stale = terminatedRuntimeLaunch("MyApp / ThinClient", PROJECT_NAME, APP_ID);
        ILaunch otherName = terminatedRuntimeLaunch("Different Config", PROJECT_NAME, APP_ID);

        List<ILaunch> result = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[] { stale, otherName }, none(),
            "MyApp / ThinClient", null, null, false, true, false, false);

        assertEquals("only the name-matching stale launch must be selected", 1, result.size());
        assertSame(stale, result.get(0));
    }

    @Test
    public void testStaleByNameSkipsLiveLaunch() throws Exception
    {
        // A live launch with the matching name belongs to the primary (live)
        // selection — the stale pass must not pick it up.
        ILaunch live = mockLaunch(mockConfig(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID,
            "MyApp / ThinClient", PROJECT_NAME, APP_ID), false);

        List<ILaunch> result = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[] { live }, none(),
            "MyApp / ThinClient", null, null, false, true, false, false);

        assertTrue("live launches must never be selected by the stale pass", result.isEmpty());
    }

    @Test
    public void testStaleByNameIgnoresNonEdtConfig() throws Exception
    {
        // Config names are not unique across Eclipse launch types; a terminated
        // Java launch with a colliding name must never be selected (or evicted).
        ILaunch javaLaunch = mockLaunch(
            mockConfig(JAVA_TYPE_ID, "MyApp / ThinClient", PROJECT_NAME, APP_ID), true);

        List<ILaunch> result = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[] { javaLaunch }, none(),
            "MyApp / ThinClient", null, null, false, true, false, false);

        assertTrue("non-EDT launches must never be selected", result.isEmpty());
    }

    @Test
    public void testStaleAttachByNameIsSelected() throws Exception
    {
        // Attach configs are EDT configs too — a stale terminated attach entry
        // must be reachable by name just like a runtime-client one.
        ILaunch staleAttach = mockLaunch(mockConfig(LaunchConfigUtils.TYPE_LOCAL_RUNTIME,
            "Debug Session", PROJECT_NAME, null), true);

        List<ILaunch> result = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[] { staleAttach }, none(),
            "Debug Session", null, null, false, true, false, false);

        assertEquals(1, result.size());
        assertSame(staleAttach, result.get(0));
    }

    // === project + applicationId mode ===

    @Test
    public void testStaleByProjectAndAppIdIsSelected() throws Exception
    {
        ILaunch stale = terminatedRuntimeLaunch("MyApp.RuntimeClient", PROJECT_NAME, APP_ID);
        ILaunch wrongAppId = terminatedRuntimeLaunch("Other", PROJECT_NAME, "other-app-uuid");
        ILaunch wrongProject = terminatedRuntimeLaunch("Foreign", OTHER_PROJECT, APP_ID);

        List<ILaunch> result = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[] { stale, wrongAppId, wrongProject }, none(),
            null, PROJECT_NAME, APP_ID, false, false, true, true);

        assertEquals("only the exact project+appId stale match must be selected",
            1, result.size());
        assertSame(stale, result.get(0));
    }

    // === all mode ===

    @Test
    public void testAllModeSelectsEveryStaleEdtLaunch() throws Exception
    {
        ILaunch staleA = terminatedRuntimeLaunch("A", PROJECT_NAME, APP_ID);
        ILaunch staleB = terminatedRuntimeLaunch("B", OTHER_PROJECT, "another-id");
        ILaunch liveC = mockLaunch(mockConfig(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID,
            "C", PROJECT_NAME, APP_ID), false);
        ILaunch javaStale = mockLaunch(mockConfig(JAVA_TYPE_ID, "D", PROJECT_NAME, null), true);

        List<ILaunch> result = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[] { staleA, staleB, liveC, javaStale }, none(),
            null, null, null, true, false, false, false);

        assertEquals("all=true must select every stale EDT launch and nothing else",
            2, result.size());
        assertSame(staleA, result.get(0));
        assertSame(staleB, result.get(1));
    }

    @Test
    public void testAllModeWithProjectFilterNarrowsStaleSelection() throws Exception
    {
        ILaunch inProject = terminatedRuntimeLaunch("A", PROJECT_NAME, APP_ID);
        ILaunch otherProject = terminatedRuntimeLaunch("B", OTHER_PROJECT, "another-id");

        List<ILaunch> result = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[] { inProject, otherProject }, none(),
            null, PROJECT_NAME, null, true, false, true, false);

        assertEquals("project filter must narrow the stale selection", 1, result.size());
        assertSame(inProject, result.get(0));
    }

    // === Robustness ===

    @Test
    public void testAlreadySelectedLaunchIsNotDuplicated() throws Exception
    {
        // Terminate-between-scans race: the live pass picked the launch, then it
        // terminated before the stale scan ran. The identity skip must prevent a
        // duplicate entry (which would mean a double terminateOne + double report).
        ILaunch raced = terminatedRuntimeLaunch("MyApp / ThinClient", PROJECT_NAME, APP_ID);
        List<ILaunch> alreadySelected = new ArrayList<>();
        alreadySelected.add(raced);

        List<ILaunch> result = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[] { raced }, alreadySelected,
            "MyApp / ThinClient", null, null, false, true, false, false);

        assertTrue("a launch the live pass already selected must be skipped",
            result.isEmpty());
    }

    @Test
    public void testNullAndConfiglessEntriesAreSkipped() throws Exception
    {
        ILaunch noConfig = mockLaunch(null, true);
        ILaunch stale = terminatedRuntimeLaunch("MyApp / ThinClient", PROJECT_NAME, APP_ID);

        List<ILaunch> result = TerminateLaunchTool.selectStaleTerminated(
            new ILaunch[] { null, noConfig, stale }, none(),
            "MyApp / ThinClient", null, null, false, true, false, false);

        assertEquals("null/config-less entries must be skipped without errors",
            1, result.size());
        assertSame(stale, result.get(0));
    }

    @Test
    public void testNullLaunchArrayYieldsEmptySelection()
    {
        List<ILaunch> result = TerminateLaunchTool.selectStaleTerminated(
            null, none(), "AnyName", null, null, false, true, false, false);
        assertTrue(result.isEmpty());
    }
}

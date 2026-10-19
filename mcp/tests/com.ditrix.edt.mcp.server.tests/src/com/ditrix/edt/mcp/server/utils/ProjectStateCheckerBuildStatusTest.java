/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.Test;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.pipeline.DerivedDataSegmentBucket;
import com._1c.g5.v8.derived.pipeline.PipelineSegmentStatus;
import com._1c.g5.v8.derived.pipeline.PipelineStatus;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;

/**
 * Issue #769: the wording of the BUILDING verdict of {@link ProjectStateChecker}.
 * <p>
 * The refusal used to append {@code DerivedDataStatus.toString()}. The platform class does not
 * override it, so every client got {@code com._1c.g5.v8.derived.DerivedDataStatus@<hash>} - a JVM
 * object identity, different on every call, naming nothing that is building. These tests pin the
 * wording built from the status getters instead, and the verdict that must not change with it.
 * On EDT 2026.1 and 2026.2 the polled map is empty; segment fixtures below fill it by hand solely
 * for forward-compatible renderer coverage if a future polled snapshot supplies segments.
 * <p>
 * Wherever the identity question is asked, the status is a REAL {@link DerivedDataStatus} (its
 * constructor and segment map are public), so its {@code toString()} is exactly the platform's
 * {@code Object.toString()} - a mock would answer Mockito's own text and prove nothing.
 */
public class ProjectStateCheckerBuildStatusTest
{
    /** A JVM identity as {@code Object.toString()} renders it: {@code <class>@<hex hash>}. */
    private static final Pattern JAVA_IDENTITY = Pattern.compile("@[0-9a-fA-F]{4,}"); //$NON-NLS-1$

    private static final String BUILDING_PREFIX = "Project is building: "; //$NON-NLS-1$

    // --- describeBuildStatus ------------------------------------------------------------------

    @Test
    public void aFullStatusNamesTheStageThePipelineTheSyncAndEverySegment()
    {
        assertEquals("stage NORMAL, pipeline WAITING_STAGE_PROCCESING_END, model sync active; " //$NON-NLS-1$
            + "computing FORM (0 of 5 units remaining), MD (12 of 340 units remaining)", //$NON-NLS-1$
            ProjectStateChecker.describeBuildStatus(fullStatus()));
    }

    @Test
    public void aStatusWithoutActiveSegmentsNamesOnlyTheStageAndThePipeline()
    {
        DerivedDataStatus status = new DerivedDataStatus(DerivedDataSegmentBucket.SYNC, PipelineStatus.READY,
            false);
        assertEquals("stage SYNC, pipeline READY", ProjectStateChecker.describeBuildStatus(status)); //$NON-NLS-1$
    }

    @Test
    public void aStatusWithOnlySegmentsNamesTheSegmentsWithoutAHead()
    {
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        when(status.getActivePipelineSegments()).thenReturn(segments("MD", 1, 2)); //$NON-NLS-1$
        assertEquals("computing MD (1 of 2 units remaining)", //$NON-NLS-1$
            ProjectStateChecker.describeBuildStatus(status));
    }

    @Test
    public void aNullStatusIsWordedNeutrally()
    {
        assertEquals("derived data is being computed", ProjectStateChecker.describeBuildStatus(null)); //$NON-NLS-1$
    }

    @Test
    public void aStatusThatNamesNothingIsWordedNeutrally()
    {
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        when(status.getActivePipelineSegments()).thenReturn(Collections.emptyMap());
        assertEquals("derived data is being computed", ProjectStateChecker.describeBuildStatus(status)); //$NON-NLS-1$
    }

    @Test
    public void aNullStagePipelineOrSegmentIsLeftOutNotRenderedAsNull()
    {
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        Map<String, PipelineSegmentStatus> active = new HashMap<>();
        active.put("MD", new PipelineSegmentStatus(0L, 9, 3)); //$NON-NLS-1$
        active.put("FORM", null); //$NON-NLS-1$
        active.put(null, new PipelineSegmentStatus(0L, 7, 7));
        when(status.getActivePipelineSegments()).thenReturn(active);
        when(status.getPipelineStatus()).thenReturn(PipelineStatus.BLOCKED);

        String text = ProjectStateChecker.describeBuildStatus(status);

        assertEquals("pipeline BLOCKED; computing MD (3 of 9 units remaining)", text); //$NON-NLS-1$
        assertFalse(text, text.toLowerCase().contains("null")); //$NON-NLS-1$
    }

    @Test
    public void aThrowingGetterDegradesOnlyTheWording()
    {
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        when(status.getActiveStage()).thenReturn(DerivedDataSegmentBucket.NORMAL);
        when(status.getActivePipelineSegments()).thenThrow(new IllegalStateException("segments")); //$NON-NLS-1$
        assertEquals("derived data is being computed", ProjectStateChecker.describeBuildStatus(status)); //$NON-NLS-1$
    }

    @Test
    public void manySegmentsAreCutWithACountOfTheRestInKeyOrder()
    {
        // Inserted in REVERSE key order: the wording must come out sorted all the same.
        Map<String, PipelineSegmentStatus> active = new LinkedHashMap<>();
        for (int i = 9; i >= 0; i--)
        {
            active.put("S0" + i, new PipelineSegmentStatus(0L, 10, i)); //$NON-NLS-1$
        }
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        when(status.getActivePipelineSegments()).thenReturn(active);

        assertEquals("computing S00 (0 of 10 units remaining), S01 (1 of 10 units remaining), " //$NON-NLS-1$
            + "S02 (2 of 10 units remaining), S03 (3 of 10 units remaining), " //$NON-NLS-1$
            + "S04 (4 of 10 units remaining), S05 (5 of 10 units remaining), " //$NON-NLS-1$
            + "S06 (6 of 10 units remaining), S07 (7 of 10 units remaining), ... (+2 more)", //$NON-NLS-1$
            ProjectStateChecker.describeBuildStatus(status));
    }

    @Test
    public void exactlyTheCapOfSegmentsIsNotCut()
    {
        Map<String, PipelineSegmentStatus> active = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++)
        {
            active.put("S0" + i, new PipelineSegmentStatus(0L, 1, 1)); //$NON-NLS-1$
        }
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        when(status.getActivePipelineSegments()).thenReturn(active);

        String text = ProjectStateChecker.describeBuildStatus(status);

        assertTrue(text, text.endsWith("S07 (1 of 1 units remaining)")); //$NON-NLS-1$
        assertFalse(text, text.contains("more")); //$NON-NLS-1$
    }

    // --- checkDerivedDataState: the verdict and the wording it carries -------------------------

    /**
     * The identity ratchet. The status is a real platform object, so its {@code toString()} IS the
     * leak (asserted first, as the positive control); the refusal must carry none of it. A revert to
     * {@code status.toString()} turns this red.
     */
    @Test
    public void theBuildingRefusalCarriesNoJavaObjectIdentity()
    {
        DerivedDataStatus status = fullStatus();
        assertTrue("positive control: the platform status renders an identity", //$NON-NLS-1$
            status.toString().contains("DerivedDataStatus@")); //$NON-NLS-1$

        ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(busyManager(status));

        String message = result.getMessage();
        assertEquals(ProjectState.BUILDING, result.getState());
        assertFalse(message, message.contains("DerivedDataStatus")); //$NON-NLS-1$
        assertFalse(message, JAVA_IDENTITY.matcher(message).find());
    }

    /**
     * Forward-compatible wiring: if a future polled snapshot supplies segments, the busy verdict
     * renders them behind the stable retry prefix. Current EDT polling never fills this map.
     */
    @Test
    public void aBusyPipelineRendersSegmentsIfAFuturePolledSnapshotSuppliesThem()
    {
        ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(busyManager(fullStatus()));

        assertEquals(ProjectState.BUILDING, result.getState());
        assertFalse(result.isReady());
        assertEquals(BUILDING_PREFIX + "stage NORMAL, pipeline WAITING_STAGE_PROCCESING_END, " //$NON-NLS-1$
            + "model sync active; computing FORM (0 of 5 units remaining), MD (12 of 340 units remaining)", //$NON-NLS-1$
            result.getMessage());
    }

    @Test
    public void aBusyPipelineWithoutAStatusIsStillBuilding()
    {
        ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(busyManager(null));

        assertEquals(ProjectState.BUILDING, result.getState());
        assertEquals(BUILDING_PREFIX + "derived data is being computed", result.getMessage()); //$NON-NLS-1$
    }

    @Test
    public void aBusyPipelineWhoseStatusCannotBeReadIsStillBuilding()
    {
        IDerivedDataManager manager = mock(IDerivedDataManager.class);
        when(manager.isIdle()).thenReturn(false);
        when(manager.getDerivedDataStatus()).thenThrow(new IllegalStateException("no status")); //$NON-NLS-1$

        ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(manager);

        assertEquals(ProjectState.BUILDING, result.getState());
        assertEquals(BUILDING_PREFIX + "derived data is being computed", result.getMessage()); //$NON-NLS-1$
    }

    @Test
    public void aBusyPipelineWithAThrowingGetterIsStillBuilding()
    {
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        when(status.getActiveStage()).thenThrow(new IllegalStateException("stage")); //$NON-NLS-1$

        ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(busyManager(status));

        assertEquals(ProjectState.BUILDING, result.getState());
        assertEquals(BUILDING_PREFIX + "derived data is being computed", result.getMessage()); //$NON-NLS-1$
    }

    /** Regression pin for the second BUILDING branch: idle, but not everything computed. */
    @Test
    public void anIdlePipelineWithUncomputedDataKeepsTheDerivedDataNotCompleteWording()
    {
        DerivedDataStatus status = new DerivedDataStatus(DerivedDataSegmentBucket.FINISHING, PipelineStatus.EMPTY,
            false);

        ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(idleManager(status, false));

        assertEquals(ProjectState.BUILDING, result.getState());
        assertEquals("Project build in progress (derived data not complete)", result.getMessage()); //$NON-NLS-1$
    }

    /**
     * Forward-compatible renderer coverage: only a future polled snapshot could supply this map.
     * The idle verdict itself keeps its base wording; see {@link ProjectStateCheckerPolledStatusTest}.
     */
    @Test
    public void anIdleStatusCanDescribeSegmentsIfAFuturePolledSnapshotSuppliesThem()
    {
        DerivedDataStatus status = new DerivedDataStatus(DerivedDataSegmentBucket.AFTER_BUILD, PipelineStatus.EMPTY,
            false);
        status.getActivePipelineSegments().put("MD", new PipelineSegmentStatus(0L, 9, 3)); //$NON-NLS-1$

        assertEquals("stage AFTER_BUILD, pipeline EMPTY; computing MD (3 of 9 units remaining)", //$NON-NLS-1$
            ProjectStateChecker.describeBuildStatus(status));
    }

    @Test
    public void anIdleFullyComputedPipelineIsReady()
    {
        ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(idleManager(fullStatus(), true));

        assertEquals(ProjectState.READY, result.getState());
        assertTrue(result.isReady());
        assertEquals("Project is ready", result.getMessage()); //$NON-NLS-1$
    }

    // --- fixtures --------------------------------------------------------------------------------

    /** A real status with a manually filled map for forward-compatible segment-rendering tests. */
    private static DerivedDataStatus fullStatus()
    {
        DerivedDataStatus status = new DerivedDataStatus(DerivedDataSegmentBucket.NORMAL,
            PipelineStatus.WAITING_STAGE_PROCCESING_END, true);
        status.getActivePipelineSegments().put("MD", new PipelineSegmentStatus(0L, 340, 12)); //$NON-NLS-1$
        status.getActivePipelineSegments().put("FORM", new PipelineSegmentStatus(0L, 5, 0)); //$NON-NLS-1$
        return status;
    }

    private static Map<String, PipelineSegmentStatus> segments(String key, int remaining, int total)
    {
        return Collections.singletonMap(key, new PipelineSegmentStatus(0L, total, remaining));
    }

    private static IDerivedDataManager busyManager(DerivedDataStatus status)
    {
        IDerivedDataManager manager = mock(IDerivedDataManager.class);
        when(manager.isIdle()).thenReturn(false);
        when(manager.getDerivedDataStatus()).thenReturn(status);
        return manager;
    }

    private static IDerivedDataManager idleManager(DerivedDataStatus status, boolean allComputed)
    {
        IDerivedDataManager manager = mock(IDerivedDataManager.class);
        when(manager.isIdle()).thenReturn(true);
        when(manager.isAllComputed()).thenReturn(allComputed);
        when(manager.getDerivedDataStatus()).thenReturn(status);
        return manager;
    }
}

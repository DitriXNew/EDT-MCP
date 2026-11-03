/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.pipeline.DerivedDataSegmentBucket;
import com._1c.g5.v8.derived.pipeline.PipelineSegmentStatus;
import com._1c.g5.v8.derived.pipeline.PipelineStatus;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectState;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;

/**
 * Issue #769 review: the EDT 2026.1/2026.2 polled snapshot has no active segments. The idle
 * incomplete branch keeps its original refusal without polling, and unreadable wording remains
 * a BUILDING verdict with a warning that explains the fallback.
 */
public class ProjectStateCheckerPolledStatusTest
{
    @Test
    public void aBusyPolledSnapshotNamesTheStagePipelineAndSyncFlag()
    {
        DerivedDataStatus status = new DerivedDataStatus(DerivedDataSegmentBucket.NORMAL,
            PipelineStatus.WAITING_STAGE_PROCCESING_END, true);
        assertTrue("the polled status shape has no active segments", status.getActivePipelineSegments().isEmpty()); //$NON-NLS-1$
        IDerivedDataManager manager = mock(IDerivedDataManager.class);
        when(manager.getDerivedDataStatus()).thenReturn(status);

        ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(manager);

        assertEquals(ProjectState.BUILDING, result.getState());
        assertEquals("Project is building: stage NORMAL, pipeline WAITING_STAGE_PROCCESING_END, model sync active", //$NON-NLS-1$
            result.getMessage());
    }

    @Test
    public void anIdleIncompletePipelineDoesNotReadTheBuildStatus()
    {
        IDerivedDataManager manager = mock(IDerivedDataManager.class);
        when(manager.isIdle()).thenReturn(true);
        when(manager.getDerivedDataStatus()).thenThrow(new IllegalStateException("unused polled status")); //$NON-NLS-1$

        ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(manager);

        assertEquals(ProjectState.BUILDING, result.getState());
        assertEquals("Project build in progress (derived data not complete)", result.getMessage()); //$NON-NLS-1$
        verify(manager, never()).getDerivedDataStatus();
    }

    @Test
    public void anIdleIncompletePipelineKeepsBaseWordingEvenIfFutureSnapshotsSupplySegments()
    {
        DerivedDataStatus status = new DerivedDataStatus(DerivedDataSegmentBucket.AFTER_BUILD,
            PipelineStatus.EMPTY, false);
        status.getActivePipelineSegments().put("MD", new PipelineSegmentStatus(0L, 9, 3)); //$NON-NLS-1$
        IDerivedDataManager manager = mock(IDerivedDataManager.class);
        when(manager.isIdle()).thenReturn(true);
        when(manager.getDerivedDataStatus()).thenReturn(status);

        ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(manager);

        assertEquals(ProjectState.BUILDING, result.getState());
        assertEquals("Project build in progress (derived data not complete)", result.getMessage()); //$NON-NLS-1$
        verify(manager, never()).getDerivedDataStatus();
    }

    @Test
    public void aThrowingStageGetterLogsAWarningAndKeepsTheBuildingVerdict()
    {
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        String detail = "review769-stage-getter"; //$NON-NLS-1$
        when(status.getActiveStage()).thenThrow(new IllegalStateException(detail));

        assertWarningAndBuildingVerdict(status, detail);
    }

    @Test
    public void aThrowingSegmentGetterLogsAWarningAndKeepsTheBuildingVerdict()
    {
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        String detail = "review769-segment-getter"; //$NON-NLS-1$
        when(status.getActivePipelineSegments()).thenThrow(new IllegalStateException(detail));

        assertWarningAndBuildingVerdict(status, detail);
    }

    @Test
    public void aThrowingWorkCounterLogsAWarningAndKeepsTheBuildingVerdict()
    {
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        PipelineSegmentStatus segment = mock(PipelineSegmentStatus.class);
        String detail = "review769-work-counter"; //$NON-NLS-1$
        when(status.getActivePipelineSegments()).thenReturn(Collections.singletonMap("MD", segment)); //$NON-NLS-1$
        when(segment.getRemainingUnitsOfWork()).thenThrow(new IllegalStateException(detail));

        assertWarningAndBuildingVerdict(status, detail);
    }

    private static void assertWarningAndBuildingVerdict(DerivedDataStatus status, String detail)
    {
        Bundle bundle = FrameworkUtil.getBundle(ProjectStateChecker.class);
        assertNotNull("the warning must be observed inside the production OSGi bundle", bundle); //$NON-NLS-1$
        ILog log = Platform.getLog(bundle);
        List<IStatus> recorded = Collections.synchronizedList(new ArrayList<>());
        ILogListener listener = (entry, plugin) -> recorded.add(entry);
        log.addLogListener(listener);
        try
        {
            IDerivedDataManager manager = mock(IDerivedDataManager.class);
            when(manager.getDerivedDataStatus()).thenReturn(status);

            ProjectStateResult result = ProjectStateChecker.checkDerivedDataState(manager);

            assertEquals(ProjectState.BUILDING, result.getState());
            assertEquals("Project is building: derived data is being computed", result.getMessage()); //$NON-NLS-1$
            List<IStatus> warnings = new ArrayList<>();
            synchronized (recorded)
            {
                for (IStatus entry : recorded)
                {
                    if (entry.getMessage().contains(detail))
                    {
                        warnings.add(entry);
                    }
                }
            }
            assertEquals("a failed status getter must explain the fallback once", 1, warnings.size()); //$NON-NLS-1$
            assertEquals(IStatus.WARNING, warnings.get(0).getSeverity());
            assertTrue(warnings.get(0).getMessage().contains("java.lang.IllegalStateException: " + detail)); //$NON-NLS-1$
            assertNull("the warning follows readBuildStatus and carries no stack", warnings.get(0).getException()); //$NON-NLS-1$
        }
        finally
        {
            log.removeLogListener(listener);
        }
    }
}

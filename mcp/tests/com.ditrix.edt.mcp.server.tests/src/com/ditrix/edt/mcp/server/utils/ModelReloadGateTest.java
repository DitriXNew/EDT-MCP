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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.pipeline.DerivedDataSegmentBucket;
import com._1c.g5.v8.derived.pipeline.PipelineStatus;

/**
 * Issue #922: right after {@code switch_git_branch}, EDT has only SCHEDULED the import of the changed files into the
 * {@code SYNC} stage; the model segments still read as computed for the model about to be replaced, and the sync
 * flag may not be set. The metadata-write gate must refuse in that window, by EDT's own definition of "the import of
 * a change batch is not finished" (pipeline not empty and the active stage not past {@code SYNC}).
 */
public class ModelReloadGateTest
{
    /** No automatic workspace refresh is running or queued. */
    private static final java.util.function.BooleanSupplier NO_REFRESH = () -> false;

    /**
     * THE #922 pin measured on the stand: the auto-refresh job is still taking up the checked-out files - EDT
     * registers their import inside it - while the pipeline is still EMPTY and the model segments read as
     * computed. A write now landed in the previous branch's model and its export put that model back on disk.
     */
    @Test
    public void aWriteIsRefusedWhileTheWorkspaceRefreshIsTakingUpChangedFiles()
    {
        IDerivedDataManager manager = manager(PipelineStatus.EMPTY, DerivedDataSegmentBucket.NORMAL);

        assertEquals(ProjectStateChecker.LOADING_FROM_DISK_ERROR,
            ProjectStateChecker.modelBuildingErrorOrNull(manager, () -> true));
        assertNull("the same state without the refresh is admitted, as before",
            ProjectStateChecker.modelBuildingErrorOrNull(manager, NO_REFRESH));
    }

    /** Model segments computed, sync flag off, nothing in the pipeline at all - the pre-existing admission. */
    private static IDerivedDataManager manager(PipelineStatus pipeline, DerivedDataSegmentBucket stage)
    {
        DerivedDataStatus status = mock(DerivedDataStatus.class);
        when(status.isModelSyncActive()).thenReturn(false);
        when(status.getPipelineStatus()).thenReturn(pipeline);
        when(status.getActiveStage()).thenReturn(stage);
        IDerivedDataManager manager = mock(IDerivedDataManager.class);
        when(manager.getDerivedDataStatus()).thenReturn(status);
        when(manager.isComputed(ArgumentMatchers.<java.util.Collection<String>> any())).thenReturn(true);
        when(manager.isAllComputed()).thenReturn(false);
        return manager;
    }

    /**
     * THE #922 pin: the import of the switched files is queued in SYNC, the model segments still read as computed
     * and the sync flag is off - exactly the state the gate admitted before, and the write was then overwritten.
     */
    @Test
    public void aWriteIsRefusedWhileTheImportOfChangedFilesIsQueued()
    {
        IDerivedDataManager manager = manager(PipelineStatus.READY, DerivedDataSegmentBucket.SYNC);

        assertEquals(ProjectStateChecker.LOADING_FROM_DISK_ERROR, ProjectStateChecker.modelBuildingErrorOrNull(manager, NO_REFRESH));
    }

    /** The refusal keeps the prefix the e2e harness and agents retry on, and says nothing was changed. */
    @Test
    public void theLoadingRefusalIsRetryableAndSaysNothingChanged()
    {
        String error = ProjectStateChecker.LOADING_FROM_DISK_ERROR;

        assertTrue(error, error.startsWith("Project is building: ")); //$NON-NLS-1$
        assertTrue(error, error.contains("Nothing was changed")); //$NON-NLS-1$
        assertTrue(error, error.endsWith("Please wait and retry.")); //$NON-NLS-1$
    }

    /** A stage that waits for its processing to end is still the import (EDT's predicate only reads the stage). */
    @Test
    public void theImportStagesAreLoadingWhateverTheNonEmptyPipelineStatus()
    {
        for (PipelineStatus pipeline : new PipelineStatus[] { PipelineStatus.READY,
            PipelineStatus.WAITING_STAGE_PROCCESING_END, PipelineStatus.BLOCKED })
        {
            assertTrue(pipeline + "/SYNC", //$NON-NLS-1$
                ProjectStateChecker.isLoadingChangedFiles(manager(pipeline, DerivedDataSegmentBucket.SYNC)));
            assertTrue(pipeline + "/STARTING", //$NON-NLS-1$
                ProjectStateChecker.isLoadingChangedFiles(manager(pipeline, DerivedDataSegmentBucket.STARTING)));
        }
    }

    /**
     * Past SYNC the import is finished by EDT's own definition - the post-build validation of #495 in particular
     * must never hold a write back, and the model segments decide as before.
     */
    @Test
    public void stagesPastSyncAreNotLoading()
    {
        for (DerivedDataSegmentBucket stage : new DerivedDataSegmentBucket[] { DerivedDataSegmentBucket.AFTER_SYNC,
            DerivedDataSegmentBucket.BEFORE_BUILD, DerivedDataSegmentBucket.NORMAL, DerivedDataSegmentBucket.AFTER_BUILD,
            DerivedDataSegmentBucket.FINISHING })
        {
            IDerivedDataManager manager = manager(PipelineStatus.READY, stage);
            assertFalse(stage.name(), ProjectStateChecker.isLoadingChangedFiles(manager));
            assertNull(stage.name(), ProjectStateChecker.modelBuildingErrorOrNull(manager, NO_REFRESH));
        }
    }

    /** An empty pipeline is a finished import even when the reported stage is still SYNC. */
    @Test
    public void anEmptyPipelineIsNotLoading()
    {
        IDerivedDataManager manager = manager(PipelineStatus.EMPTY, DerivedDataSegmentBucket.SYNC);

        assertFalse(ProjectStateChecker.isLoadingChangedFiles(manager));
        assertNull(ProjectStateChecker.modelBuildingErrorOrNull(manager, NO_REFRESH));
    }

    /** A status that names no stage or no pipeline status names no import. */
    @Test
    public void aStatusThatNamesNoStageOrPipelineIsNotLoading()
    {
        assertFalse(ProjectStateChecker.isLoadingChangedFiles(manager(PipelineStatus.READY, null)));
        assertFalse(ProjectStateChecker.isLoadingChangedFiles(manager(null, DerivedDataSegmentBucket.SYNC)));
    }

    /** Not being able to read the status is never proof that nothing is loading. */
    @Test
    public void anUnreadableStatusCountsAsLoading()
    {
        IDerivedDataManager noStatus = mock(IDerivedDataManager.class);
        when(noStatus.getDerivedDataStatus()).thenReturn(null);
        assertTrue(ProjectStateChecker.isLoadingChangedFiles(noStatus));

        IDerivedDataManager throwing = mock(IDerivedDataManager.class);
        when(throwing.getDerivedDataStatus()).thenThrow(new IllegalStateException("no status")); //$NON-NLS-1$
        assertTrue(ProjectStateChecker.isLoadingChangedFiles(throwing));
    }

    /** With no import queued, an uncomputed model segment still refuses with the model message. */
    @Test
    public void withoutAnImportTheModelSegmentsStillDecide()
    {
        IDerivedDataManager manager = manager(PipelineStatus.EMPTY, DerivedDataSegmentBucket.NORMAL);
        when(manager.isComputed(ArgumentMatchers.<java.util.Collection<String>> any())).thenReturn(false);

        assertEquals(ProjectStateChecker.MODEL_COMPUTING_ERROR, ProjectStateChecker.modelBuildingErrorOrNull(manager, NO_REFRESH));
    }

    // ==================== awaitModelReload ====================

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    /** Projects whose gate opens within the wait settle; the call answers with nothing pending. */
    @Test
    public void projectsThatSettleWithinTheWaitLeaveNothingPending()
    {
        IProject a = project("A"); //$NON-NLS-1$
        IProject b = project("B"); //$NON-NLS-1$
        Map<String, AtomicInteger> polls = new HashMap<>();
        List<String> pending = ProjectStateChecker.awaitModelReload(List.of(a, b), 10_000L,
            p -> polls.computeIfAbsent(p.getName(), k -> new AtomicInteger()).incrementAndGet() < 3
                ? "Project is building" : null, //$NON-NLS-1$
            ms -> true);

        assertTrue(pending.isEmpty());
        assertEquals("a settled project is not asked again", 3, polls.get("A").get()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** A project still refused when the time runs out is named; a settled one is not. */
    @Test
    public void theWaitEndsAtItsBoundAndNamesTheProjectsStillLoading() throws Exception
    {
        IProject settled = project("Settled"); //$NON-NLS-1$
        IProject loading = project("Loading"); //$NON-NLS-1$
        long started = System.nanoTime();

        List<String> pending = ProjectStateChecker.awaitModelReload(List.of(settled, loading), 300L,
            p -> "Loading".equals(p.getName()) ? "Project is building" : null, //$NON-NLS-1$ //$NON-NLS-2$
            ms -> {
                try
                {
                    Thread.sleep(ms);
                    return true;
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return false;
                }
            });

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(List.of("Loading"), pending); //$NON-NLS-1$
        assertTrue("bounded by its timeout: " + elapsedMs, elapsedMs >= 250L && elapsedMs < 5_000L); //$NON-NLS-1$
    }

    /** An interrupted pause ends the wait at once and names what is still loading. */
    @Test
    public void anInterruptedWaitNamesWhatIsStillLoading()
    {
        List<Long> pauses = new ArrayList<>();
        List<String> pending = ProjectStateChecker.awaitModelReload(List.of(project("Loading")), 60_000L, //$NON-NLS-1$
            p -> "Project is building", //$NON-NLS-1$
            ms -> {
                pauses.add(ms);
                return false;
            });

        assertEquals(List.of("Loading"), pending); //$NON-NLS-1$
        assertEquals("one pause, then the wait ends", 1, pauses.size()); //$NON-NLS-1$
    }

    /** Nothing to wait for: no pause at all. */
    @Test
    public void noProjectsNoWait()
    {
        assertTrue(ProjectStateChecker.awaitModelReload(List.of(), 60_000L, p -> "x", ms -> { //$NON-NLS-1$
            throw new AssertionError("must not pause"); //$NON-NLS-1$
        }).isEmpty());
    }
}

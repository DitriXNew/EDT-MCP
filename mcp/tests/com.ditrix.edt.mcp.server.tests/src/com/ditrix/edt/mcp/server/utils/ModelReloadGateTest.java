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
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.derived.pipeline.DerivedDataSegmentBucket;
import com._1c.g5.v8.derived.pipeline.PipelineStatus;

/**
 * Issue #922: right after this server's checkout, EDT has not loaded the checked-out files into the model yet - the
 * automatic refresh is still taking them up, or their import is queued in {@code SYNC} - while the model segments
 * read as computed. The metadata-write gate refuses in that window, but ONLY for a project whose checkout reload is
 * registered ({@link CheckoutReloads}): the same signals are workspace-wide or shared with ordinary derived-data
 * work, and must not hold back an ordinary write.
 */
public class ModelReloadGateTest
{
    private static final BooleanSupplier NO_REFRESH = () -> false;

    private static final BooleanSupplier REFRESHING = () -> true;

    @Before
    @After
    public void forgetRegistrations()
    {
        CheckoutReloads.clear();
    }

    /** Model segments computed, sync flag off, the pipeline in the given state. */
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

    // ==================== the gate: only registered reloads are refused ====================

    /**
     * THE #922 pin measured on the stand: right after the checkout the auto-refresh job is still taking up the
     * files - EDT registers their import inside it - while the pipeline is EMPTY and the model segments read as
     * computed. A write into a project of that checkout landed in the previous branch's model.
     */
    @Test
    public void aWriteIntoAJustCheckedOutProjectIsRefusedWhileTheRefreshTakesUpItsFiles()
    {
        CheckoutReloads.register(List.of("Switched")); //$NON-NLS-1$
        IDerivedDataManager manager = manager(PipelineStatus.EMPTY, DerivedDataSegmentBucket.NORMAL);

        assertEquals(ProjectStateChecker.LOADING_FROM_DISK_ERROR,
            ProjectStateChecker.modelBuildingErrorOrNull(manager, "Switched", REFRESHING)); //$NON-NLS-1$
    }

    /** ...and while its import is queued in SYNC. */
    @Test
    public void aWriteIntoAJustCheckedOutProjectIsRefusedWhileItsImportIsQueued()
    {
        CheckoutReloads.register(List.of("Switched")); //$NON-NLS-1$
        IDerivedDataManager manager = manager(PipelineStatus.READY, DerivedDataSegmentBucket.SYNC);

        assertEquals(ProjectStateChecker.LOADING_FROM_DISK_ERROR,
            ProjectStateChecker.modelBuildingErrorOrNull(manager, "Switched", NO_REFRESH)); //$NON-NLS-1$
    }

    /** One idle sample right after the checkout proves nothing - the import may not have started yet. */
    @Test
    public void oneIdleSampleDoesNotReleaseAJustCheckedOutProject()
    {
        CheckoutReloads.register(List.of("Switched")); //$NON-NLS-1$
        IDerivedDataManager manager = manager(PipelineStatus.EMPTY, DerivedDataSegmentBucket.NORMAL);

        assertEquals(ProjectStateChecker.LOADING_FROM_DISK_ERROR,
            ProjectStateChecker.modelBuildingErrorOrNull(manager, "Switched", NO_REFRESH)); //$NON-NLS-1$
        assertTrue(CheckoutReloads.isPending("Switched")); //$NON-NLS-1$
    }

    /**
     * Codex P1 on #928: an automatic refresh anywhere in the workspace must not refuse a write into a project this
     * server did not just check out.
     */
    @Test
    public void anUnrelatedRefreshDoesNotRefuseAnOrdinaryWrite()
    {
        CheckoutReloads.register(List.of("Switched")); //$NON-NLS-1$
        IDerivedDataManager manager = manager(PipelineStatus.EMPTY, DerivedDataSegmentBucket.NORMAL);

        assertNull(ProjectStateChecker.modelBuildingErrorOrNull(manager, "Other", REFRESHING)); //$NON-NLS-1$
    }

    /** An ordinary write does not even ask the workspace-wide refresh question (a job-manager scan per write). */
    @Test
    public void anOrdinaryWriteDoesNotAskAboutWorkspaceRefreshes()
    {
        AtomicInteger asked = new AtomicInteger();
        IDerivedDataManager manager = manager(PipelineStatus.EMPTY, DerivedDataSegmentBucket.NORMAL);

        assertNull(ProjectStateChecker.modelBuildingErrorOrNull(manager, "Ordinary", () -> { //$NON-NLS-1$
            asked.incrementAndGet();
            return true;
        }));
        assertEquals(0, asked.get());
    }

    /**
     * Codex P1 on #928: ordinary derived-data work at STARTING/SYNC (after a write and its export) is not a disk
     * import and must not refuse the next write.
     */
    @Test
    public void ordinarySyncStageWorkDoesNotRefuseAnOrdinaryWrite()
    {
        for (DerivedDataSegmentBucket stage : new DerivedDataSegmentBucket[] { DerivedDataSegmentBucket.STARTING,
            DerivedDataSegmentBucket.SYNC })
        {
            IDerivedDataManager manager = manager(PipelineStatus.READY, stage);
            assertNull(stage.name(), ProjectStateChecker.modelBuildingErrorOrNull(manager, "Ordinary", NO_REFRESH)); //$NON-NLS-1$
        }
    }

    /** The refusal keeps the prefix the e2e harness and agents retry on, and says nothing was changed. */
    @Test
    public void theLoadingRefusalIsRetryableAndSaysNothingChanged()
    {
        String error = ProjectStateChecker.LOADING_FROM_DISK_ERROR;

        assertTrue(error, error.startsWith("Project is building: ")); //$NON-NLS-1$
        assertTrue(error, error.contains("git branch switch")); //$NON-NLS-1$
        assertTrue(error, error.contains("Nothing was changed")); //$NON-NLS-1$
        assertTrue(error, error.endsWith("Please wait and retry.")); //$NON-NLS-1$
    }

    /** With no reload registered, an uncomputed model segment still refuses with the model message. */
    @Test
    public void withoutAReloadTheModelSegmentsStillDecide()
    {
        IDerivedDataManager manager = manager(PipelineStatus.EMPTY, DerivedDataSegmentBucket.NORMAL);
        when(manager.isComputed(ArgumentMatchers.<java.util.Collection<String>> any())).thenReturn(false);

        assertEquals(ProjectStateChecker.MODEL_COMPUTING_ERROR,
            ProjectStateChecker.modelBuildingErrorOrNull(manager, "Ordinary", NO_REFRESH)); //$NON-NLS-1$
    }

    // ==================== isLoadingChangedFiles: EDT's own "import not finished" ====================

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

    /** Past SYNC the import is finished by EDT's own definition (the post-build validation of #495 included). */
    @Test
    public void stagesPastSyncAreNotLoading()
    {
        for (DerivedDataSegmentBucket stage : new DerivedDataSegmentBucket[] { DerivedDataSegmentBucket.AFTER_SYNC,
            DerivedDataSegmentBucket.BEFORE_BUILD, DerivedDataSegmentBucket.NORMAL, DerivedDataSegmentBucket.AFTER_BUILD,
            DerivedDataSegmentBucket.FINISHING })
        {
            assertFalse(stage.name(), ProjectStateChecker.isLoadingChangedFiles(manager(PipelineStatus.READY, stage)));
        }
    }

    /** An empty pipeline is a finished import even when the reported stage is still SYNC. */
    @Test
    public void anEmptyPipelineIsNotLoading()
    {
        assertFalse(ProjectStateChecker.isLoadingChangedFiles(manager(PipelineStatus.EMPTY, DerivedDataSegmentBucket.SYNC)));
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

    // ==================== awaitModelReload ====================

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static boolean sleep(long ms)
    {
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
    }

    /** Projects whose gate opens within the wait settle; the call answers with nothing pending. */
    @Test
    public void projectsThatSettleWithinTheWaitLeaveNothingPending()
    {
        Map<String, AtomicInteger> polls = new HashMap<>();
        List<String> pending = ProjectStateChecker.awaitModelReload(List.of(project("A"), project("B")), 10_000L, //$NON-NLS-1$ //$NON-NLS-2$
            p -> polls.computeIfAbsent(p.getName(), k -> new AtomicInteger()).incrementAndGet() < 3
                ? "Project is building" : null, //$NON-NLS-1$
            ms -> true);

        assertTrue(pending.isEmpty());
    }

    /**
     * Codex P2 on #928: a project admitted once can be refused again later (its import started after an idle
     * sample). The wait must not report completion while it is refused now.
     */
    @Test
    public void aProjectRefusedAgainAfterOneAdmittedSampleIsWaitedForAgain()
    {
        AtomicInteger pass = new AtomicInteger();
        Map<String, List<Boolean>> asked = new HashMap<>();
        List<String> pending = ProjectStateChecker.awaitModelReload(List.of(project("Early"), project("Late")), 10_000L, //$NON-NLS-1$ //$NON-NLS-2$
            p -> {
                int n = pass.get();
                asked.computeIfAbsent(p.getName(), k -> new ArrayList<>()).add(Boolean.TRUE);
                if ("Early".equals(p.getName())) //$NON-NLS-1$
                {
                    // admitted on pass 0, refused on passes 1-2, admitted again from pass 3
                    return n == 1 || n == 2 ? "Project is building" : null; //$NON-NLS-1$
                }
                return n == 0 ? "Project is building" : null; //$NON-NLS-1$
            },
            ms -> {
                pass.incrementAndGet();
                return true;
            });

        assertTrue(pending.isEmpty());
        assertEquals("the wait ended only once both were admitted in the same pass", 3, pass.get()); //$NON-NLS-1$
        assertEquals("Early is asked on every pass", 4, asked.get("Early").size()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** At the timeout the answer is the CURRENT state: a project refused again is named even if admitted before. */
    @Test
    public void theTimeoutNamesEveryProjectRefusedNow()
    {
        AtomicInteger pass = new AtomicInteger();
        long started = System.nanoTime();
        List<String> pending = ProjectStateChecker.awaitModelReload(List.of(project("Flips"), project("Loading")), //$NON-NLS-1$ //$NON-NLS-2$
            300L,
            p -> "Loading".equals(p.getName()) || pass.get() > 0 ? "Project is building" : null, //$NON-NLS-1$ //$NON-NLS-2$
            ms -> {
                pass.incrementAndGet();
                return sleep(ms);
            });

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(List.of("Flips", "Loading"), pending); //$NON-NLS-1$ //$NON-NLS-2$
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

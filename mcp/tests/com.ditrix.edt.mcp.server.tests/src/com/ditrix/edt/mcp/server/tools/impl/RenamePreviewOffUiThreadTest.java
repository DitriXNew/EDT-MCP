/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.rename.RenameProgress;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;

/**
 * Issue #975: a rename PREVIEW is computed on the bounded job's own thread - never handed to EDT's
 * UI thread - with that job's monitor, so its deadline and {@code cancel_job} stop it and give its
 * place back; a CONFIRM still goes through the UI executor, with a monitor nothing cancels.
 */
public class RenamePreviewOffUiThreadTest
{
    private static final String PREVIEW = "---\naction: preview\ncontentHash: abc\n---\n";

    @Test
    public void testAPreviewRunsWithoutTheUiExecutorUnderTheJobsMonitor() throws Exception
    {
        AtomicInteger uiCalls = new AtomicInteger();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        AtomicReference<IProgressMonitor> received = new AtomicReference<>();
        IProgressMonitor jobMonitor = new NullProgressMonitor();

        RenameMetadataObjectTool.IRenameAction action = RenameMetadataObjectTool.hostedAction(
            "Catalog.Products", "Goods", false, work -> {
                uiCalls.incrementAndGet();
                work.run();
            }, (progress, monitor) -> {
                ranOn.set(Thread.currentThread());
                received.set(monitor);
                return PREVIEW;
            });

        assertEquals(PREVIEW, action.rename(new RenameProgress(), jobMonitor));
        assertEquals("A preview must never be handed to EDT's UI thread", 0, uiCalls.get());
        assertSame("It runs on the bounded job's own thread", Thread.currentThread(), ranOn.get());
        assertSame("It polls the bounded job's monitor", jobMonitor, received.get());
    }

    @Test
    public void testAConfirmRunsThroughTheUiExecutorUnderAMonitorNothingCancels() throws Exception
    {
        AtomicInteger uiCalls = new AtomicInteger();
        AtomicReference<IProgressMonitor> received = new AtomicReference<>();
        IProgressMonitor jobMonitor = new NullProgressMonitor();

        RenameMetadataObjectTool.IRenameAction action = RenameMetadataObjectTool.hostedAction(
            "Catalog.Products", "Goods", true, work -> {
                uiCalls.incrementAndGet();
                work.run();
            }, (progress, monitor) -> {
                received.set(monitor);
                return "executed";
            });

        assertEquals("executed", action.rename(new RenameProgress(), jobMonitor));
        assertEquals("A confirm applies on EDT's UI thread, as before", 1, uiCalls.get());
        assertNotSame(jobMonitor, received.get());
        jobMonitor.setCanceled(true);
        assertFalse("A deadline must not cut a confirm short", received.get().isCanceled());
    }

    @Test
    public void testAStoppedPreviewAnswersWithAClearErrorInsteadOfAFailure() throws Exception
    {
        IProgressMonitor jobMonitor = new NullProgressMonitor();
        String direct = RenameMetadataObjectTool.hostedAction("Catalog.Products", "Goods", false, Runnable::run,
            (progress, monitor) -> {
                throw new OperationCanceledException();
            }).rename(new RenameProgress(), jobMonitor);
        assertStopped(direct);

        String wrapped = RenameMetadataObjectTool.hostedAction("Catalog.Products", "Goods", false, Runnable::run,
            (progress, monitor) -> {
                throw new IllegalStateException("BM task failed", new OperationCanceledException());
            }).rename(new RenameProgress(), jobMonitor);
        assertStopped(wrapped);

        IProgressMonitor cancelled = new NullProgressMonitor();
        cancelled.setCanceled(true);
        String afterCancel = RenameMetadataObjectTool.hostedAction("Catalog.Products", "Goods", false, Runnable::run,
            (progress, monitor) -> {
                throw new IllegalStateException("platform gave up");
            }).rename(new RenameProgress(), cancelled);
        assertStopped(afterCancel);

        String confirm = RenameMetadataObjectTool.hostedAction("Catalog.Products", "Goods", true, Runnable::run,
            (progress, monitor) -> {
                throw new IllegalStateException("apply failed");
            }).rename(new RenameProgress(), cancelled);
        assertTrue(ToolResult.isErrorPayload(confirm));
        assertFalse("A confirm failure is not a stopped preview: " + confirm, confirm.contains("was stopped"));
    }

    /** The deadline cancels the very monitor the action polls, and its end releases the place. */
    @Test(timeout = 60_000)
    public void testTheDeadlineCancelsTheMonitorTheActionReceived() throws Exception
    {
        AtomicBoolean sawCancel = new AtomicBoolean();
        AtomicInteger ended = new AtomicInteger();
        String answer = RenameMetadataObjectTool.runRenameBounded("Catalog.Products", "Goods", false, 300L,
            (progress, monitor) -> {
                pollUntilCancelled(monitor, 20_000L);
                sawCancel.set(monitor.isCanceled());
                return PREVIEW;
            }, ended::incrementAndGet);

        assertTrue("The wait ends at the deadline: " + answer, ToolResult.isErrorPayload(answer));
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (ended.get() == 0 && System.nanoTime() < deadline)
        {
            Thread.sleep(10L);
        }
        assertTrue("The action must see its monitor cancelled at the deadline", sawCancel.get());
        assertEquals("Its end - not the 20s fallback - releases the place", 1, ended.get());
    }

    /** A timed-out preview whose work stops on its monitor gives its place back; a repeat runs. */
    @Test(timeout = 60_000)
    public void testATimedOutPreviewGivesItsPlaceBackAndARepeatIsAccepted() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            RenameMetadataObjectTool tool = new RenameMetadataObjectTool((project, timeout) -> null, jobs);
            String first = jobId(tool.startOrAttachJob("Demo", "Catalog.Products", "Goods", false, 20, 0, 500L,
                (progress, monitor) -> {
                    pollUntilCancelled(monitor, 20_000L);
                    return PREVIEW;
                }));
            JobSnapshot failed = jobs.await(first, 20_000L);
            assertEquals(BackgroundJobs.Status.FAILED, failed.getStatus());
            assertTrue(failed.getErrorMessage(), failed.getErrorMessage().contains("This was a PREVIEW"));

            awaitNoHeldPlaces(tool, 5_000L);
            assertEquals("A repeat preview of the same rename is accepted", PREVIEW,
                tool.startOrAttachJob("Demo", "Catalog.Products", "Goods", false, 20, 5, 500L,
                    (progress, monitor) -> PREVIEW));
        }
    }

    /** cancel_job on a running preview cancels its work's monitor: a preview is never committed. */
    @Test(timeout = 60_000)
    public void testCancelJobCancelsARunningPreviewsMonitor() throws Exception
    {
        CountDownLatch inWork = new CountDownLatch(1);
        AtomicBoolean sawCancel = new AtomicBoolean();
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            RenameMetadataObjectTool tool = new RenameMetadataObjectTool((project, timeout) -> null, jobs);
            String id = jobId(tool.startOrAttachJob("Demo", "Catalog.Products", "Goods", false, 20, 0, 30_000L,
                (progress, monitor) -> {
                    inWork.countDown();
                    pollUntilCancelled(monitor, 20_000L);
                    sawCancel.set(monitor.isCanceled());
                    return PREVIEW;
                }));
            assertTrue("The preview must reach its work", inWork.await(20, TimeUnit.SECONDS));

            String outcome = new CancelJobTool(jobs).execute(Map.of("jobId", id, "confirm", "true"));
            assertTrue("A running preview is a real stop: " + outcome,
                outcome.startsWith("# Background job cancellation: cancelled"));
            assertEquals(BackgroundJobs.Status.CANCELLED, jobs.await(id, 20_000L).getStatus());
            awaitNoHeldPlaces(tool, 5_000L);
            assertTrue("The preview's work must see its monitor cancelled", sawCancel.get());
        }
    }

    private static void assertStopped(String answer)
    {
        assertTrue(answer, ToolResult.isErrorPayload(answer));
        assertTrue(answer, answer.contains("The preview of 'Catalog.Products' -> 'Goods' was stopped at its "
            + "deadline or by cancel_job before EDT finished computing the change points. Nothing was "
            + "renamed."));
    }

    private static void pollUntilCancelled(IProgressMonitor monitor, long capMs) throws InterruptedException
    {
        long deadline = System.nanoTime() + capMs * 1_000_000L;
        while (!monitor.isCanceled() && System.nanoTime() < deadline)
        {
            Thread.sleep(10L);
        }
    }

    private static void awaitNoHeldPlaces(RenameMetadataObjectTool tool, long capMs) throws InterruptedException
    {
        long deadline = System.nanoTime() + capMs * 1_000_000L;
        while (tool.heldPreviewPlaces() != 0 && System.nanoTime() < deadline)
        {
            Thread.sleep(20L);
        }
        assertEquals("The place comes back once the stopped work ended", 0, tool.heldPreviewPlaces());
    }

    private static String jobId(String answer)
    {
        Matcher matcher = Pattern.compile("(?m)^\\| jobId \\| ([^|]+) \\|\\s*$").matcher(answer);
        assertTrue("Missing jobId: " + answer, matcher.find());
        return matcher.group(1).trim();
    }
}

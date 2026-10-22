/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;

/**
 * Round 3 of #803: an attached preview answers exactly what its own call would have answered (its
 * spelling, its timeout), and a preview whose UI-thread hand-off timed out keeps its place until
 * EDT actually finishes building it.
 */
public class RenamePreviewPlaceTest
{
    private static final String PREVIEW = "---\naction: preview\ncontentHash: abc\n---\n";
    private static final String ENGLISH_FIELD = "Catalog.Products.Form.ItemForm.Field.Price";
    /** The same element with Russian type, form and kind tokens. */
    private static final String RUSSIAN_FIELD = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.Products."
        + "\u0424\u043e\u0440\u043c\u0430.ItemForm.\u041f\u043e\u043b\u0435.Price";
    /** What the service echoes for {@link #RUSSIAN_FIELD}: only the leading type token normalized. */
    private static final String RUSSIAN_FIELD_ECHO = "Catalog.Products."
        + "\u0424\u043e\u0440\u043c\u0430.ItemForm.\u041f\u043e\u043b\u0435.Price";

    @Test
    public void testRussianAndEnglishFormElementSpellingsEachGetTheirOwnAnswer() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs);
                String english = jobId(start(tool, ENGLISH_FIELD, "Cost", 0, 5_000L, progress -> {
                    await(release);
                    return "objectFqn: " + ENGLISH_FIELD;
                }));
                // Attached to the English job this would be its Pending answer; on its own job the
                // Russian call gets the preview its own synchronous call would have returned.
                String russian = start(tool, RUSSIAN_FIELD, "Cost", 5, 5_000L,
                    progress -> "objectFqn: " + RUSSIAN_FIELD_ECHO);
                assertEquals("objectFqn: " + RUSSIAN_FIELD_ECHO, russian);
                release.countDown();
                assertEquals("objectFqn: " + ENGLISH_FIELD, jobs.await(english, 5_000L).getResult());
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testTheSameSpellingAndTheTypeTokenAliasStillAttach() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs);
                RenameMetadataObjectTool.IRenameAction blocked = blocked(release);
                String first = jobId(start(tool, RUSSIAN_FIELD, "Cost", 0, 5_000L, blocked));
                assertEquals(first, jobId(start(tool, RUSSIAN_FIELD, "Cost", 0, 5_000L, blocked)));
                // The service echoes a Russian type token as the English one, so both answers match.
                assertEquals(first, jobId(start(tool, RUSSIAN_FIELD_ECHO, "Cost", 0, 5_000L, blocked)));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testADifferentTimeoutStartsItsOwnPreview() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs);
                RenameMetadataObjectTool.IRenameAction blocked = blocked(release);
                String first = jobId(start(tool, "Catalog.Products", "Goods", 0, 5_000L, blocked));
                String longer = jobId(start(tool, "Catalog.Products", "Goods", 0, 6_000L, blocked));
                assertNotEquals("A call must not run under another caller's timeout", first, longer);
                assertEquals(first, jobId(start(tool, "Catalog.Products", "Goods", 0, 5_000L, blocked)));
                String refused = start(tool, "Catalog.Products", "Goods", 0, 7_000L, blocked);
                assertTrue("A third timeout is a third preview, subject to the limit: " + refused,
                    refused.contains("the limit for rename previews"));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test(timeout = 60_000)
    public void testATimedOutPreviewKeepsItsPlaceUntilEdtFinishesBuildingIt() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            RenameMetadataObjectTool tool = tool(jobs);
            try
            {
                RenameMetadataObjectTool.IRenameAction stuck = blocked(release);
                String first = jobId(start(tool, "Catalog.Products", "Goods", 0, 500L, stuck));
                String second = jobId(start(tool, "Catalog.Other", "Items", 0, 500L, stuck));
                for (String id : new String[] {first, second})
                {
                    JobSnapshot failed = jobs.await(id, 20_000L);
                    assertEquals(BackgroundJobs.Status.FAILED, failed.getStatus());
                    assertTrue(failed.getErrorMessage(), failed.getErrorMessage().contains("This was a PREVIEW"));
                }

                String third = start(tool, "Catalog.Third", "More", 0, 500L, stuck);
                assertTrue("Two orphaned hand-offs still hold both places: " + third,
                    third.contains("2 other rename previews are already running, the limit for rename "
                        + "previews. 2 of them already ended (timeout or cancellation), but EDT is still "
                        + "building those previews on its UI thread: ending a job stops the waiting, not "
                        + "EDT's work, so such a place is freed only when EDT finishes. Nothing was "
                        + "renamed. Retry after EDT finishes;"));

                String again = start(tool, "Catalog.Products", "Goods", 0, 500L, stuck);
                assertTrue("The same preview must not start on top of the orphaned one: " + again,
                    again.contains("A previous preview of 'Catalog.Products' -> 'Goods' (job '" + first
                        + "') already ended (timeout or cancellation), but EDT is still building it on "
                        + "its UI thread"));
                assertEquals(2, heldPlaces(tool));

                release.countDown();
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (heldPlaces(tool) != 0 && System.nanoTime() < deadline)
                {
                    Thread.sleep(20L);
                }
                assertEquals("The places come back once EDT finishes the hand-offs", 0, heldPlaces(tool));
                assertEquals(PREVIEW, start(tool, "Catalog.Third", "More", 5, 5_000L, progress -> PREVIEW));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testGuideNamesTheAttachKeyAndTheHeldPlace()
    {
        String guide = new RenameMetadataObjectTool().getGuide();
        assertTrue(guide.contains("the same project, objectFqn, newName and effective `timeout` attaches"));
        assertTrue(guide.contains("runs its own preview, and so does a different timeout"));
        assertTrue(guide.contains("A preview whose job ended (timeout or cancellation) while EDT was still "
            + "building it on its UI thread keeps its place until EDT finishes that work, at most an hour "
            + "after the timeout"));
        assertTrue(guide.contains("a new preview of the same project, objectFqn and newName is refused "
            + "whatever its `timeout` - retry after EDT finishes."));
        assertFalse(guide.contains("A preview that reported a timeout keeps its place"));
        assertFalse(guide.contains("attach to one job"));
        assertFalse(guide.contains("keeps the timeout budget of the first submission"));
    }

    /**
     * A preview still in its settle has no hand-off in flight, but its job is RUNNING: it keeps its
     * attach entry and its place. Giving the place back there would start a duplicate job for the
     * same call and let a third preview past the limit.
     */
    @Test(timeout = 60_000)
    public void testAPreviewStillSettlingKeepsItsAttachEntryAndItsPlace() throws Exception
    {
        CountDownLatch settle = new CountDownLatch(1);
        AtomicInteger settling = new AtomicInteger();
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> {
                settling.incrementAndGet();
                await(settle);
                return null;
            });
            try
            {
                String first = jobId(start(tool, "Catalog.Products", "Goods", 0, 5_000L, progress -> PREVIEW));
                awaitCount(settling, 1);
                assertEquals("A call during the settle attaches to the running job", first,
                    jobId(start(tool, "Catalog.Products", "Goods", 0, 5_000L, progress -> PREVIEW)));
                String second = jobId(start(tool, "Catalog.Other", "Items", 0, 5_000L, progress -> PREVIEW));
                awaitCount(settling, 2);
                assertEquals("Both settling previews hold a place", 2, heldPlaces(tool));
                String third = start(tool, "Catalog.Third", "More", 0, 5_000L, progress -> PREVIEW);
                assertTrue("A third preview is refused while two settle: " + third,
                    third.contains("2 other rename previews are already running, the limit for rename "
                        + "previews. Poll them with get_job_status"));
                assertEquals("No duplicate job was started", 2, settling.get());

                settle.countDown();
                assertEquals(PREVIEW, jobs.await(first, 20_000L).getResult());
                assertEquals(PREVIEW, jobs.await(second, 20_000L).getResult());
            }
            finally
            {
                settle.countDown();
            }
        }
    }

    /**
     * A job that left RUNNING while it was still settling gave its place back, so its body must not
     * go on to the UI-thread hand-off: that work would run uncounted. A registry cancel cannot
     * produce this state - the job stays RUNNING until its worker unwinds - but stopping the
     * registry fails it at once, exactly as the registry deadline does.
     */
    @Test(timeout = 60_000)
    public void testAPreviewWhoseJobEndedWhileSettlingNeverStartsItsUiThreadWork() throws Exception
    {
        CountDownLatch settle = new CountDownLatch(1);
        CountDownLatch inSettle = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(1);
        AtomicInteger uiWork = new AtomicInteger();
        BackgroundJobs jobs = new BackgroundJobs(10, 4);
        Thread closer = new Thread(jobs::close, "rename-preview-place-test-close");
        try
        {
            RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> {
                inSettle.countDown();
                awaitSwallowingInterrupts(settle);
                settled.countDown();
                return null;
            });
            String id = jobId(start(tool, "Catalog.Products", "Goods", 0, 5_000L, progress -> {
                uiWork.incrementAndGet();
                return PREVIEW;
            }));
            assertTrue("The job must reach its settle", inSettle.await(20, TimeUnit.SECONDS));

            closer.start();
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (isRunning(jobs, id) && System.nanoTime() < deadline)
            {
                Thread.sleep(10L);
            }
            JobSnapshot ended = jobs.get(id);
            assertNotNull(ended);
            assertEquals(BackgroundJobs.Status.FAILED, ended.getStatus());
            assertTrue(ended.getErrorMessage(), ended.getErrorMessage().contains("EDT-MCP is stopping"));
            assertEquals("The place comes back once the job left RUNNING", 0, heldPlaces(tool));

            // Released well before close() escalates to shutdownNow (after its 2 s grace), so the
            // body runs on with its interrupt already swallowed by the settle.
            settle.countDown();
            assertTrue(settled.await(20, TimeUnit.SECONDS));
            long quiet = System.nanoTime() + 3_000_000_000L;
            while (uiWork.get() == 0 && System.nanoTime() < quiet)
            {
                Thread.sleep(10L);
            }
            assertEquals("The job body must not hand off after its place was given back", 0, uiWork.get());
            closer.join(30_000L);
            assertFalse("close() must return once the worker unwound", closer.isAlive());
        }
        finally
        {
            settle.countDown();
            if (!closer.isAlive())
            {
                jobs.close();
            }
        }
    }

    /**
     * A retry with a larger timeout - what the preview's own timeout error suggests - is the same
     * rename: while EDT still builds the earlier preview it is refused, not queued behind it.
     */
    @Test(timeout = 60_000)
    public void testALargerTimeoutIsRefusedWhileEdtStillBuildsTheEarlierPreview() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            RenameMetadataObjectTool tool = tool(jobs);
            try
            {
                String first = jobId(start(tool, "Catalog.Products", "Goods", 0, 500L, blocked(release)));
                JobSnapshot failed = jobs.await(first, 20_000L);
                assertEquals(BackgroundJobs.Status.FAILED, failed.getStatus());
                assertTrue(failed.getErrorMessage(), failed.getErrorMessage().contains("EDT did not finish "
                    + "computing the change points in time and keeps building them on its UI thread, so a "
                    + "new preview of this rename, with any timeout, is refused until EDT finishes. Then "
                    + "retry, or raise the bound."));

                AtomicInteger uiWork = new AtomicInteger();
                String larger = start(tool, "Catalog.Products", "Goods", 0, 5_000L, progress -> {
                    uiWork.incrementAndGet();
                    return PREVIEW;
                });
                assertTrue("A larger timeout must not start on top of the still-building preview: " + larger,
                    larger.contains("A previous preview of 'Catalog.Products' -> 'Goods' (job '" + first
                        + "') already ended (timeout or cancellation), but EDT is still building it on its "
                        + "UI thread: ending the job stops the waiting, not EDT's work. A new preview of the "
                        + "same rename is not started on top of it, whatever its timeout. Nothing was renamed. "
                        + "Retry after EDT finishes that work; this refusal stops then."));
                assertEquals(1, heldPlaces(tool));

                release.countDown();
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (heldPlaces(tool) != 0 && System.nanoTime() < deadline)
                {
                    Thread.sleep(20L);
                }
                assertEquals(0, heldPlaces(tool));
                assertEquals(PREVIEW, start(tool, "Catalog.Products", "Goods", 5, 5_000L, progress -> {
                    uiWork.incrementAndGet();
                    return PREVIEW;
                }));
                assertEquals("Only the retry after EDT finished built a preview", 1, uiWork.get());
            }
            finally
            {
                release.countDown();
            }
        }
    }

    /** The first progress line names the spelling every attached call shares, not the first caller's. */
    @Test
    public void testTheAcceptedLineNamesTheNormalizedTarget() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 4))
        {
            try
            {
                String pending = start(tool(jobs), "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.Products",
                    "Goods", 0, 5_000L, blocked(release));
                // The renderer escapes '>' in progress lines.
                assertTrue(pending, pending.contains("Accepted the rename preview for Catalog.Products -\\> Goods."));
                assertFalse(pending, pending.contains("preview for \u0421\u043f\u0440\u0430\u0432"));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    /** Only the cap - not a conclusive end or the Job's done notification - logs the warning. */
    @Test
    public void testOnlyACapReleaseLogsAWarning()
    {
        List<String> warnings = new ArrayList<>();
        AtomicInteger released = new AtomicInteger();
        AtomicBoolean observed = new AtomicBoolean();
        Runnable release = RenameMetadataObjectTool.capAwareRelease("Catalog.Products",
            released::incrementAndGet, observed::get, warnings::add);

        release.run();
        assertEquals(1, released.get());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).contains("the UI-thread hand-off of the preview of "
            + "'Catalog.Products' was not reported done within 3600 seconds after its wait ended, so its "
            + "preview place was released by the cap. EDT may still be building it"));

        observed.set(true);
        release.run();
        assertEquals(2, released.get());
        assertEquals("A release after the done notification is silent", 1, warnings.size());
    }

    private static boolean isRunning(BackgroundJobs jobs, String id)
    {
        JobSnapshot job = jobs.get(id);
        return job != null && job.getStatus() == BackgroundJobs.Status.RUNNING;
    }

    private static void awaitCount(AtomicInteger counter, int expected) throws InterruptedException
    {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (counter.get() < expected && System.nanoTime() < deadline)
        {
            Thread.sleep(10L);
        }
        assertTrue("expected " + expected + " but was " + counter.get(), counter.get() >= expected);
    }

    /** Like EDT work that never polls cancellation: an interrupt neither ends nor survives the wait. */
    private static void awaitSwallowingInterrupts(CountDownLatch latch)
    {
        while (latch.getCount() != 0)
        {
            try
            {
                latch.await();
            }
            catch (InterruptedException e)
            {
                // swallowed on purpose
            }
        }
    }

    private static RenameMetadataObjectTool.IRenameAction blocked(CountDownLatch release)
    {
        return progress -> {
            await(release);
            return PREVIEW;
        };
    }

    private static RenameMetadataObjectTool tool(BackgroundJobs jobs) throws Exception
    {
        return tool(jobs, (project, timeout) -> null);
    }

    private static RenameMetadataObjectTool tool(BackgroundJobs jobs,
        RenameMetadataObjectTool.CascadeSettler settler) throws Exception
    {
        Constructor<RenameMetadataObjectTool> ctor = RenameMetadataObjectTool.class.getDeclaredConstructor(
            RenameMetadataObjectTool.CascadeSettler.class, BackgroundJobs.class);
        ctor.setAccessible(true);
        return ctor.newInstance(settler, jobs);
    }

    private static String start(RenameMetadataObjectTool tool, String fqn, String name, int wait,
        long timeoutMs, RenameMetadataObjectTool.IRenameAction action) throws Exception
    {
        Method method = RenameMetadataObjectTool.class.getDeclaredMethod("startOrAttachJob",
            String.class, String.class, String.class, boolean.class, int.class, int.class, long.class,
            RenameMetadataObjectTool.IRenameAction.class);
        method.setAccessible(true);
        return (String)method.invoke(tool, "Demo", fqn, name, false, 20, wait, timeoutMs, action);
    }

    private static int heldPlaces(RenameMetadataObjectTool tool) throws Exception
    {
        Method method = RenameMetadataObjectTool.class.getDeclaredMethod("heldPreviewPlaces");
        method.setAccessible(true);
        return ((Integer)method.invoke(tool)).intValue();
    }

    private static String jobId(String answer)
    {
        Matcher matcher = Pattern.compile("(?m)^\\| jobId \\| ([^|]+) \\|\\s*$").matcher(answer);
        assertTrue("Missing jobId: " + answer, matcher.find());
        return matcher.group(1).trim();
    }

    /** Waits ignoring interrupts, like EDT's UI-thread work that does not poll cancellation. */
    private static void await(CountDownLatch latch)
    {
        boolean interrupted = false;
        while (latch.getCount() != 0)
        {
            try
            {
                latch.await();
            }
            catch (InterruptedException e)
            {
                interrupted = true;
            }
        }
        if (interrupted)
        {
            Thread.currentThread().interrupt();
        }
    }
}

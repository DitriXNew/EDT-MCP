/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
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
                        + "previews. 2 of them already reported a timeout, but EDT is still building that "
                        + "preview on its UI thread: a timeout stops the waiting, not EDT's work, so its "
                        + "place is freed only when EDT finishes it. Nothing was renamed."));

                String again = start(tool, "Catalog.Products", "Goods", 0, 500L, stuck);
                assertTrue("The same preview must not start on top of the orphaned one: " + again,
                    again.contains("A previous preview of 'Catalog.Products' -> 'Goods' (job '" + first
                        + "') already reported a timeout, but EDT is still building it on its UI thread"));
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
        assertTrue(guide.contains("A preview that reported a timeout keeps its place until EDT actually "
            + "finishes building it on its UI thread"));
        assertFalse(guide.contains("attach to one job"));
        assertFalse(guide.contains("keeps the timeout budget of the first submission"));
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
        Constructor<RenameMetadataObjectTool> ctor = RenameMetadataObjectTool.class.getDeclaredConstructor(
            RenameMetadataObjectTool.CascadeSettler.class, BackgroundJobs.class);
        ctor.setAccessible(true);
        return ctor.newInstance((RenameMetadataObjectTool.CascadeSettler)(project, timeout) -> null, jobs);
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

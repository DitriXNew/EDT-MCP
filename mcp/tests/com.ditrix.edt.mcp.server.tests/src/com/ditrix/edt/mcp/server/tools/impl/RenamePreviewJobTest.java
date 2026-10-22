/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.rename.RenameProgress;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;

/** Exercises actual registry workers and deadlines without an EDT workbench. */
public class RenamePreviewJobTest
{
    private static final String PREVIEW = "---\r\naction: preview\r\ncontentHash: abc123\r\n---\r\n\n| # | Type |\n";

    @Test
    public void testDoneWithinWaitReturnsIdenticalBytesAndStoredHash() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> null);
            assertEquals(PREVIEW, start(tool, "Catalog.Products", "Goods", 5, 5_000,
                progress -> PREVIEW));
        }
    }

    @Test
    public void testWaitZeroReturnsPendingAndJobResultIsTheUnwrappedPreview() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> null);
                String answer = start(tool, "Catalog.Products", "Goods", 0, 5_000, progress -> {
                    entered.countDown();
                    release.await();
                    return PREVIEW;
                });
                assertTrue(answer, answer.startsWith("**Pending:**"));
                assertTrue(answer.contains("Nothing was or will be renamed"));
                assertTrue(answer.contains("contentHash"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                String id = jobId(answer);
                assertEquals(BackgroundJobs.Status.RUNNING, jobs.get(id).getStatus());
                release.countDown();
                JobSnapshot done = jobs.await(id, 5_000);
                assertEquals(BackgroundJobs.Status.DONE, done.getStatus());
                assertEquals(PREVIEW, done.getResult());
                assertTrue(BackgroundJobRenderer.render(done).contains("## Result\n\n" + PREVIEW));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testErrorPayloadBecomesFailedJobAndInlineError() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> null);
                String id = jobId(start(tool, "Catalog.Products", "Goods", 0, 5_000, progress -> {
                    release.await();
                    return ToolResult.error("Could not build the preview").toJson();
                }));
                release.countDown();
                JobSnapshot failed = jobs.await(id, 5_000);
                assertEquals(BackgroundJobs.Status.FAILED, failed.getStatus());
                assertNull(failed.getResult());
                assertEquals("Could not build the preview", failed.getErrorMessage());
                String rendered = BackgroundJobRenderer.render(failed);
                assertTrue(rendered.contains("## Error\n\nCould not build the preview"));
                assertFalse(rendered.contains("## Result"));
                String inline = start(tool, "Catalog.Other", "OtherGoods", 5, 5_000,
                    progress -> ToolResult.error("Could not build the preview").toJson());
                assertEquals(ToolResult.error("Could not build the preview").toJson(), inline);
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testSameNormalizedKeyAttachesAndNewNameStartsAnotherJob() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger settles = new AtomicInteger();
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> {
                    settles.incrementAndGet();
                    return null;
                });
                RenameMetadataObjectTool.IRenameAction blocked = progress -> {
                    release.await();
                    return PREVIEW;
                };
                String first = jobId(start(tool, "Catalog.Products", "Goods", 0, 5_000, blocked));
                String alias = "\u0421\u043f\u0440\u0430\u0432\u043e\u0447\u043d\u0438\u043a.Products";
                assertEquals(first, jobId(start(tool, alias, "Goods", 0, 5_000, blocked)));
                String second = jobId(start(tool, "Catalog.Products", "Items", 0, 5_000, blocked));
                assertNotEquals(first, second);
                release.countDown();
                jobs.await(first, 5_000);
                assertEquals(PREVIEW, jobs.await(second, 5_000).getResult());
                assertEquals(2, settles.get());
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testFinishedPreviewIsNeverReusedByANewSubmission() throws Exception
    {
        AtomicInteger runs = new AtomicInteger();
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> null);
            RenameMetadataObjectTool.IRenameAction work = progress -> "preview " + runs.incrementAndGet();
            assertEquals("preview 1", start(tool, "Catalog.Products", "Goods", 5, 5_000, work));
            assertEquals("preview 2", start(tool, "Catalog.Products", "Goods", 5, 5_000, work));
        }
    }

    @Test
    public void testDifferentPlanOptionsCannotAttachToTheWrongPreview() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> null);
                RenameMetadataObjectTool.IRenameAction blocked = progress -> {
                    release.await();
                    return PREVIEW;
                };
                start(tool, "Catalog.Products", "Goods", 0, 5_000, blocked);
                String error = start(tool, "Catalog.Products", "Goods", true, 20, 0, 5_000, blocked);
                assertTrue(error, error.contains("different textOccurrences or maxResults"));
                assertTrue(error.contains("get_job_status"));
                assertTrue(start(tool, "Catalog.Products", "Goods", false, 0, 0, 5_000, blocked)
                    .contains("different textOccurrences or maxResults"));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test(timeout = 10_000)
    public void testSettleRunsInsideTheJobAndWaitZeroDoesNotBlockTheCaller() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> {
                    assertNotEquals(caller, Thread.currentThread());
                    assertEquals(60_000, timeout);
                    entered.countDown();
                    await(release);
                    return "Derived data is still building. Nothing was renamed.";
                });
                Map<String, String> params = params();
                params.put("waitSeconds", "0");
                String answer = tool.execute(params);
                assertTrue(answer, answer.startsWith("**Pending:**"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                release.countDown();
                JobSnapshot failed = jobs.await(jobId(answer), 5_000);
                assertEquals(BackgroundJobs.Status.FAILED, failed.getStatus());
                assertTrue(failed.getErrorMessage().contains("Derived data"));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testTimedOutPreviewJobFailsWithTheExistingStageAdvice() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> null);
                String id = jobId(start(tool, "Catalog.Products", "Goods", 0, 500, progress -> {
                    progress.enter(RenameProgress.Phase.PREPARING);
                    await(release);
                    return PREVIEW;
                }));
                JobSnapshot failed = jobs.await(id, 5_000);
                assertEquals(BackgroundJobs.Status.FAILED, failed.getStatus());
                assertTrue(failed.getErrorMessage(), failed.getErrorMessage().contains("This was a PREVIEW"));
                assertTrue(failed.getErrorMessage().contains("nothing was renamed"));
                assertFalse(failed.getErrorMessage().contains("may still apply"));
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testConfirmRefusesWaitSecondsBeforeSettling() throws Exception
    {
        AtomicInteger settles = new AtomicInteger();
        RenameMetadataObjectTool tool = new RenameMetadataObjectTool((project, timeout) -> {
            settles.incrementAndGet();
            return "Settle must not run";
        });
        Map<String, String> params = params();
        params.put("confirm", "true");
        params.put("waitSeconds", "5");
        String answer = tool.execute(params);
        assertTrue(answer, answer.contains("preview only"));
        assertTrue(answer.contains("synchronously under timeout"));
        assertEquals(0, settles.get());
    }

    @Test
    public void testInvalidWaitAndTextPolicyAreRefusedBeforeAnyWork()
    {
        AtomicInteger settles = new AtomicInteger();
        RenameMetadataObjectTool tool = new RenameMetadataObjectTool((project, timeout) -> {
            settles.incrementAndGet();
            return "Settle must not run";
        });
        for (String value : new String[] {"-1", "46", "1.5", "no", "null"})
        {
            Map<String, String> args = params();
            args.put("waitSeconds", value);
            assertTrue(tool.execute(args).contains("waitSeconds must be an integer from 0 to 45"));
        }
        for (String value : new String[] {"omit", "EXCLUDE", "null", ""})
        {
            Map<String, String> args = params();
            args.put("textOccurrences", value);
            assertTrue(tool.execute(args).contains("textOccurrences must be"));
        }
        assertEquals(0, settles.get());
    }

    @Test
    public void testAdmissionLimitNamesPollingAndAllowsAttachment() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            try
            {
                RenameMetadataObjectTool tool = tool(jobs, (project, timeout) -> null);
                RenameMetadataObjectTool.IRenameAction blocked = progress -> {
                    release.await();
                    return PREVIEW;
                };
                String first = jobId(start(tool, "Catalog.Products", "Goods", 0, 5_000, blocked));
                start(tool, "Catalog.Other", "Items", 0, 5_000, blocked);
                String refused = start(tool, "Catalog.Third", "More", 0, 5_000, blocked);
                assertTrue(refused, refused.contains("the limit for rename previews"));
                assertTrue(refused.contains("get_job_status"));
                assertEquals(first, jobId(start(tool, "Catalog.Products", "Goods", 0, 5_000, blocked)));
            }
            finally
            {
                release.countDown();
            }
        }
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
        long timeout, RenameMetadataObjectTool.IRenameAction action) throws Exception
    {
        return start(tool, fqn, name, false, 20, wait, timeout, action);
    }

    private static String start(RenameMetadataObjectTool tool, String fqn, String name, boolean exclude,
        int maxResults, int wait, long timeout, RenameMetadataObjectTool.IRenameAction action) throws Exception
    {
        Method method = RenameMetadataObjectTool.class.getDeclaredMethod("startOrAttachJob",
            String.class, String.class, String.class, boolean.class, int.class, int.class, long.class,
            RenameMetadataObjectTool.IRenameAction.class);
        method.setAccessible(true);
        return (String)method.invoke(tool, "Demo", fqn, name, exclude, maxResults, wait, timeout, action);
    }

    private static String jobId(String answer)
    {
        Matcher matcher = Pattern.compile("(?m)^\\| jobId \\| ([^|]+) \\|\\s*$").matcher(answer);
        assertTrue("Missing jobId: " + answer, matcher.find());
        return matcher.group(1).trim();
    }

    private static Map<String, String> params()
    {
        return new HashMap<>(Map.of("projectName", "Demo", "objectFqn", "Catalog.Products", "newName", "Goods"));
    }

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
